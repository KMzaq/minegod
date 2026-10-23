package com.sande.mythictrpg.prototype;

import com.sande.mythictrpg.accessory.AccessoryRewardBridge;
import com.sande.mythictrpg.accessory.AccessoryRuntime;
import com.sande.mythictrpg.skills.CombatCastRuntime;
import com.sande.mythictrpg.skills.LearnedSkillRuntime;
import com.sande.mythictrpg.skills.SkillCastEvents;
import com.sande.mythictrpg.skills.SkillExecutor;
import com.sande.mythictrpg.skills.SkillMana;
import com.sande.mythictrpg.weaponskill.WeaponSkillRuntime;
import com.sande.mythictrpg.weaponskill.WeaponSkillState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Actual prototype item authentication, paid projectile, real health hits and accessory snapshot. */
@GameTestHolder("mythictrpg_prototype_catalog")
@PrefixGameTestTemplate(false)
public final class PrototypeAccessoryIntegrationGameTests {
    @GameTest(templateNamespace = "mythictrpg_prototype_catalog", template = "empty", batch = "prototype_accessory_integration", timeoutTicks = 200)
    public static void realWaveThreeHitsReduceDodgeOnceUsingApprovalKnotSnapshot(GameTestHelper h) {
        var state = new State(h);
        h.startSequence().thenWaitUntil(() -> h.assertTrue(PrototypeCatalogGameTests.captureReady(h), "power acquisition fence"))
                .thenExecute(() -> {
                    try {
                        state.fixture = new PrototypePracticeGameTests.Fixture(h);
                        state.player = state.fixture.connect(0);
                        var grant = PrototypeGrantService.grantKit(state.player.createCommandSourceStack().withPermission(2));
                        h.assertTrue(grant.succeeded(), "integration prototype ownership: " + grant.message());
                        h.assertValueEqual(state.fixture.command(state.player, "arena"), 1, "integration arena registration");
                        state.targets = PrototypeCombatAccess.targets(state.player).stream()
                                .sorted(java.util.Comparator.comparingDouble(id -> state.fixture.target(id).distanceToSqr(state.player))).toList();
                        var rights = AccessoryRuntime.entitlements(state.player);
                        var knots = rights.stream().filter(right -> right.definitionId().equals(PrototypeCatalog.BLADE_KNOT)).toList();
                        state.stones = rights.stream().filter(right -> right.definitionId().equals(PrototypeCatalog.CLEAR_MANA_STONE)).toList();
                        h.assertTrue(knots.size() == 2 && state.stones.size() == 2, "OP kit did not provide separate legitimate accessory copies");
                        h.assertTrue(AccessoryRuntime.equip(state.player, 0, knots.get(0).id())
                                && AccessoryRuntime.equip(state.player, 1, knots.get(1).id()), "two legal knots did not equip");
                        h.assertTrue(AccessoryRuntime.linkTarget(state.player, PrototypeCatalog.SHORT_DODGE), "actual learned dodge did not accept link target");
                        var expected = PrototypeCatalog.item(PrototypeCatalog.WAVE_BLADE).reward().createStack(state.player.registryAccess());
                        int slot = -1;
                        for (int i = 0; i < 9; i++) if (ItemStack.isSameItemSameComponents(state.player.getInventory().getItem(i), expected)) { slot = i; break; }
                        h.assertTrue(slot >= 0, "granted wave blade missing from inventory hotbar");
                        state.player.getInventory().selected = slot;
                        h.assertValueEqual(WeaponSkillRuntime.heldSkill(state.player).orElseThrow().id(), PrototypeCatalog.BLADE_WAVE,
                                "actual acquired variant did not bind the real projectile definition");
                        long now = SkillMana.now(state.player);
                        String cooldown = SkillExecutor.cooldownKey(SkillCastEvents.Source.LEARNED, PrototypeCatalog.SHORT_DODGE);
                        h.assertValueEqual(WeaponSkillState.get(state.player.server).trySpend(state.player.getUUID(), cooldown, 30, 200, now),
                                WeaponSkillState.Result.SUCCESS, "authoritative dodge cooldown seed");
                        h.assertValueEqual(LearnedSkillRuntime.remainingTicks(state.player, PrototypeCatalog.SHORT_DODGE), 200L, "dodge initial cooldown");
                        state.originalDodgeEnd = now + 200;
                        state.observer = new Observer(state.player);
                        NeoForge.EVENT_BUS.register(state.observer);
                    } catch (RuntimeException | Error failure) { state.close(); throw failure; }
                }).thenIdle(1).thenExecute(() -> {
                    try {
                        h.assertValueEqual(WeaponSkillRuntime.cast(state.player, state.player.getInventory().selected, PrototypeCatalog.BLADE_WAVE),
                                WeaponSkillRuntime.Result.SUCCESS, "actual right-click weapon skill path rejected");
                        h.assertTrue(state.observer.starts == 1 && state.observer.hits.isEmpty(), "wave hit before its projectile windup");
                        h.assertTrue(AccessoryRuntime.equip(state.player, 0, state.stones.get(0).id())
                                && AccessoryRuntime.equip(state.player, 1, state.stones.get(1).id()), "combat accessory swap rejected");
                        h.assertTrue(AccessoryRuntime.linkTarget(state.player, null), "link target clear after approval");
                        h.assertTrue(AccessoryRuntime.bonus(state.player, com.sande.mythictrpg.accessory.AccessoryEffect.Kind.LINK_KNOT) == 0
                                && AccessoryRuntime.maximumMana(state.player) == 280, "live equipment did not change after snapshot");
                    } catch (RuntimeException | Error failure) { state.close(); throw failure; }
                }).thenIdle(16).thenExecute(() -> {
                    try {
                        h.assertTrue(state.fixture.target(state.targets.get(0)).getHealth() == 192
                                && state.fixture.target(state.targets.get(1)).getHealth() == 196
                                && state.fixture.target(state.targets.get(2)).getHealth() == 196
                                && state.fixture.target(state.targets.get(3)).getHealth() == 200,
                                "actual registered wave did not hit first three targets with 8/4/4");
                        h.assertTrue(state.observer.hits.size() == 3 && state.observer.finished.size() == 1
                                && state.observer.finished.getFirst().hitCount() == 3 && !state.observer.finished.getFirst().cancelled(),
                                "real projectile did not publish one complete three-hit cast");
                        long now = SkillMana.now(state.player);
                        long end = now + LearnedSkillRuntime.remainingTicks(state.player, PrototypeCatalog.SHORT_DODGE);
                        h.assertValueEqual(state.originalDodgeEnd - end, 40L, "two snapshotted knots must subtract 40 total, not once for each hit");
                        h.assertTrue(!CombatCastRuntime.locked(state.player), "completed wave retained recovery lock");
                    } finally { state.close(); }
                }).thenSucceed();
    }

