package com.sande.mythictrpg.raid;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import com.sande.mythictrpg.quest.reward.ResolvedQuestReward;
import com.sande.mythictrpg.quest.reward.RewardClaimService;
import com.sande.mythictrpg.quest.reward.RewardClaimState;
import com.sande.mythictrpg.raid.trial.SealedGuardianEntity;
import com.sande.mythictrpg.raid.trial.TrialRaidEntities;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exercises installed trial definitions and entity registration; never replaces the global raid catalog. */
@GameTestHolder("mythictrpg_raid_trial")
@PrefixGameTestTemplate(false)
public final class TrialRaidGameTests {
    private static final ResourceLocation RAID = ResourceLocation.parse("mythictrpg:trial/sealed_guardian");
    private static final ResourceLocation ARENA = ResourceLocation.parse("mythictrpg:trial/sealed_court");

    private TrialRaidGameTests() { }

    // One lifecycle fixture owns the one authored trial slot; its subchecks cannot race each other.
    @GameTest(templateNamespace = "mythictrpg_raid", template = "empty", timeoutTicks = 200)
    public static void authoredArenaOwnershipCancellationAndRestart(GameTestHelper helper) {
        var channels = new ArrayList<EmbeddedChannel>();
        ServerPlayer leader = connect(helper, "TrialLeader", channels);
        ServerPlayer outsider = connect(helper, "TrialObserver", channels);
        var createdAttempts = new ArrayList<UUID>();
        ServerLevel trial = leader.server.getLevel(TrialRaidArenaService.DIMENSION);
        BlockPos decoration = new BlockPos(5, 80, 3);
        BlockState previousDecoration = null;
        BlockPos seal = TrialRaidArenaService.SEAL_ANCHORS.getFirst();
        BlockState previousSeal = null;
        try {
            helper.assertTrue(trial != null, "installed trial dimension is missing");
            RaidDefinition definition = RaidCatalog.INSTANCE.find(RAID).orElseThrow();
            helper.assertTrue(definition.arenaIds().contains(ARENA), "installed trial raid references a different arena");
            helper.assertTrue(definition.boss().entityType().filter(id -> id.equals(ResourceLocation.parse("mythictrpg:sealed_guardian"))).isPresent(),
                    "installed trial raid does not use the registered native guardian");
            var prepared = TrialRaidArenaService.prepare(leader.server);
            helper.assertTrue(prepared.success(), "trial arena preparation failed: " + prepared.message());
            helper.assertTrue(TrialRaidArenaService.isReady(leader.server), "prepared arena is not ready");

            previousDecoration = trial.getBlockState(decoration);
            trial.setBlockAndUpdate(decoration, Blocks.EMERALD_BLOCK.defaultBlockState());
            helper.assertTrue(TrialRaidArenaService.prepare(leader.server).success(), "safe existing arena edit made preparation fail");
            helper.assertTrue(trial.getBlockState(decoration).is(Blocks.EMERALD_BLOCK), "repeat preparation overwrote existing decoration");
            previousSeal = trial.getBlockState(seal);
            trial.setBlockAndUpdate(seal, Blocks.AIR.defaultBlockState());
            helper.assertTrue(!TrialRaidArenaService.isReady(leader.server), "missing essential seal was accepted as ready");
            helper.assertTrue(!TrialRaidArenaService.prepare(leader.server).success(), "damaged prepared arena was silently regenerated");
            helper.assertTrue(trial.getBlockState(seal).isAir(), "failed preparation overwrote the missing seal edit");
            trial.setBlockAndUpdate(seal, previousSeal);
            trial.setBlockAndUpdate(decoration, previousDecoration);
            helper.assertTrue(TrialRaidArenaService.isReady(leader.server), "restored essential blocks did not restore readiness");
            assertBossSpaceProtection(helper, leader, trial);

            // Initialize runtime recovery before creating the fixture, rather than aborting a new active cast.
            tick(leader);
            Vec3 original = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 2, 2))).add(12, 0, 0);
            leader.teleportTo(helper.getLevel(), original.x, original.y, original.z, 17, 6);
            acknowledgeTeleport(leader, channels.getFirst());
            outsider.setPos(original.add(4, 0, 0));
            RaidState.Attempt first = start(helper, leader, createdAttempts, channels.getFirst());
            SealedGuardianEntity boss = guardian(helper, trial, first);
            helper.assertValueEqual(boss.getType(), TrialRaidEntities.SEALED_GUARDIAN.get(), "runtime spawned a placeholder entity type");
            helper.assertTrue(first.id.equals(RaidRuntime.raidTag(boss)), "guardian is not owned by its raid attempt");
            helper.assertTrue(leader.serverLevel() == trial && first.arena.bounds().contains(leader.position()), "leader did not enter the authored trial arena");
            helper.assertTrue(TrialRaidArenaService.isReady(leader.server), "active owned guardian incorrectly obstructed arena block readiness");
            assertProtectedDamage(helper, leader, outsider, boss);
            helper.assertTrue(!RaidRuntime.INSTANCE.join(outsider, first.id).succeeded(), "active authored roster admitted a late outsider");
            assertPatternsAndSeals(helper, leader, outsider, trial, first, boss, channels.get(1));

            helper.assertTrue(RaidRuntime.INSTANCE.cancel(leader, first.id).succeeded(), "leader could not cancel active trial");
            helper.assertValueEqual(first.status, RaidState.Status.CANCELLED, "cancel did not complete owned entity cleanup");
            helper.assertTrue(boss.isRemoved(), "cancel left a live guardian behind");
            helper.assertTrue(SealedGuardianEncounter.view(first.id) == null, "cancel retained cast or boss-bar state");
            assertReturned(helper, leader, original, channels.getFirst());
            helper.assertTrue(RaidState.get(leader.server).arenaAvailable(first.arena), "cancel kept the authored arena lease");

            RaidState.Attempt interrupted = start(helper, leader, createdAttempts, channels.getFirst());
            SealedGuardianEntity interruptedBoss = guardian(helper, trial, interrupted);
            helper.assertTrue(!interrupted.id.equals(first.id), "second trial reused the old attempt UUID");
            assertShieldTimeout(helper, leader, outsider, interrupted, interruptedBoss);
            CompoundTag activeSnapshot = RaidState.get(leader.server).save(new CompoundTag(), leader.registryAccess());
            RaidState restored = RaidState.load(activeSnapshot, leader.registryAccess());
            helper.assertTrue(restored.ready(), "installed trial active snapshot failed to load");
            RaidState.Attempt restoredAttempt = restored.find(interrupted.id).orElseThrow();
            helper.assertValueEqual(restoredAttempt.status, RaidState.Status.ACTIVE, "active trial did not survive NBT round trip");
            helper.assertValueEqual(restoredAttempt.definition.id(), RAID, "snapshot lost installed trial definition identity");
            helper.assertValueEqual(restoredAttempt.arena.id(), ARENA, "snapshot lost installed trial arena identity");
            RaidRuntime.INSTANCE.simulateRuntimeRestartForTesting(leader.server, interrupted.id);
            helper.assertValueEqual(interrupted.status, RaidState.Status.FAILED, "interrupted trial resumed instead of failing safely");
            helper.assertValueEqual(interrupted.reason, "SERVER_RESTART", "interrupted trial used a different termination path");
            helper.assertTrue(interruptedBoss.isRemoved(), "restart cleanup left the old guardian alive");
            helper.assertTrue(SealedGuardianEncounter.view(interrupted.id) == null, "restart cleanup retained encounter state");
            assertReturned(helper, leader, original, channels.getFirst());
            helper.assertTrue(RaidState.get(leader.server).arenaAvailable(interrupted.arena), "restart cleanup kept the trial arena lease");
            tick(leader);
            helper.assertValueEqual(interrupted.status, RaidState.Status.FAILED, "later tick resurrected interrupted combat");

            RaidState.Attempt victory = start(helper, leader, createdAttempts, channels.getFirst());
            SealedGuardianEntity victoryBoss = guardian(helper, trial, victory);
            helper.assertTrue(victory.definition.rewards().isEmpty(), "trial fixture unexpectedly authors a progression reward");
            var pendingSource = ResourceLocation.parse("mythictrpg:test_trial_pending/" + UUID.randomUUID());
            var pendingGod = ResourceLocation.parse("mythictrpg:demeter");
            helper.assertTrue(GodDefinitionManager.INSTANCE.find(pendingGod).isPresent(), "pending reward fixture God is not installed");
            var pendingReward = new ResolvedQuestReward("Unrelated fixture reward",
                    List.of(new NpcRewardEntry(ResourceLocation.parse("minecraft:amethyst_shard"), 1)), List.of());
            var queued = RewardClaimService.INSTANCE.queueBatch(leader.server, pendingGod, pendingSource,
                    Map.of(leader.getUUID(), pendingReward));
            helper.assertTrue(queued.succeeded(), "unrelated pending item reward could not be queued: " + queued.reason());
            var pendingClaim = RewardClaimState.get(leader.server).findBySource(leader.getUUID(), pendingSource).orElseThrow();
            helper.assertTrue(!pendingClaim.automaticGranted(), "queued fixture reward was already delivered before trial victory");
            int inventoryBeforeVictory = leader.getInventory().countItem(Items.AMETHYST_SHARD);
            helper.assertTrue(victoryBoss.hurt(leader.damageSources().playerAttack(leader), 10_000), "participant's lethal hit was rejected");
            tick(leader);
            helper.assertValueEqual(victory.status, RaidState.Status.SUCCEEDED, "actual trial boss death did not settle victory");
            helper.assertTrue(SealedGuardianEncounter.view(victory.id) == null, "victory retained cast or boss-bar state");
            assertReturned(helper, leader, original, channels.getFirst());
            var rewardSource = ResourceLocation.parse("mythictrpg:raid/" + victory.id);
            helper.assertTrue(RewardClaimState.get(leader.server).findBySource(leader.getUUID(), rewardSource).isEmpty(),
                    "empty trial rewards created an implicit God reward receipt");
            helper.assertTrue(!RewardClaimState.get(leader.server).findBySource(leader.getUUID(), pendingSource).orElseThrow().automaticGranted(),
                    "reward-free trial victory delivered an unrelated pending quest reward");
            helper.assertValueEqual(leader.getInventory().countItem(Items.AMETHYST_SHARD), inventoryBeforeVictory,
                    "reward-free trial victory changed inventory by delivering an unrelated item");
            tick(leader);
            helper.assertValueEqual(victory.status, RaidState.Status.SUCCEEDED, "repeated tick changed settled trial victory");
            helper.assertTrue(RewardClaimState.get(leader.server).findBySource(leader.getUUID(), rewardSource).isEmpty(),
                    "repeated trial settlement issued an unintended reward");
            var stillPending = RewardClaimState.get(leader.server).findBySource(leader.getUUID(), pendingSource).orElseThrow();
            helper.assertTrue(!stillPending.automaticGranted() && stillPending.claimId().equals(pendingClaim.claimId()),
                    "repeated trial settlement consumed or replaced the unrelated pending receipt");
            helper.assertValueEqual(leader.getInventory().countItem(Items.AMETHYST_SHARD), inventoryBeforeVictory,
                    "repeated trial settlement delivered an unrelated pending item");
            helper.succeed();
        } catch (RuntimeException failure) {
            helper.fail("Installed trial lifecycle failed: " + failure.getMessage());
        } finally {
            for (UUID id : createdAttempts) {
                RaidState.get(leader.server).find(id).filter(attempt -> !attempt.terminal())
                        .ifPresent(attempt -> RaidRuntime.INSTANCE.cancelAdministrative(leader.server, id));
            }
            if (trial != null) {
                if (previousSeal != null) trial.setBlockAndUpdate(seal, previousSeal);
                if (previousDecoration != null) trial.setBlockAndUpdate(decoration, previousDecoration);
            }
            leader.server.getPlayerList().remove(leader);
            outsider.server.getPlayerList().remove(outsider);
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        }
    }

    private static void assertPatternsAndSeals(GameTestHelper helper, ServerPlayer leader,
            ServerPlayer outsider, ServerLevel trial, RaidState.Attempt attempt, SealedGuardianEntity boss,
            EmbeddedChannel outsiderChannel) {
        leader.setGameMode(GameType.SURVIVAL);
        leader.setHealth(leader.getMaxHealth());
        leader.setAbsorptionAmount(0);
        leader.invulnerableTime = 0;
        Vec3 insideSweep = attempt.arena.bossSpawn().add(5, 0, 0);
        leader.setPos(insideSweep);
        outsider.teleportTo(trial, insideSweep.x, insideSweep.y, insideSweep.z, 0, 0);
        acknowledgeTeleport(outsider, outsiderChannel);
        helper.assertTrue(!leader.isChangingDimension() && !outsider.isChangingDimension(), "mock clients did not acknowledge arena entry");
        float before = leader.getHealth(), outsiderBefore = outsider.getHealth();
        long castAt = SealedGuardianEncounter.view(attempt.id).nextAttack();
        SealedGuardianEncounter.tick(leader.server, attempt, boss, castAt);
        helper.assertTrue(SealedGuardianEncounter.view(attempt.id).casting(), "first sweep did not enter its telegraph");
        helper.assertValueEqual(leader.getHealth(), before, "sweep damaged a participant during its telegraph");
        SealedGuardianEncounter.tick(leader.server, attempt, boss, castAt + 39);
        helper.assertValueEqual(leader.getHealth(), before, "sweep hit before the authored windup deadline");
        SealedGuardianEncounter.tick(leader.server, attempt, boss, castAt + 40);
        float afterHit = leader.getHealth();
        helper.assertTrue(afterHit < before, "server-owned sweep never damaged its valid participant");
        helper.assertValueEqual(outsider.getHealth(), outsiderBefore, "sweep damaged an outsider at the same target position");
        leader.invulnerableTime = 0;
        SealedGuardianEncounter.tick(leader.server, attempt, boss, castAt + 40);
        SealedGuardianEncounter.tick(leader.server, attempt, boss, castAt + 41);
        helper.assertValueEqual(leader.getHealth(), afterHit, "same cast repeated damage after invulnerability was removed");

        long now = castAt + 42;
        leader.setHealth(leader.getMaxHealth());
        leader.setPos(TrialRaidArenaService.ENTRY);
        boss.setHealth(boss.getMaxHealth() * .64f);
        SealedGuardianEncounter.tick(leader.server, attempt, boss, now++);
        helper.assertTrue(SealedGuardianEncounter.view(attempt.id).shielding() && boss.shielded(), "65-percent phase did not raise the shield");
        helper.assertTrue(!SealedGuardianEncounter.view(attempt.id).casting(), "shield transition retained the interrupted sweep");
        float shieldHealth = boss.getHealth();
        helper.assertTrue(!boss.hurt(leader.damageSources().playerAttack(leader), 10), "shielded guardian accepted participant damage");
        helper.assertValueEqual(boss.getHealth(), shieldHealth, "shielded damage changed guardian health");

        Vec3 firstSeal = chargePosition(0);
        outsider.setPos(firstSeal);
        outsider.setShiftKeyDown(true);
        for (int i = 0; i < SealedGuardianEncounter.SEAL_CHARGE_TICKS; i++)
            SealedGuardianEncounter.tick(leader.server, attempt, boss, now++);
        helper.assertValueEqual(SealedGuardianEncounter.view(attempt.id).releasedSeals(), 0, "outsider crouching released an encounter seal");
        outsider.setShiftKeyDown(false);
        outsider.teleportTo(helper.getLevel(), 0, 100, 0, 0, 0);
        acknowledgeTeleport(outsider, outsiderChannel);

        leader.setPos(firstSeal);
        leader.setShiftKeyDown(true);
        for (int i = 0; i < 20; i++) SealedGuardianEncounter.tick(leader.server, attempt, boss, now++);
        leader.setShiftKeyDown(false);
        SealedGuardianEncounter.tick(leader.server, attempt, boss, now++);
        leader.setShiftKeyDown(true);
        for (int i = 0; i < 20; i++) SealedGuardianEncounter.tick(leader.server, attempt, boss, now++);
        helper.assertValueEqual(SealedGuardianEncounter.view(attempt.id).releasedSeals(), 0, "interrupted charge accumulated across a crouch break");
        for (int i = 0; i < 20; i++) SealedGuardianEncounter.tick(leader.server, attempt, boss, now++);
        helper.assertValueEqual(SealedGuardianEncounter.view(attempt.id).releasedSeals(), 1, "continuous solo charge did not release the first seal");
        for (int sealIndex = 1; sealIndex < 3; sealIndex++) {
            leader.setPos(chargePosition(sealIndex));
            for (int i = 0; i < SealedGuardianEncounter.SEAL_CHARGE_TICKS; i++)
                SealedGuardianEncounter.tick(leader.server, attempt, boss, now++);
            helper.assertValueEqual(SealedGuardianEncounter.view(attempt.id).releasedSeals(), sealIndex + 1, "solo sequential seal did not release " + sealIndex);
        }
        leader.setShiftKeyDown(false);
        var released = SealedGuardianEncounter.view(attempt.id);
        helper.assertTrue(released.phase() == 2 && !released.shielding() && !boss.shielded(), "three seals did not end the shield and enter phase two");
        helper.assertValueEqual(boss.pose(), SealedGuardianEntity.STAGGER, "successful seal release did not expose the guardian");
        helper.assertTrue(!released.casting(), "successful release retained the shield's pending bombardment");
        long releaseTick = now - 1;
        boss.setHealth(boss.getMaxHealth() * .29f);
        SealedGuardianEncounter.tick(leader.server, attempt, boss, releaseTick + 99);
        helper.assertValueEqual(SealedGuardianEncounter.view(attempt.id).phase(), 2, "low health skipped the promised stagger window");
        helper.assertTrue(!SealedGuardianEncounter.view(attempt.id).casting(), "guardian attacked during its stagger window");
        SealedGuardianEncounter.tick(leader.server, attempt, boss, releaseTick + 100);
        helper.assertValueEqual(SealedGuardianEncounter.view(attempt.id).phase(), 3, "30-percent phase did not begin after the stagger window");
    }

    private static void assertBossSpaceProtection(GameTestHelper helper, ServerPlayer leader, ServerLevel trial) {
        for (BlockPos obstruction : List.of(new BlockPos(0, 83, 0), new BlockPos(-1, 81, 0))) {
            BlockState previous = trial.getBlockState(obstruction);
            try {
                trial.setBlockAndUpdate(obstruction, Blocks.STONE.defaultBlockState());
                helper.assertTrue(!TrialRaidArenaService.isReady(leader.server), "guardian body obstruction was accepted: " + obstruction);
                helper.assertTrue(!TrialRaidArenaService.prepare(leader.server).success(), "prepare accepted obstructed guardian body: " + obstruction);
                helper.assertTrue(trial.getBlockState(obstruction).is(Blocks.STONE), "prepare overwrote an existing guardian-space edit");
            } finally {
                trial.setBlockAndUpdate(obstruction, previous);
            }
            helper.assertTrue(TrialRaidArenaService.isReady(leader.server), "restoring guardian clearance did not restore readiness");
        }
    }

    private static Vec3 chargePosition(int sealIndex) {
        return Vec3.atBottomCenterOf(TrialRaidArenaService.SEAL_ANCHORS.get(sealIndex)).add(0, 0, 1.5);
    }

    private static void assertShieldTimeout(GameTestHelper helper, ServerPlayer leader, ServerPlayer outsider,
            RaidState.Attempt attempt, SealedGuardianEntity boss) {
        leader.setHealth(leader.getMaxHealth());
        leader.setAbsorptionAmount(0);
        leader.invulnerableTime = 0;
        leader.setShiftKeyDown(false);
        leader.setPos(TrialRaidArenaService.ENTRY);
        float before = leader.getHealth(), outsiderBefore = outsider.getHealth();
        boss.setHealth(boss.getMaxHealth() * .64f);
        long shieldStart = leader.server.overworld().getGameTime() + 1;
        SealedGuardianEncounter.tick(leader.server, attempt, boss, shieldStart);
        helper.assertTrue(SealedGuardianEncounter.view(attempt.id).shielding(), "timeout fixture did not enter the shield");
        long timeout = shieldStart + SealedGuardianEncounter.SHIELD_TICKS;
        SealedGuardianEncounter.tick(leader.server, attempt, boss, timeout);
        var failedShield = SealedGuardianEncounter.view(attempt.id);
        helper.assertTrue(failedShield.phase() == 2 && !failedShield.shielding() && !boss.shielded(),
                "expired shield did not expose the guardian and advance phase two");
        helper.assertValueEqual(failedShield.releasedSeals(), 0, "unoccupied timeout fixture released a seal");
        float afterPulse = leader.getHealth();
        helper.assertTrue(afterPulse < before, "expired shield did not apply its actual failure pulse");
        helper.assertValueEqual(outsider.getHealth(), outsiderBefore, "shield failure pulse damaged an outsider");
        leader.invulnerableTime = 0;
        SealedGuardianEncounter.tick(leader.server, attempt, boss, timeout);
        SealedGuardianEncounter.tick(leader.server, attempt, boss, timeout + 1);
        helper.assertValueEqual(leader.getHealth(), afterPulse, "expired shield repeated its one-use failure pulse");
    }

    private static RaidState.Attempt start(GameTestHelper helper, ServerPlayer leader, List<UUID> attempts,
            EmbeddedChannel channel) {
        var created = RaidRuntime.INSTANCE.create(leader, RAID);
        helper.assertTrue(created.succeeded(), "installed trial creation failed: " + created.message());
        UUID id = created.attemptId().orElseThrow();
        attempts.add(id);
        helper.assertTrue(RaidRuntime.INSTANCE.start(leader, id).succeeded(), "installed trial could not enter the queue");
        tick(leader);
        acknowledgeTeleport(leader, channel);
        RaidState.Attempt attempt = RaidState.get(leader.server).find(id).orElseThrow();
        helper.assertValueEqual(attempt.status, RaidState.Status.ACTIVE, "installed trial did not become ACTIVE: " + attempt.reason);
        return attempt;
    }

    private static SealedGuardianEntity guardian(GameTestHelper helper, ServerLevel level, RaidState.Attempt attempt) {
        var entity = level.getEntity(attempt.bossId);
        helper.assertTrue(entity instanceof SealedGuardianEntity, "raid boss is not the native sealed guardian");
        return (SealedGuardianEntity) entity;
    }

    private static void assertProtectedDamage(GameTestHelper helper, ServerPlayer leader,
            ServerPlayer outsider, SealedGuardianEntity boss) {
        var outsiderHitsBoss = new LivingIncomingDamageEvent(boss,
                new DamageContainer(boss.damageSources().playerAttack(outsider), 1));
        RaidProtection.damage(outsiderHitsBoss);
        helper.assertTrue(outsiderHitsBoss.isCanceled(), "outsider was permitted to damage the authored guardian");
        var bossHitsOutsider = new LivingIncomingDamageEvent(outsider,
                new DamageContainer(boss.damageSources().mobAttack(boss), 1));
        RaidProtection.damage(bossHitsOutsider);
        helper.assertTrue(bossHitsOutsider.isCanceled(), "owned boss attack could damage an outsider");
        var outsiderHitsLeader = new LivingIncomingDamageEvent(leader,
                new DamageContainer(leader.damageSources().playerAttack(outsider), 1));
        RaidProtection.damage(outsiderHitsLeader);
        helper.assertTrue(outsiderHitsLeader.isCanceled(), "outside player could interfere with the active participant");
        var leaderHitsOutsider = new LivingIncomingDamageEvent(outsider,
                new DamageContainer(outsider.damageSources().playerAttack(leader), 1));
        RaidProtection.damage(leaderHitsOutsider);
        helper.assertTrue(leaderHitsOutsider.isCanceled(), "participant could damage an unrelated player");
        var leaderHitsBoss = new LivingIncomingDamageEvent(boss,
                new DamageContainer(boss.damageSources().playerAttack(leader), 1));
        RaidProtection.damage(leaderHitsBoss);
        helper.assertTrue(!leaderHitsBoss.isCanceled(), "valid roster attack was rejected by ownership protection");
    }

    private static void assertReturned(GameTestHelper helper, ServerPlayer leader, Vec3 original, EmbeddedChannel channel) {
        acknowledgeTeleport(leader, channel);
        helper.assertTrue(leader.serverLevel() == helper.getLevel() && leader.position().distanceToSqr(original) < 4,
                "trial did not return the participant to the original dimension and position");
        helper.assertTrue(RaidState.get(leader.server).membership(leader.getUUID()).isEmpty(), "terminated trial retained participant membership");
    }

    private static void tick(ServerPlayer player) {
        RaidRuntime.tick(new ServerTickEvent.Post(() -> true, player.server));
    }

    private static ServerPlayer connect(GameTestHelper helper, String name, List<EmbeddedChannel> channels) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        channels.add(channel);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        acknowledgeTeleport(player, channel);
        // ServerPlayer starts with 60 ticks of vanilla spawn immunity. Advance only this mock's
        // normal tick hook; synthetic encounter time alone does not age a connected player's grace.
        for (int i = 0; i < 60; i++) player.tick();
        return player;
    }

    /** The same vanilla ACK handler a client uses; no immunity flags or product protection are bypassed. */
    private static void acknowledgeTeleport(ServerPlayer player, EmbeddedChannel channel) {
        channel.runPendingTasks();
        ClientboundPlayerPositionPacket latest = null;
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            if (outbound instanceof ClientboundPlayerPositionPacket position) latest = position;
            ReferenceCountUtil.release(outbound);
        }
        if (latest != null) player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(latest.getId()));
        if (player.isChangingDimension()) throw new IllegalStateException("Mock client did not receive its dimension teleport packet");
    }
}
