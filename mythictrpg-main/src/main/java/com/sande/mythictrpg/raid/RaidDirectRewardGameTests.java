package com.sande.mythictrpg.raid;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.data.god.*;
import com.sande.mythictrpg.power.CombatPowerPolicy;
import com.sande.mythictrpg.power.CombatPowerPolicyManager;
import com.sande.mythictrpg.power.CombatPowerRuntime;
import com.sande.mythictrpg.power.RewardPowerState;
import com.sande.mythictrpg.quest.reward.*;
import com.sande.mythictrpg.quest.reward.blessing.*;
import com.sande.mythictrpg.quest.reward.luck.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.lang.reflect.Field;
import java.util.*;

/** Actual recruitment, boss death and reward receipts; direct rewards remain distinct from quest completion. */
@GameTestHolder("mythictrpg_blessings")
@PrefixGameTestTemplate(false)
public final class RaidDirectRewardGameTests {
    private static final ResourceLocation GOD = id("direct_reward_god"), RAID = id("direct_reward_raid"), ARENA = id("direct_reward_arena");
    private static final ResourceLocation BLESSING = id("direct_reward_blessing"), POOL = id("direct_reward_pool");
    private static final ResourceLocation STRENGTH = ResourceLocation.withDefaultNamespace("strength");

    @GameTest(templateNamespace = "mythictrpg_blessings", template = "empty", batch = "raid_direct_rewards", timeoutTicks = 200)
    public static void realVictoryKeepsDirectBlessingOriginAndAppliesLuckyItemPool(GameTestHelper h) {
        final Fixture fixture;
        try { fixture = new Fixture(h); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
        h.onEachTick(() -> {
            if (fixture.done) return;
            try { fixture.tick(); }
            catch (Exception | AssertionError failure) {
                try { fixture.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                h.fail("Direct raid rewards: " + failure);
            }
        });
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;
        final Field godField, blessingField, luckField, powerField, raidField;
        final Object oldGod, oldBlessings, oldLuck, oldPower, oldRaids;
        ServerPlayer player;
        EmbeddedChannel channel;
        RaidState.Attempt attempt;
        RaidDefinition definition;
        RaidDefinition.Arena arena;
        NpcRewardEntry item;
        Vec3 initialPosition;
        int phase, ticks;
        boolean done;

        Fixture(GameTestHelper h) throws Exception {
            this.h = h;
            godField = field(GodDefinitionManager.class, "snapshot"); blessingField = field(BlessingDefinitions.class, "snapshot");
            luckField = field(LuckDefinitions.class, "snapshot"); powerField = field(CombatPowerPolicyManager.class, "current");
            raidField = field(RaidCatalog.class, "snapshot");
            oldGod = godField.get(GodDefinitionManager.INSTANCE); oldBlessings = blessingField.get(BlessingDefinitions.INSTANCE);
            oldLuck = luckField.get(LuckDefinitions.INSTANCE); oldPower = powerField.get(CombatPowerPolicyManager.INSTANCE);
            oldRaids = raidField.get(RaidCatalog.INSTANCE);
        }

        void tick() throws Exception {
            h.assertTrue(++ticks < 180, "raid reward fixture timed out before clean settlement");
            if (phase == 0) {
                var ready = CombatPowerRuntime.class.getDeclaredMethod("captureReady", MinecraftServer.class); ready.setAccessible(true);
                if (!(boolean) ready.invoke(null, h.getLevel().getServer())) return;
                setUp(); phase = 1; return;
            }
            if (phase == 1) {
                h.assertTrue(!attempt.terminal(), "raid failed before combat: " + attempt.reason);
                if (attempt.status != RaidState.Status.ACTIVE) return;
                var boss = RaidRuntime.living(h.getLevel().getEntity(attempt.bossId));
                h.assertTrue(boss != null && arena.bounds().contains(player.position()), "real raid did not spawn boss/enter arena");
                if (boss instanceof Mob mob) mob.setNoAi(true);
                h.assertTrue(boss.hurt(player.damageSources().playerAttack(player), 1000), "valid participant could not defeat real boss");
                phase = 2;
                RaidRuntime.tick(new ServerTickEvent.Post(() -> true, player.server));
            }
            if (phase == 2) {
                h.assertTrue(attempt.status != RaidState.Status.FAILED && attempt.status != RaidState.Status.CANCELLED,
                        "boss death did not settle as victory: " + attempt.reason);
                if (attempt.status != RaidState.Status.SUCCEEDED) return;
                verifyPayout(); close(); h.succeed();
            }
        }

        void setUp() throws Exception {
            var previous = (GodDefinitionManager.ProgressionSnapshot) oldGod;
            var gods = new HashMap<>(previous.definitions());
            gods.put(GOD, new GodDefinition(1, Component.literal("Reward test God"), id("origin"), id("faction"), Set.of(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(GodRank.HIGH)));
            godField.set(GodDefinitionManager.INSTANCE, new GodDefinitionManager.ProgressionSnapshot(gods,
                    GodUnlockDependencyIndex.build(gods), GodAppearanceIndex.build(gods), previous.generation() + 1));
            blessingField.set(BlessingDefinitions.INSTANCE, new BlessingDefinitions.Snapshot(
                    Map.of(BLESSING, new BlessingDefinition(BLESSING, GOD, 25,
                            List.of(new BlessingCandidate("strength", STRENGTH, BlessingCandidate.Mode.FLOOR, 2)), BlessingDefinition.Fallback.ONLY_OWNERSHIP)),
                    Map.of(GOD, new GodBlessingPolicy(GOD, Map.of(STRENGTH, new GodBlessingPolicy.EffectRule(17, 0)))),
                    Map.of(GodRank.HIGH, new BlessingRankPolicy(GodRank.HIGH, Map.of(STRENGTH, 17)))));
            var components = new CompoundTag(); components.putInt("minecraft:custom_model_data", 716);
            item = new NpcRewardEntry(ResourceLocation.withDefaultNamespace("emerald"), 1, components);
            var pool = new LuckyItemPool(POOL, List.of(new LuckyItemCandidate("victory_item", item, 10, 10, 2, 1, true)));
            luckField.set(LuckDefinitions.INSTANCE, new LuckDefinitions.Snapshot(Map.of(POOL, pool), LuckModel.DEFAULT));
            var policy = new CombatPowerPolicy("test:direct_raid_reward", CombatPowerPolicy.Aggregation.SUM_SLOT_BEST,
                    0, 1, 1, 1, 0.5, List.of(new CombatPowerPolicy.ItemRule(item.itemId(), item.components(), "other", 0, true)),
                    Map.of(), Map.of(), List.of(), CombatPowerPolicyManager.initialProposalModel());
            powerField.set(CombatPowerPolicyManager.INSTANCE, new CombatPowerPolicyManager.Loaded(policy, "READY"));
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "DirectRaidOwner"), false);
            player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection);
            NetworkRegistry.configureMockConnection(connection); player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
            player.setGameMode(GameType.SURVIVAL);
            h.assertTrue(RewardPowerState.get(player.server).history(player.getUUID()).orElseThrow().complete(), "fresh player power history unavailable");
            player.addEffect(new MobEffectInstance(MobEffects.LUCK, 1200, 16));

            var center = h.absolutePos(new BlockPos(2, 2, 2));
            var anchor = Vec3.atBottomCenterOf(center);
            for (int x = -3; x <= 6; x++) for (int z = -3; z <= 6; z++) {
                var floor = center.offset(x, -1, z);
                h.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                for (int y = 1; y <= 3; y++) h.getLevel().setBlockAndUpdate(floor.above(y), Blocks.AIR.defaultBlockState());
            }
            arena = RaidDefinition.parseArena(ARENA, JsonParser.parseString(RaidGameTests.arenaJson(anchor, h.getLevel().dimension().location())).getAsJsonObject());
            player.setPos(anchor.add(14, 0, 0)); initialPosition = player.position();
            h.getLevel().setBlockAndUpdate(BlockPos.containing(initialPosition).below(), Blocks.STONE.defaultBlockState());
            JsonObject json = JsonParser.parseString(RaidGameTests.fixtureJson()).getAsJsonObject();
            json.getAsJsonArray("arenas").set(0, new JsonPrimitive(ARENA.toString()));
            json.addProperty("rewardGodId", GOD.toString()); json.addProperty("queueTimeoutTicks", 180); json.addProperty("combatTimeoutTicks", 180);
            json.add("rewards", JsonParser.parseString("[{\"type\":\"adaptive_blessing\",\"definitionId\":\"" + BLESSING
                    + "\"},{\"type\":\"lucky_item\",\"poolId\":\"" + POOL + "\"}]").getAsJsonArray());
            definition = RaidDefinition.parse(RAID, json);
            h.assertTrue(definition.rewards().get(0) instanceof AdaptiveBlessingRewardEntry
                    && definition.rewards().get(1) instanceof LuckyRewardEntry, "raid static parser rejected dynamic reward entries");
            verifyDefinitionGuards(json);
            catalog(definition);
            var created = RaidRuntime.INSTANCE.create(player, RAID);
            h.assertTrue(created.succeeded(), "valid direct reward raid failed creation: " + created.message());
            attempt = RaidState.get(player.server).find(created.attemptId().orElseThrow()).orElseThrow();
            var validLuck = LuckDefinitions.INSTANCE.snapshot();
            try {
                luckField.set(LuckDefinitions.INSTANCE, new LuckDefinitions.Snapshot(Map.of(), LuckModel.DEFAULT));
                h.assertFalse(RaidRuntime.INSTANCE.start(player, attempt.id).succeeded(), "start ignored a removed reward pool");
                h.assertValueEqual(attempt.status, RaidState.Status.FORMING, "failed reward preflight consumed queue state");
            } finally { luckField.set(LuckDefinitions.INSTANCE, validLuck); }
            h.assertTrue(RaidRuntime.INSTANCE.start(player, attempt.id).succeeded(), "leader could not start valid reward raid");
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, player.server));
        }

        void verifyDefinitionGuards(JsonObject valid) throws Exception {
            var missing = valid.deepCopy();
            missing.getAsJsonArray("rewards").get(1).getAsJsonObject().addProperty("poolId", id("missing_pool").toString());
            catalog(RaidDefinition.parse(RAID, missing));
            h.assertFalse(RaidRuntime.INSTANCE.canCreate(player, RAID).succeeded(), "missing lucky pool reached recruitment");
            var foreign = valid.deepCopy(); foreign.addProperty("rewardGodId", id("foreign_god").toString());
            catalog(RaidDefinition.parse(RAID, foreign));
            h.assertFalse(RaidRuntime.INSTANCE.canCreate(player, RAID).succeeded(), "foreign blessing God reached recruitment");
            var missingBlessing = valid.deepCopy();
            missingBlessing.getAsJsonArray("rewards").get(0).getAsJsonObject().addProperty("definitionId", id("missing_blessing").toString());
            catalog(RaidDefinition.parse(RAID, missingBlessing));
            h.assertFalse(RaidRuntime.INSTANCE.canCreate(player, RAID).succeeded(), "missing adaptive definition reached recruitment");
            var injected = valid.deepCopy(); injected.getAsJsonArray("rewards").get(0).getAsJsonObject().addProperty("origin", "QUEST");
            boolean rejected = false;
            try { RaidDefinition.parse(RAID, injected); } catch (IllegalArgumentException expected) { rejected = true; }
            h.assertTrue(rejected, "authored raid reward was allowed to override authoritative origin");
            h.assertTrue(RaidState.get(player.server).membership(player.getUUID()).isEmpty(), "read-only preflight created an attempt");
        }

        void verifyPayout() throws Exception {
            var state = RewardClaimState.get(player.server);
            var source = ResourceLocation.fromNamespaceAndPath("mythictrpg", "raid/" + attempt.id);
            var claim = state.findBySource(player.getUUID(), source).orElseThrow();
            h.assertTrue(claim.fullyClaimed(), "victory receipt was not delivered");
            h.assertValueEqual(claim.origin(), RewardOrigin.RAID_DIRECT, "direct raid receipt was labelled as a quest");
            var acquired = state.acquiredBlessingsFor(player.getUUID());
            h.assertValueEqual(acquired.size(), 1, "direct blessing ownership missing/duplicated");
            h.assertValueEqual(acquired.getFirst().origin(), RewardOrigin.RAID_DIRECT, "named blessing lost direct raid origin");
            h.assertFalse(state.hasNonRaidBlessing(player.getUUID()), "direct raid blessing satisfied non-raid acquisition route");
            h.assertValueEqual(player.getEffect(MobEffects.DAMAGE_BOOST).getAmplifier(), 1, "direct adaptive blessing did not apply Strength II");
            var lucky = (ResolvedLuckyRewardEntry) claim.automaticRewards().get(1);
            h.assertValueEqual(lucky.selection().level(), 17, "direct raid ignored actual luck level");
            h.assertValueEqual(lucky.selection().reward().count(), 13, "direct raid quantity bonus not resolved");
            h.assertValueEqual(itemCount(), 13, "direct lucky item/components did not reach actual inventory");
            h.assertTrue(player.position().distanceToSqr(initialPosition) < 4, "winner was not returned before reward settlement");
            var load = RewardClaimState.class.getDeclaredMethod("load", CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
            load.setAccessible(true);
            var restored = (RewardClaimState) load.invoke(null, state.save(new CompoundTag(), player.registryAccess()), player.registryAccess());
            h.assertTrue(restored.isWritable(), "direct reward origin failed save/load: " + restored.rejectionReason());
            h.assertValueEqual(restored.find(claim.claimId()).orElseThrow().origin(), RewardOrigin.RAID_DIRECT, "receipt origin changed after load");
            h.assertFalse(restored.hasNonRaidBlessing(player.getUUID()), "saved direct reward became non-raid blessing");
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, player.server)); RewardClaimService.INSTANCE.deliverQueued(player);
            h.assertValueEqual(itemCount(), 13, "victory/queued delivery retry duplicated lucky reward");
            h.assertValueEqual(state.acquiredBlessingsFor(player.getUUID()).size(), 1, "victory retry duplicated blessing");

            // A quest completed because of a boss kill still comes through the quest service, even with a raid-like source path.
            var questBlessing = new ResolvedBlessingRewardEntry(new ResolvedBlessing(id("quest_after_raid"), GOD, GodRank.HIGH,
                    "quest_strength", Optional.of(STRENGTH), 3, 10));
            var questSource = ResourceLocation.fromNamespaceAndPath("mythictrpg", "raid/quest_after_boss/" + UUID.randomUUID());
            var quest = RewardClaimService.INSTANCE.issue(player, GOD, questSource, new ResolvedQuestReward("quest", List.of(questBlessing), List.of()));
            h.assertTrue(quest.succeeded(), "separate quest blessing failed: " + quest.reason());
            h.assertValueEqual(state.find(quest.claimId()).orElseThrow().origin(), RewardOrigin.QUEST, "quest completion was relabelled by source path");
            h.assertTrue(state.hasNonRaidBlessing(player.getUUID()), "real quest-origin blessing did not satisfy non-raid route");
            h.assertTrue(state.acquiredBlessingsFor(player.getUUID()).stream().anyMatch(value -> value.origin() == RewardOrigin.QUEST),
                    "quest blessing origin was not retained independently");
        }

        int itemCount() {
            var expected = item.createStack(player.registryAccess());
            return java.util.stream.IntStream.range(0, player.getInventory().getContainerSize()).mapToObj(player.getInventory()::getItem)
                    .filter(stack -> ItemStack.isSameItemSameComponents(stack, expected)).mapToInt(ItemStack::getCount).sum();
        }
        void catalog(RaidDefinition raid) throws Exception { raidField.set(RaidCatalog.INSTANCE, new RaidCatalog.Snapshot(Map.of(RAID, raid), Map.of(ARENA, arena))); }
        @Override public void close() throws Exception {
            if (done) return; done = true;
            try {
                if (attempt != null) {
                    if (!attempt.terminal()) RaidRuntime.INSTANCE.cancel(player, attempt.id);
                    if (attempt.terminal()) { RaidState.get(player.server).attempts.remove(attempt.id); RaidState.get(player.server).changed(); }
                }
                if (player != null && player.connection != null) player.server.getPlayerList().remove(player);
            } finally {
                if (channel != null) channel.finishAndReleaseAll();
                godField.set(GodDefinitionManager.INSTANCE, oldGod); blessingField.set(BlessingDefinitions.INSTANCE, oldBlessings);
                luckField.set(LuckDefinitions.INSTANCE, oldLuck); powerField.set(CombatPowerPolicyManager.INSTANCE, oldPower);
                raidField.set(RaidCatalog.INSTANCE, oldRaids);
            }
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mythictrpg", "raid_reward_test/" + path); }
}
