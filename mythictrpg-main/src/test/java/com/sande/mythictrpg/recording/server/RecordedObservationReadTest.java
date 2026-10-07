package com.sande.mythictrpg.recording.server;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.experiencecontract.*;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.watch.*;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real action/watch journals and SQLite, with an owning-thread exact-proof gate. No Minecraft/model/network. */
public final class RecordedObservationReadTest {
    private static int checks;
    private static final Gson JSON = new Gson();
    private static final UUID WORLD = UUID.randomUUID(), PLAYER = UUID.randomUUID(), OTHER = UUID.randomUUID();
    private static final String G = "test:a", H = "test:b";
    private static final Ref RULE = new Ref("test:disclosure", 1);
    private static final RecordingSettings SETTINGS = new RecordingSettings(RecordingSettings.Mode.SHADOW, 128_000_000, 2_000_000, .9, .95);
    private record Committed(ActionRecord raw, List<Proof> proofs) { }
    private static final class Rig implements AutoCloseable, WatchRecordingCapture.Probe {
        final Path root; final UUID boot = UUID.randomUUID(); final Thread owner = Thread.currentThread();
        final BlockingQueue<Runnable> game = new LinkedBlockingQueue<>(); final AtomicBoolean live = new AtomicBoolean(true);
        final AsyncActionLedger raw; final AsyncGodWatch watch; final boolean incremental;
        WorldRecordingService store; WatchRecordingCapture capture; ProducerCapability producer;
        Rig(Path root, boolean incremental) throws Exception {
            this.root = root; this.incremental = incremental; Files.createDirectories(root);
            raw = new AsyncActionLedger(root.resolve("mythictrpg-action-ledger-v1"), WORLD, new ActionLedgerStore.Limits(8_000_000, 128_000, 5000), 64); await(raw.ready());
            watch = new AsyncGodWatch(root.resolve("mythictrpg-god-watch-v1"), WORLD, boot, raw, new AsyncGodWatch.Limits(8_000_000, 5000, 64)); await(watch.ready());
            await(watch.disclose(new Disclosure(RULE, Set.of(PLAYER))));
            await(watch.start(UUID.randomUUID(), approval(G, false))); await(watch.start(UUID.randomUUID(), approval(H, true))); openStore();
        }
        void openStore() throws Exception {
            store = await(WorldRecordingService.open(root, WORLD, SETTINGS, new WorldRecordingService.CutoverBoundary("observation-read-fixture",
                    incremental ? Map.of() : Map.of(WatchRecordingCapture.PRODUCER, raw.status().committedSequence()))));
            check(store.health().state() == WorldRecordingService.State.READY, "real recording SQLite READY");
            capture = new WatchRecordingCapture(store);
            // Reuse this actual game adapter's registered capability; never register the same owner twice.
            var field = WatchRecordingCapture.class.getDeclaredField("producer"); field.setAccessible(true);
            producer = (ProducerCapability) field.get(capture);
        }
        Committed event(boolean archive) throws Exception {
            return event(archive, "minecraft:wheat");
        }
        Committed event(boolean archive, String block) throws Exception {
            long order = raw.status().committedSequence() + 1;
            var draft = new ActionRecord.Draft(UUID.randomUUID(), boot, order, "mythictrpg:crop_remove_commit", 1, PLAYER,
                    new ActionRecord.Subject("BLOCK", block, null), 1234, 50, 6000, "minecraft:overworld", new ActionRecord.Position(0, 64, 0),
                    ActionRecord.Type.MATURE_CROP_REMOVED, "COMPLETED", Map.of("secret", "PRIVATE_PAYLOAD"), "mythictrpg:admin_only_unprojected");
            var fields = new EnumMap<Field, Visibility>(Field.class);
            for (var field : Field.values()) fields.put(field, new Visibility(true, Map.of(PLAYER, RULE)));
            var scene = new Scene(draft.occurrenceId(), boot, order, new Ref("test:scene", 1), Set.of("test:power"), Set.of("test:domain"), false, false, fields, Set.of());
            var result = watch.capture(draft, scene); var committed = new Committed(await(result.raw().durable()), await(result.observed()));
            check(committed.proofs().size() == 2, "actual committed occurrence has independent A and B watch proofs");
            if (archive) check(pump(capture.capture(committed.raw(), committed.proofs(), this)).complete(), "existing production capture writes allowed projection only");
            return committed;
        }
        @Override public boolean current() { return live.get(); }
        @Override public CompletableFuture<AsyncGodWatch.ReadSnapshot> read(Audience audience, UUID observation) { return watch.readExact(audience, Set.of(observation)); }
        @Override public boolean current(AsyncGodWatch.ReadSnapshot snapshot, Audience audience) { return live.get() && watch.current(snapshot, audience); }
        @Override public void dispatch(Runnable task) { game.add(task); }
        <T> T pump(CompletableFuture<T> future) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!future.isDone() && System.nanoTime() < deadline) { var task = game.poll(10, TimeUnit.MILLISECONDS); if (task != null) task.run(); }
            return await(future);
        }
        @Override public void close() throws Exception { await(store.closeAsync()); watch.close(); raw.close(); }
    }

    /** Models the existing game-owned prepare/current boundary using real exact Watch snapshots. */
    private static final class Gate {
        final Rig rig; final String god; final Set<UUID> players; final UUID room = UUID.randomUUID();
        final Map<RoomEvidenceReference, AsyncGodWatch.ReadSnapshot> prepared = new HashMap<>();
        boolean reject, throwPrepare, throwCurrent, invalidateAfterPrepare; int calls;
        Gate(Rig rig, String god, Set<UUID> players) { this.rig = rig; this.god = god; this.players = Set.copyOf(players); }
        CompletableFuture<Boolean> prepare(List<RoomEvidenceReference> refs) {
            calls++; if (throwPrepare) throw new IllegalStateException("FIXTURE_PREPARE_FAILURE");
            if (reject || !rig.live.get()) return CompletableFuture.completedFuture(false);
            var result = new CompletableFuture<Boolean>(); next(refs, 0, result); return result;
        }
        void next(List<RoomEvidenceReference> refs, int index, CompletableFuture<Boolean> result) {
            if (index == refs.size()) { if (invalidateAfterPrepare) rig.live.set(false); result.complete(true); return; }
            try {
                var ref = refs.get(index); var data = JsonParser.parseString(ref.payload()).getAsJsonObject();
                if (!ref.kind().equals(ExperienceRoomEvidence.KIND) || !data.get("worldId").getAsString().equals(WORLD.toString())
                        || !data.get("godId").getAsString().equals(god)) { result.complete(false); return; }
                UUID subject = UUID.fromString(data.get("subjectId").getAsString()), observation = UUID.fromString(data.get("observationId").getAsString());
                var audience = new Audience(WORLD, new Key(god, subject), players, new Ref(room.toString(), 1));
                rig.watch.readExact(audience, Set.of(observation)).whenComplete((snapshot, failure) -> rig.dispatch(() -> {
                    try {
                        if (failure != null || snapshot == null || !rig.live.get() || !rig.watch.current(snapshot, audience)
                                || !ref.equals(ExperienceRoomEvidence.capture(snapshot).get(observation))) { result.complete(false); return; }
                        prepared.put(ref, snapshot); next(refs, index + 1, result);
                    } catch (RuntimeException unavailable) { result.complete(false); }
                }));
            } catch (RuntimeException invalid) { result.complete(false); }
        }
        boolean current(List<RoomEvidenceReference> refs) {
            if (throwCurrent) throw new IllegalStateException("FIXTURE_CURRENT_FAILURE");
            return rig.live.get() && refs.stream().allMatch(ref -> prepared.containsKey(ref)
                    && rig.watch.current(prepared.get(ref), prepared.get(ref).audience()));
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args.length == 0 ? "build/recorded-observation-read-test" : args[0]).toAbsolutePath().normalize();
        if (!base.toString().replace('\\', '/').contains("/build/")) throw new IllegalArgumentException("BUILD_ONLY");
        Files.createDirectories(base); Path root = Files.createTempDirectory(base, "observations-");
        exactAndPrivate(root.resolve("exact")); liveProofAndWithdrawal(root.resolve("withdrawal"));
        paginationAndFailure(root.resolve("pages")); incrementalWatermark(root.resolve("incremental"));
        boundedLongTail(root.resolve("long-tail")); supersession(root.resolve("supersession")); corruptedProjection(root.resolve("corruption"));
        durableWatchRestart(root.resolve("watch-restart"));
        System.out.println("RecordedObservationReadTest: " + checks + " checks passed; real Watch/SQLite fixtures=" + root);
    }

    private static void exactAndPrivate(Path root) throws Exception {
        try (var r = new Rig(root, false)) {
            var source = r.event(true); var gate = new Gate(r, G, Set.of(PLAYER)); var a = session(r, gate, false, Set.of(G), "PERSONAL");
            var page = read(r, a, "", Optional.empty(), 8, 16384);
            check(page.status() == MemoryReadSession.Status.PARTIAL && page.entries().size() == 1 && a.current(page), "exact typed observation page is issued only after live proof preparation");
            var entry = page.entries().getFirst();
            check(entry.source().sourceId().equals(source.raw().event().occurrenceId().toString()) && entry.observerGodId().equals(G)
                    && entry.subjectPlayerId().equals(PLAYER) && entry.experience().observationId().equals(proof(source, G).id()), "typed source preserves actual actor/observer/event/proof identity");
            check(entry.experience().outcome().equals("BLOCK_REMOVED_NOT_ITEM_ACQUISITION") && entry.experience().gameTime().equals("NOT_DISCLOSED"),
                    "observed block removal is not inventory gain and undisclosed time stays absent");
            var b = session(r, new Gate(r, H, Set.of(PLAYER)), false, Set.of(H), "PERSONAL");
            check(read(r, b, "", Optional.empty(), 8, 16384).entries().getFirst().experience().gameTime().equals("utc=1234;tick=50;dayTime=6000"), "different God's approved field projection remains distinct");
            check(!JSON.toJson(entry).contains("PRIVATE_PAYLOAD") && !JSON.toJson(entry).contains("minecraft:overworld"), "no hidden administrative payload/position is exposed");
            check(count(r, "messages") == 0 && count(r, "message_parts") == 0 && count(r, "source_refs") == 1, "typed observation never fabricates chat RAW or duplicates source by God");
            for (var denied : List.of(session(r, new Gate(r, G, Set.of(PLAYER)), true, Set.of(G), "PERSONAL"),
                    session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G, H), "PERSONAL"),
                    session(r, new Gate(r, G, Set.of(PLAYER, OTHER)), false, Set.of(G), "PERSONAL"),
                    session(r, new Gate(r, G, Set.of(OTHER)), false, Set.of(G), "PERSONAL"),
                    session(r, new Gate(r, "test:unheard", Set.of(PLAYER)), false, Set.of("test:unheard"), "PERSONAL"),
                    session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "RUMOR_TEST"))) {
                check(read(r, denied, "", Optional.empty(), 8, 16384).entries().isEmpty(), "public/multi-God/extra LISTENER/other subject/unheard God/other mode denied");
            }
            check(read(r, session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "PERSONAL"), "minecraft:wheat", Optional.empty(), 8, 16384).entries().size() == 1,
                    "literal allowed typed-field search can select known crop");
            check(read(r, session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "PERSONAL"), "PRIVATE_PAYLOAD", Optional.empty(), 8, 16384).entries().isEmpty(), "search never matches hidden source payload");
            var temporal = new MemoryReadSession.Query("", Optional.of(Instant.EPOCH), Optional.empty());
            check(r.pump(a.observations(temporal, Optional.empty(), new MemoryReadSession.Budget(8, 16384))).status() == MemoryReadSession.Status.UNAVAILABLE,
                    "UTC filter is explicitly unsupported rather than invented from gameTime");
            int bytes = RecordedObservationSearch.wireByteSize(entry);
            check(bytes > 256 && bytes <= 16384, "complete typed event wire budget includes source/proof metadata");
            check(read(r, session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "PERSONAL"), "", Optional.empty(), 8, bytes - 1).entries().isEmpty(), "whole event is not trimmed to fit insufficient bytes");
            check(read(r, session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "PERSONAL"), "", Optional.empty(), 1, bytes).entries().size() == 1, "exact full-card budget admits event");
            UUID dataset = r.store.datasetId().orElseThrow(); await(r.store.closeAsync()); r.openStore();
            var reopened = session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "PERSONAL");
            check(r.store.datasetId().orElseThrow().equals(dataset) && read(r, reopened, "", Optional.empty(), 8, 16384).entries().size() == 1,
                    "SQLite reopen reuses durable Watch proof and retains historical projection without import");
        }
    }

    private static void liveProofAndWithdrawal(Path root) throws Exception {
        try (var r = new Rig(root, false)) {
            var source = r.event(true); var a = session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "PERSONAL");
            var page = read(r, a, "", Optional.empty(), 8, 16384); var entry = page.entries().getFirst();
            var pending = r.watch.revokeProof(proof(source, G).id());
            check(!a.current(page), "pending live Watch revoke invalidates issued page before SQLite reconciliation"); await(pending);
            var denied = read(r, session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "PERSONAL"), "", Optional.empty(), 8, 16384);
            check(denied.entries().isEmpty() && count(r, "knowledge_invalidations") == 0, "stale proof cannot be read while archive tombstone is not yet reconciled");
            var other = session(r, new Gate(r, H, Set.of(PLAYER)), false, Set.of(H), "PERSONAL");
            check(read(r, other, "", Optional.empty(), 8, 16384).entries().size() == 1, "A proof withdrawal does not deny independently observed B source");
            check(await(r.store.invalidateKnowledge(r.producer, new KnowledgeInvalidation(entry.source(), entry.knowledgeReceiptId(), r.watch.committedRevision(), "PROOF_WITHDRAWN"))).status() == RecordingRecords.Status.STORED,
                    "one existing receipt can be durably withdrawn without shared-source revocation");
            check(count(r, "source_refs") == 1 && count(r, "knowledge_receipts") == 2, "withdrawal preserves original source and both historical receipts");
            var remaining = session(r, new Gate(r, H, Set.of(PLAYER)), false, Set.of(H), "PERSONAL"); var bPage = read(r, remaining, "", Optional.empty(), 8, 16384);
            var wide = r.store.invalidate(r.producer, new SourceInvalidation(entry.source(), 1, "SOURCE_WITHDRAWN"));
            check(!remaining.current(bPage), "source-wide withdrawal invalidates B page immediately too"); await(wide);
            check(read(r, session(r, new Gate(r, H, Set.of(PLAYER)), false, Set.of(H), "PERSONAL"), "", Optional.empty(), 8, 16384).entries().isEmpty(), "source-wide tombstone denies fresh typed reads");
        }
    }

    private static void paginationAndFailure(Path root) throws Exception {
        try (var r = new Rig(root, false)) {
            for (int i = 0; i < 3; i++) r.event(true);
            var a = session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "PERSONAL");
            var first = read(r, a, "", Optional.empty(), 1, 16384); var cursor = first.next(); var ids = new HashSet<UUID>(); var page = first;
            for (int i = 0; i < 3; i++) {
                check(page.entries().size() == 1 && a.current(page) && ids.add(page.entries().getFirst().knowledgeReceiptId()), "bounded page returns distinct complete observation");
                if (i < 2) page = read(r, a, "", page.next(), 1, 16384);
            }
            var foreign = session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "PERSONAL");
            check(read(r, foreign, "", cursor, 1, 16384).status() == MemoryReadSession.Status.STALE, "foreign session cannot reuse observation cursor");
            check(read(r, a, "wheat", cursor, 1, 16384).status() == MemoryReadSession.Status.STALE, "cursor binds exact query");
            check(read(r, a, "", Optional.of(ObservationReadRecords.Cursor.unregistered()), 1, 16384).status() == MemoryReadSession.Status.STALE, "unregistered cursor denied");
            check(!foreign.current(first) && !a.current(new ObservationReadRecords.Page(first.status(), first.entries(), first.next())), "only exact issued page carries current authority");
            var reject = new Gate(r, G, Set.of(PLAYER)); reject.reject = true;
            check(read(r, session(r, reject, false, Set.of(G), "PERSONAL"), "", Optional.empty(), 8, 16384).entries().isEmpty(), "unavailable live proof preparation returns no archived event");
            var failure = new Gate(r, G, Set.of(PLAYER)); failure.throwPrepare = true;
            check(read(r, session(r, failure, false, Set.of(G), "PERSONAL"), "", Optional.empty(), 8, 16384).entries().isEmpty(), "prepare exception is isolated without archive fallback");
            var stale = new Gate(r, G, Set.of(PLAYER)); stale.invalidateAfterPrepare = true;
            check(read(r, session(r, stale, false, Set.of(G), "PERSONAL"), "", Optional.empty(), 8, 16384).entries().isEmpty(), "runtime change after proof preparation cannot publish late content"); r.live.set(true);
            var current = new Gate(r, G, Set.of(PLAYER)); var s = session(r, current, false, Set.of(G), "PERSONAL"); var issued = read(r, s, "", Optional.empty(), 8, 16384);
            current.throwCurrent = true; check(!s.current(issued), "current proof-check failure revokes typed page");
        }
    }

    private static void incrementalWatermark(Path root) throws Exception {
        try (var r = new Rig(root, true)) {
            UUID lineage = UUID.randomUUID(), dataset = r.store.datasetId().orElseThrow();
            check(await(r.store.registerSource(r.producer, new SourceRegistration(WORLD, dataset, WatchRecordingCapture.PRODUCER, lineage, 0))).receipt().status() == RecordingRecords.Status.STORED,
                    "synthetic dynamic source captures cutoff before original event");
            for (int i = 0; i < 12; i++) r.event(false); // Original owner cursor is independent of archive ingest sequence.
            var committed = r.event(false);
            await(r.store.registerSource(r.producer, new SourceRegistration(WORLD, dataset, WatchRecordingCapture.PRODUCER, lineage, committed.raw().sequence())));
            var method = WatchRecordingCapture.class.getDeclaredMethod("source", ActionRecord.class); method.setAccessible(true);
            SourceRef source = (SourceRef) method.invoke(r.capture, committed.raw()); // Existing adapter owns canonical native source hashing.
            var a = knowledge(r, committed, G); var b = knowledge(r, committed, H); long sequence = committed.raw().sequence();
            check(await(r.store.appendKnowledge(r.producer, new SourceKnowledgeCapture(source, lineage, sequence, sequence,
                    List.of(new AcquiredKnowledge(a, sequence))))).status() == RecordingRecords.Status.STORED, "first actual proof is durably acquired");
            check(sequence > r.store.health().highWatermark(), "fixture owner cursor is greater than archive watermark");
            check(read(r, session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "PERSONAL"), "", Optional.empty(), 8, 16384).entries().size() == 1,
                    "valid acquisition is not denied by comparing unrelated owner cursor to archive watermark");
            var before = session(r, new Gate(r, H, Set.of(PLAYER)), false, Set.of(H), "PERSONAL");
            var empty = read(r, before, "", Optional.empty(), 8, 16384); check(empty.entries().isEmpty(), "source presence alone does not grant B receipt");
            check(await(r.store.appendKnowledge(r.producer, new SourceKnowledgeCapture(source, lineage, sequence, sequence,
                    List.of(new AcquiredKnowledge(b, sequence))))).status() == RecordingRecords.Status.STORED, "second real proof is archived later without duplicating source");
            check(read(r, before, "", empty.next(), 8, 16384).entries().isEmpty(), "late knowledge work cannot enter fixed W even when owner acquired_cursor is old");
            check(read(r, before, "", Optional.empty(), 8, 16384).entries().isEmpty(), "fresh first-page search in old session still excludes the late receipt by archive W");
            var fresh = session(r, new Gate(r, H, Set.of(PLAYER)), false, Set.of(H), "PERSONAL");
            check(read(r, fresh, "", Optional.empty(), 8, 16384).entries().size() == 1, "fresh session sees receipt-specific committed acquisition");
            check(count(r, "source_refs") == 1 && count(r, "knowledge_receipts") == 2 && count(r, "messages") == 0, "incremental two-God acquisition retains one source and no fabricated speech");
        }
    }

    private static void boundedLongTail(Path root) throws Exception {
        try (var r = new Rig(root, false)) {
            var oldest = r.event(true, "minecraft:carrots");
            for (int i = 0; i < 129; i++) r.event(true);
            var gate = new Gate(r, G, Set.of(PLAYER)); var s = session(r, gate, false, Set.of(G), "PERSONAL");
            Optional<ObservationReadRecords.Cursor> cursor = Optional.empty(); var found = new ArrayList<ObservationReadRecords.Entry>(); int pages = 0;
            do {
                var page = read(r, s, "minecraft:carrots", cursor, 8, 16384); pages++;
                check(page.status() == MemoryReadSession.Status.PARTIAL && s.current(page), "bounded long-tail search retains issued current scope");
                found.addAll(page.entries()); cursor = page.next();
            } while (found.isEmpty() && cursor.isPresent() && pages < 8);
            check(pages >= 2 && found.size() == 1 && found.getFirst().experience().eventId().equals(oldest.raw().event().occurrenceId()),
                    "unmatched recent 128-source window cannot permanently hide older allowed observation");
            check(gate.calls == 1, "only selected allowed-field match reaches live proof preparation");
        }
    }

    private static void supersession(Path root) throws Exception {
        try (var r = new Rig(root, false)) {
            var original = r.event(true); var gate = new Gate(r, G, Set.of(PLAYER)); var s = session(r, gate, false, Set.of(G), "PERSONAL");
            var page = read(r, s, "", Optional.empty(), 8, 16384); var source = page.entries().getFirst().source();
            var newer = new SourceRef(source.worldId(), source.datasetId(), source.kind(), source.owner(), source.sourceId(), source.revision() + 1, RecordingRecords.sha256("synthetic-new-owner-revision"));
            check(await(r.store.captureSource(r.producer, new SourceCapture(newer, original.raw().sequence(), List.of()))).status() == RecordingRecords.Status.STORED,
                    "owner commits newer immutable source revision without copying old receipt");
            check(gate.current(new ArrayList<>(gate.prepared.keySet())), "old Watch proof alone remains current in supersession fixture");
            check(!s.current(page), "new source revision invalidates issued old projection independently of live Watch proof");
            check(read(r, session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "PERSONAL"), "", Optional.empty(), 8, 16384).entries().isEmpty(),
                    "fresh read denies superseded older source even when newer source has no receipt");
        }
    }

    private static void corruptedProjection(Path root) throws Exception {
        try (var r = new Rig(root, false)) {
            r.event(true); var page = read(r, session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "PERSONAL"), "", Optional.empty(), 8, 16384);
            UUID receipt = page.entries().getFirst().knowledgeReceiptId(); String projection, hash;
            try (var db = inspect(r); var q = db.prepareStatement("SELECT projection,receipt_hash FROM knowledge_receipts WHERE id=?")) {
                q.setString(1, receipt.toString()); try (var row = q.executeQuery()) { check(row.next(), "original bounded fixture receipt exists"); projection = row.getString(1); hash = row.getString(2); }
            }
            replaceReceipt(r, receipt, projection, "0".repeat(64));
            check(freshEntries(r).isEmpty(), "receipt hash mismatch cannot be admitted as typed experience");
            var extra = JsonParser.parseString(projection).getAsJsonObject(); extra.addProperty("hidden", "PRIVATE_PAYLOAD");
            String injected = JSON.toJson(extra);
            String matchingHash = RecordingRecords.sha256(JSON.toJson(List.of(receipt, G, "DIRECT_WATCH", injected,
                    List.of(new ActorRef(ActorKind.GOD, G).key(), new ActorRef(ActorKind.PLAYER, PLAYER.toString()).key()).stream().sorted().toList(), 1L)));
            replaceReceipt(r, receipt, injected, matchingHash);
            check(freshEntries(r).isEmpty(), "extra projection fields are rejected even with matching outer receipt hash");
            replaceReceipt(r, receipt, projection + " ", RecordingRecords.sha256(JSON.toJson(List.of(receipt, G, "DIRECT_WATCH", projection + " ",
                    List.of(new ActorRef(ActorKind.GOD, G).key(), new ActorRef(ActorKind.PLAYER, PLAYER.toString()).key()).stream().sorted().toList(), 1L))));
            check(freshEntries(r).isEmpty(), "noncanonical serialized projection is denied rather than permissively coerced");
            replaceReceipt(r, receipt, projection, hash);
            check(freshEntries(r).size() == 1, "restoring exact synthetic row restores approved projection");
        }
    }

    private static List<ObservationReadRecords.Entry> freshEntries(Rig r) throws Exception {
        return read(r, session(r, new Gate(r, G, Set.of(PLAYER)), false, Set.of(G), "PERSONAL"), "", Optional.empty(), 8, 16384).entries();
    }
    private static void durableWatchRestart(Path root) throws Exception {
        UUID dataset;
        try (var r = new Rig(root, false)) {
            var captured = r.event(true); dataset = r.store.datasetId().orElseThrow();
            check(freshEntries(r).size() == 1, "proof is initially readable before clean journal close");
            await(r.watch.revokeProof(proof(captured, G).id()));
        }
        try (var r = new Rig(root, false)) {
            check(r.store.datasetId().orElseThrow().equals(dataset) && r.raw.status().committedSequence() == 1,
                    "clean reopening actual raw/watch/SQLite does not recapture old source");
            check(freshEntries(r).isEmpty() && count(r, "knowledge_invalidations") == 0,
                    "durably revoked A proof remains unreadable after journal restart before archive reconciliation");
            check(read(r, session(r, new Gate(r, H, Set.of(PLAYER)), false, Set.of(H), "PERSONAL"), "", Optional.empty(), 8, 16384).entries().size() == 1,
                    "independent historical B proof remains readable after clean journal restart");
            check(count(r, "source_refs") == 1 && count(r, "knowledge_receipts") == 2 && count(r, "messages") == 0,
                    "restart preserves source/receipts without duplicate ingestion or invented conversation RAW");
        }
    }
    private static Path database(Rig r) { return r.root.resolve("mythictrpg-recording-v2").resolve(r.store.datasetId().orElseThrow().toString()).resolve("recording.sqlite"); }
    private static Connection inspect(Rig r) throws Exception {
        var db = DriverManager.getConnection("jdbc:sqlite:" + database(r).toUri() + "?mode=ro");
        try (var q = db.createStatement()) { q.execute("PRAGMA query_only=ON"); } return db;
    }
    /** Deliberate corruption of this test's newly created build-only SQLite, never an operational database. */
    private static void replaceReceipt(Rig r, UUID id, String projection, String hash) throws Exception {
        if (!database(r).toString().replace('\\', '/').contains("/build/")) throw new IllegalArgumentException("BUILD_ONLY");
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + database(r)); var q = db.prepareStatement("UPDATE knowledge_receipts SET projection=?,receipt_hash=? WHERE id=?")) {
            q.setString(1, projection); q.setString(2, hash); q.setString(3, id.toString()); check(q.executeUpdate() == 1, "corruption/restoration affects exactly one synthetic receipt");
        }
    }

    private static KnowledgeReceipt knowledge(Rig r, Committed committed, String god) throws Exception {
        var proof = proof(committed, god); var audience = new Audience(WORLD, new Key(god, PLAYER), Set.of(PLAYER), new Ref("test:projection", 1));
        var snapshot = await(r.watch.readExact(audience, Set.of(proof.id()))); check(r.watch.current(snapshot, audience), "incremental knowledge derives from actual current Watch proof");
        var event = ExperienceProjection.project(snapshot.view(), PLAYER, ExperienceView.Relationship.UNKNOWN).events().getFirst();
        var projection = JSON.toJson(new TreeMap<>(Map.of("memoryMode", "PERSONAL", "experience", event, "evidence", ExperienceRoomEvidence.capture(snapshot).get(proof.id()))));
        return new KnowledgeReceipt(UUID.randomUUID(), god, "DIRECT_WATCH", projection,
                Set.of(new ActorRef(ActorKind.GOD, god), new ActorRef(ActorKind.PLAYER, PLAYER.toString())), proof.policy().revision());
    }
    private static RecordedMemoryAccess.Session session(Rig r, Gate gate, boolean publicRoom, Set<String> gods, String mode) {
        var audience = new HashSet<ActorRef>(); gate.players.forEach(p -> audience.add(new ActorRef(ActorKind.PLAYER, p.toString())));
        gods.forEach(g -> audience.add(new ActorRef(ActorKind.GOD, g)));
        var scope = new RecordedRoomSearch.Scope(r.store.datasetId().orElseThrow(), gate.god, audience, publicRoom, "STANDARD", mode);
        return new RecordedMemoryAccess.Session(r.store, scope, () -> Thread.currentThread() == r.owner, r::dispatch, r.live::get, gate::prepare, gate::current);
    }
    private static ObservationReadRecords.Page read(Rig r, MemoryReadSession session, String text, Optional<ObservationReadRecords.Cursor> cursor, int rows, int bytes) throws Exception {
        return r.pump(session.observations(new MemoryReadSession.Query(text, Optional.empty(), Optional.empty()), cursor, new MemoryReadSession.Budget(rows, bytes)));
    }
    private static Approval approval(String god, boolean time) {
        Set<Field> fields = EnumSet.of(Field.ACTOR, Field.ACTION, Field.SUBJECT_TYPE, Field.OUTCOME); if (time) fields.add(Field.TIME);
        return new Approval(WORLD, new Key(god, PLAYER), new Ref("test:eligibility/" + god, 1), new Ref("test:approved", 1),
                new Policy(new Ref("test:policy/" + god, 1), god, "test:power", "test:domain", List.of(new Area("minecraft:overworld", -1, 0, -1, 1, 100, 1)), fields));
    }
    private static Proof proof(Committed committed, String god) { return committed.proofs().stream().filter(p -> p.observerGodId().equals(god)).findFirst().orElseThrow(); }
    private static long count(Rig r, String table) throws Exception {
        if (!Set.of("messages", "message_parts", "source_refs", "knowledge_receipts", "knowledge_invalidations").contains(table)) throw new IllegalArgumentException("FIXTURE_TABLE");
        Path path = r.root.resolve("mythictrpg-recording-v2").resolve(r.store.datasetId().orElseThrow().toString()).resolve("recording.sqlite");
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path.toUri() + "?mode=ro"); var q = db.createStatement()) {
            q.execute("PRAGMA query_only=ON"); try (var row = q.executeQuery("SELECT count(*) FROM " + table)) { row.next(); return row.getLong(1); }
        }
    }
    private static <T> T await(CompletionStage<T> future) throws Exception { return future.toCompletableFuture().get(15, TimeUnit.SECONDS); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
