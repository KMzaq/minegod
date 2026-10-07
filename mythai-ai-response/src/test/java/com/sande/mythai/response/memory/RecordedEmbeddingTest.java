package com.sande.mythai.response.memory;

import com.google.gson.*;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.EmbeddingRecords.*;
import com.sande.mythictrpg.recording.api.EmbeddingRecords.Status;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Fake transport/port, actual native consumer and backend contract. No network or model. */
public final class RecordedEmbeddingTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static final String DIGEST = "a".repeat(64);
    static final ModelSpace SPACE = new ModelSpace("bge-m3:latest", DIGEST, 2, EmbeddingRecords.ENCODER_VERSION);
    static Work work() {
        var god = new ActorRef(ActorKind.GOD, "mythictrpg:test_god");
        var player = new ActorRef(ActorKind.PLAYER, UUID.randomUUID().toString());
        return new Work(EmbeddingWorkToken.unregistered(), SPACE, UUID.randomUUID(),
                new SourceRef(UUID.randomUUID(), UUID.randomUUID(), SourceKind.DIALOGUE_DIRECT, "room-publication-v2",
                        UUID.randomUUID().toString(), 1, "b".repeat(64)), UUID.randomUUID(), "c".repeat(64), player, god.id(),
                Set.of(player, god), "d".repeat(64), "내일 돌아올게", RecordingRecords.sha256("내일 돌아올게"), 7, 7,
                System.currentTimeMillis() + 45000);
    }
    private static final class Port implements MemoryEmbeddingPort {
        final AtomicInteger claims = new AtomicInteger(), commits = new AtomicInteger(), finishes = new AtomicInteger();
        final Work work = work();
        volatile Status status = Status.STORED;
        volatile WorkOutcome outcome;
        boolean revoked;
        public CompletionStage<ClaimResult> claimWork(EmbeddingWorkerCapability capability, int seconds) {
            check(seconds == 45, "bounded lease"); claims.incrementAndGet();
            return CompletableFuture.completedFuture(new ClaimResult(Status.CLAIMED, Optional.of(work), "CLAIMED"));
        }
        public CompletionStage<Result> commitEmbedding(EmbeddingWorkToken token, float[] values) {
            check(token == work.token(), "original opaque token"); check(values.length == 2, "only native vector values returned");
            check(!ModelAdmission.status().optionalActive(), "transport already unwound before commit");
            commits.incrementAndGet(); return CompletableFuture.completedFuture(new Result(status, status.name()));
        }
        public CompletionStage<Result> finishWork(EmbeddingWorkToken token, WorkOutcome outcome, String reason) {
            check(!Thread.currentThread().isInterrupted(), "interruption cannot suppress durable deferral");
            finishes.incrementAndGet(); this.outcome = outcome; return CompletableFuture.completedFuture(new Result(Status.STORED, reason));
        }
        public void revokeWorker(EmbeddingWorkerCapability capability) { revoked = true; }
    }
    static MemoryIndexSettings settings() {
        return new MemoryIndexSettings(true, MemoryIndexSettings.Mode.ON, false, 8192, 100,
                URI.create("http://127.0.0.1:11434"), SPACE.modelName(), DIGEST, 2, "", "", 350, 30000,
                new MemoryIndexSettings.Execution(true, 4, 0, true, 4, 0, 768, 0, .75, false));
    }
    private static RecordedEmbeddingRuntime runtime(Port port, RecordedEmbeddingRuntime.Encoder encoder) {
        return new RecordedEmbeddingRuntime(port, EmbeddingWorkerCapability.unregistered(), encoder);
    }
    public static void main(String[] args) throws Exception {
        try {
            backend(); ModelAdmission.players(0);
            var port = new Port();
            try (var runtime = runtime(port, work -> { check(ModelAdmission.status().optionalActive(), "one model permit"); return new float[]{1, 0}; })) {
                runtime.pump(); runtime.awaitIdle(); check(port.claims.get() == 0, "default OFF");
                runtime.eligible(true); ModelAdmission.players(1); runtime.pump(); runtime.awaitIdle(); check(port.claims.get() == 0, "players prevent claim");
                ModelAdmission.players(0);
                try (var foreground = ModelAdmission.foreground()) { runtime.pump(); runtime.awaitIdle(); }
                check(port.claims.get() == 0, "foreground priority");
                runtime.pump(); runtime.awaitIdle(); check(port.commits.get() == 1 && port.finishes.get() == 0, "one successful commit");
            }
            check(port.revoked, "close revokes capability");
            for (boolean timeout : List.of(false, true)) {
                var bad = new Port();
                try (var runtime = runtime(bad, work -> { if (timeout) throw new TimeoutException(); throw new IllegalArgumentException(); })) {
                    runtime.eligible(true); runtime.pump(); runtime.awaitIdle();
                    check(bad.outcome == WorkOutcome.FAILED && bad.commits.get() == 0, "actual model failure/timeout bounded retry");
                }
            }
            for (Status status : List.of(Status.FULL, Status.UNAVAILABLE, Status.DEFERRED, Status.REJECTED)) {
                var blocked = new Port(); blocked.status = status;
                try (var runtime = runtime(blocked, work -> new float[]{1, 0})) {
                    runtime.eligible(true); runtime.pump(); runtime.awaitIdle();
                    check(status == Status.REJECTED ? blocked.finishes.get() == 0 : blocked.outcome == WorkOutcome.DEFERRED, "storage outcome accounted once");
                }
            }
            preemption();
            check(RecordedEmbeddingService.backgroundTurn(false, false, false), "sole projection can start");
            check(!RecordedEmbeddingService.backgroundTurn(false, true, false), "projection cannot monopolize next idle slot");
            check(!RecordedEmbeddingService.backgroundTurn(true, true, true), "active long task retains its turn");
            check(RecordedEmbeddingService.backgroundTurn(true, true, false), "embedding gets next idle turn");
            check(!RecordedEmbeddingService.backgroundTurn(true, true, false), "embedding cannot monopolize");
            check(RecordedEmbeddingService.backgroundTurn(false, true, false), "round robin returns to projection");
            RecordedSemanticShadowTest.run();
            System.out.println("RecordedEmbeddingTest: " + checks + " checks passed; native model/runtime + semantic SHADOW; no LLM/network");
        } finally { ModelAdmission.players(-1); }
    }
    private static void preemption() throws Exception {
        var port = new Port(); var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var runtime = runtime(port, work -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException expected) { interrupted.countDown(); release.await(5, TimeUnit.SECONDS); throw expected; }
            return new float[]{1, 0};
        })) {
            runtime.eligible(true); runtime.pump(); check(entered.await(5, TimeUnit.SECONDS), "worker entered");
            for (int i = 0; i < 20; i++) runtime.pump(); check(port.claims.get() == 1, "bounded queue");
            try (var foreground = ModelAdmission.foreground()) {
                check(interrupted.await(5, TimeUnit.SECONDS), "foreground interrupts HTTP owner");
                check(ModelAdmission.status().optionalActive(), "permit held during actual unwind");
                release.countDown(); runtime.awaitIdle();
                check(!ModelAdmission.status().optionalActive(), "permit released after unwind");
            }
            check(port.outcome == WorkOutcome.DEFERRED && port.commits.get() == 0, "preempted vector not committed");
        } finally { release.countDown(); }
    }
    private static void backend() throws Exception {
        var settings = settings(); var requests = new AtomicInteger();
        var backend = new OllamaMemoryBackend(settings, (uri, body, timeout) -> {
            requests.incrementAndGet();
            if (body == null) return "{\"models\":[{\"name\":\"bge-m3:latest\",\"digest\":\"" + DIGEST + "\"}]}";
            var data = JsonParser.parseString(body).getAsJsonObject();
            check(uri.getPath().equals("/api/embed"), "only embedding endpoint");
            check(!data.get("truncate").getAsBoolean(), "no hidden backend truncation");
            check(data.getAsJsonObject("options").get("num_gpu").getAsInt() == 0, "existing CPU policy");
            check(timeout <= 30000, "existing timeout bound");
            return "{\"model\":\"bge-m3:latest\",\"embeddings\":[[1,0]]}";
        });
        var model = new RecordedEmbeddingModel(settings, backend);
        check(Arrays.equals(model.embed(work()), new float[]{1, 0}), "game-issued work encoded");
        var query = model.query("옛 약속 기억해?");
        check(query.inputHash().equals(RecordingRecords.sha256("옛 약속 기억해?")) && query.modelSpace().equals(SPACE), "native query hash and space");
        check(!SPACE.fingerprint().equals(settings.fingerprint()), "legacy fingerprint not mislabeled native");
        check(requests.get() == 6, "digest verified before and after each request");
        boolean rejected = false; try { model.query("x".repeat(1601)); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected && requests.get() == 6, "oversized query rejected before transport");
        var changed = new RecordedEmbeddingModel(settings, new OllamaMemoryBackend(settings, (uri, body, timeout) ->
                "{\"models\":[{\"name\":\"bge-m3:latest\",\"digest\":\"" + "b".repeat(64) + "\"}]}"));
        rejected = false; try { changed.query("약속"); } catch (Exception expected) { rejected = true; }
        check(rejected, "model digest mismatch closed");
    }
}
