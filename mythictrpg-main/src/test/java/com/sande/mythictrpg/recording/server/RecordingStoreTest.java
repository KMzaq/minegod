package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Actual SQLite/JDBC fixtures only under the supplied build directory. No model, live server or legacy import. */
public final class RecordingStoreTest {
    private static int assertions;
    private static final WorldRecordingService.CutoverBoundary CUTOVER = new WorldRecordingService.CutoverBoundary("m1-offline-fixture", Map.of("action", 10L));
    private static final RecordingSettings ENABLED = new RecordingSettings(RecordingSettings.Mode.RECORD_ONLY, 64_000_000, 2_000_000, .90, .95);
    private static final ActorRef PLAYER = new ActorRef(ActorKind.PLAYER, UUID.randomUUID().toString());
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("crash-child")) { crashChild(Path.of(args[1]), UUID.fromString(args[2])); return; }
        Path parent = args.length == 0 ? Path.of("build/recording-m1-test") : Path.of(args[0]);
        parent = parent.toAbsolutePath().normalize();
        if (!parent.toString().replace('\\', '/').contains("/build/")) throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "sqlite-");
        settingsAndOff(root.resolve("off"));
        transactionsAndRestart(root.resolve("normal"));
        identityCorruptionAndLock(root.resolve("identity"));
        lowQuotaAndQueue(root.resolve("quota"));
        forcedExit(root.resolve("crash"));
        System.out.println("RecordingStoreTest: " + assertions + " assertions passed; fixtures=" + root);
    }
    private static void settingsAndOff(Path root) throws Exception {
        check(RecordingSettings.load(root.resolve("missing.json")).archiveMode() == RecordingSettings.Mode.OFF, "missing config is OFF");
        check(RecordingSettings.off().worldRecordingLimitBytes() == 100_000_000_000L, "decimal 100GB uses 64-bit");
        var off = await(WorldRecordingService.open(root, UUID.randomUUID(), RecordingSettings.off(), CUTOVER));
        check(off.health().state() == WorldRecordingService.State.OFF && !Files.exists(root), "OFF creates no directory identity native connection or database");
        await(off.closeAsync()); check(!Files.exists(root), "OFF shutdown creates no marker");
        Files.createDirectories(root);
        Path config = root.resolve("fixture.json");
        String valid = "{\"schemaVersion\":2,\"archiveMode\":\"RECORD_ONLY\",\"worldRecordingLimitBytes\":100000000000,\"maintenanceHeadroomBytes\":1000000000,\"warningRatio\":0.9,\"deferBackgroundRatio\":0.95,\"overflowPolicy\":\"STOP_NEW_RECORDS\",\"automaticRawDeletion\":false,\"importLegacyTestData\":false}";
        Files.writeString(config, valid); check(RecordingSettings.load(config).worldRecordingLimitBytes() == 100_000_000_000L, "real config parser preserves 100GB");
        for (String malformed : List.of("{}", valid.replace("100000000000", "9223372036854775808"), valid.replace("100000000000", "1.5"),
                valid.replace("\"automaticRawDeletion\":false", "\"automaticRawDeletion\":true"), valid.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
                valid.replace("\"schemaVersion\":2", "\"schemaVersion\":2.5"), valid.replace("\"automaticRawDeletion\":false", "\"automaticRawDeletion\":\"false\""))) {
            Files.writeString(config, malformed); expectFailure(() -> RecordingSettings.load(config), "malformed policy never becomes a guessed default");
        }
    }
    private static void transactionsAndRestart(Path root) throws Exception {
        Files.createDirectories(root);
        Path legacy = root.resolve("mythictrpg-ai-memory/memories.json"); Files.createDirectories(legacy.getParent()); Files.writeString(legacy, "legacy-fixture-preserve");
        UUID world = UUID.randomUUID(); var store = open(root, world);
        UUID dataset = store.datasetId().orElseThrow(), epoch = store.runtimeEpoch();
        var producer = store.registerProducer("room", Set.of("PRIVATE"), Set.of());
        var sourceProducer = store.registerProducer("action", Set.of(), Set.of(SourceKind.ACTION_OBSERVED));
        var envelope = envelope(world, dataset, UUID.randomUUID(), true);
        String text = "  아주 오래된 대사\n" + "🌌한글 ".repeat(1500) + "끝의 정정도 보존한다.  ";
        var message = raw(text, "one");
        var receipt = delivery(PLAYER, "HUD", List.of("첫 페이지", "다음 페이지"), Set.of(0));
        var stored = await(store.capture(producer, envelope, message, List.of(receipt)));
        check(stored.status() == Status.STORED && stored.ingestSequence().isPresent(), "durable raw + receipt + work commit");
        check(await(store.inspectMessage(message.messageId(), 100_000)).orElseThrow().equals(text), "long exact Korean supplementary characters whitespace and old date survive all parts");
        var counts = await(store.statistics());
        check(counts.messages() == 1 && counts.deliveries() == 1 && counts.workItems() == 2, "one raw not one per audience; actual work transaction");
        var duplicate = await(store.capture(producer, envelope, message, List.of(receipt)));
        check(duplicate.status() == Status.DUPLICATE && duplicate.ingestSequence().equals(stored.ingestSequence()), "same occurrence yields existing durable sequence");
        check(await(store.capture(producer, envelope, raw(text + "changed", "one"), List.of())).status() == Status.CONFLICT, "occurrence conflicting content never overwrites");
        check(await(store.capture(producer, envelope, raw(text, "two"), List.of())).status() == Status.STORED, "same words another occurrence preserved");
        var stranger = new ActorRef(ActorKind.PLAYER, UUID.randomUUID().toString());
        var late = delivery(stranger, "CHAT", List.of(text), Set.of(0));
        var batch = new DeliveryBatch(UUID.randomUUID(), message.messageId(), "late", List.of(late));
        check(await(store.recordDeliveries(producer, batch)).status() == Status.STORED, "late actual receiver transaction");
        check(await(store.recordDeliveries(producer, batch)).status() == Status.DUPLICATE, "late batch retry idempotent");
        var doomed = raw("cannot attach another message's receipt", "rolled-back");
        check(await(store.capture(producer, envelope, doomed, List.of(receipt))).status() == Status.CONFLICT, "receipt UUID cannot move to another message");
        check(await(store.inspectMessage(doomed.messageId(), 4096)).isEmpty(), "raw insertion rolled back with invalid receipt");
        check(await(store.capture(ProducerCapability.unregistered(), envelope, raw("forged", "forged"), List.of())).status() == Status.UNAVAILABLE,
                "unregistered capability grants no authority");
        check(await(store.capture(producer, envelope(world, UUID.randomUUID(), envelope.conversationId(), true), raw("foreign", "foreign"), List.of())).status() == Status.UNAVAILABLE,
                "foreign dataset rejected before queue");
        check(await(store.capture(producer, envelope(world, dataset, envelope.conversationId(), false), raw("off", "off"), List.of())).status() == Status.UNAVAILABLE,
                "recording-off raw never queued");
        var source = new SourceRef(world, dataset, SourceKind.ACTION_OBSERVED, "action", "game-source", 1, RecordingRecords.sha256("game-owned-source"));
        var knowledge = new KnowledgeReceipt(UUID.randomUUID(), "test:god", "DIRECT_WATCH", "게임이 허용한 관찰 투영", Set.of(PLAYER), 1);
        check(await(store.captureSource(sourceProducer, new SourceCapture(source, 10, List.of(knowledge)))).status() == Status.UNAVAILABLE, "pre-cutover source not imported");
        check(await(store.captureSource(sourceProducer, new SourceCapture(source, 11, List.of(knowledge)))).status() == Status.STORED, "post-cutover game reference with permitted projection stored");
        check(await(store.invalidate(sourceProducer, new SourceInvalidation(source, 3, "PROOF_REVOKED"))).status() == Status.STORED, "revocation durable with separate state version");
        check(await(store.invalidate(sourceProducer, new SourceInvalidation(source, 2, "OLDER_NOTICE"))).status() == Status.DUPLICATE, "older invalidation never regresses current state");
        check(await(store.captureSource(sourceProducer, new SourceCapture(source, 12, List.of(knowledge)))).status() == Status.CONFLICT, "revoked source cannot reenter via capture");
        long high = store.health().highWatermark();
        await(store.closeAsync());
        check(Files.readString(legacy).equals("legacy-fixture-preserve"), "legacy test file untouched and not imported");
        var regressedAfterCapture = await(WorldRecordingService.open(root, world, ENABLED, CUTOVER));
        check(regressedAfterCapture.health().state() == WorldRecordingService.State.UNAVAILABLE, "restore before last consumed source cursor fails closed even after original cutover");
        await(regressedAfterCapture.closeAsync());
        var reopened = await(WorldRecordingService.open(root, world, ENABLED, new WorldRecordingService.CutoverBoundary("m1-offline-fixture", Map.of("action", 13L))));
        check(reopened.health().state() == WorldRecordingService.State.READY, "restart with actual current source cursor opens");
        check(reopened.datasetId().orElseThrow().equals(dataset) && !reopened.runtimeEpoch().equals(epoch), "same dataset new runtime epoch on restart");
        check(!reopened.health().possibleGap() && reopened.health().highWatermark() == high, "clean shutdown keeps durable cursor");
        check(await(reopened.inspectMessage(message.messageId(), 100_000)).orElseThrow().equals(text), "raw survives actual SQLite restart");
        check(await(reopened.capture(producer, envelope, raw("old epoch", "stale"), List.of())).status() == Status.UNAVAILABLE, "old runtime capability invalid after restart");
        var fresh = reopened.registerProducer("action", Set.of(), Set.of(SourceKind.ACTION_OBSERVED));
        check(await(reopened.captureSource(fresh, new SourceCapture(source, 13, List.of(knowledge)))).status() == Status.CONFLICT, "revocation survives restart");
        check(await(reopened.statistics()).messages() == 2, "failed/record-off/foreign writes created no raw rows");
        await(reopened.closeAsync());
        Path db = database(root, dataset);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var query = connection.createStatement()) {
            try (var row = query.executeQuery("PRAGMA foreign_key_check")) { check(!row.next(), "all real foreign keys consistent"); }
            try (var row = query.executeQuery("SELECT state_version FROM invalidations")) { row.next(); check(row.getLong(1) == 3, "revocation state version persisted monotonically"); }
            try (var row = query.executeQuery("SELECT count(*) FROM work_items WHERE state='INVALIDATED'")) { row.next(); check(row.getLong(1) == 1, "dependent work is invalidated transactionally"); }
            try (var row = query.executeQuery("SELECT count(*) FROM search_documents WHERE search_documents MATCH 'test'")) { row.next(); check(row.getLong(1) == 0, "actual native FTS5 query executes without invented indexed knowledge"); }
        }
    }
    private static void identityCorruptionAndLock(Path root) throws Exception {
        Files.createDirectories(root); UUID world = UUID.randomUUID(); var one = open(root, world);
        UUID dataset = one.datasetId().orElseThrow();
        var contender = await(WorldRecordingService.open(root, world, ENABLED, CUTOVER));
        check(contender.health().state() == WorldRecordingService.State.UNAVAILABLE, "second writer cannot acquire active world");
        await(contender.closeAsync()); await(one.closeAsync());
        Path manifest = root.resolve("mythictrpg-recording-v2/manifest.json"); String original = Files.readString(manifest);
        var foreign = await(WorldRecordingService.open(root, UUID.randomUUID(), ENABLED, CUTOVER));
        check(foreign.health().state() == WorldRecordingService.State.UNAVAILABLE && Files.readString(manifest).equals(original), "world mismatch preserves manifest"); await(foreign.closeAsync());
        var regressed = await(WorldRecordingService.open(root, world, ENABLED, new WorldRecordingService.CutoverBoundary("fixture", Map.of("action", 9L))));
        check(regressed.health().state() == WorldRecordingService.State.UNAVAILABLE, "source cursor rollback fails closed"); await(regressed.closeAsync());
        Path database = database(root, dataset);
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = db.createStatement()) { sql.execute("PRAGMA user_version=999"); }
        var future = await(WorldRecordingService.open(root, world, ENABLED, CUTOVER));
        check(future.health().state() == WorldRecordingService.State.UNAVAILABLE, "unknown schema never migrated or replaced"); await(future.closeAsync());
        byte[] corrupt = "not a sqlite database; preserve evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(database, corrupt);
        var broken = await(WorldRecordingService.open(root, world, ENABLED, CUTOVER));
        check(broken.health().state() == WorldRecordingService.State.UNAVAILABLE && Arrays.equals(Files.readAllBytes(database), corrupt), "truncated corrupt original preserved fail closed"); await(broken.closeAsync());
        Path absent = root.resolve("missing-manifest"); Files.createDirectories(absent.resolve("mythictrpg-recording-v2/staging"));
        var partial = await(WorldRecordingService.open(absent, UUID.randomUUID(), ENABLED, CUTOVER));
        check(partial.health().state() == WorldRecordingService.State.UNAVAILABLE && !Files.exists(absent.resolve("mythictrpg-recording-v2/manifest.json")), "partial missing manifest does not make another dataset"); await(partial.closeAsync());
    }
    private static void lowQuotaAndQueue(Path root) throws Exception {
        Files.createDirectories(root); UUID world = UUID.randomUUID();
        var settings = new RecordingSettings(RecordingSettings.Mode.RECORD_ONLY, 8_000_000, 1_000_000, .90, .95);
        var store = await(WorldRecordingService.open(root, world, settings, CUTOVER));
        check(store.health().state() == WorldRecordingService.State.READY, "small real physical quota initializes");
        var producer = store.registerProducer("room", Set.of("PRIVATE"), Set.of());
        var envelope = envelope(world, store.datasetId().orElseThrow(), UUID.randomUUID(), true);
        var oversized = await(store.capture(producer, envelope, raw("x".repeat(WorldRecordingService.QUEUE_BYTES + 1), "oversized"), List.of()));
        check(oversized.status() == Status.UNAVAILABLE && oversized.reasonCode().equals("QUEUE_FULL"), "oversized already accepted input reports gap not truncated success");
        var futures = new ArrayList<CompletionStage<WriteReceipt>>();
        for (int i = 0; i < 40; i++) futures.add(store.capture(producer, envelope, raw("x".repeat(20_000), "concurrent-" + i), List.of()));
        boolean full = false;
        for (var future : futures) full |= await(future).status() == Status.FULL;
        check(full && store.health().gapCount() > 0, "queued writes reserve combined worst growth before execution");
        check(ManagedStoreRegistry.open(root).measure().totalBytes() <= settings.worldRecordingLimitBytes(), "real DB WAL SHM staging and legacy sum below hard limit");
        await(store.closeAsync());
    }
    private static void forcedExit(Path root) throws Exception {
        Files.createDirectories(root); UUID world = UUID.randomUUID();
        String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.exists(Path.of(javaBinary))) javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaBinary, "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"), RecordingStoreTest.class.getName(),
                "crash-child", root.toString(), world.toString()).redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(root.resolve("durable.ready")) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            boolean committed = Files.exists(root.resolve("durable.ready"));
            // Native Windows diagnostics can use another encoding. Do not decode a successful
            // child's log eagerly, or let diagnostic text hide the actual durability assertion.
            String diagnostic = committed ? "" : new String(Files.readAllBytes(root.resolve("child.log")),
                    java.nio.charset.StandardCharsets.UTF_8);
            check(committed, "child reached real durable commit before forced process exit: " + diagnostic);
        } finally { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
        var store = open(root, world);
        check(store.health().possibleGap(), "unclean process termination exposes POSSIBLE_GAP");
        check(await(store.statistics()).messages() == 1 && await(store.statistics()).coverageGaps() >= 1, "committed WAL recovers and unclean gap is durable");
        await(store.closeAsync());
    }
    private static void crashChild(Path root, UUID world) throws Exception {
        var store = open(root, world); var producer = store.registerProducer("room", Set.of("PRIVATE"), Set.of());
        var result = await(store.capture(producer, envelope(world, store.datasetId().orElseThrow(), UUID.randomUUID(), true), raw("durable before process loss", "crash"), List.of()));
        if (result.status() != Status.STORED) throw new IllegalStateException(result.toString());
        Files.writeString(root.resolve("durable.ready"), "committed");
        new CountDownLatch(1).await();
    }
    private static WorldRecordingService open(Path path, UUID world) throws Exception {
        var value = await(WorldRecordingService.open(path, world, ENABLED, CUTOVER));
        check(value.health().state() == WorldRecordingService.State.READY, "actual SQLite store ready: " + value.health());
        check(value.health().sqliteVersion().equals("3.53.4"), "runtime engine is pinned patched version"); return value;
    }
    private static ConversationEnvelope envelope(UUID world, UUID dataset, UUID room, boolean recording) { return new ConversationEnvelope(world, dataset, room, "PRIVATE", "game-policy", 1, 1, recording, false, "fixture-v1"); }
    private static RawMessage raw(String text, String key) { return new RawMessage(UUID.randomUUID(), Optional.empty(), 0, PLAYER, text, Instant.parse("2020-01-01T00:00:00Z"), MessageKind.ACCEPTED_INPUT, key); }
    private static DeliveryReceipt delivery(ActorRef player, String kind, List<String> parts, Set<Integer> received) {
        return new DeliveryReceipt(UUID.randomUUID(), player, kind, Instant.parse("2020-01-01T00:00:01Z"), 1,
                parts.size() == received.size() ? DeliveryStatus.SERVER_DISPATCHED : DeliveryStatus.PARTIAL_DISPATCH,
                new DeliveryView(String.join("", parts), parts), received);
    }
    private static Path database(Path root, UUID dataset) { return root.resolve("mythictrpg-recording-v2").resolve(dataset.toString()).resolve("recording.sqlite"); }
    private static <T> T await(CompletionStage<T> future) throws Exception { return future.toCompletableFuture().get(30, TimeUnit.SECONDS); }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void expectFailure(Throwing action, String description) throws Exception { try { action.run(); throw new AssertionError(description); } catch (java.io.IOException expected) { assertions++; } }
    private static void check(boolean condition, String description) { assertions++; if (!condition) throw new AssertionError(description); }
}
