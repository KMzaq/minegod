package com.sande.mythictrpg.gameplay.watch;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Bounded append-only transaction log. Dedicated worker only; corruption never triggers repair/deletion. */
final class WatchJournal implements AutoCloseable {
    record Envelope(int schemaVersion, UUID worldId, long sequence, WatchStore.Change change) {}
    interface Faults { void at(String point) throws IOException; }
    static final int MAX_FRAME = 64 * 1024;
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
        if (maxBytes < MAX_FRAME + 8 || maxBytes > (1L << 40) || maxEntries < 1 || maxEntries > 100_000)
            throw new IllegalArgumentException("watch storage limits");
        Files.createDirectories(directory);
        channel = FileChannel.open(directory.resolve("watch-v1.journal"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("WATCH_WRITER_LOCKED");
            bytes = channel.size();
        } catch (Exception failure) { channel.close(); throw new IOException("WATCH_OPEN_FAILED", failure); }
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
        byte[] data = gson.toJson(new Envelope(1, world, sequence + 1, change)).getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_FRAME || bytes + data.length + 8 > maxBytes || sequence >= maxEntries)
            throw new IOException("WATCH_STORAGE_LIMIT");
        CRC32 crc = new CRC32(); crc.update(data);
        ByteBuffer frame = ByteBuffer.allocate(data.length + 8).putInt(data.length).putInt((int)crc.getValue()).put(data); frame.flip();
        try {
            faults.at("beforeWrite"); channel.position(bytes);
            while (frame.hasRemaining()) channel.write(frame);
            bytes = channel.size(); faults.at("afterWrite"); channel.force(true); faults.at("afterForce"); sequence++;
        } catch (IOException failure) { failed = true; throw failure; }
    }
    private void read(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("WATCH_TRUNCATED");
    }
    long bytes() { return bytes; }
    long maxBytes() { return maxBytes; }
    @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
