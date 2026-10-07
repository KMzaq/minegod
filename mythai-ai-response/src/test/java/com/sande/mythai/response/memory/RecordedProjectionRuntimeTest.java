package com.sande.mythai.response.memory;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.Status;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic ports/transports only. No game boot, JDBC, network or LLM. */
public final class RecordedProjectionRuntimeTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final class Port implements MemoryProjectionPort {
        final AtomicInteger claims = new AtomicInteger(), commits = new AtomicInteger(), finishes = new AtomicInteger();
        volatile WorkOutcome outcome;
        volatile Status commitStatus = Status.STORED;
        volatile boolean revoked;
        final Work work = work();
        public CompletionStage<ClaimResult> claimWork(ProjectionWorkerCapability capability, WorkBudget budget) {
            claims.incrementAndGet(); check(budget.maxSources() <= 6 && budget.maxUtf8Bytes() <= 16384, "bounded request");
            return CompletableFuture.completedFuture(new ClaimResult(Status.CLAIMED, Optional.of(work), "CLAIMED"));
        }
        public CompletionStage<Result> commitProjection(ProjectionWorkToken token, List<Candidate> candidates) {
            check(token == work.token(), "same opaque lease"); check(!ModelAdmission.status().optionalActive(), "transport permit released before DB commit");
            commits.incrementAndGet(); return CompletableFuture.completedFuture(new Result(commitStatus, commitStatus.name(), 1));
        }
        public CompletionStage<Result> finishWork(ProjectionWorkToken token, WorkOutcome outcome, String reason) {
            this.outcome = outcome; finishes.incrementAndGet();
            check(!Thread.currentThread().isInterrupted(), "deferral not lost to interrupt flag");
            return CompletableFuture.completedFuture(new Result(Status.DEFERRED, reason, 0));
        }
        public void revokeWorker(ProjectionWorkerCapability capability) { revoked = true; }
    }
    private static Work work() {
        var god = new ActorRef(ActorKind.GOD, "mythictrpg:test_god");
        var player = new ActorRef(ActorKind.PLAYER, UUID.randomUUID().toString());
        var evidence = new Evidence("e0", new SourceRef(UUID.randomUUID(), UUID.randomUUID(), SourceKind.DIALOGUE_DIRECT,
                "room-publication-v2", UUID.randomUUID().toString(), 1, "a".repeat(64)), UUID.randomUUID(), "b".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), player, god.id(), Set.of(player, god), "c".repeat(64),
                "STANDARD", "PERSONAL", Instant.parse("2026-01-01T00:00:00Z"), "내일 돌아올게", false, 7);
        return new Work(ProjectionWorkToken.unregistered(), "fixture-v1", List.of(evidence), "e0", System.currentTimeMillis() + 45000);
    }
    private static List<Candidate> candidate(Work work) {
        return List.of(new Candidate(Layer.EVENT, ClaimKind.INTENTION_OR_PROMISE,
                List.of(new Quote(work.targetAlias(), work.evidence().getFirst().text())), List.of()));
    }
    private static RecordedProjectionRuntime runtime(Port port, RecordedProjectionRuntime.Extractor extractor) {
        return new RecordedProjectionRuntime(port, ProjectionWorkerCapability.unregistered(), extractor);
    }
    public static void main(String[] args) throws Exception {
        try {
            ModelAdmission.players(0);
            var success = new Port();
            try (var runtime = runtime(success, work -> { check(ModelAdmission.status().optionalActive(), "model admission held"); return candidate(work); })) {
                runtime.pump(); runtime.awaitIdle(); check(success.claims.get() == 0, "default ineligible");
                runtime.eligible(true); ModelAdmission.players(1); runtime.pump(); runtime.awaitIdle();
                check(success.claims.get() == 0, "online player prevents claim");
                ModelAdmission.players(0);
                try (var foreground = ModelAdmission.foreground()) { runtime.pump(); runtime.awaitIdle(); }
                check(success.claims.get() == 0, "foreground prevents claim");
                runtime.pump(); runtime.awaitIdle();
                check(success.commits.get() == 1 && success.finishes.get() == 0, "valid result commits once");
            }
            var error = new Port();
            try (var runtime = runtime(error, work -> { throw new IllegalArgumentException("invalid model data"); })) {
                runtime.eligible(true); runtime.pump(); runtime.awaitIdle();
                check(error.outcome == WorkOutcome.FAILED && error.commits.get() == 0, "model failure durable FAILED");
            }
            var timeout = new Port();
            try (var runtime = runtime(timeout, work -> { throw new TimeoutException("backend exceeded budget"); })) {
                runtime.eligible(true); runtime.pump(); runtime.awaitIdle();
                check(timeout.outcome == WorkOutcome.FAILED, "actual model timeout consumes retry");
            }
            var rejected = new Port(); rejected.commitStatus = Status.REJECTED;
            try (var runtime = runtime(rejected, RecordedProjectionRuntimeTest::candidate)) {
                runtime.eligible(true); runtime.pump(); runtime.awaitIdle();
                check(rejected.commits.get() == 1 && rejected.finishes.get() == 0, "storage rejected already accounts attempt");
            }
            for (Status status : List.of(Status.FULL, Status.UNAVAILABLE, Status.DEFERRED)) {
                var full = new Port(); full.commitStatus = status;
                try (var runtime = runtime(full, RecordedProjectionRuntimeTest::candidate)) {
                    runtime.eligible(true); runtime.pump(); runtime.awaitIdle();
                    check(full.outcome == WorkOutcome.DEFERRED, "temporary storage admission is not model failure");
                }
            }
            var preempted = new Port();
            var entered = new CountDownLatch(1); var unwinding = new CountDownLatch(1); var release = new CountDownLatch(1);
            try (var runtime = runtime(preempted, work -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) {
                    unwinding.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test cleanup timeout");
                    throw interrupted;
                }
                return candidate(work);
            })) {
                runtime.eligible(true); runtime.pump(); check(entered.await(5, TimeUnit.SECONDS), "worker started");
                for (int i = 0; i < 20; i++) runtime.pump();
                check(preempted.claims.get() == 1, "no growing background model queue");
                try (var foreground = ModelAdmission.foreground()) {
                    check(unwinding.await(5, TimeUnit.SECONDS), "foreground interrupts background");
                    check(ModelAdmission.status().optionalActive(), "permit retained during actual unwind");
                    release.countDown(); runtime.awaitIdle();
                    check(!ModelAdmission.status().optionalActive(), "permit released after unwind");
                }
                check(preempted.outcome == WorkOutcome.DEFERRED && preempted.commits.get() == 0, "preemption defers without committing");
            } finally { release.countDown(); }
            var stopped = new Port();
            var runtime = runtime(stopped, RecordedProjectionRuntimeTest::candidate);
            runtime.eligible(true); runtime.close(); runtime.pump(); runtime.awaitIdle();
            check(stopped.claims.get() == 0, "closed worker cannot reopen");
            check(stopped.revoked, "close withdraws the work capability");
            System.out.println("RecordedProjectionRuntimeTest: " + checks + " checks passed; no network/LLM/game");
        } finally { ModelAdmission.players(-1); }
    }
}
