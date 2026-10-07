package com.sande.mythictrpg.gameplay.ledger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import com.sande.mythictrpg.recording.server.LegacyRecordingQuota;
import com.sande.mythictrpg.recording.server.ManagedStoreRegistry;

/** One bounded queue/one writer per world. Producer never waits for storage or calls game consumers. */
public final class AsyncActionLedger implements AutoCloseable {
    public enum State { STARTING, READY, CLOSING, CLOSED, FAILED }
    public enum Acceptance { PENDING_NOT_DURABLE, UNAVAILABLE, QUEUE_FULL, CONFLICT, QUOTA_REJECTED }
    public record Submission(Acceptance acceptance, CompletableFuture<ActionRecord> durable, boolean transitionRetry) {
        public Submission(Acceptance acceptance,CompletableFuture<ActionRecord> durable) {this(acceptance,durable,false);}
    }
    public record Status(State state, String reason, long usedBytes, long maxBytes, long committedSequence,
                         int indexedEvents, int pending, long rejected, long firstGapUtc, long lastGapUtc,
                         boolean recoveredUnclean, long previousCheckpointUtc) {
        public double usedRatio() { return maxBytes == 0 ? 0 : (double)usedBytes / maxBytes; }
    }
    private interface Work { void run(ActionLedgerStore store) throws Exception; void fail(Throwable failure); }
    private record Pending(ActionRecord.Draft draft, CompletableFuture<ActionRecord> future) {}
    private final BlockingQueue<Work> queue;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final ActionLedgerStore.Limits limits;
    private final Path directory;
    private final Thread worker;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private volatile Status status;
    private boolean closing;
    private long rejected, firstGapUtc, lastGapUtc;
    private String gapReason = "NONE";
    private String pendingGapFailure;

