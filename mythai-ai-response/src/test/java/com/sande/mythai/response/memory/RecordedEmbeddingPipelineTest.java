package com.sande.mythai.response.memory;

import com.google.gson.*;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import com.sande.mythictrpg.recording.server.RecordingSettings;
import com.sande.mythictrpg.recording.server.WorldRecordingService;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Actual native capture -> leased worker -> backend with fake transport -> SQLite -> scoped semantic reader.
 * Build-directory synthetic worlds only. No model/socket, legacy import, or direct SQL mutations. */
public final class RecordedEmbeddingPipelineTest {
    private static int checks;
    private static final Gson JSON = new Gson();
    private static final String PRODUCER = "room-publication-v2", MODEL = "fixture:embed", DIGEST = "a".repeat(64);
    private static final ActorRef PLAYER = new ActorRef(ActorKind.PLAYER, UUID.randomUUID().toString());
    private static final ActorRef GOD_A = new ActorRef(ActorKind.GOD, "test:a"), GOD_B = new ActorRef(ActorKind.GOD, "test:b");
    private static final String QUERY = "옛 약속 기억해?";
    private static final Instant DATE = Instant.parse("2026-09-30T12:00:00Z");
    private static final RecordingSettings ARCHIVE = new RecordingSettings(RecordingSettings.Mode.SHADOW, 128_000_000, 2_000_000, .90, .95);
    private static final WorldRecordingService.CutoverBoundary BOUNDARY = new WorldRecordingService.CutoverBoundary("embedding-pipeline-fixture", Map.of());

