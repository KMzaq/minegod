package com.sande.mythictrpg.recording.server;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.experiencecontract.ExperienceRoomEvidence;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.watch.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.Status;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.objectweb.asm.*;

/** Actual raw/watch journals -> filtered game proof -> SQLite source/knowledge transaction. No live server or LLM. */
public final class WatchRecordingCaptureTest {
    private static final UUID WORLD = UUID.randomUUID(), PLAYER = UUID.randomUUID(), OTHER = UUID.randomUUID(), BOOT = UUID.randomUUID();
    private static final String G = "mythictrpg:fortuna", H = "mythictrpg:demeter";
    private static final Ref RULE = new Ref("test:owner", 1);
    private static int checks;
    private record Committed(ActionRecord raw, List<Proof> proofs) { }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args.length == 0 ? "build/watch-recording-capture-test" : args[0]).toAbsolutePath().normalize();
        if (!base.toString().replace('\\', '/').contains("/build/")) throw new IllegalArgumentException("BUILD_ONLY");
        Path root = Files.createTempDirectory(Files.createDirectories(base), "watch-capture-");
        Path rawPath = root.resolve("mythictrpg-action-ledger-v1"), watchPath = root.resolve("mythictrpg-god-watch-v1");
        var rawLimits = new ActionLedgerStore.Limits(4_000_000, 128_000, 5000);
        var watchLimits = new AsyncGodWatch.Limits(4_000_000, 5000, 64);
        Committed durable;
        UUID dataset;
        try (var raw = new AsyncActionLedger(rawPath, WORLD, rawLimits, 64)) {
            await(raw.ready());
            try (var watch = new AsyncGodWatch(watchPath, WORLD, BOOT, raw, watchLimits)) {
                await(watch.ready()); await(watch.disclose(new Disclosure(RULE, Set.of(PLAYER))));
                await(watch.start(UUID.randomUUID(), approval(G, false)));
                await(watch.start(UUID.randomUUID(), approval(H, true)));
                Committed old = commit(watch, draft(1), false, Set.of());
                check(old.proofs().size() == 2, "fixture has two actual committed God observations before cutover");
                var store = open(root, raw.status().committedSequence());
                try {
                    dataset = store.datasetId().orElseThrow();
                    var capture = new WatchRecordingCapture(store); var probe = new TestProbe(watch);
                    var rejected = probe.pump(capture.capture(old.raw(), old.proofs(), probe));
                    check(!rejected.complete() && rejected.reason().equals("BEFORE_CUTOVER_OR_ORIGIN_UNKNOWN"), "pre-cutover actual proof is not backfilled");
                    check(count(root, store, "source_refs") == 0, "old raw remains outside recording dataset");
                    var d = draft(2); var submission = raw.submit(d);
                    var observed = watch.observeSubmitted(d, scene(d, false, Set.of()), submission);
                    durable = new Committed(await(submission.durable()), await(observed));
                    var written = probe.pump(capture.capture(durable.raw(), durable.proofs(), probe));
                    check(written.status() == Status.STORED && written.knowledgeCount() == 2, "durable observation produces one complete multi-God capture");
                    check(raw.status().committedSequence() == 2, "adapter never resubmits or duplicates raw input");
                    check(count(root, store, "messages") == 0 && count(root, store, "message_parts") == 0, "raw administrative body is not copied into conversation storage");
                    check(count(root, store, "source_refs") == 1 && count(root, store, "knowledge_receipts") == 2, "one native source supports independent God knowledge receipts");
                    try (var db = db(root, store); var q = db.createStatement(); var rows = q.executeQuery("SELECT k.god_id,k.acquisition,k.projection,k.audience_json,k.policy_revision,s.source_hash,s.ingest_sequence,w.created_sequence,c.cursor FROM knowledge_receipts k JOIN source_refs s ON s.id=k.source_ref JOIN work_items w ON w.source_ref=s.id JOIN consumer_cursors c ON c.stream='action-ledger-v1' AND c.consumer='capture' ORDER BY k.god_id")) {
                        int receipts = 0;
                        while (rows.next()) {
                            receipts++;
                            var projection = JsonParser.parseString(rows.getString(3)).getAsJsonObject();
                            var experience = projection.getAsJsonObject("experience");
                            check(projection.keySet().equals(Set.of("memoryMode", "experience", "evidence")) && projection.get("memoryMode").getAsString().equals("PERSONAL"), "explicit existing PERSONAL projection only");
                            check(projection.getAsJsonObject("evidence").get("kind").getAsString().equals(ExperienceRoomEvidence.KIND), "existing portable proof accompanies durable knowledge");
                            check(rows.getString(2).equals("DIRECT_WATCH") && experience.get("outcome").getAsString().equals("BLOCK_REMOVED_NOT_ITEM_ACQUISITION"), "direct observation is not promoted to loot/reward outcome");
                            check(!rows.getString(3).contains("PRIVATE_PAYLOAD") && !rows.getString(3).contains("minecraft:overworld"), "unprojected raw payload and coordinates stay out of knowledge");
                            check(experience.get("gameTime").getAsString().equals(rows.getString(1).equals(G) ? "NOT_DISCLOSED" : "utc=1234;tick=50;dayTime=6000"), "different God field policies remain different projections");
                            var audience = JsonParser.parseString(rows.getString(4)).getAsJsonArray();
                            check(audience.size() == 2 && rows.getString(4).contains(PLAYER.toString()) && rows.getString(4).contains(rows.getString(1)) && !rows.getString(4).contains(OTHER.toString()), "receipt audience contains only owner God and approved player");
                            check(rows.getLong(5) == 1 && rows.getString(6).matches("[0-9a-f]{64}") && rows.getLong(7) == rows.getLong(8) && rows.getLong(9) == durable.raw().sequence(), "source/knowledge work and source cursor commit together");
                        }
                        check(receipts == 2, "both separate receipt rows are durable");
                    }
                    check(probe.pump(capture.capture(durable.raw(), durable.proofs().reversed(), probe)).status() == Status.DUPLICATE, "same occurrence retry order cannot duplicate or rewrite knowledge");
                    check(count(root, store, "source_refs") == 1 && count(root, store, "knowledge_receipts") == 2, "duplicate source/receipt rows remain singular");
                    await(store.closeAsync()); store = open(root, raw.status().committedSequence()); capture = new WatchRecordingCapture(store);
                    var reread = await(raw.after(new ActionLedgerStore.Cursor(WORLD, durable.raw().sequence() - 1), PLAYER, 1)).records().getFirst();
                    check(probe.pump(capture.capture(reread, durable.proofs(), probe)).status() == Status.DUPLICATE, "reopened SQLite and deserialized raw preserve immutable source fingerprint");
                    Committed occluded = commit(watch, draft(3), true, Set.of());
                    check(occluded.proofs().isEmpty() && !probe.pump(capture.capture(occluded.raw(), occluded.proofs(), probe)).complete(), "occluded actual input does not invent knowledge");
                    Committed blocked = commit(watch, draft(4), false, Set.of(G, H));
                    check(blocked.proofs().isEmpty() && !probe.pump(capture.capture(blocked.raw(), blocked.proofs(), probe)).complete(), "blocked Gods are not added from raw facts");
                    Committed fresh = commit(watch, draft(5), false, Set.of());
                    probe.live.set(false);
                    check(!probe.pump(capture.capture(fresh.raw(), fresh.proofs(), probe)).complete(), "stale runtime/mode cannot start a new capture");
                    probe.live.set(true);
                    var late = capture.capture(fresh.raw(), fresh.proofs(), probe); probe.live.set(false);
                    check(!probe.pump(late).complete(), "runtime change while proof read is in flight cannot commit knowledge");
                    probe.live.set(true);
                    check(!probe.pump(capture.capture(fresh.raw(), durable.proofs(), probe)).complete(), "foreign event proof cannot authorize another raw source");
                    await(watch.revokeProof(fresh.proofs().getFirst().id()));
                    check(!probe.pump(capture.capture(fresh.raw(), fresh.proofs(), probe)).complete(), "pending or committed proof revoke rejects complete capture batch");
                    check(count(root, store, "source_refs") == 1, "all failed optional attempts leave existing source intact");
                    Committed hidden = commit(watch, draft(6), false, Set.of());
                    await(watch.disclose(new Disclosure(new Ref(RULE.id(), 2), Set.of(OTHER))));
                    check(!probe.pump(capture.capture(hidden.raw(), hidden.proofs(), probe)).complete(), "current disclosure cannot be bypassed by previously returned proof");
                    // Disclosure failure does not rewrite historical facts or claim that a different God's raw source was revoked.
                    check(count(root, store, "source_refs") == 1 && count(root, store, "knowledge_receipts") == 2 && raw.status().committedSequence() == 6, "optional capture failure does not remove original knowledge or block raw commits");
                } finally { await(store.closeAsync()); }
            }
        }
        // Reopening does not import old raw or recreate watch state. Existing recorded projection remains immutable.
        try (var raw = new AsyncActionLedger(rawPath, WORLD, rawLimits, 64)) {
            await(raw.ready());
            try (var watch = new AsyncGodWatch(watchPath, WORLD, UUID.randomUUID(), raw, watchLimits)) {
                await(watch.ready()); var store = open(root, raw.status().committedSequence());
                try {
                    check(store.datasetId().orElseThrow().equals(dataset) && count(root, store, "source_refs") == 1 && count(root, store, "knowledge_receipts") == 2, "dataset reopen preserves native source and independent receipts without legacy import");
                    var probe = new TestProbe(watch);
                    check(!probe.pump(new WatchRecordingCapture(store).capture(durable.raw(), durable.proofs(), probe)).complete(), "durably changed disclosure rejects old source replay after restart");
                    check(count(root, store, "messages") == 0 && raw.status().committedSequence() == 6, "restart capture neither creates conversation originals nor resubmits raw");
                } finally { await(store.closeAsync()); }
            }
        }
        activityAndUnsupported(root.resolve("activity"), rawLimits, watchLimits);
        durableReconciliation(root.resolve("reconcile"), rawLimits, watchLimits);
        productionHook();
        WatchKnowledgeReconcilerTest.run();
        System.out.println("WatchRecordingCaptureTest: " + checks + " checks passed; fixtures=" + root + "; NO server/GameTest/LLM");
    }
    private static void activityAndUnsupported(Path root, ActionLedgerStore.Limits rawLimits, AsyncGodWatch.Limits watchLimits) throws Exception {
        Files.createDirectories(root);
        try (var raw = new AsyncActionLedger(root.resolve("mythictrpg-action-ledger-v1"), WORLD, rawLimits, 64)) {
            await(raw.ready());
            try (var watch = new AsyncGodWatch(root.resolve("mythictrpg-god-watch-v1"), WORLD, BOOT, raw, watchLimits)) {
                await(watch.ready()); await(watch.disclose(new Disclosure(RULE, Set.of(PLAYER))));
                var own = await(watch.start(UUID.randomUUID(), approval(G, false)));
                await(watch.start(UUID.randomUUID(), approval(H, false)));
                var store = open(root, 0);
                try {
                    var capture = new WatchRecordingCapture(store); var probe = new TestProbe(watch);
                    var summary = new ActionRecord.Draft(UUID.randomUUID(), BOOT, 1, "mythictrpg:detail/observed_activity_summary", 1, PLAYER,
                            new ActionRecord.Subject("ACTIVITY", "minecraft:wheat", null), 1234, 50, 6000, "minecraft:overworld", new ActionRecord.Position(0, 64, 0),
                            ActionRecord.Type.OBSERVED_ACTIVITY_SUMMARY, "COMPLETED", Map.of("observer_god", G, "watch_id", own.id().toString(),
                            "count", "3", "activity", "MATURE_CROP_REMOVED", "coverage", "VISIBLE_COMMITTED_SAMPLES_ONLY_NOT_TOTAL_STATS"), "mythictrpg:admin_only_unprojected");
                    var activity = commit(watch, summary, false, Set.of());
                    check(activity.proofs().size() == 1 && probe.pump(capture.capture(activity.raw(), activity.proofs(), probe)).knowledgeCount() == 1, "existing activity watch ownership is not broadened to other active God");
                    try (var db = db(root, store); var q = db.createStatement(); var rows = q.executeQuery("SELECT s.kind,k.god_id,k.projection FROM source_refs s JOIN knowledge_receipts k ON k.source_ref=s.id")) {
                        check(rows.next() && rows.getString(1).equals("ACTIVITY_OBSERVED") && rows.getString(2).equals(G)
                                && JsonParser.parseString(rows.getString(3)).getAsJsonObject().getAsJsonObject("experience")
                                .get("outcome").getAsString().equals("VISIBLE_SAMPLES=3;ACTIVITY=MATURE_CROP_REMOVED"),
                                "actual supported activity stores qualified samples, not inferred total stats");
                        check(!rows.next(), "activity receipt remains per existing watch");
                    }
                    var unsupported = new ActionRecord.Draft(UUID.randomUUID(), BOOT, 2, "mythictrpg:detail/block_removed", 1, PLAYER,
                            new ActionRecord.Subject("BLOCK", "minecraft:stone", null), 1234, 50, 6000, "minecraft:overworld", new ActionRecord.Position(0, 64, 0),
                            ActionRecord.Type.BLOCK_REMOVED, "COMPLETED", Map.of(), "mythictrpg:admin_only_unprojected");
                    var other = commit(watch, unsupported, false, Set.of());
                    var omitted = probe.pump(capture.capture(other.raw(), other.proofs(), probe));
                    check(!other.proofs().isEmpty() && omitted.reason().equals("NO_SUPPORTED_EXPERIENCE"), "unsupported existing projection is omitted, not converted into a new event meaning");
                    check(count(root, store, "source_refs") == 1 && raw.status().committedSequence() == 2, "unsupported optional projection preserves raw without broadening knowledge");
                } finally { await(store.closeAsync()); }
            }
        }
    }
    private static final class TestProbe implements WatchRecordingCapture.Probe {
        private final AsyncGodWatch watch;
        private final BlockingQueue<Runnable> game = new LinkedBlockingQueue<>();
        final AtomicBoolean live = new AtomicBoolean(true);
        TestProbe(AsyncGodWatch watch) { this.watch = watch; }
        public boolean current() { return live.get(); }
        public CompletableFuture<AsyncGodWatch.ReadSnapshot> read(Audience audience, UUID observation) { return watch.readExact(audience, Set.of(observation)); }
        public boolean current(AsyncGodWatch.ReadSnapshot snapshot, Audience audience) { return live.get() && watch.current(snapshot, audience); }
        public void dispatch(Runnable task) { game.add(task); }
        <T> T pump(CompletableFuture<T> future) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!future.isDone() && System.nanoTime() < deadline) { var task = game.poll(20, TimeUnit.MILLISECONDS); if (task != null) task.run(); }
            return await(future);
        }
    }
    private static void durableReconciliation(Path root, ActionLedgerStore.Limits rawLimits, AsyncGodWatch.Limits watchLimits) throws Exception {
        Files.createDirectories(root); UUID otherProof; long checkpointRevision;
        try (var raw = new AsyncActionLedger(root.resolve("mythictrpg-action-ledger-v1"), WORLD, rawLimits, 64)) {
            await(raw.ready());
            try (var watch = new AsyncGodWatch(root.resolve("mythictrpg-god-watch-v1"), WORLD, BOOT, raw, watchLimits)) {
                await(watch.ready()); await(watch.disclose(new Disclosure(RULE, Set.of(PLAYER))));
                await(watch.start(UUID.randomUUID(), approval(G, false))); await(watch.start(UUID.randomUUID(), approval(H, true)));
                var store = open(root, 0);
                try {
                    var capture = new WatchRecordingCapture(store); var captureProbe = new TestProbe(watch);
                    var original = commit(watch, draft(1), false, Set.of());
                    check(captureProbe.pump(capture.capture(original.raw(), original.proofs(), captureProbe)).complete(), "reconciliation fixture captures original two-God knowledge");
                    var archive = capture.reconciliationArchive(); var probe = new ReconcileProbe(watch);
                    var service = new WatchKnowledgeReconciler(archive, probe); probe.untilCurrent(service);
                    check(count(root, store, "knowledge_invalidations") == 0 && await(archive.loadCheckpoint()).isPresent(), "actual SQLite checkpoint commits after exact durable proof validation");
                    long watermark = store.health().highWatermark(); probe.untilCurrent(service);
                    check(store.health().highWatermark() == watermark, "checkpoint housekeeping does not create a self-triggered ingest event");
                    UUID revoked = original.proofs().stream().filter(p -> p.observerGodId().equals(G)).findFirst().orElseThrow().id();
                    otherProof = original.proofs().stream().filter(p -> p.observerGodId().equals(H)).findFirst().orElseThrow().id();
                    await(watch.revokeProof(revoked)); probe.untilCurrent(service);
                    check(count(root, store, "knowledge_invalidations") == 1 && count(root, store, "source_refs") == 1
                            && count(root, store, "knowledge_receipts") == 2, "actual tombstone withdraws only one receipt without deleting source or evidence");
                    try (var db = db(root, store); var query = db.createStatement(); var rows = query.executeQuery(
                            "SELECT k.god_id,s.revoked,i.state_version FROM knowledge_invalidations i JOIN knowledge_receipts k ON k.id=i.receipt_id JOIN source_refs s ON s.id=k.source_ref")) {
                        check(rows.next() && rows.getString(1).equals(G) && rows.getInt(2) == 0
                                && rows.getLong(3) == watch.committedRevision(), "durable proof revision maps to A receipt while shared source remains live");
                    }
                    checkpointRevision = await(archive.loadCheckpoint()).orElseThrow().watchRevision();
                    service.close();
                } finally { await(store.closeAsync()); }
            }
        }
        try (var raw = new AsyncActionLedger(root.resolve("mythictrpg-action-ledger-v1"), WORLD, rawLimits, 64)) {
            await(raw.ready());
            try (var watch = new AsyncGodWatch(root.resolve("mythictrpg-god-watch-v1"), WORLD, UUID.randomUUID(), raw, watchLimits)) {
                await(watch.ready()); var store = open(root, raw.status().committedSequence());
                try {
                    var capture = new WatchRecordingCapture(store); var archive = capture.reconciliationArchive();
                    check(await(archive.loadCheckpoint()).orElseThrow().watchRevision() == checkpointRevision, "actual checkpoint survives SQLite and Watch restart");
                    var probe = new ReconcileProbe(watch); var service = new WatchKnowledgeReconciler(archive, probe); probe.untilCurrent(service);
                    check(count(root, store, "knowledge_invalidations") == 1 && await(watch.reconcileExact(
                            new Audience(WORLD, new Key(H, PLAYER), Set.of(PLAYER), new Ref("test:after_restart", 1)), Set.of(otherProof)))
                            .proofs().getFirst().state() == AsyncGodWatch.ProofState.CURRENT, "clean restart pause does not revoke other God historical proof");
                    await(watch.disclose(new Disclosure(new Ref(RULE.id(), 2), Set.of(OTHER)))); probe.untilCurrent(service);
                    check(count(root, store, "knowledge_invalidations") == 2, "post-restart disclosure withdrawal reconciles the remaining exact receipt");
                    check(count(root, store, "messages") == 0 && count(root, store, "source_refs") == 1
                            && raw.status().committedSequence() == 1, "repair neither imports old proofs nor replays game occurrences");
                    service.close();
                } finally { await(store.closeAsync()); }
            }
        }
    }
    private static final class ReconcileProbe implements WatchKnowledgeReconciler.Probe {
        final AsyncGodWatch watch; final BlockingQueue<Runnable> game = new LinkedBlockingQueue<>();
        ReconcileProbe(AsyncGodWatch watch) { this.watch = watch; }
        public boolean current() { return watch.status().state().equals("READY"); }
        public long durableRevision() { return watch.committedRevision(); }
        public CompletableFuture<AsyncGodWatch.ReconciliationSnapshot> reconcile(Audience audience, Set<UUID> ids) { return watch.reconcileExact(audience, ids); }
        public void dispatch(Runnable task) { game.add(task); }
        void untilCurrent(WatchKnowledgeReconciler service) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            do {
                service.pump(); var task = game.poll(10, TimeUnit.MILLISECONDS); if (task != null) task.run();
                if (!service.status().busy() && service.status().state().equals("CURRENT")) return;
            } while (System.nanoTime() < deadline);
            throw new AssertionError("reconciliation failed: " + service.status());
        }
    }
    private static Committed commit(AsyncGodWatch watch, ActionRecord.Draft draft, boolean occluded, Set<String> blocked) throws Exception {
        var capture = watch.capture(draft, scene(draft, occluded, blocked));
        return new Committed(await(capture.raw().durable()), await(capture.observed()));
    }
    private static ActionRecord.Draft draft(long order) {
        return new ActionRecord.Draft(UUID.randomUUID(), BOOT, order, "mythictrpg:crop_remove_commit", 1, PLAYER,
                new ActionRecord.Subject("BLOCK", "minecraft:wheat", null), 1234, 50, 6000, "minecraft:overworld",
                new ActionRecord.Position(0, 64, 0), ActionRecord.Type.MATURE_CROP_REMOVED, "COMPLETED",
                Map.of("secret", "PRIVATE_PAYLOAD"), "mythictrpg:admin_only_unprojected");
    }
    private static Approval approval(String god, boolean time) {
        Set<Field> fields = EnumSet.of(Field.ACTOR, Field.ACTION, Field.SUBJECT_TYPE, Field.OUTCOME);
        if (time) fields.add(Field.TIME);
        return new Approval(WORLD, new Key(god, PLAYER), new Ref("test:eligibility/" + god, 1), new Ref("test:approved", 1),
                new Policy(new Ref("test:policy/" + god, 1), god, "test:power", "test:domain", List.of(new Area("minecraft:overworld", -1, 0, -1, 1, 100, 1)), fields));
    }
    private static Scene scene(ActionRecord.Draft draft, boolean occluded, Set<String> blocked) {
        Map<Field, Visibility> fields = new EnumMap<>(Field.class);
        for (Field field : Field.values()) fields.put(field, new Visibility(true, Map.of(PLAYER, RULE)));
        return new Scene(draft.occurrenceId(), BOOT, draft.captureOrder(), new Ref("test:scene", 1), Set.of("test:power"), Set.of("test:domain"), occluded, false, fields, blocked);
    }
    private static WorldRecordingService open(Path root, long cursor) throws Exception {
        var store = await(WorldRecordingService.open(root, WORLD, new RecordingSettings(RecordingSettings.Mode.SHADOW, 256_000_000, 2_000_000, .9, .95),
                new WorldRecordingService.CutoverBoundary("watch-capture-fixture", Map.of(WatchRecordingCapture.PRODUCER, cursor))));
        check(store.health().state() == WorldRecordingService.State.READY, "actual SQLite ready: " + store.health().reasonCode()); return store;
    }
    private static Connection db(Path root, WorldRecordingService store) throws Exception {
        var properties = new Properties(); properties.setProperty("open_mode", "1");
        return DriverManager.getConnection("jdbc:sqlite:" + root.resolve("mythictrpg-recording-v2").resolve(store.datasetId().orElseThrow().toString()).resolve("recording.sqlite"), properties);
    }
    private static long count(Path root, WorldRecordingService store, String table) throws Exception {
        if (!Set.of("source_refs", "knowledge_receipts", "knowledge_invalidations", "messages", "message_parts").contains(table)) throw new IllegalArgumentException();
        try (var db = db(root, store); var q = db.createStatement(); var row = q.executeQuery("SELECT count(*) FROM " + table)) { row.next(); return row.getLong(1); }
    }
    private static void productionHook() throws Exception {
        boolean[] hooked = new boolean[2];
        try (var in = WatchRecordingCaptureTest.class.getResourceAsStream("/com/sande/mythictrpg/gameplay/watch/GodWatchRuntime.class")) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) { @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        if (owner.endsWith("RecordingRuntime") && method.equals("watchCapture")) hooked[0] = true;
                        if (owner.endsWith("WatchRecordingCapture") && method.equals("capture")) hooked[1] = true;
                    }};
                }
            }, ClassReader.SKIP_DEBUG);
        }
        check(hooked[0] && hooked[1], "actual gameplay post-commit runtime invokes recording adapter");
    }
    private static <T> T await(CompletionStage<T> future) throws Exception { return future.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