    public AsyncActionLedger(Path path, UUID worldId, ActionLedgerStore.Limits limits, int queueCapacity) {
        this(path, worldId, limits, queueCapacity, ActionLedgerStore.NO_FAULTS);
    }
    public AsyncActionLedger(Path path, UUID worldId, ActionLedgerStore.Limits limits, int queueCapacity,
                             ActionLedgerStore.Faults faults) {
        if (queueCapacity < 1 || queueCapacity > 4096) throw new IllegalArgumentException("queue capacity");
        this.limits = limits; this.directory = path.toAbsolutePath().normalize(); queue = new ArrayBlockingQueue<>(queueCapacity);
        status = new Status(State.STARTING, "OPENING", 0, limits.maxBytes(), 0, 0, 0, 0, 0, 0, false, 0);
        worker = new Thread(() -> run(path, worldId, faults), "mythictrpg-action-ledger");
        worker.setDaemon(true); worker.start();
    }
    public synchronized Submission submit(ActionRecord.Draft draft) {
        return submit(draft,false);
    }
    public synchronized Submission submitTransition(ActionRecord.Draft draft) { return submit(draft,true); }
    private Submission submit(ActionRecord.Draft draft,boolean transition) {
        Objects.requireNonNull(draft);
        Pending same = pending.get(draft.occurrenceId());
        if (same != null) {
            if (same.draft.equals(draft) || transition && ActionRecord.sameTransition(same.draft,draft))
                return new Submission(Acceptance.PENDING_NOT_DURABLE, same.future,transition);
            gap("DUPLICATE_ID_CONFLICT");
            return rejected(Acceptance.CONFLICT);
        }
        if (closing || status.state != State.READY) { gap("UNAVAILABLE:" + status.state); return rejected(Acceptance.UNAVAILABLE); }
        if (queue.remainingCapacity() == 0) { gap("QUEUE_FULL"); return rejected(Acceptance.QUEUE_FULL); }
        final LegacyRecordingQuota.Ticket quota;
        try { quota = LegacyRecordingQuota.reserve(directory, ManagedStoreRegistry.ACTION_LEDGER, ActionLedgerStore.APPEND_WRITE_BOUND, false); }
        catch (IOException denied) { gap("QUOTA:" + denied.getMessage()); return rejected(Acceptance.QUOTA_REJECTED); }
        CompletableFuture<ActionRecord> receipt = new CompletableFuture<>();
        Work work = new Work() {
            private boolean began;
            public void run(ActionLedgerStore store) throws Exception {
                began = true;
                ActionRecord result = LegacyRecordingQuota.within(quota,
                        () -> transition ? store.appendTransition(draft) : store.append(draft));
                synchronized (AsyncActionLedger.this) { pending.remove(draft.occurrenceId()); publish(store, State.READY, "READY"); }
                receipt.complete(result);
            }
            public void fail(Throwable failure) {
                if (!began) quota.cancelUnstarted();
                synchronized (AsyncActionLedger.this) { pending.remove(draft.occurrenceId()); }
                receipt.completeExceptionally(failure);
            }
        };
        if (!queue.offer(work)) { quota.cancelUnstarted(); gap("QUEUE_FULL"); return rejected(Acceptance.QUEUE_FULL); }
        pending.put(draft.occurrenceId(), new Pending(draft, receipt));
        return new Submission(Acceptance.PENDING_NOT_DURABLE, receipt,transition);
    }
    private Submission rejected(Acceptance reason) {
        return new Submission(reason, CompletableFuture.failedFuture(new IOException(reason.name())));
    }
    /** A worker-only read. No unbounded task queue or main-thread file scans. */
    public synchronized CompletableFuture<ActionLedgerStore.Page> after(ActionLedgerStore.Cursor cursor, UUID actor, int limit) {
        CompletableFuture<ActionLedgerStore.Page> result = new CompletableFuture<>();
        if (closing || status.state != State.READY) return CompletableFuture.failedFuture(new IOException("LEDGER_UNAVAILABLE"));
        Work work = new Work() {
            public void run(ActionLedgerStore store) throws IOException {
                try { result.complete(store.after(cursor, actor, limit)); }
                catch (IllegalArgumentException invalid) { result.completeExceptionally(invalid); }
            }
            public void fail(Throwable failure) { result.completeExceptionally(failure); }
        };
        if (!queue.offer(work)) result.completeExceptionally(new IOException("QUERY_QUEUE_FULL"));
        return result;
    }
    public synchronized void gap(String reason) {
        gap(reason, 1);
    }
    public synchronized void gap(String reason, long count) {
        if (count < 1) return;
        rejected += count; long now = System.currentTimeMillis(); if (firstGapUtc == 0) firstGapUtc = now;
        lastGapUtc = now; gapReason = reason.length() <= 256 ? reason : reason.substring(0, 256);
    }
    public synchronized Status status() {
        Status s = status;
        return new Status(s.state, s.reason, s.usedBytes, s.maxBytes, s.committedSequence, s.indexedEvents,
                pending.size(), rejected, firstGapUtc, lastGapUtc, s.recoveredUnclean, s.previousCheckpointUtc);
    }
    public CompletableFuture<Void> ready() { return ready; }
    public CompletableFuture<Void> stopped() { return stopped; }
    /** FIFO fence for game-owned watch transitions; includes earlier pending appends, not future captures. */
    public synchronized CompletableFuture<Long> fence() {
        if (closing || status.state != State.READY) return CompletableFuture.failedFuture(new IOException("LEDGER_UNAVAILABLE"));
        CompletableFuture<Long> result = new CompletableFuture<>();
        Work work = new Work() {
            public void run(ActionLedgerStore store) { result.complete(store.sequence()); }
            public void fail(Throwable failure) { result.completeExceptionally(failure); }
        };
        if (!queue.offer(work)) result.completeExceptionally(new IOException("FENCE_QUEUE_FULL"));
        return result;
    }
    private void run(Path path, UUID worldId, ActionLedgerStore.Faults faults) {
        ActionLedgerStore store = null; Work current = null;
        try {
            store = new ActionLedgerStore(path, worldId, limits, faults);
            synchronized (this) {
                var old = store.gaps(); rejected += old.rejected();
                if (old.firstUtc() != 0) firstGapUtc = firstGapUtc == 0 ? old.firstUtc() : Math.min(firstGapUtc, old.firstUtc());
                lastGapUtc = Math.max(lastGapUtc, old.lastUtc());
                if (gapReason.equals("NONE")) gapReason = old.reason();
                publish(store, State.READY, "READY");
            }
            ready.complete(null);
            long savedGaps = -1;
            while (true) {
                synchronized (this) { if (closing && queue.isEmpty()) break; }
                current = queue.poll(250, TimeUnit.MILLISECONDS);
                if (current != null) { current.run(store); current = null; }
                ActionLedgerStore.GapSummary summary;
                synchronized (this) { summary = new ActionLedgerStore.GapSummary(rejected, firstGapUtc, lastGapUtc, gapReason); }
                if (summary.rejected() != savedGaps) {
                    try { store.updateGaps(summary); savedGaps = summary.rejected(); pendingGapFailure = null; }
                    catch (IOException unavailable) {
                        String reason = String.valueOf(unavailable.getMessage());
                        if (!reason.equals("FULL") && !reason.equals("MAINTENANCE_HEADROOM") && !reason.startsWith("QUOTA_")) throw unavailable;
                        // A full metadata reserve is a RAM gap warning, not permission to erase raw history or block reads.
                        pendingGapFailure = reason;
                    }
                }
                synchronized (this) { publish(store, closing ? State.CLOSING : State.READY, closing ? "DRAINING" : "READY"); }
            }
            ActionLedgerStore.GapSummary finalGaps;
            synchronized (this) { finalGaps = new ActionLedgerStore.GapSummary(rejected, firstGapUtc, lastGapUtc, gapReason); }
            store.updateGaps(finalGaps);
            store.close(); synchronized (this) { publish(store, State.CLOSED, "CLEAN_CLOSE"); }
            stopped.complete(null);
        } catch (Throwable failure) {
            synchronized (this) { gap("WRITER_FAILED:" + failure.getMessage()); closing = true; }
            if (current != null) current.fail(failure);
            Work remaining; while ((remaining = queue.poll()) != null) { gap("DRAINED_AFTER_FAILURE"); remaining.fail(failure); }
            if (store != null) {
                // A control-only diagnostic may still be writable after a segment/limit failure.
                try {
                    ActionLedgerStore.GapSummary failedGaps;
                    synchronized (this) { failedGaps = new ActionLedgerStore.GapSummary(rejected, firstGapUtc, lastGapUtc, gapReason); }
                    store.updateGaps(failedGaps);
                }
                catch (Exception ignored) { /* Disk failure may prevent even diagnostic persistence; unclean marker remains. */ }
                store.abort();
            }
            synchronized (this) { publish(store, State.FAILED, String.valueOf(failure.getMessage())); }
            ready.completeExceptionally(failure); stopped.completeExceptionally(failure);
        }
    }
    private void publish(ActionLedgerStore store, State state, String reason) {
        Status previous = status;
        if (state == State.READY && pendingGapFailure != null) reason = "RAM_GAP_ONLY:" + pendingGapFailure;
        status = new Status(state, reason, store == null ? previous.usedBytes : store.usedBytes(), limits.maxBytes(),
                store == null ? previous.committedSequence : store.sequence(), store == null ? previous.indexedEvents : store.size(),
                pending.size(), rejected, firstGapUtc, lastGapUtc, store != null && store.recoveredUnclean(),
                store == null ? previous.previousCheckpointUtc : store.previousCheckpointUtc());
    }
    public synchronized void requestClose() { closing = true; }
    /** Bounded graceful drain. A timeout never pretends pending writes are durable or releases a live writer lock. */
    public boolean awaitClose(long millis) {
        requestClose();
        try { stopped.get(millis, TimeUnit.MILLISECONDS); return true; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (ExecutionException | TimeoutException failure) { return false; }
    }
    @Override public void close() { awaitClose(5000); }
}
