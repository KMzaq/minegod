package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Real SQLite fixtures: durable dynamic cutover, incremental knowledge and independent receipt withdrawal. */
public final class RecordingKnowledgeStoreTest {
    private static int checks;
    private static final String OWNER = "rumor-metadata-v1";
    private static final RecordingSettings SETTINGS = new RecordingSettings(RecordingSettings.Mode.RECORD_ONLY, 128_000_000, 2_000_000, .90, .95);
    private static final WorldRecordingService.CutoverBoundary EMPTY = new WorldRecordingService.CutoverBoundary("knowledge-fixture", Map.of());
    public static void main(String[] args) throws Exception {
        Path parent = (args.length == 0 ? Path.of("build/recording-knowledge-test") : Path.of(args[0])).toAbsolutePath().normalize();
        if (!parent.toString().replace('\\', '/').contains("/build/")) throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "knowledge-");
        incremental(root.resolve("incremental"));
        for (int version : List.of(2, 3, 4)) migrate(root.resolve("schema-" + version), version);
        System.out.println("RecordingKnowledgeStoreTest: " + checks + " checks passed; fixtures=" + root);
    }
    private static void incremental(Path root) throws Exception {
        Files.createDirectories(root);
        UUID world = UUID.randomUUID(), lineage = UUID.randomUUID();
        var store = await(WorldRecordingService.open(root, world, SETTINGS, EMPTY));
        check(store.health().state() == WorldRecordingService.State.READY, "store opens");
        UUID dataset = store.datasetId().orElseThrow();
        var producer = store.registerProducer(OWNER, Set.of(), Set.of(SourceKind.RUMOR_RECEIVED));
        var first = await(store.registerSource(producer, registration(world, dataset, lineage, 10)));
        check(first.receipt().status() == Status.STORED && first.cutoverCursor().orElseThrow() == 10, "first durable snapshot freezes cutover");
        check(await(store.registerSource(producer, registration(world, dataset, lineage, 10))).receipt().status() == Status.DUPLICATE, "same registration retry stable");
        check(await(store.registerSource(ProducerCapability.unregistered(), registration(world, dataset, lineage, 30))).receipt().status() == Status.UNAVAILABLE, "forged capability registration denied");
        var confirmed = await(store.registerSource(producer, registration(world, dataset, lineage, 30)));
        check(confirmed.receipt().status() == Status.STORED && confirmed.cutoverCursor().orElseThrow() == 10, "future snapshot retains original cutoff");
        var source = source(world, dataset, "root", 1);
        var a = knowledge("test:a", "a knows the claim");
        check(await(store.appendKnowledge(producer, capture(source, lineage, 9, 12, a, 14))).reasonCode().equals("BEFORE_CUTOVER_OR_ORIGIN_UNKNOWN"), "late delivery never promotes pre-cutover root");
        check(await(store.appendKnowledge(producer, capture(source, lineage, 11, 12, a, 31))).reasonCode().equals("SOURCE_NOT_DURABLY_CONFIRMED"), "future noncommitted receipt denied");
        var firstCapture = capture(source, lineage, 11, 12, a, 14);
        check(await(store.appendKnowledge(producer, firstCapture)).status() == Status.STORED, "new root and first actual receipt atomically captured");
        check(await(store.appendKnowledge(producer, firstCapture)).status() == Status.DUPLICATE, "retry does not duplicate source or receipt");
        var b = knowledge("test:b", "b heard this later");
        check(await(store.appendKnowledge(producer, capture(source, lineage, 11, 12, b, 20))).status() == Status.STORED, "late God receipt appends without source batch conflict");
        check(scalar(root, dataset, "SELECT count(*) FROM source_refs") == 1 && scalar(root, dataset, "SELECT count(*) FROM knowledge_receipts") == 2, "one shared source and independent acquisitions");
        var altered = new SourceRef(world, dataset, SourceKind.RUMOR_RECEIVED, OWNER, "root", 1, RecordingRecords.sha256("altered immutable source"));
        check(await(store.appendKnowledge(producer, capture(altered, lineage, 11, 12, b, 20))).reasonCode().equals("SOURCE_PROVENANCE_CONFLICT"), "source hash immutable on late receipt");
        var differentProjection = new KnowledgeReceipt(b.receiptId(), b.godId(), b.acquisition(), "changed projection", b.permittedAudience(), b.policyRevision());
        check(await(store.appendKnowledge(producer, capture(source, lineage, 11, 12, differentProjection, 20))).reasonCode().equals("KNOWLEDGE_RECEIPT_CONFLICT"), "receipt cannot be silently rewritten");
        check(await(store.appendKnowledge(producer, capture(source, lineage, 11, 12, b, 21))).reasonCode().equals("KNOWLEDGE_RECEIPT_CONFLICT"), "receipt acquisition cursor immutable");
        long generation = store.authorityGeneration();
        check(await(store.invalidateKnowledge(ProducerCapability.unregistered(), new KnowledgeInvalidation(source, a.receiptId(), 25, "FORGED"))).status() == Status.UNAVAILABLE
                && store.authorityGeneration() == generation && store.readAuthorityStable(), "unregistered withdrawal cannot retire valid leases");
        check(await(store.invalidateKnowledge(producer, new KnowledgeInvalidation(source, a.receiptId(), 25, "PROOF_WITHDRAWN"))).status() == Status.STORED, "one proof durably withdrawn");
        check(store.authorityGeneration() > generation && store.readAuthorityStable(), "invalidation retires prior read leases and releases pending barrier");
        check(live(root, dataset, "test:a") == 0 && live(root, dataset, "test:b") == 1, "other God still owns valid shared-source receipt");
        check(scalar(root, dataset, "SELECT revoked FROM source_refs") == 0, "receipt withdrawal does not revoke source");
        check(await(store.invalidateKnowledge(producer, new KnowledgeInvalidation(source, a.receiptId(), 24, "OLDER_NOTICE"))).status() == Status.DUPLICATE, "older withdrawal cannot regress state");
        check(await(store.appendKnowledge(producer, firstCapture)).reasonCode().equals("KNOWLEDGE_REVOKED"), "retry cannot resurrect proof");
        var next = source(world, dataset, "next-root", 1);
        var withdrawnBeforeInsert = knowledge("test:c", "never grant after tombstone");
        check(await(store.invalidateKnowledge(producer, new KnowledgeInvalidation(next, withdrawnBeforeInsert.receiptId(), 28, "PROOF_WITHDRAWN"))).status() == Status.STORED, "tombstone can precede source insertion");
        check(await(store.appendKnowledge(producer, capture(next, lineage, 21, 22, withdrawnBeforeInsert, 23))).reasonCode().equals("KNOWLEDGE_REVOKED"), "tombstone before insert prevents revival");
        check(scalar(root, dataset, "SELECT count(*) FROM source_refs WHERE source_id='next-root'") == 0, "failed receipt rolled back source and work insertion");
        var otherOwner = store.registerProducer("foreign", Set.of(), Set.of(SourceKind.RUMOR_RECEIVED));
        var foreignSource = new SourceRef(world, dataset, SourceKind.RUMOR_RECEIVED, "foreign", "root", 1, source.hash());
        check(await(store.invalidateKnowledge(otherOwner, new KnowledgeInvalidation(foreignSource, b.receiptId(), 100, "FORGED"))).reasonCode().equals("FOREIGN_KNOWLEDGE_RECEIPT"), "producer cannot revoke foreign receipt UUID");
        check(live(root, dataset, "test:b") == 1, "foreign attempt preserves valid recipient");
        check(store.readAuthorityStable(), "conflicted withdrawal releases pending barrier");
        check(await(store.invalidateKnowledge(otherOwner, new KnowledgeInvalidation(foreignSource, withdrawnBeforeInsert.receiptId(), 100, "FORGED"))).reasonCode().equals("FOREIGN_KNOWLEDGE_TOMBSTONE"), "preinsert tombstone ownership immutable");
        var good = knowledge("test:d", "valid retry after another root failed");
        var laterSource = source(world, dataset, "later-root", 1);
        check(await(store.appendKnowledge(producer, capture(laterSource, lineage, 26, 26, good, 27))).status() == Status.STORED, "later root may commit while earlier root unavailable");
        var retry = knowledge("test:e", "earlier root retried without skip");
        check(await(store.appendKnowledge(producer, capture(next, lineage, 21, 22, retry, 23))).status() == Status.STORED, "earlier root still processed after later cursor success");
        check(scalar(root, dataset, "SELECT count(*) FROM consumer_cursors WHERE consumer='capture' AND stream='" + OWNER + "'") == 0, "incremental sources never advance lossy MAX ingestion checkpoint");
        var rolledBack = knowledge("test:f", "batch first member must rollback");
        var batch = new SourceKnowledgeCapture(source, lineage, 11, 12, List.of(new AcquiredKnowledge(rolledBack, 16), new AcquiredKnowledge(good, 27)));
        check(await(store.appendKnowledge(producer, batch)).status() == Status.CONFLICT, "one foreign receipt conflicts whole batch");
        check(scalar(root, dataset, "SELECT count(*) FROM knowledge_receipts WHERE god_id='test:f'") == 0, "same-source batch atomic despite member order");
        check(await(store.invalidate(producer, new SourceInvalidation(laterSource, 30, "CLAIM_REVOKED"))).status() == Status.STORED, "source-wide withdrawal remains distinct");
        check(live(root, dataset, "test:d") == 0 && live(root, dataset, "test:b") == 1, "source withdrawal affects only that root revision");
        await(store.closeAsync());
        check(await(store.invalidateKnowledge(producer, new KnowledgeInvalidation(source, b.receiptId(), 31, "CLOSED"))).status() == Status.UNAVAILABLE
                && store.readAuthorityStable(), "closed store rejection does not leave pending barrier");
        var reopened = await(WorldRecordingService.open(root, world, SETTINGS, EMPTY));
        check(reopened.health().state() == WorldRecordingService.State.READY, "dynamic source metadata survives reopen without manifest rewrite");
        var fresh = reopened.registerProducer(OWNER, Set.of(), Set.of(SourceKind.RUMOR_RECEIVED));
        check(await(reopened.appendKnowledge(fresh, capture(source, lineage, 11, 12, b, 20))).reasonCode().equals("SOURCE_NOT_CONFIRMED_THIS_RUNTIME"), "reopen requires original store current durable confirmation");
        check(await(reopened.registerSource(fresh, registration(world, dataset, lineage, 29))).receipt().reasonCode().equals("SOURCE_CURSOR_REGRESSED"), "original-store rollback detected");
        check(await(reopened.registerSource(fresh, registration(world, dataset, UUID.randomUUID(), 31))).receipt().reasonCode().equals("SOURCE_LINEAGE_MISMATCH"), "missing or reset original metadata cannot bootstrap existing dataset");
        var restored = await(reopened.registerSource(fresh, registration(world, dataset, lineage, 31)));
        check(restored.receipt().status() == Status.STORED && restored.cutoverCursor().orElseThrow() == 10, "legitimate next durable snapshot retains first registration cutoff");
        check(await(reopened.appendKnowledge(fresh, capture(source, lineage, 11, 12, b, 20))).status() == Status.DUPLICATE, "partial snapshot retry survives restart");
        check(await(reopened.appendKnowledge(fresh, firstCapture)).reasonCode().equals("KNOWLEDGE_REVOKED"), "receipt tombstone survives restart");
        check(scalar(root, dataset, "SELECT state_version FROM knowledge_invalidations WHERE receipt_id='" + a.receiptId() + "'") == 25, "older notice did not modify durable version");
        await(reopened.closeAsync());
    }
    private static void migrate(Path root, int oldVersion) throws Exception {
        Files.createDirectories(root);
        UUID world = UUID.randomUUID();
        var boundary = new WorldRecordingService.CutoverBoundary("migration", Map.of("action", 10L));
        var store = await(WorldRecordingService.open(root, world, SETTINGS, boundary));
        UUID dataset = store.datasetId().orElseThrow();
        var producer = store.registerProducer("action", Set.of(), Set.of(SourceKind.ACTION_OBSERVED));
        var source = new SourceRef(world, dataset, SourceKind.ACTION_OBSERVED, "action", "old", 1, RecordingRecords.sha256("preserved raw provenance"));
        var receipt = knowledge("test:old", "preserved projection");
        check(await(store.captureSource(producer, new SourceCapture(source, 11, List.of(receipt)))).status() == Status.STORED, "old source fixture stored");
        await(store.closeAsync());
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + database(root, dataset)); var sql = db.createStatement()) {
            sql.execute("DROP TABLE IF EXISTS native_memory_evidence");
            sql.execute("DROP TABLE IF EXISTS projection_input_manifests");
            sql.execute("DROP TABLE IF EXISTS native_interpretation_evidence");
            for(String table:List.of("embedding_rows","embedding_jobs","embedding_seed_progress"))sql.execute("DROP TABLE "+table);
            sql.execute("DROP INDEX embedding_source_seed");
            for(String table:List.of("recording_lexical_fts","recording_lexical_manifest","recording_lexical_progress","recording_lexical_skips"))sql.execute("DROP TABLE "+table);
            for (String table : List.of("memory_links", "memory_subjects", "memory_sources", "memories")) sql.execute("DROP TABLE " + table);
            for (String column : List.of("extractor_version", "attempt_count", "next_attempt_utc", "last_failure", "lease_nonce")) sql.execute("ALTER TABLE work_items DROP COLUMN " + column);
            for (String table : List.of("knowledge_origins", "source_origins", "source_cutovers", "knowledge_invalidations")) sql.execute("DROP TABLE " + table);
            if (oldVersion < 4) { sql.execute("DROP VIEW delivery_parts_resolved"); sql.execute("DROP TABLE delivery_part_refs"); }
            if (oldVersion < 3) sql.execute("DROP TABLE message_contexts");
            sql.execute("UPDATE recording_meta SET schema_version=" + oldVersion); sql.execute("PRAGMA user_version=" + oldVersion);
        }
        var reopened = await(WorldRecordingService.open(root, world, SETTINGS, new WorldRecordingService.CutoverBoundary("migration", Map.of("action", 11L))));
        check(reopened.health().state() == WorldRecordingService.State.READY, "schema " + oldVersion + " migration ready");
        check(scalar(root, dataset, "PRAGMA user_version") == RecordingSchema.VERSION && scalar(root, dataset, "SELECT schema_version FROM recording_meta") == RecordingSchema.VERSION, "both schema markers migrated");
        check(scalar(root, dataset, "SELECT count(*) FROM knowledge_receipts WHERE projection='preserved projection'") == 1, "old knowledge and source data preserved");
        check(scalar(root, dataset, "SELECT count(*) FROM source_cutovers") == 0 && scalar(root, dataset, "SELECT count(*) FROM source_origins") == 0, "migration never invents provenance or imports old root");
        var fresh = reopened.registerProducer("action", Set.of(), Set.of(SourceKind.ACTION_OBSERVED));
        check(await(reopened.invalidateKnowledge(fresh, new KnowledgeInvalidation(source, receipt.receiptId(), 1, "OLD_PROOF_REVOKED"))).status() == Status.STORED, "new receipt tombstones also apply to preserved old source");
        check(live(root, dataset, "test:old") == 0, "migrated old receipt denied after withdrawal");
        await(reopened.closeAsync());
    }
    private static SourceRegistration registration(UUID world, UUID dataset, UUID lineage, long cursor) { return new SourceRegistration(world, dataset, OWNER, lineage, cursor); }
    private static SourceRef source(UUID world, UUID dataset, String id, long revision) { return new SourceRef(world, dataset, SourceKind.RUMOR_RECEIVED, OWNER, id, revision, RecordingRecords.sha256(id + ":" + revision)); }
    private static KnowledgeReceipt knowledge(String god, String projection) { return new KnowledgeReceipt(UUID.randomUUID(), god, "RUMOR_RECEIVED", projection, Set.of(new ActorRef(ActorKind.GOD, god)), 1); }
    private static SourceKnowledgeCapture capture(SourceRef source, UUID lineage, long origin, long state, KnowledgeReceipt receipt, long acquired) { return new SourceKnowledgeCapture(source, lineage, origin, state, List.of(new AcquiredKnowledge(receipt, acquired))); }
    private static Path database(Path root, UUID dataset) { return root.resolve("mythictrpg-recording-v2").resolve(dataset.toString()).resolve("recording.sqlite"); }
    private static long live(Path root, UUID dataset, String god) throws Exception {
        return scalar(root, dataset, "SELECT count(*) FROM knowledge_receipts k JOIN source_refs s ON s.id=k.source_ref WHERE s.revoked=0 AND k.god_id='" + god + "' AND NOT EXISTS(SELECT 1 FROM knowledge_invalidations x WHERE x.dataset_id=k.dataset_id AND x.receipt_id=k.id)");
    }
    private static long scalar(Path root, UUID dataset, String query) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + database(root, dataset)); var sql = db.createStatement(); var row = sql.executeQuery(query)) { row.next(); return row.getLong(1); }
    }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(20, TimeUnit.SECONDS); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