    private static final class Observer {
        final ServerPlayer player;
        int starts;
        final List<SkillCastEvents.Hit> hits = new ArrayList<>();
        final List<SkillCastEvents.Finished> finished = new ArrayList<>();
        Observer(ServerPlayer player) { this.player = player; }
        @SubscribeEvent public void start(SkillCastEvents.Start event) {
            if (event.context().player() == player && event.context().skillId().equals(PrototypeCatalog.BLADE_WAVE)) starts++;
        }
        @SubscribeEvent public void hit(SkillCastEvents.Hit event) {
            if (event.context().player() == player && event.context().skillId().equals(PrototypeCatalog.BLADE_WAVE)) hits.add(event);
        }
        @SubscribeEvent public void finished(SkillCastEvents.Finished event) {
            if (event.context().player() == player && event.context().skillId().equals(PrototypeCatalog.BLADE_WAVE)) finished.add(event);
        }
    }

    private static final class State implements AutoCloseable {
        final GameTestHelper h;
        PrototypePracticeGameTests.Fixture fixture;
        ServerPlayer player;
        Observer observer;
        List<UUID> targets;
        List<AccessoryRewardBridge.Entitlement> stones;
        long originalDodgeEnd;
        boolean closed;
        State(GameTestHelper h) { this.h = h; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (observer != null) NeoForge.EVENT_BUS.unregister(observer);
            if (player != null) CombatCastRuntime.cancel(player);
            if (fixture != null) fixture.close();
        }
    }
}
