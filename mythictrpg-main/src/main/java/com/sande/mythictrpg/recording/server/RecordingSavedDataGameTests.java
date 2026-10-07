package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.rumor.ReputationSavedData;
import com.sande.mythictrpg.rumor.RumorSavedData;
import java.nio.file.*;
import java.util.Random;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.*;

/** Real NeoForge save queue/atomic NBT writes, in a new isolated fixture directory only. */
@GameTestHolder("mythictrpg_recording")
@PrefixGameTestTemplate(false)
public final class RecordingSavedDataGameTests {
    private RecordingSavedDataGameTests() {}
    @GameTest(templateNamespace = "mythictrpg_recording", template = "empty", timeoutTicks = 600)
    public static void namedSavedDataReservationsSurviveActualAsyncIo(GameTestHelper helper) {
        try {
            Fixture fixture = new Fixture(helper);
            helper.onEachTick(() -> {
                if (fixture.finished) return;
                try { fixture.tick(); }
                catch (Exception | AssertionError failure) {
                    fixture.release.countDown(); fixture.finished = true;
                    helper.fail("SavedData quota fixture: " + failure);
                }
            });
        } catch (Exception failure) { helper.fail("SavedData quota setup: " + failure); }
    }
    @GameTest(templateNamespace = "mythictrpg_recording", template = "empty", timeoutTicks = 600)
    public static void snapshotCallbacksFollowActualCommitAndIsolateConsumers(GameTestHelper helper) {
        try {
            CallbackFixture fixture = new CallbackFixture(helper);
            helper.onEachTick(() -> {
                if (fixture.finished) return;
                try { fixture.tick(); }
                catch (Exception | AssertionError failure) {
                    fixture.release.countDown(); fixture.finished = true;
                    helper.fail("SavedData commit callback fixture: " + failure);
                }
            });
        } catch (Exception failure) { helper.fail("SavedData callback setup: " + failure); }
    }
    private static final class CallbackFixture {
        final GameTestHelper helper;
        final Path world, file, missingParentFile;
        final WorldRecordingBudget budget;
        final LegacyRecordingQuota.SavedDataGate gate = new LegacyRecordingQuota.SavedDataGate(ManagedStoreRegistry.RUMOR);
        final LegacyRecordingQuota.SavedDataGate failingGate = new LegacyRecordingQuota.SavedDataGate(ManagedStoreRegistry.RUMOR);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean entered = new AtomicBoolean(), fence = new AtomicBoolean();
        final List<ManagedSavedDataIo.WriteResult> writes = new CopyOnWriteArrayList<>(), failures = new CopyOnWriteArrayList<>();
        final List<Long> callbackReservations = new CopyOnWriteArrayList<>();
        int phase;
        boolean finished;
        CallbackFixture(GameTestHelper helper) throws Exception {
            this.helper = helper;
            Path root = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("recording-quota-fixtures");
            world = Files.createTempDirectory(Files.createDirectories(root), "callback-").toAbsolutePath().normalize();
            Files.createDirectories(world.resolve("data"));
            var registry = ManagedStoreRegistry.open(world);
            file = registry.root(ManagedStoreRegistry.RUMOR);
            budget = new WorldRecordingBudget(registry, 500_000, 1_000);
            LegacyRecordingQuota.install(world, budget);
            // This separate, unregistered test world deliberately has no data directory: actual atomic write must fail.
            missingParentFile = world.resolve("io-failure-world/data/mythictrpg_memory_rumor_v1.dat");
            IOUtilities.withIOWorker(() -> {
                entered.set(true);
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
        }
        void tick() throws Exception {
            if (phase == 0) {
                if (!entered.get()) return;
                CompoundTag first = snapshot(10), second = snapshot(20);
                helper.assertTrue(ManagedSavedDataIo.queue(file.toFile(), first, gate, result -> {
                    writes.add(result); callbackReservations.add(budget.snapshot().outstandingReservations());
                    throw new IllegalStateException("synthetic consumer failure must not become a write failure");
                }), "first callback snapshot not queued");
                helper.assertTrue(ManagedSavedDataIo.queue(file.toFile(), second, gate, result -> {
                    writes.add(result); callbackReservations.add(budget.snapshot().outstandingReservations());
                }), "second callback snapshot not queued");
                first.putInt("marker", 999); second.putInt("marker", 999);
                helper.assertTrue(writes.isEmpty() && !Files.exists(file), "callback ran on admission before the blocked physical write");
                helper.assertTrue(budget.snapshot().outstandingReservations() == 2 * LegacyRecordingQuota.SAVED_DATA_WRITE_BOUND,
                        "callback write reservation released before actual commit");
                IOUtilities.withIOWorker(() -> fence.set(true)); release.countDown(); phase = 1;
            } else if (phase == 1) {
                if (!fence.get()) return;
                helper.assertTrue(writes.size() == 2 && writes.stream().allMatch(r -> r.status() == ManagedSavedDataIo.WriteStatus.COMMITTED),
                        "consumer exception changed commit outcome or poisoned the save chain");
                helper.assertTrue(marker(writes.getFirst()) == 10 && marker(writes.getLast()) == 20,
                        "callback order or exact frozen snapshot changed with caller mutation");
                var detached = writes.getFirst().snapshot().orElseThrow(); detached.getCompound("data").putInt("marker", 888);
                helper.assertTrue(marker(writes.getFirst()) == 10, "result accessor exposed mutable retained snapshot");
                helper.assertTrue(callbackReservations.equals(List.of(LegacyRecordingQuota.SAVED_DATA_WRITE_BOUND, 0L)) && !gate.retryNeeded(),
                        "completion preceded gate reconciliation or consumer failure became retry");
                helper.assertTrue(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data").getInt("marker") == 20,
                        "callback reported a snapshot that was not atomically installed");
                fence.set(false);
                helper.assertTrue(ManagedSavedDataIo.queue(missingParentFile.toFile(), snapshot(30), failingGate, result -> {
                    failures.add(result); throw new IllegalStateException("synthetic failed-write consumer failure");
                }),
                        "physical failure fixture was rejected before actual I/O attempt");
                IOUtilities.withIOWorker(() -> fence.set(true)); phase = 2;
            } else if (phase == 2) {
                if (!fence.get()) return;
                helper.assertTrue(failures.size() == 1 && failures.getFirst().status() == ManagedSavedDataIo.WriteStatus.FAILED
                                && failures.getFirst().snapshot().isEmpty() && failingGate.retryNeeded() && !Files.exists(missingParentFile),
                        "actual I/O failure falsely exposed a durable snapshot");
                var full = budget.reserve(ManagedStoreRegistry.RECORDING, 500_000 - budget.snapshot().usedPhysicalBytes(), true);
                var refused = new CopyOnWriteArrayList<ManagedSavedDataIo.WriteResult>();
                helper.assertTrue(!ManagedSavedDataIo.queue(file.toFile(), snapshot(40), gate, result -> {
                    refused.add(result); throw new IllegalStateException("synthetic rejection consumer failure");
                }) && refused.size() == 1 && refused.getFirst().status() == ManagedSavedDataIo.WriteStatus.REJECTED
                                && refused.getFirst().snapshot().isEmpty(), "FULL did not report one isolated admission rejection");
                helper.assertTrue(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data").getInt("marker") == 20,
                        "FULL callback attempt changed the committed file");
                budget.reconcile(full);
                Files.createDirectories(missingParentFile.getParent()); fence.set(false);
                helper.assertTrue(ManagedSavedDataIo.queue(missingParentFile.toFile(), snapshot(30), failingGate, failures::add), "failed I/O not retryable");
                IOUtilities.withIOWorker(() -> fence.set(true)); phase = 3;
            } else if (phase == 3) {
                if (!fence.get()) return;
                helper.assertTrue(failures.size() == 2 && failures.getLast().status() == ManagedSavedDataIo.WriteStatus.COMMITTED
                                && marker(failures.getLast()) == 30 && !failingGate.retryNeeded(), "retry was not distinguished from failed snapshot");
                helper.assertTrue(NbtIo.readCompressed(missingParentFile, NbtAccounter.unlimitedHeap()).getCompound("data").getInt("marker") == 30,
                        "retry callback did not follow the actual atomic write");
                LegacyRecordingQuota.requireDrained(world, budget); LegacyRecordingQuota.uninstall(world, budget);
                finished = true; helper.succeed();
            }
        }
        private static CompoundTag snapshot(int marker) {
            var tag = new CompoundTag(); tag.putInt("dataVersion", 1); tag.putString("state", "{}"); tag.putInt("marker", marker); return tag;
        }
        private static int marker(ManagedSavedDataIo.WriteResult result) { return result.snapshot().orElseThrow().getCompound("data").getInt("marker"); }
    }
    private static final class Fixture {
        final GameTestHelper helper;
        final Path world, rumorFile, reputationFile;
        final ManagedStoreRegistry registry;
        final WorldRecordingBudget budget;
        final RumorSavedData rumors = new RumorSavedData();
        final ReputationSavedData reputation = new ReputationSavedData(rumors.worldId());
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean workerEntered = new AtomicBoolean(), completed = new AtomicBoolean();
        final AtomicBoolean gameCompleted = new AtomicBoolean();
        WorldRecordingBudget.Reservation full;
        int phase;
        boolean finished;
        Fixture(GameTestHelper helper) throws Exception {
            this.helper = helper;
            Path testRoot = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("recording-quota-fixtures");
            Files.createDirectories(testRoot);
            world = Files.createTempDirectory(testRoot, "saveddata-").toAbsolutePath().normalize();
            Files.createDirectories(world.resolve("data"));
            registry = ManagedStoreRegistry.open(world); budget = new WorldRecordingBudget(registry, 500_000, 1_000);
            rumorFile = registry.root(ManagedStoreRegistry.RUMOR); reputationFile = registry.root(ManagedStoreRegistry.REPUTATION);
            LegacyRecordingQuota.install(world, budget);
            IOUtilities.withIOWorker(() -> {
                workerEntered.set(true);
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
        }
        void tick() throws Exception {
            var lookups = helper.getLevel().getServer().registryAccess();
            if (phase == 0) {
                if (!workerEntered.get()) return;
                rumors.save(rumorFile.toFile(), lookups); reputation.save(reputationFile.toFile(), lookups);
                helper.assertTrue(!rumors.isDirty() && !reputation.isDirty(), "snapshots not queued");
                helper.assertTrue(budget.snapshot().outstandingReservations() == 2 * LegacyRecordingQuota.SAVED_DATA_WRITE_BOUND,
                        "setDirty(false) prematurely released in-flight snapshot reservations");
                rumors.setDirty(); rumors.save(rumorFile.toFile(), lookups);
                helper.assertTrue(budget.snapshot().outstandingReservations() == 3 * LegacyRecordingQuota.SAVED_DATA_WRITE_BOUND,
                        "successive queued snapshots did not reserve separately");
                rumors.setDirty();
                helper.assertTrue(!Files.exists(rumorFile) && !Files.exists(reputationFile), "barrier failed to delay actual I/O");
                IOUtilities.withIOWorker(() -> completed.set(true)); release.countDown(); phase = 1;
            } else if (phase == 1) {
                if (!completed.get()) return;
                helper.assertTrue(Files.isRegularFile(rumorFile) && Files.isRegularFile(reputationFile), "native NBT files missing");
                helper.assertTrue(budget.snapshot().outstandingReservations() == 0, "actual successful I/O did not reconcile reservations");
                helper.assertTrue(rumors.isDirty() && !reputation.isDirty(), "older I/O completion cleared a newer game-thread dirty change");
                CompoundTag rumorNbt = NbtIo.readCompressed(rumorFile, NbtAccounter.unlimitedHeap()).getCompound("data");
                CompoundTag reputationNbt = NbtIo.readCompressed(reputationFile, NbtAccounter.unlimitedHeap()).getCompound("data");
                helper.assertTrue(RumorSavedData.load(rumorNbt, lookups).worldId().equals(rumors.worldId()), "rumor world identity changed");
                helper.assertTrue(ReputationSavedData.load(reputationNbt, lookups).ready(rumors.worldId()), "reputation identity changed");
                helper.assertTrue(budget.snapshot().usedPhysicalBytes() == Files.size(rumorFile) + Files.size(reputationFile),
                        "physical atomic-write result was not measured");
                full = budget.reserve(ManagedStoreRegistry.RECORDING, 500_000 - budget.snapshot().usedPhysicalBytes(), true);
                rumors.setDirty(); rumors.save(rumorFile.toFile(), lookups);
                helper.assertTrue(rumors.isDirty(), "FULL falsely acknowledged a dedicated record save");
                // A normal game SavedData still uses the untouched base implementation at FULL.
                byte[] gameState = new byte[200_000]; new Random(1234).nextBytes(gameState);
                SavedData authoritativeFixture = new SavedData() {
                    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
                        tag.putByteArray("testGameState", gameState); return tag;
                    }
                };
                authoritativeFixture.setDirty(); authoritativeFixture.save(world.resolve("data/mythictrpg_reward_claims.dat").toFile(), lookups);
                IOUtilities.withIOWorker(() -> gameCompleted.set(true)); phase = 2;
            } else if (phase == 2) {
                if (!gameCompleted.get()) return;
                Path authoritative = world.resolve("data/mythictrpg_reward_claims.dat");
                helper.assertTrue(Files.size(authoritative) > 190_000, "ordinary authoritative SavedData was blocked by recording FULL");
                budget.reconcile(full); full = null;
                helper.assertTrue(registry.measure().totalBytes() == Files.size(rumorFile) + Files.size(reputationFile),
                        "authoritative game file incorrectly entered recording quota");
                LegacyRecordingQuota.uninstall(world, budget); finished = true; helper.succeed();
            }
        }
    }
}
