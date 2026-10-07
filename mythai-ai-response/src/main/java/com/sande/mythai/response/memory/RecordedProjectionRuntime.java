package com.sande.mythai.response.memory;

import com.sande.mythictrpg.recording.api.MemoryProjectionPort;
import com.sande.mythictrpg.recording.api.ProjectionWorkerCapability;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded idle consumer. It has no Minecraft references, raw file access or gameplay authority. */
public final class RecordedProjectionRuntime implements AutoCloseable {
    @FunctionalInterface public interface Extractor { List<Candidate> extract(Work work) throws Exception; }
    private final MemoryProjectionPort port;
    private final ProjectionWorkerCapability capability;
    private final Extractor extractor;
    private final ThreadPoolExecutor worker;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean eligible, closed;
    private volatile Thread activeThread;
    private volatile String diagnostic = "IDLE";
    private volatile CompletableFuture<Void> lastRun = CompletableFuture.completedFuture(null);

    public RecordedProjectionRuntime(MemoryProjectionPort port, ProjectionWorkerCapability capability, Extractor extractor) {
        this.port = java.util.Objects.requireNonNull(port);
        this.capability = java.util.Objects.requireNonNull(capability);
        this.extractor = java.util.Objects.requireNonNull(extractor);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            var thread = new Thread(task, "mythai-recorded-projection"); thread.setDaemon(true); return thread;
        });
    }
    /** A game-thread signal only. Authority is rechecked independently by the storage transaction. */
    public void eligible(boolean value) {
        eligible = value;
        if (!value) { var thread = activeThread; if (thread != null) thread.interrupt(); }
    }
    public String diagnostic() { return diagnostic; }
    public boolean running() { return running.get(); }
    public synchronized void pump() {
        if (closed || !eligible || ModelAdmission.status().onlinePlayers() != 0
                || !running.compareAndSet(false, true)) return;
        var done = new CompletableFuture<Void>(); lastRun = done;
        try {
            worker.execute(() -> {
                activeThread = Thread.currentThread();
                try { maintainOne(); }
                finally { activeThread = null; Thread.interrupted(); running.set(false); done.complete(null); }
            });
        } catch (RejectedExecutionException stopped) { running.set(false); done.complete(null); }
    }
    private boolean allowed() {
        return !closed && eligible && ModelAdmission.status().onlinePlayers() == 0;
    }
    private void maintainOne() {
        Work work = null;
        boolean extracting = false;
        try {
            if (!allowed()) return;
            var admission = ModelAdmission.status();
            if (admission.foregroundActive() != 0 || admission.foregroundPending() != 0 || admission.optionalActive()) {
                diagnostic = "DEFERRED_BUSY"; return;
            }
            var claimed = port.claimWork(capability, new WorkBudget(6, 16384, 45)).toCompletableFuture().get(3, TimeUnit.SECONDS);
            diagnostic = claimed.status() + ":" + claimed.reasonCode();
            if (claimed.work().isEmpty()) return;
            work = claimed.work().get();
            if (!allowed()) { finish(work, WorkOutcome.DEFERRED, "POLICY_OR_PLAYERS_CHANGED"); return; }
            List<Candidate> candidates;
            // Keep the hardware permit until the transport has actually unwound, including cancellation.
            try (var lease = ModelAdmission.optional(true)) {
                if (lease == null) { finish(work, WorkOutcome.DEFERRED, "FOREGROUND_BUSY"); return; }
                extracting = true;
                candidates = extractor.extract(work);
                extracting = false;
            }
            if (!allowed() || Thread.currentThread().isInterrupted()) {
                finish(work, WorkOutcome.DEFERRED, "FOREGROUND_OR_POLICY_CHANGED"); return;
            }
            var stored = port.commitProjection(work.token(), candidates).toCompletableFuture().get(3, TimeUnit.SECONDS);
            diagnostic = stored.status() + ":" + stored.reasonCode();
            // REJECTED already consumes the token and records a bounded failed attempt in storage.
            if (stored.status() == Status.FULL || stored.status() == Status.UNAVAILABLE || stored.status() == Status.DEFERRED)
                finish(work, WorkOutcome.DEFERRED, "STORAGE_DEFERRED");
        } catch (InterruptedException interrupted) {
            if (work != null) finish(work, WorkOutcome.DEFERRED, "INTERRUPTED");
            else diagnostic = "DEFERRED_INTERRUPTED_CLAIM";
        } catch (Exception failed) {
            boolean deferred = !allowed() || Thread.currentThread().isInterrupted()
                    || !extracting && failed instanceof TimeoutException;
            if (work != null) finish(work, deferred ? WorkOutcome.DEFERRED : WorkOutcome.FAILED,
                    deferred ? "INTERRUPTED_OR_STORAGE_TIMEOUT" : "EXTRACTION_FAILED");
            else diagnostic = "CLAIM_UNAVAILABLE";
        }
    }
    private void finish(Work work, WorkOutcome outcome, String reason) {
        // Preemption interrupts the model transport; do not let that flag prevent durable deferral.
        boolean interrupted = Thread.interrupted();
        try {
            var result = port.finishWork(work.token(), outcome, reason).toCompletableFuture().get(3, TimeUnit.SECONDS);
            diagnostic = outcome + ":" + result.status() + ":" + result.reasonCode();
        } catch (Exception unconfirmed) {
            diagnostic = "FINISH_UNCONFIRMED_LEASE_WILL_EXPIRE";
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    /** Test/shutdown helper, never called from a live server tick. */
    public void awaitIdle() throws Exception { lastRun.get(10, TimeUnit.SECONDS); }
    @Override public void close() {
        closed = true; eligible(false);
        try { port.revokeWorker(capability); }
        catch (RuntimeException unavailable) { diagnostic = "REVOCATION_UNCONFIRMED"; }
        finally { worker.shutdown(); }
        // The active task owns transport cleanup and durable deferral. No synchronous game-thread wait.
    }
    public boolean drained() { return worker.isTerminated(); }
}
