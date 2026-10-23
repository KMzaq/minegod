package com.sande.mythictrpg.recording.server;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deterministic delayed I/O, queued game dispatch and cancellation; no model or operating server. */
public final class CaptureReadFenceTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var fence = new CaptureReadFence(4);
        check(fence.snapshot().join(), "empty fence is settled");
        var raw = new CompletableFuture<Boolean>();
        var receipts = new CompletableFuture<Boolean>();
        fence.track(raw.thenCompose(ok -> receipts));
        var first = fence.snapshot();
        check(!first.isDone(), "pending RAW cannot be read as a fresh snapshot");
        raw.complete(true);
        check(!first.isDone(), "first commit is not completion of later receipt batches");
        var nextTurn = new CompletableFuture<Boolean>();
        fence.track(nextTurn);
        receipts.complete(true);
        check(first.join() && !nextTurn.isDone(), "later captures do not extend the snapshot");
        var otherWorld = new CaptureReadFence(4);
        check(otherWorld.snapshot().join(), "world lifecycles do not share completion fences");
        nextTurn.complete(false);
        check(fence.snapshot().join(), "settled failures are diagnosed by capture, not an eternal read outage");

        var failed = new CompletableFuture<Boolean>();
        fence.track(failed);
        var waitingFailure = fence.snapshot();
        failed.completeExceptionally(new IllegalStateException("disk unavailable"));
        check(!waitingFailure.join(), "pending write failure cannot claim read-after-write success");
        var incomplete = new CompletableFuture<Boolean>();
        fence.track(incomplete);
        var waitingIncomplete = fence.snapshot();
        incomplete.complete(false);
        check(!waitingIncomplete.join(), "incomplete delivery fails the waiter closed");

        var pressure = new CaptureReadFence(1);
        var a = new CompletableFuture<Boolean>(); var b = new CompletableFuture<Boolean>();
        pressure.track(a); pressure.track(b);
        check(!pressure.snapshot().join(), "bounded tracker overflow fails closed");
        b.complete(true);
        var recovered = pressure.snapshot();
        check(!recovered.isDone(), "overflow recovery still waits for known captures");
        a.complete(true);
        check(recovered.join(), "tracker recovers once admitted captures settle");

        var stop = new CompletableFuture<Boolean>();
        fence.track(stop); var stopped = fence.snapshot(); fence.close();
        check(!stopped.join() && !fence.snapshot().join(), "shutdown invalidates existing and new waits");
        check(!stop.isDone(), "shutdown of a read fence does not cancel durable I/O");
        stop.complete(true);
        check(!stopped.join(), "late completion cannot revive a stopped fence");

        var gameQueue = new ArrayDeque<Runnable>();
        var capture = new CompletableFuture<Boolean>();
        var watermark = new AtomicInteger(1); var issued = new AtomicInteger();
        var read = CaptureReadFence.after(capture, gameQueue::add, () -> true,
                () -> { issued.incrementAndGet(); return Optional.of(watermark.get()); }, 5000);
        check(!read.isDone() && issued.get() == 0, "read issuance does not block or run before capture");
        watermark.set(2); capture.complete(true);
        check(!read.isDone() && issued.get() == 0, "writer completion dispatches issuance to game thread");
        gameQueue.remove().run();
        check(read.join().equals(Optional.of(2)) && issued.get() == 1, "fixed watermark is acquired after commit");

        for (String changed : List.of("turn", "room/revision/audience", "engine", "mode", "runtime")) {
            var current = new AtomicBoolean(true);
            var delayed = new CompletableFuture<Boolean>();
            var invalidated = CaptureReadFence.after(delayed, gameQueue::add, current::get,
                    () -> { throw new AssertionError("stale issuance: " + changed); }, 5000);
            delayed.complete(true); current.set(false); gameQueue.remove().run();
            check(invalidated.join().isEmpty(), "revalidate after game dispatch: " + changed);
        }
        var cancelledCapture = new CompletableFuture<Boolean>();
        var cancelled = CaptureReadFence.after(cancelledCapture, gameQueue::add, () -> true,
                () -> { throw new AssertionError("cancelled issuance"); }, 5000);
        cancelledCapture.complete(true); cancelled.cancel(false); gameQueue.remove().run();
        check(cancelled.isCancelled() && !cancelledCapture.isCancelled(), "read cancellation cannot cancel capture or issue later");

        var late = new CompletableFuture<Boolean>();
        var timedOut = CaptureReadFence.after(late, gameQueue::add, () -> true,
                () -> { throw new AssertionError("timed-out issuance"); }, 20);
        check(timedOut.get(2, TimeUnit.SECONDS).isEmpty() && !late.isDone(), "bounded read timeout leaves writes alone");
        late.complete(true);
        check(gameQueue.isEmpty(), "late I/O does not queue expired read issuance");
        var rejected = CaptureReadFence.after(CompletableFuture.completedFuture(true), task -> {
            throw new RejectedExecutionException("server stopped");
        }, () -> true, () -> Optional.of("must not issue"), 5000);
        check(rejected.join().isEmpty(), "stopped executor fails closed");
        var denied = CaptureReadFence.after(CompletableFuture.completedFuture(false), Runnable::run, () -> true,
                () -> { throw new AssertionError("failed capture issuance"); }, 5000);
        check(denied.join().isEmpty(), "failed capture never reaches issuer");
        System.out.println("CaptureReadFenceTest: " + checks + " checks passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
