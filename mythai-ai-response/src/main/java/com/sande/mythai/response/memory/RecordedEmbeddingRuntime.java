package com.sande.mythai.response.memory;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.EmbeddingRecords.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One idle native worker. A lease supplies every source binding; only vector values go back. */
public final class RecordedEmbeddingRuntime implements AutoCloseable {
    @FunctionalInterface public interface Encoder { float[] encode(Work work) throws Exception; }
    private final MemoryEmbeddingPort port;
    private final EmbeddingWorkerCapability capability;
    private final Encoder encoder;
    private final ThreadPoolExecutor worker;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean eligible, closed;
    private volatile Thread active;
    private volatile String diagnostic = "IDLE";
    private volatile CompletableFuture<Void> lastRun = CompletableFuture.completedFuture(null);
    public RecordedEmbeddingRuntime(MemoryEmbeddingPort port, EmbeddingWorkerCapability capability, Encoder encoder) {
        this.port = java.util.Objects.requireNonNull(port); this.capability = java.util.Objects.requireNonNull(capability);
        this.encoder = java.util.Objects.requireNonNull(encoder);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            var thread = new Thread(task, "mythai-recorded-embedding"); thread.setDaemon(true); return thread;
        });
    }
    public void eligible(boolean value) { eligible = value; if (!value) { var thread = active; if (thread != null) thread.interrupt(); } }
    public String diagnostic() { return diagnostic; }
    public boolean running() { return running.get(); }
    private boolean allowed() { return !closed && eligible && ModelAdmission.status().onlinePlayers() == 0; }
    public synchronized void pump() {
        if (!allowed() || !running.compareAndSet(false, true)) return;
        var done = new CompletableFuture<Void>(); lastRun = done;
        try { worker.execute(() -> {
            active = Thread.currentThread();
            try { one(); } finally { active = null; Thread.interrupted(); running.set(false); done.complete(null); }
        }); } catch (RejectedExecutionException stopped) { running.set(false); done.complete(null); }
    }
    private void one() {
        Work work = null; boolean encoding = false;
        try {
            if (!allowed()) return;
            var status = ModelAdmission.status();
            if (status.foregroundActive() != 0 || status.foregroundPending() != 0 || status.optionalActive()) {
                diagnostic = "DEFERRED_BUSY"; return;
            }
            var claimed = port.claimWork(capability, 45).toCompletableFuture().get(3, TimeUnit.SECONDS);
            diagnostic = claimed.status() + ":" + claimed.reasonCode();
            if (claimed.work().isEmpty()) return;
            work = claimed.work().get();
            if (!allowed()) { finish(work, WorkOutcome.DEFERRED, "POLICY_OR_PLAYERS_CHANGED"); return; }
            float[] vector;
            try (var permit = ModelAdmission.optional(true)) {
                if (permit == null) { finish(work, WorkOutcome.DEFERRED, "FOREGROUND_BUSY"); return; }
                encoding = true; vector = encoder.encode(work); encoding = false;
            }
            if (!allowed() || Thread.currentThread().isInterrupted()) { finish(work, WorkOutcome.DEFERRED, "POLICY_OR_FOREGROUND_CHANGED"); return; }
            var stored = port.commitEmbedding(work.token(), vector).toCompletableFuture().get(3, TimeUnit.SECONDS);
            diagnostic = stored.status() + ":" + stored.reasonCode();
            if (stored.status() == Status.FULL || stored.status() == Status.UNAVAILABLE || stored.status() == Status.DEFERRED)
                finish(work, WorkOutcome.DEFERRED, "STORAGE_DEFERRED");
        } catch (InterruptedException interrupted) {
            if (work != null) finish(work, WorkOutcome.DEFERRED, "INTERRUPTED"); else diagnostic = "DEFERRED_INTERRUPTED_CLAIM";
        } catch (Exception failure) {
            boolean deferred = !allowed() || Thread.currentThread().isInterrupted() || !encoding && failure instanceof TimeoutException;
            if (work != null) finish(work, deferred ? WorkOutcome.DEFERRED : WorkOutcome.FAILED,
                    deferred ? "INTERRUPTED_OR_STORAGE_TIMEOUT" : "EMBEDDING_FAILED");
            else diagnostic = "CLAIM_UNAVAILABLE";
        }
    }
    private void finish(Work work, WorkOutcome outcome, String reason) {
        boolean interrupted = Thread.interrupted();
        try { var stored = port.finishWork(work.token(), outcome, reason).toCompletableFuture().get(3, TimeUnit.SECONDS);
            diagnostic = outcome + ":" + stored.status() + ":" + stored.reasonCode();
        } catch (Exception unconfirmed) { diagnostic = "FINISH_UNCONFIRMED_LEASE_WILL_EXPIRE"; }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    public void awaitIdle() throws Exception { lastRun.get(10, TimeUnit.SECONDS); }
    public boolean drained() { return worker.isTerminated(); }
    public void close() {
        closed = true; eligible(false);
        try { port.revokeWorker(capability); } catch (RuntimeException ignored) { diagnostic = "REVOCATION_UNCONFIRMED"; }
        finally { worker.shutdown(); }
    }
}
