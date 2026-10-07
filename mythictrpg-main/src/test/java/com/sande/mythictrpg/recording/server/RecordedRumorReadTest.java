package com.sande.mythictrpg.recording.server;

import com.google.gson.Gson;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import com.sande.mythictrpg.rumor.*;
import net.minecraft.nbt.CompoundTag;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual courier/rumor/reputation state and capture into SQLite. Test-only reflection crosses package seams,
 * never adds a public authority issuer. No Minecraft world, model, network or operating-world writes. */
public final class RecordedRumorReadTest {
    private static final Gson JSON = new Gson();
    private static final UUID PLAYER = UUID.randomUUID(), OTHER = UUID.randomUUID(), OUTSIDE = UUID.randomUUID();
    private static final UUID BIRD = UUID.randomUUID(), OTHER_BIRD = UUID.randomUUID();
    private static final String G = "test:athena", H = "test:hermes", RULE = "test:courier";
    private static int checks;
    private static final RecordingSettings SETTINGS = new RecordingSettings(RecordingSettings.Mode.SHADOW, 128_000_000, 2_000_000, .9, .95);
    private static final Method LEDGER_EVIDENCE = method(RumorLedger.class, "evidence", UUID.class);
    private static final Method HEARD_ONE = method(CourierEngine.class, "heardOne", UUID.class, String.class, UUID.class, Set.class);
    private static final Method ASSESS = method(ReputationLedger.class, "apply", ReputationLedger.Decision.class);
    private static final Method ASSESSMENT = method(NativeRumorReadAccess.class, "assessment", UUID.class, RumorLedger.Evidence.class,
            RumorLedger.HeardRumor.class, String.class, ReputationSettings.Rule.class, ReputationLedger.Entry.class);
    private static final Class<?> SCOPE = nested("Scope"), PROBE = nested("Probe");
    private static final Method READ = method(NativeRumorReadAccess.class, "read", SCOPE, UUID.class, PROBE);

