package com.sande.mythictrpg.gameplay.watch;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import com.sande.mythictrpg.recording.server.LegacyRecordingQuota;
import com.sande.mythictrpg.recording.server.ManagedStoreRegistry;

/** Bounded append-only transaction log. Dedicated worker only; corruption never triggers repair/deletion. */
final class WatchJournal implements AutoCloseable {
    record Envelope(int schemaVersion, UUID worldId, long sequence, WatchStore.Change change) {}
    interface Faults { void at(String point) throws IOException; }
    static final int MAX_FRAME = 64 * 1024;
    static final long WRITE_BOUND = MAX_FRAME + 8L;
    private final Path directory;
    private final UUID world;
    private final long maxBytes;
    private final int maxEntries;
    private final Faults faults;
    private final FileChannel channel;
    private final FileLock lock;
    private final Gson gson = new Gson();
    private long sequence, bytes;
    private boolean failed;

    WatchJournal(Path directory, UUID world, long maxBytes, int maxEntries, Faults faults) throws IOException {
        this.world = Objects.requireNonNull(world); this.maxBytes = maxBytes; this.maxEntries = maxEntries; this.faults = faults;
        this.directory = directory.toAbsolutePath().normalize();
        if (maxBytes < MAX_FRAME + 8 || maxBytes > (1L << 40) || maxEntries < 1 || maxEntries > 100_000)
            throw new IllegalArgumentException("watch storage limits");
        LegacyRecordingQuota.legacyLimits(this.directory, ManagedStoreRegistry.GOD_WATCH, maxBytes, maxEntries,
                MAX_FRAME, "WATCH_INDEX_LIMIT_OR_WATCH_STORAGE_LIMIT");
        FileChannel opened = null; FileLock acquired = null;
        try (var startup = LegacyRecordingQuota.reserve(this.directory, ManagedStoreRegistry.GOD_WATCH, 0, true)) {
            startup.validateBeforeWrite(); Files.createDirectories(this.directory);
            opened = FileChannel.open(this.directory.resolve("watch-v1.journal"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            acquired = opened.tryLock();
            if (acquired == null) throw new IOException("WATCH_WRITER_LOCKED");
            bytes = opened.size();
        } catch (Exception failure) {
            if (acquired != null) try { acquired.release(); } catch (IOException ignored) { }
            if (opened != null) try { opened.close(); } catch (IOException ignored) { }
            throw new IOException("WATCH_OPEN_FAILED", failure);
        }
        channel = opened; lock = acquired;
    }
    void replay(java.util.function.Consumer<WatchStore.Change> consumer) throws IOException {
        try {
            channel.position(0);
            while (channel.position() < bytes) {
                if (sequence >= maxEntries) throw new IOException("WATCH_INDEX_LIMIT");
                ByteBuffer header = ByteBuffer.allocate(8); read(header); header.flip();
                int length = header.getInt(), checksum = header.getInt();
                if (length < 1 || length > MAX_FRAME || length > bytes - channel.position()) throw new IOException("WATCH_FRAME_LENGTH");
                ByteBuffer payload = ByteBuffer.allocate(length); read(payload);
                CRC32 crc = new CRC32(); crc.update(payload.array());
                if ((int)crc.getValue() != checksum) throw new IOException("WATCH_CRC");
                Envelope e = gson.fromJson(new String(payload.array(), StandardCharsets.UTF_8), Envelope.class);
                if (e == null || e.schemaVersion != 1 || !world.equals(e.worldId) || e.sequence != sequence + 1 || e.change == null)
                    throw new IOException("WATCH_SCHEMA_WORLD_ORDER");
                consumer.accept(e.change); sequence++;
            }
        } catch (Exception failure) { failed = true; throw new IOException("WATCH_REPLAY_REJECTED", failure); }
    }
    void append(WatchStore.Change change) throws IOException {
        if (failed) throw new IOException("WATCH_FAILED");
        long nextSequence = Math.addExact(sequence, 1);
        byte[] data = gson.toJson(new Envelope(1, world, nextSequence, change)).getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_FRAME) throw new IOException("WATCH_FRAME_LIMIT");
        if (sequence >= maxEntries) throw new IOException("WATCH_INDEX_LIMIT");
        if (bytes + data.length + 8 > maxBytes) throw new IOException("WATCH_STORAGE_LIMIT");
        CRC32 crc = new CRC32(); crc.update(data);
        ByteBuffer frame = ByteBuffer.allocate(data.length + 8).putInt(data.length).putInt((int)crc.getValue()).put(data); frame.flip();
        boolean maintenance = change.kind().startsWith("REVOKE_") || change.kind().equals("CLEAN_CLOSE")
                || change.kind().equals("BOOT") || change.kind().equals("WATCH")
                && change.watch().state() != WatchContract.State.ACTIVE;
        try (var admission = LegacyRecordingQuota.reserve(directory, ManagedStoreRegistry.GOD_WATCH, data.length + 8L, maintenance)) {
            admission.validateBeforeWrite();
            faults.at("beforeWrite"); channel.position(bytes);
            while (frame.hasRemaining()) channel.write(frame);
            bytes = channel.size(); faults.at("afterWrite"); channel.force(true); faults.at("afterForce"); sequence = nextSequence;
        } catch (IOException failure) { failed = true; throw failure; }
    }
    private void read(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("WATCH_TRUNCATED");
    }
    long bytes() { return bytes; }
    /** Monotonic durable transaction revision, unlike the last observed raw occurrence cursor. */
    long committedRevision() { return sequence; }
    long maxBytes() { return maxBytes; }
    @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