    private static final class FakeTransport implements OllamaMemoryBackend.Transport {
        int posts, metadata;
        final List<String> inputs = new ArrayList<>();
        @Override public String request(URI uri, String body, int timeoutMs) {
            check(uri.getHost().equals("127.0.0.1") && timeoutMs > 0 && timeoutMs <= 30000, "production backend retains loopback and bounded timeout");
            check(ModelAdmission.status().optionalActive(), "actual runtime/query holds the shared model admission permit through transport");
            if (body == null) {
                check(uri.getPath().equals("/api/tags"), "digest verification uses metadata endpoint"); metadata++;
                return JSON.toJson(Map.of("models", List.of(Map.of("name", MODEL, "digest", DIGEST))));
            }
            check(uri.getPath().equals("/api/embed"), "only deterministic fake embedding endpoint is invoked"); posts++;
            JsonObject request = JsonParser.parseString(body).getAsJsonObject(); String text = request.get("input").getAsString(); inputs.add(text);
            check(request.get("model").getAsString().equals(MODEL) && request.get("dimensions").getAsInt() == 3
                    && !request.get("truncate").getAsBoolean(), "exact model/dimensions and no hidden backend truncation");
            check(request.getAsJsonObject("options").get("num_gpu").getAsInt() == 0
                    && request.getAsJsonObject("options").get("num_thread").getAsInt() == 2, "existing CPU/thread policy is reused");
            check(text.length() <= 1600 && !request.has("messages") && !request.has("sourceId") && !request.has("knowledgeReceiptId"),
                    "model receives only bounded allowed text, never game receipt identifiers or a fabricated journal");
            return JSON.toJson(Map.of("model", MODEL, "embeddings", List.of(text.startsWith("다른 신") ? List.of(0, 1, 0) : List.of(1, 0, 0))));
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args.length == 0 ? "build/recorded-embedding-pipeline-test" : args[0]).toAbsolutePath().normalize();
        if (!base.toString().replace('\\', '/').contains("/build/")) throw new IllegalArgumentException("BUILD_ONLY");
        Files.createDirectories(base); Path root = Files.createTempDirectory(base, "embedding-pipeline-");
        UUID world = UUID.randomUUID(), room = UUID.randomUUID(); WorldRecordingService store = null;
        var originals = new LinkedHashMap<UUID, String>(); var leased = new ArrayList<EmbeddingRecords.Work>();
        var fake = new FakeTransport(); var settings = settings();
        var model = new RecordedEmbeddingModel(settings, new OllamaMemoryBackend(settings, fake));
        try {
            ModelAdmission.players(0); store = open(root, world); var producer = producer(store);
            UUID dataset = store.datasetId().orElseThrow(), oldEpoch = store.runtimeEpoch();
            Path database = root.resolve("mythictrpg-recording-v2").resolve(dataset.toString()).resolve("recording.sqlite");
            String longText = "promise " + "x".repeat(1591) + "🍎" + " 나중에 붙인 원문의 끝.";
            UUID first = capture(store, producer, room, PLAYER, longText, Set.of(GOD_A), originals);
            UUID second = capture(store, producer, room, GOD_B, "다른 신이 자신의 청중에게 한 말.", Set.of(GOD_B), originals);
            UUID unheard = capture(store, producer, room, PLAYER, "아무 신도 듣지 못한 말.", Set.of(), originals);
            var worker = store.registerEmbeddingWorker("offline-embedding-fixture", model.space);
            try (var runtime = new RecordedEmbeddingRuntime(store.embeddingPort(), worker, work -> { leased.add(work); return model.embed(work); })) {
                runtime.pump(); runtime.awaitIdle(); check(fake.posts == 0, "idle runtime begins disabled");
                runtime.eligible(true);
                for (int i = 0; i < 5; i++) { runtime.pump(); runtime.awaitIdle(); }
                check(fake.posts == 2 && fake.metadata == 4, "two heard native sources each use exactly one request and before/after digest validation");
                check(!runtime.running(), "real native pending work drains without a permanent model queue");
            }
            check(leased.size() == 2 && leased.stream().noneMatch(w -> w.messageId().equals(unheard)), "archive-only unheard text never enters model work");
            var firstWork = leased.stream().filter(w -> w.messageId().equals(first)).findFirst().orElseThrow();
            check(firstWork.observerGodId().equals(GOD_A.id()) && firstWork.actualActor().equals(PLAYER)
                    && firstWork.text().equals(EmbeddingRecords.prefix(longText)) && firstWork.coveredCharacters() == 1599
                    && firstWork.totalCharacters() == longText.length() && firstWork.excerpt(), "issued work has exact safe prefix and authoritative actor/observer coverage");
            check(fake.inputs.contains(firstWork.text()) && fake.inputs.stream().noneMatch(t -> t.equals(longText) || t.contains("아무 신도")),
                    "transport receives actual allowed prefix only, not its unseen tail or unreceived message");
            check(leased.stream().anyMatch(w -> w.messageId().equals(second) && w.actualActor().equals(GOD_B) && w.observerGodId().equals(GOD_B.id())),
                    "native God speech preserves its actual actor instead of becoming a player journal entry");
            EmbeddingRecords.QueryVector needle;
            try (var permit = ModelAdmission.followup()) { check(permit != null, "explicit query shares foreground-preemptible admission"); needle = model.query(QUERY); }
            check(fake.posts == 3 && fake.metadata == 6 && needle.inputHash().equals(RecordingRecords.sha256(QUERY)), "query embedding also verifies digest on both sides and binds exact question");
            var a = session(store, GOD_A); var b = session(store, GOD_B);
            var pageA = semantic(a, needle); var pageB = semantic(b, needle);
            check(pageA.entries().size() == 1 && pageA.entries().getFirst().messageId().equals(first) && a.current(pageA), "God A retrieves only its actually heard native prefix");
            check(pageB.entries().size() == 1 && pageB.entries().getFirst().messageId().equals(second) && b.current(pageB), "God B uses a separate native receipt scope");
            check(!a.current(pageB) && !b.current(pageA), "semantically identical query cannot transfer page authority between God sessions");
            inspect(database, originals, unheard, model.space, 2);
            await(store.closeAsync()); store = null;

            store = open(root, world); producer = producer(store);
            check(store.datasetId().orElseThrow().equals(dataset) && !store.runtimeEpoch().equals(oldEpoch), "clean reopen keeps dataset and changes runtime epoch");
            check(await(store.embeddingPort().claimWork(worker, 45)).work().isEmpty(), "pre-restart worker capability is rejected by new runtime");
            var freshWorker = store.registerEmbeddingWorker("offline-embedding-fixture", model.space);
            try (var runtime = new RecordedEmbeddingRuntime(store.embeddingPort(), freshWorker, model::embed)) {
                runtime.eligible(true); runtime.pump(); runtime.awaitIdle(); runtime.pump(); runtime.awaitIdle();
                check(fake.posts == 3, "completed native vectors survive restart without repeated model requests");
            }
            var reopenedA = session(store, GOD_A); var reopenedPage = semantic(reopenedA, needle);
            check(reopenedPage.entries().size() == 1 && reopenedPage.entries().getFirst().text().equals(firstWork.text()), "durable prefix and knowledge proof are readable after restart");
            var revoked = store.invalidate(producer, new SourceInvalidation(firstWork.source(), 1, "PIPELINE_SOURCE_WITHDRAWN"));
            check(!reopenedA.current(reopenedPage), "source withdrawal dispatch immediately revokes already issued page");
            check(await(revoked).status() == RecordingRecords.Status.STORED, "source withdrawal becomes durable");
            check(semantic(session(store, GOD_A), needle).entries().isEmpty(), "remaining vector row cannot bypass withdrawn source authority");
            check(semantic(session(store, GOD_B), needle).entries().size() == 1, "unrelated God's current native source remains readable");

            UUID late = capture(store, producer, room, PLAYER, "생성 도중 권한이 철회될 발언.", Set.of(GOD_A), originals);
            var lateWorker = store.registerEmbeddingWorker("late-fixture", model.space);
            var claimed = await(store.embeddingPort().claimWork(lateWorker, 45));
            check(claimed.status() == EmbeddingRecords.Status.CLAIMED && claimed.work().orElseThrow().messageId().equals(late), "new source receives exact game lease");
            var lateWork = claimed.work().orElseThrow(); float[] values;
            try (var permit = ModelAdmission.optional(true)) { check(permit != null, "late fixture uses real shared admission"); values = model.embed(lateWork); }
            check(await(store.invalidate(producer, new SourceInvalidation(lateWork.source(), 1, "WITHDRAW_BEFORE_VECTOR_COMMIT"))).status() == RecordingRecords.Status.STORED,
                    "late source invalidated after model return and before commit");
            check(await(store.embeddingPort().commitEmbedding(lateWork.token(), values)).status() == EmbeddingRecords.Status.STALE,
                    "real store rejects late successful model result after native authority changes");
            check(fake.posts == 4 && fake.metadata == 8, "all deterministic model requests retain two-sided digest validation");
            inspect(database, originals, unheard, model.space, 2);
            for (var original : originals.entrySet()) check(await(store.inspectMessage(original.getKey(), 8192)).orElseThrow().equals(original.getValue()), "withdrawal and optional indexing preserve full immutable RAW");
            check(!Files.exists(root.resolve("mythictrpg-ai-memory")) && !Files.exists(root.resolve("mythai-memory")), "pipeline creates no legacy memory journal/index stores");
            await(store.closeAsync()); store = null;
            System.out.println("RecordedEmbeddingPipelineTest: " + checks + " checks passed; actual SQLite + fake transport only; fixture=" + root);
        } finally { if (store != null) await(store.closeAsync()); ModelAdmission.players(-1); }
    }