    private static final class Rig implements AutoCloseable {
        final Path root; final Thread owner = Thread.currentThread(); final BlockingQueue<Runnable> game = new LinkedBlockingQueue<>();
        final AtomicBoolean live = new AtomicBoolean(true); final Set<String> availableRecipients = new HashSet<>(Set.of(G));
        final Map<String, ReputationSettings.Rule> policies = new HashMap<>();
        RumorSavedData data = new RumorSavedData(); RumorLedger ledger; CourierEngine engine; ReputationLedger reputation;
        WorldRecordingService store; RumorRecordingCapture capture; boolean reputationReady; long tick;
        Rig(Path root) throws Exception {
            this.root = root; Files.createDirectories(root); data.recordingEnabled(true); resetEngine();
            check(engine.bind(PLAYER, BIRD) && engine.bind(OTHER, OTHER_BIRD), "explicit actual courier bindings created");
            policies.put(G, policy(G, 0)); policies.put(H, policy(H, 0)); reputation = new ReputationLedger(data.worldId());
            checkpoint(); openStore(); check(await(capture.capture(data.recordingSnapshot().orElseThrow())).complete(), "confirmed baseline registers original cutoff before new roots");
        }
        void resetEngine() throws Exception {
            var field = RumorSavedData.class.getDeclaredField("ledger"); field.setAccessible(true); ledger = (RumorLedger) field.get(data);
            var rule = new CourierSettings.Rule(RULE, "test:event", CourierSettings.Source.GAME_EVENT, "minecraft:overworld", 8, 1, 72000,
                    Map.of(G, CourierSettings.Reception.CAUTIOUS, H, CourierSettings.Reception.INTERESTED), CourierSettings.Publication.CANDIDATE, "", "");
            var settings = new CourierSettings(1, true, false, false, 32, Set.of("minecraft:parrot"), List.of(rule));
            engine = new CourierEngine(ledger, settings, new CourierEngine.Probe() {
                public boolean available(UUID courier, UUID subject) { return true; }
                public boolean witnessed(UUID courier, CourierEngine.Event event, CourierSettings.Rule rule) { return true; }
                public boolean receiverAvailable(String god) { return availableRecipients.contains(god); }
            }, () -> tick);
        }
        void checkpoint() throws Exception {
            // Existing SavedData restore path confirms precisely this serialized checkpoint.
            // Actual atomic game-save I/O remains covered by its separate GameTest.
            data = RumorSavedData.load(data.save(new CompoundTag(), null), null); data.recordingEnabled(true); resetEngine();
            check(data.recordingSnapshot().isPresent(), "actual SavedData roundtrip confirms immutable source metadata");
        }
        void openStore() throws Exception {
            store = await(WorldRecordingService.open(root, data.worldId(), SETTINGS,
                    new WorldRecordingService.CutoverBoundary("rumor-read-fixture", Map.of())));
            check(store.health().state() == WorldRecordingService.State.READY, "real recording SQLite READY"); capture = new RumorRecordingCapture(store);
        }
        UUID event(UUID subject, Set<UUID> audience, String claim) throws Exception {
            tick += 2;
            var inputs = engine.observe(new CourierEngine.Event(UUID.randomUUID(), 1, subject, Set.of(subject), audience,
                    "test:event", CourierSettings.Source.GAME_EVENT, "minecraft:overworld", 7, 64, 9,
                    "PRIVATE_SOURCE_EXCERPT", 1234 + tick, tick));
            check(inputs.size() == 1, "actual observed source with original proof accepted"); var input = inputs.getFirst();
            check(engine.publish(new CourierEngine.Candidate(input, input.excerpt(), claim, "test-epithet")), "game applies bounded grounded claim candidate");
            check(engine.deliver() >= 1, "actual available recipient receives game receipt"); checkpoint();
            check(await(capture.capture(data.recordingSnapshot().orElseThrow())).complete(), "production capture mirrors confirmed actual receipts"); return input.rootId();
        }
        void archive() throws Exception { checkpoint(); check(await(capture.capture(data.recordingSnapshot().orElseThrow())).complete(), "latest confirmed source checkpoint reconciled"); }
        RumorLedger.Evidence evidence(UUID root) { return invoke(LEDGER_EVIDENCE, ledger, root); }
        Optional<RumorLedger.HeardRumor> heard(UUID subject, String god, UUID root, Set<UUID> audience) { return invoke(HEARD_ONE, engine, subject, god, root, audience); }
        NativeRumorReadAccess.Assessment assessment(RumorLedger.Evidence evidence, RumorLedger.HeardRumor heard, String god) {
            if (!reputationReady) return NativeRumorReadAccess.Assessment.unknown();
            return invoke(ASSESSMENT, null, data.worldId(), evidence, heard, god, policies.get(god), reputation.find(evidence.subject(), god, evidence.proof().sourceId()));
        }
        void assess(UUID root, String god, ReputationLedger.Outcome outcome) {
            var e = evidence(root); var heard = heard(e.subject(), god, root, e.disclosureAudience()).orElseThrow();
            var old = reputation.find(e.subject(), god, e.proof().sourceId()); var policy = policies.get(god);
            var decision = new ReputationLedger.Decision(UUID.randomUUID(), data.worldId(), e.subject(), god, e.proof().sourceId(), root,
                    heard.revision(), policy.id(), policy.fingerprint(), old == null ? 0 : old.version(), outcome, ReputationLedger.DirectImpact.UNKNOWN,
                    new ReputationLedger.Approval(UUID.randomUUID(), ReputationLedger.ApprovalKind.ADMIN_REVIEW, "test:review"));
            check(invoke(ASSESS, reputation, decision) == ReputationLedger.Result.APPLIED, "actual game reputation ledger commits independent judgment version");
        }
        <T> T pump(CompletableFuture<T> future) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!future.isDone() && System.nanoTime() < end) { var task = game.poll(10, TimeUnit.MILLISECONDS); if (task != null) task.run(); }
            return await(future);
        }
        @Override public void close() throws Exception { await(store.closeAsync()); }
    }

    /** Uses the production package-private facade over this fixture's real ledger and engine. */
    private static final class Gate {
        final Rig rig; final String god; final Set<UUID> players; final Set<String> gods; final boolean publicRoom;
        final Object scope, probe; boolean unavailable, throwRead; int calls, failAfter = Integer.MAX_VALUE;
        Gate(Rig rig, String god, Set<UUID> players, Set<String> gods, boolean publicRoom) throws Exception {
            this.rig = rig; this.god = god; this.players = Set.copyOf(players); this.gods = Set.copyOf(gods); this.publicRoom = publicRoom;
            var constructor = SCOPE.getDeclaredConstructor(UUID.class, String.class, Set.class, Set.class, boolean.class); constructor.setAccessible(true);
            scope = constructor.newInstance(rig.data.worldId(), god, players, gods, publicRoom);
            probe = Proxy.newProxyInstance(PROBE.getClassLoader(), new Class<?>[]{PROBE}, (proxy, m, args) -> switch (m.getName()) {
                case "current" -> rig.live.get() && !unavailable;
                case "lineage" -> rig.data.recordingSnapshot().map(v -> v.metadata().lineage());
                case "evidence" -> rig.evidence((UUID) args[0]);
                case "heard" -> rig.heard((UUID) args[0], (String) args[1], (UUID) args[2], cast(args[3]));
                case "assessment" -> rig.assessment((RumorLedger.Evidence) args[0], (RumorLedger.HeardRumor) args[1], (String) args[2]);
                case "toString" -> "BuildFixtureRumorProbe";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new IllegalStateException("FIXTURE_PROBE_METHOD");
            });
        }
        Optional<NativeRumorReadAccess.Snapshot> read(UUID root) {
            if (throwRead) throw new IllegalStateException("FIXTURE_RUMOR_LOOKUP_FAILURE");
            if (++calls > failAfter) return Optional.empty();
            return invoke(READ, null, scope, root, probe);
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args.length == 0 ? "build/recorded-rumor-read-test" : args[0]).toAbsolutePath().normalize();
        if (!base.toString().replace('\\', '/').contains("/build/")) throw new IllegalArgumentException("BUILD_ONLY");
        Path root = Files.createTempDirectory(Files.createDirectories(base), "rumor-read-");
        exactAndAudience(root.resolve("audience")); lateReception(root.resolve("late")); assessmentAndRevocation(root.resolve("current"));
        paginationAndFailure(root.resolve("pages")); restart(root.resolve("restart"));
        System.out.println("RecordedRumorReadTest: " + checks + " checks passed; actual game ledgers/SQLite fixtures=" + root);
    }
    private static void exactAndAudience(Path path) throws Exception {
        try (var r = new Rig(path)) {
            UUID root = r.event(OTHER, Set.of(PLAYER, OTHER), "푸른 성소에서 공물을 훔쳤다는 소문");
            var gate = gate(r, G, Set.of(PLAYER, OTHER)); var s = session(r, gate, "RUMOR_TEST"); var page = read(r, s, "", Optional.empty(), 8, 16384);
            check(page.status() == MemoryReadSession.Status.PARTIAL && page.entries().size() == 1 && s.current(page), "actual received rumor becomes an issued current typed allegation");
            var e = page.entries().getFirst();
            check(e.source().sourceId().equals(root.toString()) && e.recipientGodId().equals(G) && e.subjectPlayerId().equals(OTHER), "root subject is preserved, never replaced with requester/observer God");
            check(e.claim().equals("푸른 성소에서 공물을 훔쳤다는 소문") && e.reception().equals("CAUTIOUS")
                    && e.assessment().availability() == NativeRumorReadAccess.AssessmentAvailability.UNKNOWN, "received claim and reaction are not invented belief or unassessed authority");
            check(!e.toString().contains("PRIVATE_SOURCE_EXCERPT") && !e.toString().contains("minecraft:overworld"), "source witness excerpt and coordinates are not copied into result");
            check(count(r, "messages") == 0 && count(r, "source_refs") == 1 && count(r, "knowledge_receipts") == 1, "rumor has native source/actual recipient only, no fabricated chat RAW");
            check(read(r, session(r, gate(r, H, Set.of(PLAYER, OTHER)), "RUMOR_TEST"), "", Optional.empty(), 8, 16384).entries().isEmpty(), "pending authored recipient is not actual receipt authority");
            for (var denied : List.of(
                    new Gate(r, G, Set.of(PLAYER, OTHER), Set.of(G), true),
                    new Gate(r, G, Set.of(PLAYER, OTHER), Set.of(G, H), false),
                    gate(r, G, Set.of(PLAYER, OTHER, OUTSIDE)), gate(r, G, Set.of(PLAYER)), gate(r, "test:unknown", Set.of(PLAYER, OTHER))))
                check(read(r, session(r, denied, "RUMOR_TEST"), "", Optional.empty(), 8, 16384).entries().isEmpty(), "public/multiple Gods/untrusted LISTENER/missing subject/unknown recipient denied");
            check(read(r, session(r, gate(r, G, Set.of(PLAYER, OTHER)), "PERSONAL"), "", Optional.empty(), 8, 16384).entries().isEmpty(), "personal memory mode cannot read rumor-test content");
            check(read(r, session(r, gate(r, G, Set.of(PLAYER, OTHER)), "RUMOR_TEST"), "공물", Optional.empty(), 8, 16384).entries().size() == 1, "literal claim search finds permitted allegation");
            check(read(r, session(r, gate(r, G, Set.of(PLAYER, OTHER)), "RUMOR_TEST"), "PRIVATE_SOURCE_EXCERPT", Optional.empty(), 8, 16384).entries().isEmpty(), "hidden witness payload is not a search field");
            int bytes = RumorReadRecords.wireByteSize(e);
            check(bytes > 256 && read(r, session(r, gate(r, G, Set.of(PLAYER, OTHER)), "RUMOR_TEST"), "", Optional.empty(), 8, bytes - 1).entries().isEmpty(), "whole live-decorated rumor cannot exceed exact byte budget");
            int reserve = maximumBytes(e); verifyWireReservation(e, reserve);
            check(read(r, session(r, gate(r, G, Set.of(PLAYER, OTHER)), "RUMOR_TEST"), "", Optional.empty(), 1, reserve - 1).entries().isEmpty(), "pre-decoration admission conservatively reserves every current assessment variant");
            check(read(r, session(r, gate(r, G, Set.of(PLAYER, OTHER)), "RUMOR_TEST"), "", Optional.empty(), 1, reserve).entries().size() == 1, "exact full reservation budget admits the allegation");
            var dates = new MemoryReadSession.Query("", Optional.of(Instant.EPOCH), Optional.empty());
            check(r.pump(s.rumors(dates, Optional.empty(), new MemoryReadSession.Budget(8, 16384))).status() == MemoryReadSession.Status.UNAVAILABLE, "unavailable rumor UTC is not silently invented");
            var actor = new MemoryReadSession.Query("", Optional.empty(), Optional.empty(), new MemoryReadSession.ActorSelection(Optional.of(ActorKind.PLAYER), Set.of(), Set.of()));
            check(r.pump(s.rumors(actor, Optional.empty(), new MemoryReadSession.Budget(8, 16384))).status() == MemoryReadSession.Status.UNAVAILABLE, "speech actor selector is not misapplied as rumor subject selector");
        }
    }
    private static void lateReception(Path path) throws Exception {
        try (var r = new Rig(path)) {
            r.event(PLAYER, Set.of(PLAYER), "검은 수정에 대한 오래된 소문");
            var before = session(r, gate(r, H, Set.of(PLAYER)), "RUMOR_TEST");
            var empty = read(r, before, "", Optional.empty(), 8, 16384); check(empty.entries().isEmpty(), "before actual H reception no rumor is exposed");
            r.availableRecipients.add(H); check(r.engine.deliver() == 1, "late H gameplay delivery happens once"); r.archive();
            check(read(r, before, "", Optional.empty(), 8, 16384).entries().isEmpty()
                    && read(r, before, "", empty.next(), 8, 16384).entries().isEmpty(), "old archive W excludes late receipt for first-page and continuation reads");
            var current = read(r, session(r, gate(r, H, Set.of(PLAYER)), "RUMOR_TEST"), "", Optional.empty(), 8, 16384);
            check(current.entries().size() == 1 && current.entries().getFirst().reception().equals("INTERESTED"), "new session uses H's actual distinct current reception");
            check(count(r, "source_refs") == 1 && count(r, "knowledge_receipts") == 2, "late independent receipt appends without source duplication");
        }
    }
    private static void assessmentAndRevocation(Path path) throws Exception {
        try (var r = new Rig(path)) {
            UUID root = r.event(PLAYER, Set.of(PLAYER), "붉은 제단에 관한 주장");
            var s = session(r, gate(r, G, Set.of(PLAYER)), "RUMOR_TEST"); var unknown = read(r, s, "", Optional.empty(), 8, 16384);
            r.reputationReady = true; check(!s.current(unknown), "available genuine UNASSESSED differs from unknown independent assessment store");
            var unassessed = read(r, s, "", Optional.empty(), 8, 16384);
            check(unassessed.entries().getFirst().assessment().availability() == NativeRumorReadAccess.AssessmentAvailability.UNASSESSED, "no actual judgment is explicitly UNASSESSED only with current policy");
            r.assess(root, G, ReputationLedger.Outcome.DOUBTFUL); check(!s.current(unassessed), "new actual judgment invalidates old page before use");
            var judged = read(r, s, "", Optional.empty(), 8, 16384);
            check(judged.entries().getFirst().assessment().outcome().orElseThrow() == ReputationLedger.Outcome.DOUBTFUL
                    && judged.entries().getFirst().assessment().version() == 1, "returned judgment is current game version rather than persisted archive guess");
            r.assess(root, G, ReputationLedger.Outcome.RECOVERED); check(!s.current(judged), "terminal recovery changes the issued game snapshot");
            var recovered = read(r, s, "", Optional.empty(), 8, 16384);
            check(recovered.entries().getFirst().assessment().outcome().orElseThrow() == ReputationLedger.Outcome.RECOVERED, "recovery is not rewritten into rumor deletion or true fact");
            check(r.engine.confirmedDeath(BIRD), "actual courier death confirmed");
            check(s.current(recovered), "courier death cannot erase already received rumor knowledge");
            r.policies.put(G, policy(G, 1)); check(!s.current(recovered), "changed authored assessment policy invalidates old assessment snapshot");
            var changed = read(r, s, "", Optional.empty(), 8, 16384);
            check(changed.entries().getFirst().assessment().availability() == NativeRumorReadAccess.AssessmentAvailability.UNKNOWN, "mismatched assessment policy remains unknown, not zero or unassessed");
            check(r.ledger.revoke(root), "actual game root revoked");
            check(!s.current(changed) && count(r, "invalidations") == 0, "live revocation invalidates issued rumor before archive reconciliation");
            check(read(r, session(r, gate(r, G, Set.of(PLAYER)), "RUMOR_TEST"), "", Optional.empty(), 8, 16384).entries().isEmpty(), "current root refusal blocks fresh archive candidates");
            r.archive(); check(count(r, "invalidations") == 1 && count(r, "knowledge_receipts") == 1, "durable tombstone preserves historical receipt without new knowledge");
        }
    }
    private static void paginationAndFailure(Path path) throws Exception {
        try (var r = new Rig(path)) {
            for (int i = 0; i < 3; i++) r.event(PLAYER, Set.of(PLAYER), "서로 다른 주장 " + i);
            var s = session(r, gate(r, G, Set.of(PLAYER)), "RUMOR_TEST"); var page = read(r, s, "", Optional.empty(), 1, 16384); var first = page;
            var ids = new HashSet<String>();
            for (int i = 0; i < 3; i++) {
                check(page.entries().size() == 1 && s.current(page) && ids.add(page.entries().getFirst().source().sourceId()), "bounded pages return distinct exact native roots");
                if (i < 2) page = read(r, s, "", page.next(), 1, 16384);
            }
            var other = session(r, gate(r, G, Set.of(PLAYER)), "RUMOR_TEST");
            check(read(r, other, "", first.next(), 1, 16384).status() == MemoryReadSession.Status.STALE, "foreign rumor cursor rejected");
            check(read(r, s, "주장", first.next(), 1, 16384).status() == MemoryReadSession.Status.STALE, "cursor binds exact literal query");
            check(!other.current(first) && !s.current(new RumorReadRecords.Page(first.status(), first.entries(), first.next())), "foreign or reconstructed page has no issued authority");
            var missing = gate(r, G, Set.of(PLAYER)); missing.unavailable = true;
            check(read(r, session(r, missing, "RUMOR_TEST"), "", Optional.empty(), 8, 16384).entries().isEmpty(), "no game proof is not an archive fallback");
            var throwing = gate(r, G, Set.of(PLAYER)); throwing.throwRead = true;
            check(read(r, session(r, throwing, "RUMOR_TEST"), "", Optional.empty(), 8, 16384).entries().isEmpty(), "game lookup exception cannot expose saved claims");
            var changed = gate(r, G, Set.of(PLAYER)); changed.failAfter = 1;
            check(read(r, session(r, changed, "RUMOR_TEST"), "", Optional.empty(), 1, 16384).entries().isEmpty(), "proof disappearing after first lookup blocks final batch publication");
        }
    }
    private static void restart(Path path) throws Exception {
        try (var r = new Rig(path)) {
            UUID root = r.event(PLAYER, Set.of(PLAYER), "재시작해도 남는 소문"); UUID dataset = r.store.datasetId().orElseThrow();
            var page = read(r, session(r, gate(r, G, Set.of(PLAYER)), "RUMOR_TEST"), "", Optional.empty(), 8, 16384);
            check(page.entries().size() == 1, "restart fixture initially readable"); await(r.store.closeAsync());
            r.checkpoint(); r.reputation = ReputationLedger.restore(r.reputation.snapshot()); r.openStore();
            check(await(r.capture.capture(r.data.recordingSnapshot().orElseThrow())).complete(), "confirmed checkpoint replay after SQLite reopen is idempotent");
            check(r.store.datasetId().orElseThrow().equals(dataset) && count(r, "source_refs") == 1 && count(r, "knowledge_receipts") == 1,
                    "restart preserves dataset and original source/receipt without backfill duplicates");
            check(read(r, session(r, gate(r, G, Set.of(PLAYER)), "RUMOR_TEST"), "", Optional.empty(), 8, 16384).entries().getFirst().source().sourceId().equals(root.toString()), "reopened actual state revalidates original exact root");
            check(read(r, session(r, gate(r, H, Set.of(PLAYER)), "RUMOR_TEST"), "", Optional.empty(), 8, 16384).entries().isEmpty(), "restart never upgrades pending recipient into received knowledge");
        }
    }

    private static Gate gate(Rig r, String god, Set<UUID> players) throws Exception { return new Gate(r, god, players, Set.of(god), false); }
    private static RecordedMemoryAccess.Session session(Rig r, Gate gate, String mode) {
        var audience = new HashSet<ActorRef>(); gate.players.forEach(p -> audience.add(new ActorRef(ActorKind.PLAYER, p.toString())));
        gate.gods.forEach(g -> audience.add(new ActorRef(ActorKind.GOD, g)));
        var scope = new RecordedRoomSearch.Scope(r.store.datasetId().orElseThrow(), gate.god, audience, gate.publicRoom, "STANDARD", mode);
        return new RecordedMemoryAccess.Session(r.store, scope, () -> Thread.currentThread() == r.owner, r.game::add, r.live::get,
                refs -> CompletableFuture.completedFuture(refs.isEmpty()), List::isEmpty, () -> false, gate::read);
    }
    private static RumorReadRecords.Page read(Rig r, MemoryReadSession s, String text, Optional<RumorReadRecords.Cursor> cursor, int rows, int bytes) throws Exception {
        return r.pump(s.rumors(new MemoryReadSession.Query(text, Optional.empty(), Optional.empty()), cursor, new MemoryReadSession.Budget(rows, bytes)));
    }
    private static ReputationSettings.Rule policy(String god, int modifier) { return new ReputationSettings.Rule("test:policy/" + god.replace(':', '/'), RULE, god, modifier, -1000, 1000); }
    private static int maximumBytes(RumorReadRecords.Entry e) {
        return RumorReadRecords.maximumWireByteSize(e.source(), e.knowledgeReceiptId(), e.lineageId(), e.recipientGodId(),
                e.subjectPlayerId(), e.claim(), e.epithet(), e.disclosureAudience());
    }
    private static void verifyWireReservation(RumorReadRecords.Entry e, int reserved) {
        var assessments = new ArrayList<NativeRumorReadAccess.Assessment>();
        assessments.add(NativeRumorReadAccess.Assessment.unknown()); assessments.add(NativeRumorReadAccess.Assessment.unassessed());
        for (var outcome : ReputationLedger.Outcome.values()) assessments.add(new NativeRumorReadAccess.Assessment(
                NativeRumorReadAccess.AssessmentAvailability.ASSESSED, Optional.of(outcome), Long.MAX_VALUE));
        for (var reception : List.of("CAUTIOUS", "INTERESTED")) for (var assessment : assessments) {
            var variant = new RumorReadRecords.Entry(e.source(), e.knowledgeReceiptId(), e.lineageId(), e.recipientGodId(),
                    e.subjectPlayerId(), e.claim(), e.epithet(), e.disclosureAudience(), reception, assessment);
            check(RumorReadRecords.wireByteSize(variant) <= reserved, "whole-card reservation covers every valid reception/assessment/outcome/max-version wire variant");
        }
    }
    private static long count(Rig r, String table) throws Exception {
        if (!Set.of("messages", "source_refs", "knowledge_receipts", "invalidations").contains(table)) throw new IllegalArgumentException("FIXTURE_TABLE");
        Path db = r.root.resolve("mythictrpg-recording-v2").resolve(r.store.datasetId().orElseThrow().toString()).resolve("recording.sqlite");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db.toUri() + "?mode=ro"); var q = c.createStatement()) {
            q.execute("PRAGMA query_only=ON"); try (var row = q.executeQuery("SELECT count(*) FROM " + table)) { row.next(); return row.getLong(1); }
        }
    }
    private static Class<?> nested(String name) { try { return Class.forName(NativeRumorReadAccess.class.getName() + "$" + name); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    private static Method method(Class<?> type, String name, Class<?>... parameters) { try { var m = type.getDeclaredMethod(name, parameters); m.setAccessible(true); return m; } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @SuppressWarnings("unchecked") private static <T> T invoke(Method method, Object receiver, Object... args) {
        try { return (T) method.invoke(receiver, args); } catch (InvocationTargetException e) { throw new IllegalStateException("Fixture seam rejected: " + method.getName(), e.getCause()); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Fixture seam unavailable: " + method.getName(), e); }
    }
    @SuppressWarnings("unchecked") private static <T> T cast(Object value) { return (T) value; }
    private static <T> T await(CompletionStage<T> future) throws Exception { return future.toCompletableFuture().get(15, TimeUnit.SECONDS); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
