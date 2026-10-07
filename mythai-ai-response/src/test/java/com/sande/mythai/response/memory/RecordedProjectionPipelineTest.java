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

/** Real SQLite -> production idle worker -> fake Ollama transport -> real projection commit and restart.
 * Synthetic world only under build/. No Minecraft boot, model, sockets, legacy import or SQL mutations. */
public final class RecordedProjectionPipelineTest {
    private static final Gson JSON = new Gson();
    private static final String PRODUCER = "room-publication-v2", DIGEST = "d".repeat(64);
    private static final ActorRef PLAYER = new ActorRef(ActorKind.PLAYER, UUID.randomUUID().toString());
    private static final ActorRef GOD = new ActorRef(ActorKind.GOD, "mythictrpg:fortuna");
    private static final Instant DATE = Instant.parse("2026-09-30T12:00:00Z");
    private static final WorldRecordingService.CutoverBoundary BOUNDARY =
            new WorldRecordingService.CutoverBoundary("offline-recorded-projection-pipeline", Map.of());
    private static final RecordingSettings ARCHIVE = new RecordingSettings(RecordingSettings.Mode.SHADOW,
            128_000_000, 2_000_000, .90, .95);
    private static int checks;
    private static MemoryIndexSettings modelSettings() {
        return new MemoryIndexSettings(true, MemoryIndexSettings.Mode.OFF, true, 4_000_000, 1000,
                URI.create("http://127.0.0.1:11434"), "", "", 0, "fixture:extract", DIGEST, 350, 30000,
                new MemoryIndexSettings.Execution(true, 2, 20, true, 3, 2048, 768, 30, .75, false));
    }
    private static final class FakeModel implements OllamaMemoryBackend.Transport {
        int posts, metadata;
        final List<JsonArray> inputs = new ArrayList<>();
        public String request(URI uri, String body, int timeout) {
            check(timeout > 0 && timeout <= 30000 && uri.getHost().equals("127.0.0.1"), "production request keeps bounded loopback settings");
            if (body == null) {
                metadata++;
                return JSON.toJson(Map.of("models", List.of(Map.of("name", "fixture:extract", "digest", DIGEST))));
            }
            check(uri.getPath().equals("/api/chat") && ModelAdmission.status().optionalActive(), "actual idle runtime holds hardware admission for model request");
            posts++;
            var request = JsonParser.parseString(body).getAsJsonObject();
            var input = JsonParser.parseString(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonArray();
            inputs.add(input.deepCopy());
            JsonObject target = null; var summary = new JsonArray();
            for (var source : input) {
                var row = source.getAsJsonObject(); if (row.get("target").getAsBoolean()) target = row;
                var quote = new JsonObject(); quote.addProperty("sourceAlias", row.get("sourceAlias").getAsString());
                quote.addProperty("text", row.get("text").getAsString()); summary.add(quote);
            }
            var result = new JsonObject(); result.addProperty("eventKind", "SPEAKER_CLAIM");
            result.addProperty("eventQuote", target.get("text").getAsString());
            result.addProperty("relationshipQuote", target.get("text").getAsString());
            result.add("summary", summary); var links = new JsonArray();
            if (target.get("text").getAsString().contains("취소")) {
                result.addProperty("eventKind", "CORRECTION_OR_EXPLANATION");
                for (var source : input) {
                    var row = source.getAsJsonObject();
                    if (!row.get("target").getAsBoolean() && row.get("actorAlias").equals(target.get("actorAlias"))) {
                        var link = new JsonObject(); link.add("newerAlias", target.get("sourceAlias"));
                        link.add("olderAlias", row.get("sourceAlias")); link.addProperty("relation", "CANCELS"); links.add(link); break;
                    }
                }
            }
            result.add("links", links);
            return JSON.toJson(Map.of("model", "fixture:extract", "done", true, "done_reason", "stop", "message", Map.of("content", JSON.toJson(result))));
        }
    }
    public static void main(String[] args) throws Exception {
        var base = Path.of(args.length == 0 ? "build/recorded-projection-pipeline-test" : args[0]).toAbsolutePath().normalize();
        if (!base.toString().replace('\\', '/').contains("/build/")) throw new IllegalArgumentException("BUILD_ONLY");
        Files.createDirectories(base); var root = Files.createTempDirectory(base, "pipeline-");
        UUID world = UUID.randomUUID(), room = UUID.randomUUID();
        var settings = modelSettings(); String version = RecordedProjectionExtractor.version(settings);
        var fake = new FakeModel();
        var extractor = new RecordedProjectionExtractor(settings, new OllamaMemoryBackend(settings, fake));
        var records = new LinkedHashMap<UUID,String>();
        WorldRecordingService store = null;
        try {
            ModelAdmission.players(0); store = open(root, world);
            UUID dataset = store.datasetId().orElseThrow(), oldEpoch = store.runtimeEpoch();
            Path database = root.resolve("mythictrpg-recording-v2").resolve(dataset.toString()).resolve("recording.sqlite");
            var producer = store.registerProducer(PRODUCER, Set.of("ROOM_PRIVATE"), Set.of(SourceKind.DIALOGUE_DIRECT, SourceKind.DERIVED_SPEECH));
            UUID promise = capture(store, producer, room, PLAYER, "  전에 도와줘서 고마웠어. 내일 찾아올게.\n", 1, true, records);
            UUID reply = capture(store, producer, room, GOD, "기억하고 있었구나. 네 말을 들었어.", 2, true, records, Set.of(promise));
            capture(store, producer, room, PLAYER, "내일 찾아간다는 약속은 취소할게. 🌌", 3, true, records, Set.of(promise, reply));
            UUID unheard = capture(store, producer, room, PLAYER, "이 말은 신에게 전달되지 않았다.", 4, false, records);
            check(await(store.statistics()).messages() == 4, "capture stores each original once regardless of derived layers");
            var capability = store.registerProjectionWorker("offline-projection-fixture", version);
            try (var runtime = new RecordedProjectionRuntime(store.projectionPort(), capability, extractor::extract)) {
                runtime.pump(); runtime.awaitIdle(); check(fake.posts == 0, "runtime starts disabled even with existing pending work");
                runtime.eligible(true);
                for (int i = 0; i < 8; i++) { runtime.pump(); runtime.awaitIdle(); }
                check(fake.posts == 3 && fake.metadata == 6, "exactly three heard native jobs use one verified request each");
                check(!runtime.running(), "bounded worker is idle after pending jobs");
            }
            inspect(database, records, unheard, version, 9);
            check(fake.inputs.stream().flatMap(input -> input.asList().stream()).anyMatch(value -> value.getAsJsonObject().get("actorKind").getAsString().equals("GOD")),
                    "real native God speech is not fabricated as a player journal entry");
            check(fake.inputs.stream().anyMatch(input -> input.size() > 1), "same-scope previous sources feed grounded multi-source summary");
            for (var record : records.entrySet()) check(await(store.inspectMessage(record.getKey(), 4096)).orElseThrow().equals(record.getValue()), "full immutable raw text preserved after extraction");
            await(store.closeAsync()); store = null;
            var reopened = open(root, world); store = reopened;
            check(reopened.datasetId().orElseThrow().equals(dataset) && !reopened.runtimeEpoch().equals(oldEpoch)
                    && !reopened.health().possibleGap(), "clean reopen preserves dataset and advances runtime epoch");
            var invalidOld = await(reopened.projectionPort().claimWork(capability, new com.sande.mythictrpg.recording.api.ProjectionRecords.WorkBudget(6, 16384, 45)));
            check(invalidOld.work().isEmpty(), "old worker capability has no authority after restart");
            var fresh = reopened.registerProjectionWorker("offline-projection-fixture", version);
            try (var runtime = new RecordedProjectionRuntime(reopened.projectionPort(), fresh, extractor::extract)) {
                runtime.eligible(true); runtime.pump(); runtime.awaitIdle(); runtime.pump(); runtime.awaitIdle();
                check(fake.posts == 3, "completed jobs survive restart without repeated model extraction");
            }
            inspect(database, records, unheard, version, 9);
            check(!Files.exists(root.resolve("mythictrpg-ai-memory")) && !Files.exists(root.resolve("mythai-memory")), "new projection path never creates legacy journal stores");
            await(store.closeAsync()); store = null;
            System.out.println("RecordedProjectionPipelineTest: " + checks + " checks passed; actual SQLite, fake model only; fixture=" + root);
        } finally { if (store != null) await(store.closeAsync()); ModelAdmission.players(-1); }
    }
    private static UUID capture(WorldRecordingService store, ProducerCapability producer, UUID room, ActorRef actor, String text,
            int order, boolean heard, Map<UUID,String> records) throws Exception {
        return capture(store, producer, room, actor, text, order, heard, records, Set.of());
    }
    private static UUID capture(WorldRecordingService store, ProducerCapability producer, UUID room, ActorRef actor, String text,
            int order, boolean heard, Map<UUID,String> records, Set<UUID> parents) throws Exception {
        UUID id = UUID.randomUUID(); records.put(id, text); Instant at = DATE.plusSeconds(order);
        var audience = Set.of(PLAYER, GOD);
        var context = new PublicationContext(store.runtimeEpoch(), 1, "STANDARD", audience, heard ? audience : Set.of(PLAYER),
                Map.of(), List.of(), parents, Map.of(), "UNKNOWN_ORIGINAL_GAME_TICK_DAYTIME_DIMENSION", "PERSONAL");
        var envelope = new ConversationEnvelope(store.worldId(), store.datasetId().orElseThrow(), room, "ROOM_PRIVATE",
                "ACTUAL_LISTENERS_ONLY", 1, 1, true, false, PRODUCER);
        var raw = new RawMessage(id, Optional.of(UUID.randomUUID()), order, actor, text, at,
                actor.kind() == ActorKind.PLAYER ? MessageKind.ACCEPTED_INPUT : MessageKind.DELIVERED_OUTPUT, id.toString(), context);
        var playerReceipt = new DeliveryReceipt(UUID.randomUUID(), PLAYER, "CHAT", at, 1,
                DeliveryStatus.SERVER_DISPATCHED, new DeliveryView(text, List.of(text)), Set.of(0));
        List<DeliveryReceipt> receipts = heard ? List.of(playerReceipt, new DeliveryReceipt(UUID.randomUUID(), GOD, "GAME_HEARD", at, 1,
                DeliveryStatus.SERVER_DISPATCHED, new DeliveryView(text, List.of(text)), Set.of(0))) : List.of(playerReceipt);
        GameRecordingPort port = store;
        check(await(port.capture(producer, envelope, raw, receipts)).status() == RecordingRecords.Status.STORED, "actual public game recording port commits native source");
        return id;
    }
    private static WorldRecordingService open(Path root, UUID world) throws Exception {
        Files.createDirectories(root); var result = await(WorldRecordingService.open(root, world, ARCHIVE, BOUNDARY));
        check(result.health().state() == WorldRecordingService.State.READY, "real SQLite fixture opens: " + result.health().reasonCode()); return result;
    }
    private static void inspect(Path database, Map<UUID,String> originals, UUID unheard, String version, long expectedMemories) throws Exception {
        // URI mode=ro plus query_only: inspection cannot become a backdoor fixture mutation.
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=ro"); var statement = db.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            check(count(db, "SELECT count(*) FROM memories") == expectedMemories, "all three layers durably commit once per native knowledge job");
            for (String layer : List.of("EVENT", "RELATIONSHIP", "SUMMARY"))
                check(count(db, "SELECT count(*) FROM memories WHERE layer='" + layer + "'") == 3, "persisted " + layer + " per heard source");
            check(count(db, "SELECT count(*) FROM memory_sources s JOIN memories m ON m.id=s.memory_id WHERE m.layer='SUMMARY'") > 3,
                    "summary persists individual source proof instead of ungrounded free narrative");
            check(count(db, "SELECT count(*) FROM memory_sources WHERE message_id='" + unheard + "'") == 0, "raw-only unheard source never becomes NPC memory");
            check(count(db, "SELECT count(*) FROM memories WHERE observer_god='" + GOD.id() + "' AND extractor_version='" + version + "'") == expectedMemories,
                    "observer and extractor identity are durable game-owned metadata");
            check(count(db, "SELECT count(*) FROM memory_sources ms JOIN source_refs s ON s.id=ms.source_ref JOIN knowledge_receipts k ON k.id=ms.knowledge_receipt_id WHERE ms.source_hash<>s.source_hash OR ms.receipt_hash<>k.receipt_hash") == 0,
                    "stored projection binds exact original source and receipt hashes");
            check(count(db, "SELECT count(*) FROM memory_sources ms JOIN messages m ON m.id=ms.message_id WHERE ms.actual_actor_kind<>m.actor_kind OR ms.actual_actor_id<>m.actor_id") == 0,
                    "all copied subject directions match actual source author");
            check(count(db, "SELECT count(*) FROM memory_links WHERE relation='CANCELS' AND status='CANDIDATE'") == 1, "explicit same-author cancellation survives real storage validator as candidate");
            check(count(db, "SELECT count(*) FROM memory_links l JOIN memories m ON m.id=l.memory_id WHERE m.kind='CORRECTION_OR_EXPLANATION' AND EXISTS(SELECT 1 FROM memory_sources s WHERE s.memory_id=l.memory_id AND s.knowledge_receipt_id=l.newer_receipt_id) AND EXISTS(SELECT 1 FROM memory_sources s WHERE s.memory_id=l.memory_id AND s.knowledge_receipt_id=l.older_receipt_id)") == 1,
                    "persisted correction retains both grounded source receipts and aligned classification");
            try (var rows = statement.executeQuery("SELECT id,body_hash FROM messages")) {
                int seen = 0; while (rows.next()) { UUID id = UUID.fromString(rows.getString(1));
                    check(originals.containsKey(id) && RecordingRecords.sha256(originals.get(id)).equals(rows.getString(2)), "raw full-body SHA unchanged"); seen++; }
                check(seen == originals.size(), "projection commit neither creates nor deletes original messages");
            }
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) { check(!rows.next(), "source and projection SQL foreign keys remain consistent"); }
        }
    }
    private static long count(Connection db, String sql) throws Exception {
        try (var statement = db.createStatement(); var row = statement.executeQuery(sql)) { if (!row.next()) throw new AssertionError("missing scalar"); return row.getLong(1); }
    }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(15, TimeUnit.SECONDS); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
