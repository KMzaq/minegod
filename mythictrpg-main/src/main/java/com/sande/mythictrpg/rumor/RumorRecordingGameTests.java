package com.sande.mythictrpg.rumor;

import com.sande.mythictrpg.recording.server.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.*;

/** Real commit/failure boundary in isolated temporary worlds; never touches the running world's rumor file. */
@GameTestHolder("mythictrpg_recording")
@PrefixGameTestTemplate(false)
public final class RumorRecordingGameTests {
    private RumorRecordingGameTests() { }
    @GameTest(templateNamespace = "mythictrpg_recording", template = "empty", timeoutTicks = 600)
    public static void rumorMetadataFollowsAtomicCommitAndFinalSave(GameTestHelper helper) {
        try {
            Fixture fixture = new Fixture(helper);
            helper.onEachTick(() -> {
                if (fixture.finished) return;
                try { fixture.tick(); }
                catch (Exception | AssertionError failure) {
                    fixture.release.countDown(); fixture.finished = true;
                    helper.fail("Rumor durable metadata fixture: " + failure);
                }
            });
        } catch (Exception failure) { helper.fail("Rumor durable metadata setup: " + failure); }
    }
    private static final class Fixture {
        final GameTestHelper helper;
        final Path world, file, failedFile;
        final WorldRecordingBudget budget;
        final RumorSavedData data = new RumorSavedData(), failed = new RumorSavedData();
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean entered = new AtomicBoolean(), fence = new AtomicBoolean();
        final UUID player = UUID.randomUUID(), bird = UUID.randomUUID(), root = UUID.randomUUID();
        static final String GOD = "mythictrpg:demeter";
        RumorRecordingState.DurableView committed;
        long lastCursor;
        int phase;
        boolean finished;
        Fixture(GameTestHelper helper) throws Exception {
            this.helper = helper;
            Path fixtures = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("recording-quota-fixtures");
            world = Files.createTempDirectory(Files.createDirectories(fixtures), "rumor-metadata-").toAbsolutePath().normalize();
            Files.createDirectories(world.resolve("data")); var registry = ManagedStoreRegistry.open(world);
            file = registry.root(ManagedStoreRegistry.RUMOR); budget = new WorldRecordingBudget(registry, 500_000, 1_000);
            LegacyRecordingQuota.install(world, budget);
            failedFile = world.resolve("separate-failure-world/data/mythictrpg_memory_rumor_v1.dat");
            IOUtilities.withIOWorker(() -> {
                entered.set(true);
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
        }
        void tick() throws Exception {
            var registries = helper.getLevel().getServer().registryAccess();
            if (phase == 0) {
                if (!entered.get()) return;
                data.recordingEnabled(true); data.save(file.toFile(), registries);
                helper.assertTrue(data.recordingSnapshot().isEmpty(), "queue admission falsely acknowledged baseline");
                var ledger = testLedger(data);
                ledger.bindCourier(player, bird);
                ledger.observe(root, player, bird, Set.of(player), "관측된 일", Set.of(GOD), Set.of(player));
                ledger.publish(root, "전달된 소문", ""); ledger.deliver(ledger.pending().getFirst()); data.setDirty();
                // Same call vanilla makes on shutdown. It must queue the final dirty state behind the blocked older save.
                data.save(file.toFile(), registries);
                helper.assertTrue(data.recordingSnapshot().isEmpty() && !Files.exists(file), "newer queued snapshot falsely acknowledged");
                helper.assertTrue(budget.snapshot().outstandingReservations() == 2 * LegacyRecordingQuota.SAVED_DATA_WRITE_BOUND,
                        "normal final save lost behind old in-flight save");
                IOUtilities.withIOWorker(() -> fence.set(true)); release.countDown(); phase = 1;
            } else if (phase == 1) {
                if (!fence.get()) return;
                committed = data.recordingSnapshot().orElseThrow(); lastCursor = committed.metadata().cursor();
                helper.assertTrue(lastCursor == 4 && committed.root(root).isPresent() && committed.receipt(root, GOD, 1).isPresent(),
                        "durable callback missed final exact root/claim/receipt snapshot");
                var nbt = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data");
                helper.assertTrue(RumorSavedData.load(nbt, registries).recordingSnapshot().orElseThrow().equals(committed),
                        "restart differs from exact committed metadata and state");
                helper.assertTrue(budget.snapshot().outstandingReservations() == 0, "metadata write quota not reconciled");
                var full = budget.reserve(ManagedStoreRegistry.RECORDING, 500_000 - budget.snapshot().usedPhysicalBytes(), true);
                var before = data.save(new CompoundTag(), registries);
                helper.assertTrue(!testLedger(data).revoke(root), "FULL allowed unpersistable gameplay mutation");
                helper.assertTrue(data.save(new CompoundTag(), registries).equals(before), "rejected game draft advanced metadata");
                budget.reconcile(full);
                data.recordingEnabled(false); helper.assertTrue(testLedger(data).revoke(root), "OFF blocked legitimate revocation");
                data.setDirty(); helper.assertTrue(data.recordingSnapshot().orElseThrow().equals(committed), "live revoke is not committed checkpoint");
                data.save(file.toFile(), registries);
                failed.recordingEnabled(true); failed.save(failedFile.toFile(), registries);
                fence.set(false); IOUtilities.withIOWorker(() -> fence.set(true)); phase = 2;
            } else if (phase == 2) {
                if (!fence.get()) return;
                var revoked = data.recordingSnapshot().orElseThrow();
                helper.assertTrue(revoked.metadata().cursor() > lastCursor && revoked.root(root).orElseThrow().claimRevision() == 2,
                        "OFF claim revocation not durable");
                helper.assertTrue(failed.recordingSnapshot().isEmpty() && failed.isDirty() && !Files.exists(failedFile),
                        "failed I/O falsely acknowledged baseline or lost retry");
                Files.createDirectories(failedFile.getParent()); failed.save(failedFile.toFile(), registries);
                fence.set(false); IOUtilities.withIOWorker(() -> fence.set(true)); phase = 3;
            } else if (phase == 3) {
                if (!fence.get()) return;
                helper.assertTrue(failed.recordingSnapshot().orElseThrow().metadata().cursor() == 0 && !failed.isDirty(),
                        "successful baseline retry did not become durable");
                LegacyRecordingQuota.requireDrained(world, budget); LegacyRecordingQuota.uninstall(world, budget);
                finished = true; helper.succeed();
            }
        }
        /** Fixture-only access avoids binding these isolated SavedData instances to the real test world's store path. */
        private static RumorLedger testLedger(RumorSavedData data) throws Exception {
            var field = RumorSavedData.class.getDeclaredField("ledger"); field.setAccessible(true); return (RumorLedger) field.get(data);
        }
    }
}
