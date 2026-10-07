package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.MythicTrpg;
import java.io.*;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.neoforged.neoforge.common.IOUtilities;

/** Isolated hook for the two named record files. Does not intercept other SavedData or the global I/O queue. */
public final class ManagedSavedDataIo {
    /** FAILED includes unconfirmed reconciliation; it must not be interpreted as proof that no file changed. */
    public enum WriteStatus { COMMITTED, REJECTED, FAILED }
    /** A completed write is immutable; access returns a copy of the exact outer NBT written, not today's live state. */
    public static final class WriteResult {
        private final WriteStatus status;
        private final CompoundTag committedSnapshot;
        private WriteResult(WriteStatus status, CompoundTag committedSnapshot) {
            this.status = status; this.committedSnapshot = committedSnapshot;
        }
        public WriteStatus status() { return status; }
        public Optional<CompoundTag> snapshot() {
            return committedSnapshot == null ? Optional.empty() : Optional.of(committedSnapshot.copy());
        }
    }
    private ManagedSavedDataIo() {}
    public static boolean queue(File file, CompoundTag data, LegacyRecordingQuota.SavedDataGate gate) {
        return queue(file, data, gate, ignored -> { });
    }
    /**
     * Queue from the owning game thread; never waits for I/O. False means no worker was accepted.
     * A rejection callback is synchronous; accepted work reports on the existing I/O worker after its attempt.
     * COMMITTED requires atomic force/move and quota reconciliation, not queue admission. Callbacks must not block,
     * access live game state, or append to NeoForge's game-thread-owned save chain from the worker.
     */
    public static boolean queue(File file, CompoundTag data, LegacyRecordingQuota.SavedDataGate gate,
            Consumer<WriteResult> completion) {
        Objects.requireNonNull(completion);
        final LegacyRecordingQuota.Ticket ticket;
        final CompoundTag copied;
        try {
            CompoundTag outer = new CompoundTag(); outer.put("data", data); NbtUtils.addCurrentDataVersion(outer);
            copied = outer.copy(); ticket = gate.capture(file.toPath(), data.getString("state"));
        } catch (IOException | RuntimeException rejected) {
            gate.rejected(rejected); notifyCompletion(completion, new WriteResult(WriteStatus.REJECTED, null)); return false;
        }
        // The real NeoForge worker is also awaited by the normal server save/shutdown path.
        try {
            IOUtilities.withIOWorker(() -> {
                try {
                    ticket.validateBeforeWrite();
                    if (ticket.enforced()) {
                        IOUtilities.atomicWrite(file.toPath(), stream -> {
                            try (var bounded = new BoundedOutput(stream, LegacyRecordingQuota.SAVED_DATA_WRITE_BOUND);
                                 var buffered = new BufferedOutputStream(bounded)) {
                                NbtIo.writeCompressed(copied, buffered);
                            }
                        });
                    } else IOUtilities.writeNbtCompressed(copied, file.toPath());
                    gate.completed(ticket);
                } catch (IOException | RuntimeException failure) {
                    try { ticket.measureFailedIo(); } catch (IOException measurementFailure) { failure.addSuppressed(measurementFailure); }
                    gate.failed(ticket, failure);
                    MythicTrpg.LOGGER.error("Recording SavedData write failed; bounded reservation retained for retry", failure);
                    notifyCompletion(completion, new WriteResult(WriteStatus.FAILED, null));
                    return;
                }
                // Keep consumer failures OUTSIDE the I/O failure path: the ticket is already closed and the write is durable.
                notifyCompletion(completion, new WriteResult(WriteStatus.COMMITTED, copied));
            });
            return true;
        } catch (RuntimeException failure) {
            gate.failed(ticket, failure); notifyCompletion(completion, new WriteResult(WriteStatus.REJECTED, null)); return false;
        }
    }
    private static void notifyCompletion(Consumer<WriteResult> completion, WriteResult result) {
        try { completion.accept(result); }
        catch (RuntimeException unavailable) {
            // No saved body, callback exception message or fabricated retry of an already committed file.
            MythicTrpg.LOGGER.warn("Recording SavedData completion consumer unavailable; write result remains unchanged");
        }
    }
    private static final class BoundedOutput extends FilterOutputStream {
        private long remaining;
        BoundedOutput(OutputStream out, long bound) { super(out); remaining = bound; }
        @Override public void write(int b) throws IOException {
            if (remaining == 0) throw new IOException("SAVEDDATA_MAX_GROWTH_EXCEEDED");
            out.write(b); remaining--;
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length < 0 || length > remaining) throw new IOException("SAVEDDATA_MAX_GROWTH_EXCEEDED");
            out.write(bytes, offset, length); remaining -= length;
        }
    }
}
