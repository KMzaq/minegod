package com.sande.mythictrpg.recording;

import com.sande.mythictrpg.recording.server.*;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.watch.*;
import com.sande.mythictrpg.rumor.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual file lengths and real concurrent admissions; no Minecraft or mock size accounting. */
public final class ManagedStoreQuotaTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        registryCoverageAndPathSafety(); boundariesAndOverflow(); concurrentAdmissions();
        defaultOffAndWorkerScopes(); asynchronousSavedDataReservations();
        actualLegacyQueues(); prospectiveLedgerMutationGates(); failedSnapshotCannotOverrunOnRetry();
        System.out.println("Managed store quota: " + checks + " checks passed");
    }
    private static void registryCoverageAndPathSafety() throws Exception {
        Path world = Files.createTempDirectory("mythai-managed-registry-");
        ManagedStoreRegistry registry = ManagedStoreRegistry.open(world);
        check(registry.storeIds().size() == 5, "all five stores registered even when absent");
        check(registry.measure().totalBytes() == 0, "absent paths measure zero without creation");
        try (var entries = Files.list(world)) { check(entries.findAny().isEmpty(), "read-only registry creates nothing"); }
        Path recording = registry.root(ManagedStoreRegistry.RECORDING);
        bytes(recording.resolve("dataset/recording.sqlite"), 100);
        bytes(recording.resolve("dataset/recording.sqlite-wal"), 90);
        bytes(recording.resolve("dataset/recording.sqlite-shm"), 80);
        bytes(recording.resolve("dataset/recording.sqlite-journal"), 70);
        bytes(recording.resolve("inactive-copy/recording.sqlite"), 60);
        bytes(recording.resolve("staging/recovery.tmp"), 50);
        bytes(recording.resolve("manifest.json"), 40);
        bytes(registry.root(ManagedStoreRegistry.ACTION_LEDGER).resolve("segment-00000000000000000001.alog"), 30);
        bytes(registry.root(ManagedStoreRegistry.ACTION_LEDGER).resolve("checkpoint.json.tmp"), 20);
        bytes(registry.root(ManagedStoreRegistry.GOD_WATCH).resolve("watch-v1.journal"), 10);
        bytes(registry.root(ManagedStoreRegistry.RUMOR), 9);
        bytes(registry.root(ManagedStoreRegistry.RUMOR).resolveSibling("mythictrpg_memory_rumor_v1.dat123.neoforge-tmp"), 8);
        bytes(registry.root(ManagedStoreRegistry.REPUTATION), 7);
        bytes(registry.root(ManagedStoreRegistry.REPUTATION).resolveSibling("mythictrpg_reputation_judgement_v1.dat.recovery"), 6);
        bytes(world.resolve("data/mythictrpg_reward_claims.dat"), 500);
        bytes(world.resolve("data/mythictrpg_players.dat"), 500);
        bytes(world.resolve("region/r.0.0.mca"), 500);
        var measured = registry.measure();
        check(measured.totalBytes() == 580, "all physical originals/sidecars/temps/recovery counted, game files excluded");
        check(measured.storeBytes().get(ManagedStoreRegistry.RECORDING) == 490, "recording full root includes inactive files");
        check(measured.storeBytes().get(ManagedStoreRegistry.RUMOR) == 17, "mixed rumor whole file and temp counted");
        expect(IOException.class, () -> registry.validate(world.resolve("../outside")), "normalized escape rejected");
        expect(IllegalArgumentException.class, () -> registry.root("model_chosen_path"), "arbitrary registration unavailable");
        Path outside = Files.createTempDirectory("mythai-quota-outside-");
        Path linkedWorld = Files.createTempDirectory("mythai-quota-symlink-");
        boolean canLink = false;
        try {
            Files.createSymbolicLink(linkedWorld.resolve("mythictrpg-action-ledger-v1"), outside);
            canLink = true;
        } catch (UnsupportedOperationException | IOException unavailable) {
            System.out.println("Symbolic-link creation unavailable; symlink assertion not executed: " + unavailable.getClass().getSimpleName());
        }
        if (canLink) expect(IOException.class, () -> ManagedStoreRegistry.open(linkedWorld), "symlink/junction escape fails closed");
    }
    private static void boundariesAndOverflow() throws Exception {
        Path world = Files.createTempDirectory("mythai-quota-boundaries-");
        var registry = ManagedStoreRegistry.open(world);
        var budget = new WorldRecordingBudget(registry, 1000, 10);
        Path file = registry.root(ManagedStoreRegistry.RECORDING).resolve("physical.bin");
        bytes(file, 890); budget.refresh(); check(budget.snapshot().state().equals("READY"), "89 percent ready");
        bytes(file, 900); budget.refresh(); check(budget.snapshot().state().equals("WARNING"), "90 percent warning");
        bytes(file, 950); budget.refresh(); check(budget.snapshot().state().equals("DEFER_BACKGROUND"), "95 percent defers background");
        var ticket = budget.reserve(ManagedStoreRegistry.ACTION_LEDGER, 40, false);
        check(budget.tryReserve(ManagedStoreRegistry.GOD_WATCH, 1, false).isEmpty(), "shared admission preserves maintenance headroom");
        var maintenance = budget.reserve(ManagedStoreRegistry.GOD_WATCH, 10, true);
        check(budget.snapshot().state().equals("FULL"), "maintenance stays inside hard cap");
        check(budget.tryReserve(ManagedStoreRegistry.RUMOR, 1, true).isEmpty(), "maintenance cannot exceed hard cap");
        budget.reconcile(ticket); budget.reconcile(maintenance);
        check(budget.snapshot().outstandingReservations() == 0, "both reservations actually reconciled");
        expect(IllegalStateException.class, () -> budget.reconcile(ticket), "duplicate release rejected");
        var other = new WorldRecordingBudget(registry, 10_000, 100);
        var foreign = other.reserve(ManagedStoreRegistry.REPUTATION, 1, false);
        expect(IllegalStateException.class, () -> budget.reconcile(foreign), "foreign reservation rejected");
        other.reconcile(foreign);
        var huge = new WorldRecordingBudget(ManagedStoreRegistry.open(Files.createTempDirectory("mythai-quota-overflow-")), Long.MAX_VALUE, 0);
        var max = huge.reserve(ManagedStoreRegistry.RECORDING, Long.MAX_VALUE, true);
        check(huge.tryReserve(ManagedStoreRegistry.RUMOR, 1, true).isEmpty(), "checked64 reservation overflow rejected");
        huge.reconcile(max);
        var configured = new WorldRecordingBudget(registry, 2000, 1, .40, .45);
        check(configured.snapshot().state().equals("DEFER_BACKGROUND"), "configured ratios honored");
        bytes(file, 1001); expect(IOException.class, budget::refresh, "external growth closes admission");
        check(budget.tryReserve(ManagedStoreRegistry.RECORDING, 0, true).isEmpty(), "uncertainty cannot silently reopen");
    }
    private static void concurrentAdmissions() throws Exception {
        var registry = ManagedStoreRegistry.open(Files.createTempDirectory("mythai-quota-concurrency-"));
        var budget = new WorldRecordingBudget(registry, 1000, 100);
        var ready = new CountDownLatch(12); var start = new CountDownLatch(1);
        var admitted = new ConcurrentLinkedQueue<WorldRecordingBudget.Reservation>();
        try (var workers = Executors.newFixedThreadPool(12)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                final String store = i % 2 == 0 ? ManagedStoreRegistry.ACTION_LEDGER : ManagedStoreRegistry.GOD_WATCH;
                futures.add(workers.submit(() -> { ready.countDown(); start.await(); budget.tryReserve(store, 100, false).ifPresent(admitted::add); return null; }));
            }
            check(ready.await(5, TimeUnit.SECONDS), "parallel contenders ready"); start.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        }
        check(admitted.size() == 9 && budget.snapshot().outstandingReservations() == 900, "concurrent stores share exactly one checked admission balance");
        for (var reservation : admitted) budget.reconcile(reservation);
        check(budget.snapshot().outstandingReservations() == 0, "parallel admissions reconciled exactly once");
    }
    private static void defaultOffAndWorkerScopes() throws Exception {
        Path world = Files.createTempDirectory("mythai-quota-bridge-");
        var registry = ManagedStoreRegistry.open(world); var budget = new WorldRecordingBudget(registry, 1000, 100);
        Path ledger = registry.root(ManagedStoreRegistry.ACTION_LEDGER);
        var off = LegacyRecordingQuota.reserve(ledger, ManagedStoreRegistry.ACTION_LEDGER, 800, false);
        check(!off.enforced() && budget.snapshot().outstandingReservations() == 0, "OFF leaves legacy quota unchanged");
        expect(IOException.class, () -> LegacyRecordingQuota.install(world, budget), "activation refuses outstanding OFF writer");
        off.cancelUnstarted(); LegacyRecordingQuota.install(world, budget);
        var queued = LegacyRecordingQuota.reserve(ledger, ManagedStoreRegistry.ACTION_LEDGER, 600, false);
        check(budget.snapshot().outstandingReservations() == 600, "accepted queue item reserves before I/O");
        expect(IOException.class, () -> LegacyRecordingQuota.uninstall(world, budget), "live queued writer cannot be detached");
        LegacyRecordingQuota.within(queued, () -> {
            try (var nested = LegacyRecordingQuota.reserve(ledger, ManagedStoreRegistry.ACTION_LEDGER, 250, false)) {
                nested.validateBeforeWrite(); bytes(ledger.resolve("segment.alog"), 250);
                check(budget.snapshot().outstandingReservations() == 600, "nested actual write consumes admission without double reservation");
            }
            expect(IOException.class, () -> LegacyRecordingQuota.reserve(ledger, ManagedStoreRegistry.ACTION_LEDGER, 351, false), "physical upper bound enforced across nested writes");
            return null;
        });
        check(budget.snapshot().outstandingReservations() == 0 && budget.snapshot().usedPhysicalBytes() == 250, "worker completion reconciles actual file length");
        var canceled = LegacyRecordingQuota.reserve(ledger, ManagedStoreRegistry.ACTION_LEDGER, 200, false);
        canceled.cancelUnstarted(); check(budget.snapshot().outstandingReservations() == 0, "queue cancellation returns unused admission");
        LegacyRecordingQuota.uninstall(world, budget);
    }
    private static void asynchronousSavedDataReservations() throws Exception {
        Path world = Files.createTempDirectory("mythai-quota-saveddata-");
        var registry = ManagedStoreRegistry.open(world); var budget = new WorldRecordingBudget(registry, 2_000_000, 100_000);
        LegacyRecordingQuota.install(world, budget);
        var gate = new LegacyRecordingQuota.SavedDataGate(ManagedStoreRegistry.RUMOR);
        Path file = registry.root(ManagedStoreRegistry.RUMOR); gate.bind(file);
        check(gate.admit("{\"revision\":1}", false), "prospective mutation obtains reservation");
        long bound = LegacyRecordingQuota.SAVED_DATA_WRITE_BOUND;
        check(budget.snapshot().outstandingReservations() == bound, "setDirty does not release reservation");
        var first = gate.capture(file, "{\"revision\":1}");
        check(budget.snapshot().outstandingReservations() == bound, "snapshot capture does not release reservation");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var io = CompletableFuture.runAsync(() -> {
            try {
                first.validateBeforeWrite(); entered.countDown(); release.await(5, TimeUnit.SECONDS);
                bytes(file.resolveSibling(file.getFileName() + "111.neoforge-tmp"), 111);
                first.measureFailedIo(); gate.failed(first, new IOException("injected atomic move failure"));
            } catch (Exception failure) { throw new CompletionException(failure); }
        });
        check(entered.await(5, TimeUnit.SECONDS), "asynchronous write started");
        check(gate.admit("{\"revision\":2}", false), "next mutation reserves while old snapshot waits");
        check(budget.snapshot().outstandingReservations() == 2 * bound, "consecutive snapshots have separate live reservations");
        release.countDown(); io.get(5, TimeUnit.SECONDS);
        check(gate.retryNeeded() && budget.snapshot().outstandingReservations() == 2 * bound, "failed I/O keeps reservation until actual successful retry");
        check(budget.snapshot().usedPhysicalBytes() == 111, "abandoned temp stays physically counted before retry");
        var latest = gate.capture(file, "{\"revision\":2}"); latest.validateBeforeWrite(); bytes(file, 222); gate.completed(latest);
        check(!gate.retryNeeded() && budget.snapshot().outstandingReservations() == 0, "successful newer snapshot reconciles superseded failed snapshot");
        check(budget.snapshot().usedPhysicalBytes() == 333, "leftover temp not deleted or guessed away");
        check(!gate.admit("한".repeat(22_000), false), "unsupported modified-UTF bound rejected before mutation");
        check(LegacyRecordingQuota.diagnostics(world).get(ManagedStoreRegistry.RUMOR).equals("LEGACY_NBT_STRING_LIMIT"), "legacy codec bound has explicit separate reason");
        check(LegacyRecordingQuota.SavedDataGate.supportedJson("a".repeat(65_535)), "exact legacy string boundary accepted");
        check(!LegacyRecordingQuota.SavedDataGate.supportedJson("a".repeat(65_536)), "legacy string overflow rejected");
        expect(IllegalArgumentException.class, () -> new LegacyRecordingQuota.SavedDataGate("mythictrpg_reward_claims"), "reward/game SavedData can never be gated");
        check(LegacyRecordingQuota.legacyLimits(world).get(ManagedStoreRegistry.RUMOR).maxEntries() == 4096, "legacy count limit separately visible");
        LegacyRecordingQuota.uninstall(world, budget);
    }
    private static void actualLegacyQueues() throws Exception {
        Path world = Files.createTempDirectory("mythai-real-legacy-quota-");
        var registry = ManagedStoreRegistry.open(world); var budget = new WorldRecordingBudget(registry, 2_000_000, 100_000);
        LegacyRecordingQuota.install(world, budget);
        UUID worldId = UUID.randomUUID(), session = UUID.randomUUID(), actor = UUID.randomUUID();
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var raw = new AsyncActionLedger(registry.root(ManagedStoreRegistry.ACTION_LEDGER), worldId,
                new ActionLedgerStore.Limits(4_000_000, 128_000, 200), 8, point -> {
                    if (point.equals("beforeWrite")) {
                        writing.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("fixture release timeout"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                    }
                })) {
            raw.ready().get(10, TimeUnit.SECONDS);
            try (var watch = new AsyncGodWatch(registry.root(ManagedStoreRegistry.GOD_WATCH), worldId, session, raw,
                    new AsyncGodWatch.Limits(4_000_000, 200, 8))) {
                watch.ready().get(10, TimeUnit.SECONDS);
                var draft = action(session, actor, 1);
                var receipt = raw.submit(draft);
                check(receipt.acceptance() == AsyncActionLedger.Acceptance.PENDING_NOT_DURABLE, "actual action admitted with pending-not-durable status");
                check(writing.await(5, TimeUnit.SECONDS), "actual action file writer reached barrier");
                long reserved = budget.snapshot().outstandingReservations();
                check(reserved >= 16_384 + 8, "actual queued action reservation held across I/O");
                check(raw.submit(draft).durable() == receipt.durable()
                        && budget.snapshot().outstandingReservations() == reserved, "duplicate pending action shares exactly one reservation");
                release.countDown(); receipt.durable().get(10, TimeUnit.SECONDS);
                watch.disclose(new WatchContract.Disclosure(new WatchContract.Ref("test:quota_disclosure", 1), Set.of(actor)))
                        .get(10, TimeUnit.SECONDS);
                check(budget.snapshot().outstandingReservations() == 0, "actual action/watch durable callbacks occur after reconcile");
                check(budget.snapshot().storeBytes().get(ManagedStoreRegistry.ACTION_LEDGER) > 0
                        && budget.snapshot().storeBytes().get(ManagedStoreRegistry.GOD_WATCH) > 0, "actual two legacy stores counted together");
                var full = budget.reserve(ManagedStoreRegistry.RECORDING, 2_000_000 - budget.snapshot().usedPhysicalBytes(), true);
                check(raw.submit(action(session, actor, 2)).acceptance() == AsyncActionLedger.Acceptance.QUOTA_REJECTED,
                        "actual action queue rejects before accepting unreserved write");
                expect(ExecutionException.class, () -> watch.disclose(new WatchContract.Disclosure(
                        new WatchContract.Ref("test:quota_disclosure", 2), Set.of(actor))).get(5, TimeUnit.SECONDS), "actual watch queue rejects at shared FULL");
                check(watch.states().get(5, TimeUnit.SECONDS).isEmpty(), "read-only watch query remains available at FULL");
                check(raw.after(new ActionLedgerStore.Cursor(worldId, 0), actor, 10).get(5, TimeUnit.SECONDS).records().size() == 1,
                        "durable action reads remain available at FULL");
                budget.reconcile(full);
                check(LegacyRecordingQuota.legacyLimits(world).get(ManagedStoreRegistry.ACTION_LEDGER).maxEntries() == 200,
                        "real configured legacy index maximum remains visible");
            } finally { release.countDown(); }
        }
        LegacyRecordingQuota.requireDrained(world, budget); LegacyRecordingQuota.uninstall(world, budget);
    }
    private static ActionRecord.Draft action(UUID session, UUID actor, long order) {
        return new ActionRecord.Draft(UUID.randomUUID(), session, order, "mythictrpg:crop_remove_commit", 1, actor,
                new ActionRecord.Subject("BLOCK", "minecraft:wheat", null), 123456, 100, 6000, "minecraft:overworld",
                new ActionRecord.Position(2, 70, 4), ActionRecord.Type.MATURE_CROP_REMOVED, "COMPLETED", Map.of(),
                "mythictrpg:admin_only_unprojected");
    }
    private static void prospectiveLedgerMutationGates() throws Exception {
        var rumor = new RumorLedger(); UUID player = UUID.randomUUID(), bird = UUID.randomUUID();
        boolean[] allow = {false}; int[] admissions = {0};
        var rumorGate = RumorLedger.class.getDeclaredMethod("mutationGate", java.util.function.BooleanSupplier.class,
                java.util.function.BiPredicate.class); rumorGate.setAccessible(true);
        java.util.function.BiPredicate<RumorLedger.Snapshot, Boolean> checkRumor = (prospective, maintenance) -> {
            admissions[0]++; check(prospective.couriers().size() == 1, "gate receives prospective immutable state");
            check(rumor.snapshot().couriers().isEmpty(), "live ledger untouched before admission"); return allow[0];
        };
        rumorGate.invoke(rumor, (java.util.function.BooleanSupplier) () -> true, checkRumor);
        check(!rumor.bindCourier(player, bird) && rumor.snapshot().couriers().isEmpty() && rumor.revision() == 0,
                "denied rumor reservation makes no live mutation");
        rumor.heard(player, "mythictrpg:demeter", Set.of(player)); check(admissions[0] == 1, "rumor read does not ask mutation quota");
        allow[0] = true; check(rumor.bindCourier(player, bird) && rumor.snapshot().couriers().size() == 1, "approved draft updates the same live ledger");
        var reputation = new ReputationLedger(rumor.worldId());
        var reputationGate = ReputationLedger.class.getDeclaredMethod("mutationGate", java.util.function.BooleanSupplier.class,
                java.util.function.BiPredicate.class); reputationGate.setAccessible(true);
        allow[0] = false;
        reputationGate.invoke(reputation, (java.util.function.BooleanSupplier) () -> true,
                (java.util.function.BiPredicate<ReputationLedger.Snapshot, Boolean>) (prospective, maintenance) -> allow[0]);
        UUID id = UUID.randomUUID();
        var decision = new ReputationLedger.Decision(id, rumor.worldId(), player, "mythictrpg:demeter", UUID.randomUUID(),
                UUID.randomUUID(), 1, "test:policy", "a".repeat(64), 0, ReputationLedger.Outcome.ACCEPTED,
                ReputationLedger.DirectImpact.NOT_APPLIED,
                new ReputationLedger.Approval(id, ReputationLedger.ApprovalKind.ADMIN_REVIEW, "test:review"));
        var apply = ReputationLedger.class.getDeclaredMethod("apply", ReputationLedger.Decision.class); apply.setAccessible(true);
        check(apply.invoke(reputation, decision) == ReputationLedger.Result.CAPACITY && reputation.size() == 0,
                "denied reputation quota returns explicit capacity without live mutation");
        allow[0] = true;
        check(apply.invoke(reputation, decision) == ReputationLedger.Result.APPLIED && reputation.size() == 1,
                "approved reputation draft preserves existing game result semantics");
    }
    private static void failedSnapshotCannotOverrunOnRetry() throws Exception {
        Path world = Files.createTempDirectory("mythai-snapshot-retry-cap-");
        var registry = ManagedStoreRegistry.open(world); var budget = new WorldRecordingBudget(registry, 200_000, 1);
        LegacyRecordingQuota.install(world, budget);
        var gate = new LegacyRecordingQuota.SavedDataGate(ManagedStoreRegistry.REPUTATION);
        Path file = registry.root(ManagedStoreRegistry.REPUTATION); gate.bind(file);
        check(gate.admit("{}", false), "initial bounded snapshot admitted");
        var ticket = gate.capture(file, "{}"); ticket.validateBeforeWrite();
        bytes(file.resolveSibling(file.getFileName() + "123.neoforge-tmp"), 100_000);
        ticket.measureFailedIo(); gate.failed(ticket, new IOException("retained temp"));
        check(!gate.admit("{\"next\":1}", false), "failed growth is not reused as free next-snapshot capacity");
        expect(IOException.class, ticket::validateBeforeWrite, "retry cannot exceed hard cap with a second temporary file");
        ticket.close(); // fixture abandons retry only AFTER the failed I/O is complete and measured.
        LegacyRecordingQuota.uninstall(world, budget);
    }
    private static void bytes(Path file, int count) throws IOException { Files.createDirectories(file.getParent()); Files.write(file, new byte[count]); }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); checks++; }
    private interface Checked { void run() throws Exception; }
    private static void expect(Class<? extends Throwable> type, Checked task, String reason) throws Exception {
        try { task.run(); } catch (Throwable failure) { check(type.isInstance(failure), reason + ": " + failure); return; }
        throw new AssertionError("Expected " + type.getSimpleName() + ": " + reason);
    }
}
