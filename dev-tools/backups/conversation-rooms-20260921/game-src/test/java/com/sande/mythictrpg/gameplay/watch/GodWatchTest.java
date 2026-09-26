package com.sande.mythictrpg.gameplay.watch;

import com.sande.mythictrpg.gameplay.ledger.*;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.security.MessageDigest;

/** Offline fixture only: no Minecraft bootstrap, GameTest, LLM or live world. */
public final class GodWatchTest {
    static int checks;
    static Path root;
    static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();
    static final String GOD_A = "mythictrpg:fortuna", GOD_B = "mythictrpg:amphitrite";
    static final Ref RULE_A = new Ref("test:subject_a", 1), RULE_B = new Ref("test:subject_b", 1);
    static final Ref CONTEXT = new Ref("test:scene", 1);
    static final AsyncGodWatch.Limits LIMITS = new AsyncGodWatch.Limits(4_000_000, 5000, 64);
    static final ActionLedgerStore.Limits RAW_LIMITS = new ActionLedgerStore.Limits(8_000_000, 128_000, 5000);
    static class Fixture implements AutoCloseable {
        final Path dir;
        final UUID world, session;
        final AsyncActionLedger raw;
        final AsyncGodWatch watch;
        long order;
        Fixture(String name) throws Exception { this(Files.createTempDirectory(root, name), UUID.randomUUID(), UUID.randomUUID(), LIMITS, p -> {}); }
        Fixture(Path dir, UUID world, UUID session, AsyncGodWatch.Limits limits, WatchJournal.Faults faults) throws Exception {
            this.dir = dir; this.world = world; this.session = session;
            raw = new AsyncActionLedger(dir.resolve("raw"), world, RAW_LIMITS, 64); get(raw.ready());
            watch = new AsyncGodWatch(dir.resolve("watch"), world, session, raw, limits, faults); get(watch.ready());
        }
        ActionRecord.Draft event(UUID actor) {
            return new ActionRecord.Draft(UUID.randomUUID(), session, ++order, "mythictrpg:crop_remove_commit", 1, actor,
                    new ActionRecord.Subject("BLOCK", "minecraft:wheat", null), 123456, 100, 6000, "minecraft:overworld",
                    new ActionRecord.Position(2, 70, 4), ActionRecord.Type.MATURE_CROP_REMOVED, "COMPLETED",
                    Map.of("secret", "never_copy_payload"), "mythictrpg:admin_only_unprojected");
        }
        Approval approval(String god, UUID player) { return approved(world, god, player); }
        Watch start(String god, UUID player) throws Exception { return get(watch.start(UUID.randomUUID(), approval(god, player))); }
        List<Proof> capture(UUID actor) throws Exception { var d = event(actor); return get(watch.capture(d, scene(d)).observed()); }
        View view(String god, UUID target, UUID... audience) throws Exception { return get(watch.read(audience(world, god, target, audience), 100)).view(); }
        void rules() throws Exception { get(watch.disclose(new Disclosure(RULE_A, Set.of(A)))); get(watch.disclose(new Disclosure(RULE_B, Set.of(A, B)))); }
        public void close() { watch.close(); raw.close(); }
    }
    static Approval approved(UUID world, String god, UUID player) {
        return new Approval(world, new Key(god, player), new Ref("test:eligibility/" + god.substring(god.indexOf(':') + 1), 1), new Ref("test:explicit_start", 1),
                new Policy(new Ref("test:policy/" + god.substring(god.indexOf(':') + 1), 1), god, "test:sight", "test:farming",
                        List.of(new Area("minecraft:overworld", 0, 0, 0, 10, 100, 10)), Set.of(Field.values())));
    }
    static Scene scene(ActionRecord.Draft e) {
        Map<Field, Visibility> fields = new EnumMap<>(Field.class);
        for (Field f : Field.values()) fields.put(f, new Visibility(true, Map.of(e.actorId(), e.actorId().equals(A) ? RULE_A : RULE_B)));
        return new Scene(e.occurrenceId(), e.captureSession(), e.captureOrder(), CONTEXT,
                Set.of("test:sight"), Set.of("test:farming"), false, false, fields);
    }
    static Audience audience(UUID world, String god, UUID target, UUID... listeners) {
        return new Audience(world, new Key(god, target), Set.of(listeners), new Ref("test:session", 1));
    }
    static <T> T get(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    interface Checked { void run() throws Exception; }
    static void rejects(Checked c, String label) throws Exception {
        try { c.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("Expected rejection: " + label);
    }
    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory(Files.createDirectories(Path.of(args[0])), "watch-stage03-");
        boundaries(); scopeAndDisclosure(); revocations(); restart(); reconnect(); pendingFence(); queueAndFailure(); storage(); byteQuota(); rawFailure(); contract();
        System.out.println("GodWatchTest: PASS (" + checks + " checks); artifacts=" + root);
        System.out.println("No server/GameTest/LLM; policies and quotas in this suite are explicit fixtures, not approved content settings.");
    }
    static void boundaries() throws Exception {
        try (Fixture f = new Fixture("boundaries")) {
            f.rules(); check(f.capture(A).isEmpty(), "eligibility/attention alone grants nothing");
            Watch w = f.start(GOD_A, A); check(w.from() == 2, "start after earlier raw sequence");
            Proof p = f.capture(A).getFirst(); check(p.observedAtSequence() == 2, "same tick immediately after start");
            check(p.acquisitionKind().equals("DIRECT_WATCH"), "not rumor");
            check(f.view(GOD_A, A, A).proofs().size() == 1, "A observes");
            check(f.view(GOD_B, A, A).proofs().isEmpty(), "B not watching");
            check(f.view(GOD_A, B, B).proofs().isEmpty(), "other target isolated");
            var paused = get(f.watch.transition(w.id(), 1, State.PAUSED, new Ref("test:pause", 1)));
            check(paused.until() == 3 && paused.revision() == 2, "half-open pause");
            check(f.capture(A).isEmpty(), "paused does not observe");
            rejects(() -> get(f.watch.transition(w.id(), 1, State.ENDED, new Ref("test:stale", 1))), "late revision");
            rejects(() -> get(f.watch.start(w.id(), w.approval())), "reusing ended interval with new fence");
            Watch resumed = f.start(GOD_A, A); check(!w.id().equals(resumed.id()) && resumed.from() == 4, "fresh interval on resume");
            check(f.capture(A).size() == 1, "resumed observes");
            get(f.watch.transition(resumed.id(), 1, State.ENDED, new Ref("test:end", 1)));
            check(f.capture(A).isEmpty(), "ended does not observe");
            check(f.view(GOD_A, A, A).proofs().size() == 2, "stop does not erase prior knowledge");
            check(f.watch.status().observationCursor() == 5, "zero-observation receipts advance only after commit");
        }
    }
    static void scopeAndDisclosure() throws Exception {
        try (Fixture f = new Fixture("scope")) {
            f.start(GOD_A, A);
            for (int mode = 0; mode < 7; mode++) {
                var e = f.event(A); var s = scene(e);
                if (mode == 4 || mode == 5) e = new ActionRecord.Draft(e.occurrenceId(), e.captureSession(), e.captureOrder(), e.sourceRef(), 1, A,
                        e.subject(), e.occurredAtUtc(), e.gameTick(), e.gameDayTime(), mode == 4 ? "minecraft:the_nether" : e.dimensionId(),
                        mode == 5 ? new ActionRecord.Position(100, 70, 4) : e.position(), e.type(), e.outcome(), e.payload(), e.visibilityRef());
                s = new Scene(s.eventId(), s.captureSession(), s.captureOrder(), s.context(), mode == 0 ? Set.of() : s.powers(),
                        mode == 1 ? Set.of() : s.domains(), mode == 2, mode == 3, mode == 6 ? Map.of() : s.visibility());
                check(get(f.watch.capture(e, s).observed()).isEmpty(), "scope denial " + mode);
            }
            var e = f.event(A); var s = scene(e);
            Map<Field, Visibility> fields = new EnumMap<>(Field.class); fields.putAll(s.visibility());
            fields.put(Field.SUBJECT_TYPE, new Visibility(true, Map.of(A, RULE_A, B, RULE_B)));
            fields.put(Field.LOCATION, new Visibility(false, Map.of(A, RULE_A)));
            Scene multi = new Scene(s.eventId(), s.captureSession(), s.captureOrder(), s.context(), s.powers(), s.domains(), false, false, fields);
            fields.clear();
            Proof p = get(f.watch.capture(e, multi).observed()).getFirst();
            check(p.visibleProjection().size() == 5, "snapshot immutable and hidden location/absent subject ID omitted");
            check(!p.toString().contains("never_copy_payload"), "raw payload not copied");
            check(f.view(GOD_A, A, A).available() && f.view(GOD_A, A, A).proofs().isEmpty(), "knows != may disclose");
            get(f.watch.disclose(new Disclosure(RULE_A, Set.of(A))));
            View partial = f.view(GOD_A, A, A);
            check(partial.proofs().size() == 1 && !partial.proofs().getFirst().visibleProjection().containsKey(Field.SUBJECT_TYPE), "multi-subject field requires both grants");
            get(f.watch.disclose(new Disclosure(RULE_B, Set.of(A, B))));
            check(f.view(GOD_A, A, A).proofs().getFirst().visibleProjection().containsKey(Field.SUBJECT_TYPE), "all subject grants permit field");
            check(f.view(GOD_A, A, A, B).proofs().isEmpty(), "new listener not allowed by actor rule");
            rejects(() -> audience(f.world, GOD_A, A), "empty audience not public");
            check(!get(f.watch.read(audience(UUID.randomUUID(), GOD_A, A, A), 10)).view().available(), "world mismatch unavailable");
            rejects(() -> get(f.watch.read(audience(f.world, GOD_A, A, A), 101)), "bounded query");
        }
    }
    static void revocations() throws Exception {
        try (Fixture f = new Fixture("revocation")) {
            f.rules(); var wa = f.start(GOD_A, A); f.start(GOD_B, B);
            Proof first = f.capture(A).getFirst();
            Audience a = audience(f.world, GOD_A, A, A);
            var snapshot = get(f.watch.read(a, 10));
            f.capture(B);
            check(get(f.watch.revalidate(snapshot, a)), "unrelated player write does not invalidate");
            check(!get(f.watch.revalidate(snapshot, new Audience(f.world, a.key(), a.players(), new Ref("test:session", 2)))), "new session rejects late result");
            check(!get(f.watch.revalidate(snapshot, audience(f.world, GOD_A, A, A, B))), "audience change rejects late result");
            get(f.watch.revokeProof(first.id()));
            check(!get(f.watch.revalidate(snapshot, a)), "proof revoked before application");
            Proof second = f.capture(A).getFirst(); get(f.watch.revokeEvent(second.eventId()));
            check(f.view(GOD_A, A, A).proofs().isEmpty(), "source revoked");
            f.capture(A); get(f.watch.revokeRef(wa.approval().policy().ref()));
            check(f.view(GOD_A, A, A).proofs().isEmpty() && f.capture(A).isEmpty(), "policy revoked invalidates old and prevents new");
            check(f.view(GOD_B, B, B).proofs().size() == 1, "policy revocation scoped to relevant god");
            get(f.watch.revokeRef(CONTEXT));
            check(f.view(GOD_B, B, B).proofs().isEmpty(), "scene evidence revoked");
        }
        try (Fixture f = new Fixture("disclosure-revision")) {
            f.rules(); var w = f.start(GOD_A, A); f.capture(A);
            var a = audience(f.world, GOD_A, A, A); var previous = get(f.watch.read(a, 10));
            get(f.watch.disclose(new Disclosure(new Ref(RULE_A.id(), 2), Set.of(A, B))));
            check(!get(f.watch.revalidate(previous, a)), "changed rule requires new evidence version, cannot broaden historical projection");
            rejects(() -> get(f.watch.disclose(new Disclosure(RULE_A, Set.of(A)))), "stale permission mutation");
            get(f.watch.revokeRef(w.approval().eligibility()));
            check(f.capture(A).isEmpty(), "eligibility revoked prevents further observation");
        }
    }
    static void restart() throws Exception {
        Path dir; UUID world; UUID event; UUID oldSession;
        try (Fixture f = new Fixture("restart")) {
            dir = f.dir; world = f.world; oldSession = f.session; f.rules(); f.start(GOD_A, A);
            event = f.capture(A).getFirst().eventId();
        }
        try (Fixture f = new Fixture(dir, world, UUID.randomUUID(), LIMITS, p -> {})) {
            check(get(f.watch.states()).getFirst().state() == State.PAUSED, "restart requires game revalidation");
            check(f.view(GOD_A, A, A).proofs().getFirst().eventId().equals(event), "prior knowledge durable");
            check(f.capture(A).isEmpty(), "no implicit resume/replay");
            f.start(GOD_A, A); check(f.capture(A).size() == 1, "explicit new interval after restart");
            var d = f.event(A);
            var stale = new ActionRecord.Draft(d.occurrenceId(), oldSession, d.captureOrder(), d.sourceRef(), 1, A, d.subject(), d.occurredAtUtc(), d.gameTick(), d.gameDayTime(),
                    d.dimensionId(), d.position(), d.type(), d.outcome(), d.payload(), d.visibilityRef());
            rejects(() -> f.watch.capture(stale, scene(stale)), "old boot callback rejected");
            get(f.watch.revokeEvent(event));
        }
        try (Fixture f = new Fixture(dir, world, UUID.randomUUID(), LIMITS, p -> {})) {
            check(f.view(GOD_A, A, A).proofs().size() == 1, "revocation durable across second restart");
            f.raw.close();
            check(!f.view(GOD_A, A, A).available(), "raw unavailable not allowed empty");
        }
    }
    static void pendingFence() throws Exception {
        Path dir = Files.createTempDirectory(root, "fence"); UUID world = UUID.randomUUID(), session = UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicBoolean armed = new AtomicBoolean();
        try (var raw = new AsyncActionLedger(dir.resolve("raw"), world, RAW_LIMITS, 64, point -> {
            if (point.equals("beforeWrite") && armed.compareAndSet(true, false)) { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException x) { throw new IOException(x); } }
        })) {
            get(raw.ready());
            try (var watch = new AsyncGodWatch(dir.resolve("watch"), world, session, raw, LIMITS)) {
                get(watch.ready()); armed.set(true);
                var before = draft(session, 1); var pre = watch.capture(before, scene(before));
                check(entered.await(2, TimeUnit.SECONDS), "raw worker blocked");
                var start = watch.start(UUID.randomUUID(), approved(world, GOD_A, A));
                var after = draft(session, 2); var post = watch.capture(after, scene(after));
                check(!pre.raw().durable().isDone() && !start.isDone() && !post.observed().isDone(), "pending not durable or observed");
                release.countDown();
                check(get(pre.observed()).isEmpty(), "queued pre-start event not retroactively seen");
                check(get(start).from() == 2 && get(post.observed()).getFirst().observedAtSequence() == 2, "FIFO fence includes pending earlier raw append");
            } finally { release.countDown(); }
        }
    }
    static void reconnect() throws Exception {
        try (Fixture f = new Fixture("reconnect")) {
            f.rules(); f.start(GOD_A, A); f.start(GOD_B, B); f.capture(A);
            var paused = get(f.watch.suspendTarget(A, new Ref("test:logout", 1)));
            check(paused.size() == 1 && paused.getFirst().until() == 2, "logout closes exact target interval");
            check(f.capture(A).isEmpty(), "new player session not implicitly approved");
            check(f.capture(B).size() == 1, "other player watch unaffected by logout");
            check(f.view(GOD_A, A, A).proofs().size() == 1, "logout preserves actual prior observation");
            f.start(GOD_A, A); check(f.capture(A).size() == 1, "explicit revalidation starts new session interval");
            check(get(f.watch.suspendTarget(UUID.randomUUID(), new Ref("test:unrelated", 1))).isEmpty(), "unrelated logout does not suspend all gods");
        }
    }
    static ActionRecord.Draft draft(UUID session, long order) {
        return new ActionRecord.Draft(UUID.randomUUID(), session, order, "mythictrpg:crop_remove_commit", 1, A,
                new ActionRecord.Subject("BLOCK", "minecraft:wheat", null), 1, 100, 100, "minecraft:overworld",
                new ActionRecord.Position(2, 70, 4), ActionRecord.Type.MATURE_CROP_REMOVED, "COMPLETED", Map.of(), "mythictrpg:admin_only_unprojected");
    }
    static void queueAndFailure() throws Exception {
        Path dir = Files.createTempDirectory(root, "queue"); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicBoolean armed = new AtomicBoolean();
        try (Fixture f = new Fixture(dir, UUID.randomUUID(), UUID.randomUUID(), new AsyncGodWatch.Limits(1_000_000, 1000, 2), point -> {
            if (point.equals("beforeWrite") && armed.compareAndSet(true, false)) { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException x) { throw new IOException(x); } }
        })) {
            armed.set(true); var start = f.watch.start(UUID.randomUUID(), f.approval(GOD_A, A));
            check(entered.await(2, TimeUnit.SECONDS), "watch disk blocked");
            var d1 = f.event(A); var c1 = f.watch.capture(d1, scene(d1));
            var d2 = f.event(A); var c2 = f.watch.capture(d2, scene(d2));
            var d3 = f.event(A); var c3 = f.watch.capture(d3, scene(d3));
            rejects(() -> get(c3.observed()), "bounded queue rejects excess observation");
            check(get(c3.raw().durable()).sequence() == 3, "watch backpressure never loses raw result");
            check(f.watch.status().rejected() >= 1, "gap diagnostic visible");
            check(!f.watch.awaitClose(10), "bounded close does not pretend to drain");
            release.countDown(); get(start); get(c1.observed()); get(c2.observed());
            check(f.watch.awaitClose(5000), "queued work drains safely");
        } finally { release.countDown(); }
        Path failureDir = Files.createTempDirectory(root, "forced-commit"); UUID world = UUID.randomUUID();
        AtomicBoolean fail = new AtomicBoolean();
        try (Fixture f = new Fixture(failureDir, world, UUID.randomUUID(), LIMITS, point -> {
            if (point.equals("afterForce") && fail.compareAndSet(true, false)) throw new IOException("fixture crash boundary");
        })) {
            f.rules(); f.start(GOD_A, A); fail.set(true);
            var e = f.event(A); var c = f.watch.capture(e, scene(e));
            rejects(() -> get(c.observed()), "no false receipt after forced commit fault");
            check(get(c.raw().durable()).sequence() == 1, "raw unaffected by proof fault");
        }
        try (var raw = new AsyncActionLedger(failureDir.resolve("raw"), world, RAW_LIMITS, 64)) {
            get(raw.ready());
            try (var watch = new AsyncGodWatch(failureDir.resolve("watch"), world, UUID.randomUUID(), raw, LIMITS)) {
                rejects(() -> get(watch.ready()), "unclean watch cannot disclose possibly revoked facts");
                check(watch.status().reason().contains("UNCLEAN_WATCH_REQUIRES_REVIEW"), "explicit uncertain recovery status");
            }
        }
        try (WatchStore store = new WatchStore(failureDir.resolve("watch"), world, LIMITS.maxBytes(), LIMITS.maxEntries(), p -> {})) {
            check(store.view(audience(world, GOD_A, A, A), 10).proofs().size() == 1, "forced transaction preserved for administrative review, not auto-disclosed");
        }
    }
    static void storage() throws Exception {
        Path dir = Files.createTempDirectory(root, "storage"); UUID world = UUID.randomUUID(), session = UUID.randomUUID();
        var d = draft(session, 1); var raw = new ActionRecord(1, world, 1, d); List<Proof> original;
        try (WatchStore store = new WatchStore(dir, world, 1_000_000, 1000, p -> {})) {
            Watch w = store.start(UUID.randomUUID(), approved(world, GOD_A, A), 1);
            original = store.observe(raw, scene(d));
            store.transition(w.id(), 1, State.ENDED, 2, new Ref("test:end", 1));
            long bytes = store.bytes();
            check(store.observe(raw, scene(d)).equals(original) && store.bytes() == bytes, "source retry is durable idempotent, no re-projection");
            check(store.start(w.id(), w.approval(), 1).state() == State.ENDED, "start retry never reactivates old watch");
            rejects(() -> new WatchStore(dir, world, 1_000_000, 1000, p -> {}), "exclusive writer");
        }
        byte[] pristine = Files.readAllBytes(dir.resolve("watch-v1.journal"));
        rejects(() -> new WatchStore(dir, UUID.randomUUID(), 1_000_000, 1000, p -> {}), "cross-world journal");
        check(Arrays.equals(pristine, Files.readAllBytes(dir.resolve("watch-v1.journal"))), "wrong-world read preserves original");
        for (int mode = 0; mode < 3; mode++) {
            Path broken = Files.createTempDirectory(root, "corrupt"); byte[] bytes = pristine.clone();
            if (mode == 0) bytes[bytes.length - 2] ^= 1;
            if (mode == 1) bytes = Arrays.copyOf(bytes, bytes.length - 2);
            if (mode == 2) Arrays.fill(bytes, 0, 4, (byte)0x7f);
            Files.write(broken.resolve("watch-v1.journal"), bytes); byte[] before = MessageDigest.getInstance("SHA-256").digest(bytes);
            rejects(() -> new WatchStore(broken, world, 1_000_000, 1000, p -> {}), "reject corrupt frame");
            check(Arrays.equals(before, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(broken.resolve("watch-v1.journal")))), "corruption not repaired/overwritten");
        }
        Path limited = Files.createTempDirectory(root, "limit");
        try (WatchStore store = new WatchStore(limited, world, 100_000, 1, p -> {})) {
            store.start(UUID.randomUUID(), approved(world, GOD_A, A), 1); long bytes = store.bytes();
            rejects(() -> store.observe(raw, scene(d)), "bounded index limit"); check(store.bytes() == bytes && store.cursor() == 0, "no cursor before transaction");
        }
        try (WatchStore store = new WatchStore(limited, world, 200_000, 100, p -> {})) {
            store.recover(0); check(store.states().getFirst().state() == State.PAUSED, "increase quota permits safe recovery");
        }
    }
    static void contract() throws Exception {
        rejects(() -> new Policy(new Ref("test:p", 1), "invented", "test:power", "test:domain", List.of(), Set.of()), "game namespaced ID required");
        rejects(() -> new Watch(UUID.randomUUID(), approved(UUID.randomUUID(), GOD_A, A), State.ACTIVE, 0, null, 1, CONTEXT), "invalid interval");
        rejects(() -> new Visibility(true, Map.of()), "no implicit multi-subject permission");
        rejects(() -> new AsyncGodWatch.Limits(0, 1, 1), "no implicit operational quota");
        try (Fixture f = new Fixture("owner")) {
            AtomicReference<Throwable> wrongThread = new AtomicReference<>();
            Thread t = new Thread(() -> { try { f.watch.states(); } catch (Throwable x) { wrongThread.set(x); } }); t.start(); t.join();
            check(wrongThread.get() instanceof IllegalStateException, "game thread ownership");
            f.start(GOD_A, A);
            var d = f.event(A); get(f.watch.capture(d, scene(d)).observed());
            rejects(() -> f.watch.capture(d, scene(d)), "late live callback cannot replay occurrence");
            check(f.raw.status().committedSequence() == 1, "late callback did not duplicate raw");
        }
    }
    static void byteQuota() throws Exception {
        Path dir = Files.createTempDirectory(root, "byte-quota"); UUID world = UUID.randomUUID(), session = UUID.randomUUID();
        try (WatchStore store = new WatchStore(dir, world, 70_000, 1000, p -> {})) {
            store.start(UUID.randomUUID(), approved(world, GOD_A, A), 1); boolean full = false, warned = false;
            LedgerCapacityWarning warning = new LedgerCapacityWarning();
            for (long seq = 1; seq <= 100; seq++) {
                long before = store.bytes(), cursor = store.cursor(); var d = draft(session, seq);
                try { store.observe(new ActionRecord(1, world, seq, d), scene(d)); }
                catch (IOException capacity) {
                    check(capacity.getMessage().equals("WATCH_STORAGE_LIMIT"), "explicit byte quota failure");
                    check(store.bytes() == before && store.cursor() == cursor, "quota does not falsely commit/delete"); full = true; break;
                }
                warned |= warning.shouldNotify(store.bytes(), store.maxBytes(), seq * 1000);
            }
            check(full && warned, "bounded byte quota and reusable 90 percent warning policy");
        }
    }
    static void rawFailure() throws Exception {
        Path dir = Files.createTempDirectory(root, "raw-failure"); UUID world = UUID.randomUUID(), session = UUID.randomUUID();
        AtomicBoolean fail = new AtomicBoolean();
        try (var raw = new AsyncActionLedger(dir.resolve("raw"), world, RAW_LIMITS, 16, point -> {
            if (point.equals("beforeWrite") && fail.get()) throw new IOException("fixture raw failure");
        })) {
            get(raw.ready());
            try (var watch = new AsyncGodWatch(dir.resolve("watch"), world, session, raw, LIMITS)) {
                get(watch.ready()); get(watch.start(UUID.randomUUID(), approved(world, GOD_A, A))); fail.set(true);
                var d = draft(session, 1); var c = watch.capture(d, scene(d));
                rejects(() -> get(c.raw().durable()), "failed raw receipt");
                rejects(() -> get(c.observed()), "no proof without durable raw source");
            }
        }
        try (WatchStore store = new WatchStore(dir.resolve("watch"), world, LIMITS.maxBytes(), LIMITS.maxEntries(), p -> {})) {
            check(store.cursor() == 0, "failed raw never advanced observation cursor");
            check(store.view(audience(world, GOD_A, A, A), 10).proofs().isEmpty(), "failed raw created no orphan proof");
        }
    }
}