    private static MemoryIndexSettings settings() {
        return new MemoryIndexSettings(true, MemoryIndexSettings.Mode.SHADOW, false, 4_000_000, 1000,
                URI.create("http://127.0.0.1:11434"), MODEL, DIGEST, 3, "", "", 350, 30000,
                new MemoryIndexSettings.Execution(true, 2, 20, true, 2, 2048, 768, 30, .75, false));
    }
    private static WorldRecordingService open(Path root, UUID world) throws Exception {
        Files.createDirectories(root); var store = await(WorldRecordingService.open(root, world, ARCHIVE, BOUNDARY));
        check(store.health().state() == WorldRecordingService.State.READY, "real SQLite fixture READY: " + store.health().reasonCode()); return store;
    }
    private static ProducerCapability producer(WorldRecordingService store) {
        return store.registerProducer(PRODUCER, Set.of("ROOM_PRIVATE"), Set.of(SourceKind.DIALOGUE_DIRECT, SourceKind.DERIVED_SPEECH));
    }
    private static UUID capture(WorldRecordingService store, ProducerCapability producer, UUID room, ActorRef speaker, String text,
            Set<ActorRef> heardGods, Map<UUID, String> originals) throws Exception {
        UUID id = UUID.randomUUID(); originals.put(id, text); Instant at = DATE.plusSeconds(originals.size());
        var intended = Set.of(PLAYER, GOD_A, GOD_B); var full = new HashSet<>(heardGods); full.add(PLAYER);
        var context = new PublicationContext(store.runtimeEpoch(), 1, "STANDARD", intended, full, Map.of(), List.of(), Set.of(), Map.of(), "UNKNOWN", "PERSONAL");
        var envelope = new ConversationEnvelope(store.worldId(), store.datasetId().orElseThrow(), room, "ROOM_PRIVATE", "ACTUAL_LISTENERS_ONLY", 1, 1, true, false, PRODUCER);
        var raw = new RawMessage(id, Optional.empty(), originals.size(), speaker, text, at,
                speaker.kind() == ActorKind.PLAYER ? MessageKind.ACCEPTED_INPUT : MessageKind.DELIVERED_OUTPUT, id.toString(), context);
        var receipts = new ArrayList<DeliveryReceipt>();
        for (var actor : full) receipts.add(new DeliveryReceipt(UUID.randomUUID(), actor, actor.kind() == ActorKind.GOD ? "GAME_HEARD" : "CHAT", at,
                1, DeliveryStatus.SERVER_DISPATCHED, new DeliveryView(text, List.of(text)), Set.of(0)));
        GameRecordingPort port = store;
        check(await(port.capture(producer, envelope, raw, receipts)).status() == RecordingRecords.Status.STORED, "public producer commits native RAW and actual recipient receipts"); return id;
    }
    /** Test-only reflection keeps the production issuer package-private; no new public authority backdoor. */
    private static MemoryReadSession session(WorldRecordingService store, ActorRef god) throws Exception {
        var scopeType = Class.forName("com.sande.mythictrpg.recording.server.RecordedRoomSearch$Scope");
        var scopeConstructor = scopeType.getDeclaredConstructor(UUID.class, String.class, Set.class, boolean.class, String.class, String.class); scopeConstructor.setAccessible(true);
        Object scope = scopeConstructor.newInstance(store.datasetId().orElseThrow(), god.id(), Set.of(PLAYER, god), false, "STANDARD", "PERSONAL");
        var type = Class.forName("com.sande.mythictrpg.recording.server.RecordedMemoryAccess$Session");
        var constructor = type.getDeclaredConstructor(WorldRecordingService.class, scopeType, BooleanSupplier.class, Consumer.class,
                BooleanSupplier.class, Function.class, Predicate.class, BooleanSupplier.class); constructor.setAccessible(true);
        Function<List<?>, CompletableFuture<Boolean>> prepare = refs -> CompletableFuture.completedFuture(refs.isEmpty());
        Predicate<List<?>> current = List::isEmpty;
        return (MemoryReadSession) constructor.newInstance(store, scope, (BooleanSupplier) () -> true, (Consumer<Runnable>) Runnable::run,
                (BooleanSupplier) () -> true, prepare, current, (BooleanSupplier) () -> true);
    }
    private static SemanticReadRecords.Page semantic(MemoryReadSession session, EmbeddingRecords.QueryVector vector) throws Exception {
        return await(session.semantic(new MemoryReadSession.Query(QUERY, Optional.empty(), Optional.empty()), vector, Optional.empty(), new MemoryReadSession.Budget(4, 16384)));
    }
    private static void inspect(Path database, Map<UUID, String> originals, UUID unheard, EmbeddingRecords.ModelSpace space, int expectedRows) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=ro"); var statement = db.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            check(count(db, "SELECT count(*) FROM embedding_rows") == expectedRows, "vectors remain durable without duplicates or late unauthorized rows");
            check(count(db, "SELECT count(*) FROM embedding_rows WHERE message_id='" + unheard + "'") == 0, "unheard source has no embedding row");
            check(count(db, "SELECT count(*) FROM embedding_rows WHERE model_fingerprint='" + space.fingerprint() + "'") == expectedRows, "durable vectors use native exact model/digest/encoder space");
            check(count(db, "SELECT count(*) FROM embedding_rows e JOIN messages m ON m.id=e.message_id WHERE e.actual_actor_kind<>m.actor_kind OR e.actual_actor_id<>m.actor_id") == 0,
                    "persisted vector actor always matches native speaker");
            try (var rows = statement.executeQuery("SELECT id,body_hash FROM messages")) {
                int seen = 0; while (rows.next()) { UUID id = UUID.fromString(rows.getString(1));
                    check(originals.containsKey(id) && RecordingRecords.sha256(originals.get(id)).equals(rows.getString(2)), "full original raw hash remains unchanged"); seen++; }
                check(seen == originals.size(), "optional embeddings do not add or delete original messages");
            }
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) { check(!rows.next(), "native embedding foreign keys remain consistent"); }
        }
    }
    private static long count(Connection db, String sql) throws Exception { try (var q = db.createStatement(); var row = q.executeQuery(sql)) { row.next(); return row.getLong(1); } }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(20, TimeUnit.SECONDS); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
