package com.sande.mythictrpg.recording.server;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real SQLite + actual game fan-out adapter. No model, AI engine, server settings or operational world. */
public final class RoomRecordingCaptureTest {
    private static int checks;
    private static final UUID PLAYER = UUID.randomUUID();
    private static final String GOD = "test:speaker", LISTENER = "test:listener", OUTSIDE = "test:outside";
    private static final WorldRecordingService.CutoverBoundary CUTOVER = new WorldRecordingService.CutoverBoundary("m2-fixture", Map.of());
    public static void main(String[] args) throws Exception {
        var parent = Path.of(args.length == 0 ? "build/room-recording-test" : args[0]).toAbsolutePath().normalize();
        if (!parent.toString().replace('\\', '/').contains("/build/")) throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent); var root = Files.createTempDirectory(parent, "capture-");
        turnOrdering(); publicationAndSqlite(root.resolve("room")); migration(root.resolve("migration"));
        System.out.println("RoomRecordingCaptureTest: " + checks + " checks passed; fixtures=" + root);
    }
    private static void turnOrdering() {
        var ledger = new ConversationRoomLedger();
        var room = ledger.create(RoomType.PRIVATE, PLAYER, List.of(GOD), "", RecordingScope.STANDARD);
        var first = ledger.beginTurn(room.roomId(), room.revision(), PLAYER, GOD);
        check(first.sequence() == 1 && ledger.isCurrent(first), "game issues first positive sequence");
        var second = ledger.beginTurn(room.roomId(), room.revision(), PLAYER, GOD);
        check(second.sequence() == 2 && !ledger.isCurrent(first), "superseding input increments without changing membership");
        var revised = ledger.addGod(room.roomId(), room.revision(), LISTENER);
        var third = ledger.beginTurn(revised.roomId(), revised.revision(), PLAYER, GOD);
        check(third.sequence() == 3 && !ledger.isCurrent(second), "revision change invalidates lease but preserves monotonic room ordering");
        var old = new ConversationRoomLedger.TurnLease(third.roomId(), third.revision(), third.turnId(), third.playerId(), third.godId());
        check(old.sequence() == 0 && !ledger.isCurrent(old), "legacy unknown sequence is not a game-issued lease");
        check(!new ConversationRoomLedger().isCurrent(third), "runtime restart cannot inherit old turn ownership");
    }
    private static void publicationAndSqlite(Path root) throws Exception {
        Files.createDirectories(root); var world = UUID.randomUUID(); var store = open(root, world); var capture = new RoomRecordingCapture(store);
        String text = " 첫 부분\n" + "🌌 긴 원문은 그대로 보존한다. ".repeat(140) + " 마지막 정정. ";
        var parts = RoomHudText.pages(text); var partialPlayer = UUID.randomUUID(); var failedPlayer = UUID.randomUUID();
        var participants = Map.of(PLAYER, "author", partialPlayer, "partial", failedPlayer, "failed");
        var chatText = "│[방] author: " + text;
        var chat = new RoomDialogueEvent.DispatchView(chatText, List.of(chatText), Set.of(0), Map.of());
        var partial = new RoomDialogueEvent.DispatchView(text, parts, Set.of(0, 2), Map.of(0, UUID.randomUUID(), 2, UUID.randomUUID()));
        var draft = event(world, UUID.randomUUID(), UUID.randomUUID(), text, participants, RecordingScope.STANDARD).withTurnSequence(1);
        var result = new CompletableFuture<RoomRecordingCapture.Outcome>(); var failures = new AtomicInteger();
        var dispatched = RoomDialoguePublisher.publish(draft, participants.keySet(), id -> {
            if (id.equals(failedPlayer)) throw new IllegalStateException("fixture dispatch failure");
            return id.equals(PLAYER) ? new RoomDialogueEvent.Delivery("author", true, 0, Optional.of(chat), Optional.empty())
                    : new RoomDialogueEvent.Delivery("partial", false, 2, Optional.empty(), Optional.of(partial));
        }, emitted -> capture.capture(emitted, true).whenComplete((value, failure) -> {
            if (failure == null) result.complete(value); else result.completeExceptionally(failure);
        }), ignored -> { throw new IllegalStateException("AI unavailable fixture"); }, ignored -> failures.incrementAndGet());
        check(await(result).complete() && failures.get() == 2, "game capture survives recipient failure and absent/failing AI observer");
        check(await(store.inspectMessage(draft.messageId(), 100_000)).orElseThrow().equals(text), "full whitespace Unicode long original preserved once");
        check(await(store.statistics()).messages() == 1 && await(store.statistics()).deliveries() == 4,
                "one raw plus chat/partial HUD and only two actual God listeners");
        var dbPath = database(root, store);
        check(scalar(dbPath, "SELECT count(*) FROM deliveries WHERE actor_id='" + OUTSIDE + "'") == 0, "member without explicit hearing never receives a God receipt");
        check(scalar(dbPath, "SELECT count(*) FROM deliveries WHERE actor_id='" + failedPlayer + "'") == 0, "failed dispatch is not a receipt");
        check(scalar(dbPath, "SELECT count(*) FROM delivery_parts_resolved p JOIN deliveries d ON p.receipt_id=d.id WHERE d.kind='HUD' AND p.part_index IN (0,2)") == 2
                && scalar(dbPath, "SELECT count(*) FROM delivery_parts_resolved p JOIN deliveries d ON p.receipt_id=d.id WHERE d.kind='HUD' AND p.part_index=1") == 0,
                "noncontiguous successful HUD indices recorded exactly, not reconstructed as a prefix");
        check(scalar(dbPath, "SELECT count(*) FROM deliveries WHERE kind='HUD' AND status='PARTIAL_DISPATCH'") == 1, "partial HUD remains partial");
        var context = JsonParser.parseString(string(dbPath, "SELECT context_json FROM message_contexts")).getAsJsonObject();
        check(context.getAsJsonArray("fullAudience").toString().contains(PLAYER.toString())
                && !context.getAsJsonArray("fullAudience").toString().contains(partialPlayer.toString()), "archive full audience excludes partial and absent recipients");
        check(context.getAsJsonArray("evidence").toString().contains("fixture-source") && context.getAsJsonArray("sourceMessages").size() == 1
                && context.getAsJsonObject("transportMessageIds").size() == 1, "portable source ancestry and packet references survive raw transaction");
        check(await(capture.capture(dispatched, true)).rawStatus() == Status.DUPLICATE && await(store.statistics()).messages() == 1,
                "same logical occurrence retries idempotently");
        var sameWords = event(world, draft.roomId(), UUID.randomUUID(), text, participants, RecordingScope.STANDARD).withTurnSequence(2)
                .withDeliveries(dispatched.deliveries());
        check(await(capture.capture(sameWords, true)).complete() && await(store.statistics()).messages() == 2, "same words in new occurrence are not text-deduplicated");
        check(await(capture.capture(dispatched, false)).reason().equals("STALE_ROOM_CAPTURE_SCOPE"), "late obsolete revision cannot be reissued as current capture");
        check(await(capture.capture(dispatched.withWorld(UUID.randomUUID()), true)).reason().equals("STALE_ROOM_CAPTURE_SCOPE"), "foreign world capture rejected");
        check(await(capture.capture(dispatched.withTurnSequence(0), true)).reason().equals("UNKNOWN_TURN_SEQUENCE"), "unknown ordering not invented from time or UUID");
        long beforeOff = await(store.statistics()).messages(); var callbacks = new AtomicInteger();
        var off = event(world, UUID.randomUUID(), UUID.randomUUID(), "OFF_SECRET", participants, RecordingScope.TEST_EPHEMERAL);
        RoomDialoguePublisher.publish(off, List.of(PLAYER), ignored -> new RoomDialogueEvent.Delivery("author", true, 0),
                ignored -> callbacks.incrementAndGet(), ignored -> callbacks.incrementAndGet(), ignored -> { });
        check(callbacks.get() == 0 && await(capture.capture(off, true)).reason().equals("RECORDING_DISABLED")
                && await(store.statistics()).messages() == beforeOff, "recording OFF prevents archive/AI recording callbacks and stores no body");
        var legacy = event(world, UUID.randomUUID(), UUID.randomUUID(), "legacy counts", Map.of(PLAYER, "author"), RecordingScope.STANDARD)
                .withTurnSequence(1).withDeliveries(Map.of(PLAYER, new RoomDialogueEvent.Delivery("author", true, 0)));
        check(await(capture.capture(legacy, true)).reason().equals("EXACT_DELIVERY_VIEW_UNAVAILABLE"), "legacy count-only view reports a gap, never fabricated exact transmission");
        var many = new LinkedHashMap<UUID, RoomDialogueEvent.Delivery>();
        for (int index = 0; index < 300; index++) many.put(UUID.randomUUID(), new RoomDialogueEvent.Delivery("spectator", true, 0,
                Optional.of(new RoomDialogueEvent.DispatchView("many", List.of("many"), Set.of(0), Map.of())), Optional.empty()));
        var broadcast = event(world, UUID.randomUUID(), UUID.randomUUID(), "many", Map.of(PLAYER, "author"), RecordingScope.STANDARD)
                .withTurnSequence(1).withDeliveries(many);
        var all = await(capture.capture(broadcast, true));
        check(all.complete() && all.committedReceipts() == 302, "more than 256 audience receipts use bounded follow-up batches");
        check(await(capture.capture(broadcast, true)).complete()
                && scalar(dbPath, "SELECT count(*) FROM deliveries WHERE message_id='" + broadcast.messageId() + "'") == 302,
                "entire multibatch capture retry is idempotent");
        long messages = await(store.statistics()).messages(); await(store.closeAsync());
        var restarted = open(root, world);
        check(await(restarted.statistics()).messages() == messages && await(restarted.inspectMessage(draft.messageId(), 100_000)).orElseThrow().equals(text),
                "actual reopen retains raw, views and publication contexts");
        check(scalar(dbPath, "PRAGMA user_version") == RecordingSchema.VERSION, "DB schema distinguished from config/manifest schema2");
        check(scalar(dbPath, "SELECT count(*) FROM delivery_parts") == 0
                && scalar(dbPath, "SELECT count(*) FROM delivery_part_refs") >= 302,
                "new audience receipts never replicate delivered raw body per recipient");
        await(restarted.closeAsync());
    }
    private static void migration(Path root) throws Exception {
        migration(root.resolve("schema2"), 2); migration(root.resolve("schema3"), 3); migration(root.resolve("schema4"), 4);
    }
    private static void migration(Path root, int oldVersion) throws Exception {
        Files.createDirectories(root); var world = UUID.randomUUID(); var store = open(root, world); var db = database(root, store);
        var message = event(world, UUID.randomUUID(), UUID.randomUUID(), "legacy exact projection 🌌", Map.of(PLAYER, "author"), RecordingScope.STANDARD).withTurnSequence(1);
        await(new RoomRecordingCapture(store).capture(message, true)); await(store.closeAsync());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS native_memory_evidence");
            statement.execute("DROP TABLE IF EXISTS projection_input_manifests");
            statement.execute("DROP TABLE IF EXISTS native_interpretation_evidence");
            for(String table:List.of("embedding_rows","embedding_jobs","embedding_seed_progress"))statement.execute("DROP TABLE "+table);
            statement.execute("DROP INDEX embedding_source_seed");
            for(String table:List.of("recording_lexical_fts","recording_lexical_manifest","recording_lexical_progress","recording_lexical_skips"))statement.execute("DROP TABLE "+table);
            for (String table : List.of("memory_links", "memory_subjects", "memory_sources", "memories")) statement.execute("DROP TABLE " + table);
            for (String column : List.of("extractor_version", "attempt_count", "next_attempt_utc", "last_failure", "lease_nonce")) statement.execute("ALTER TABLE work_items DROP COLUMN " + column);
            for (String table : List.of("knowledge_origins", "source_origins", "source_cutovers", "knowledge_invalidations")) statement.execute("DROP TABLE " + table);
            if (oldVersion < 4) {
                statement.execute("INSERT INTO delivery_parts SELECT * FROM delivery_parts_resolved");
                statement.execute("DROP VIEW delivery_parts_resolved"); statement.execute("DROP TABLE delivery_part_refs");
            }
            if (oldVersion == 2) statement.execute("DROP TABLE message_contexts");
            statement.execute("UPDATE recording_meta SET schema_version=" + oldVersion); statement.execute("PRAGMA user_version=" + oldVersion);
        }
        var upgraded = open(root, world);
        check(upgraded.health().state() == WorldRecordingService.State.READY && scalar(db, "PRAGMA user_version") == RecordingSchema.VERSION
                && scalar(db, "SELECT count(*) FROM message_contexts") == (oldVersion == 2 ? 0 : 1), "old schema migration preserves existing context without inventing historical metadata");
        check(await(upgraded.inspectMessage(message.messageId(), 4096)).orElseThrow().equals(message.text())
                && scalar(db,"SELECT count(*) FROM delivery_parts") == (oldVersion < 4 ? 2 : 0) && scalar(db,"SELECT count(*) FROM delivery_part_refs") == (oldVersion < 4 ? 0 : 2)
                && string(db,"SELECT body FROM delivery_parts_resolved LIMIT 1").equals(message.text()),
                "schema migration retains old raw and both exact legacy God receipt bodies without rewriting");
        await(upgraded.closeAsync());
    }
    private static RoomDialogueEvent event(UUID world, UUID room, UUID turn, String text, Map<UUID, String> participants, RecordingScope recording) {
        return new RoomDialogueEvent(UUID.randomUUID(), room, 1, Optional.of(turn), RoomType.PUBLIC_MOBILE, recording, "PLAYER", PLAYER.toString(), text,
                Set.of(GOD, LISTENER, OUTSIDE), participants, Map.of(), 1_900_000_000_000L).withWorld(world).withHeardGods(Set.of(GOD, LISTENER))
                .withEvidence(List.of(new RoomEvidenceReference("fixture-source", "opaque dependency, not knowledge")), Set.of(UUID.randomUUID()));
    }
    private static WorldRecordingService open(Path root, UUID world) throws Exception {
        var service = await(WorldRecordingService.open(root, world, new RecordingSettings(RecordingSettings.Mode.RECORD_ONLY, 128_000_000, 2_000_000, .9, .95), CUTOVER));
        check(service.health().state() == WorldRecordingService.State.READY, "native store ready: " + service.health().reasonCode()); return service;
    }
    private static Path database(Path root, WorldRecordingService service) { return root.resolve("mythictrpg-recording-v2").resolve(service.datasetId().orElseThrow().toString()).resolve("recording.sqlite"); }
    private static long scalar(Path file, String sql) throws Exception { return Long.parseLong(string(file, sql)); }
    private static String string(Path file, String sql) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = db.createStatement()) {
            statement.execute("PRAGMA query_only=ON"); try (var rows = statement.executeQuery(sql)) { if (!rows.next()) throw new AssertionError("No diagnostic row"); return rows.getString(1); }
        }
    }
    private static <T> T await(CompletionStage<T> future) throws Exception { return future.toCompletableFuture().get(15, TimeUnit.SECONDS); }
    private static void check(boolean okay, String message) { checks++; if (!okay) throw new AssertionError(message); }
}
