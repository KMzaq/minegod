package com.sande.mythictrpg.quest.reward;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.data.god.*;
import com.sande.mythictrpg.power.CombatPowerPolicy;
import com.sande.mythictrpg.power.CombatPowerPolicyManager;
import com.sande.mythictrpg.power.CombatPowerRuntime;
import com.sande.mythictrpg.power.RewardPowerState;
import com.sande.mythictrpg.quest.reward.blessing.*;
import com.sande.mythictrpg.quest.reward.luck.*;
import io.netty.channel.embedded.EmbeddedChannel;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real claim, login, choice and inventory paths; each snapshot override lives for one synchronous tick. */
@GameTestHolder("mythictrpg_blessings")
@PrefixGameTestTemplate(false)
public final class AdaptiveRewardGameplayGameTests {
    private static final ResourceLocation GOD = id("adaptive_test_god"), FOREIGN = id("adaptive_other_god");
    private static final ResourceLocation STRENGTH = ResourceLocation.withDefaultNamespace("strength");
    private static final ResourceLocation UPGRADE = id("adaptive_test_upgrade");
    private static final ResourceLocation QUANTITY = id("lucky_quantity"), COUNTER = id("lucky_counter"), RARITY = id("lucky_rarity");
    private static final ResourceLocation TAKE = id("take_lucky"), LEAVE = id("take_counter");

    @GameTest(templateNamespace = "mythictrpg_blessings", template = "empty", batch = "adaptive_reward_gameplay", timeoutTicks = 200)
    public static void permanentBaselineExcludesPotionAndNamedAcquisitionNeverRerolls(GameTestHelper h) {
        ready(h, fixture -> {
            var player = fixture.connect(UUID.randomUUID(), "AdaptiveOwner");
            var state = RewardClaimState.get(player.server);
            var baseline = new ResolvedBlessingRewardEntry(new ResolvedBlessing(id("named_baseline"), GOD, GodRank.HIGH,
                    "strength", Optional.of(STRENGTH), 10, 30));
            var baselineResult = RewardClaimService.INSTANCE.issue(player, GOD, source("baseline"), offer(baseline));
            h.assertTrue(baselineResult.succeeded(), "named permanent baseline failed: " + baselineResult.reason());
            h.assertTrue(player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 1200, 16)), "temporary XVII missing");
            var rewardSource = source("upgrade");
            var result = RewardClaimService.INSTANCE.issue(player, GOD, rewardSource, offer(new AdaptiveBlessingRewardEntry(UPGRADE)));
            h.assertTrue(result.succeeded(), "adaptive claim failed: " + result.reason());
            var claim = state.find(result.claimId()).orElseThrow();
            h.assertTrue(claim.automaticRewards().getFirst() instanceof ResolvedBlessingRewardEntry, "placeholder survived delivery");
            var frozen = (ResolvedBlessingRewardEntry) claim.automaticRewards().getFirst();
            h.assertValueEqual(frozen.blessing().level(), 11, "temporary XVII raised permanent X + 1 baseline");
            h.assertValueEqual(player.getEffect(MobEffects.DAMAGE_BOOST).getAmplifier(), 16, "weaker permanent XI replaced active XVII");
            h.assertValueEqual(state.ownedPermanentBlessingLevels(player.getUUID()), Map.of(STRENGTH, 10), "XI ownership absent");
            h.assertValueEqual(state.acquiredBlessingsFor(player.getUUID()).size(), 2, "named sources collapsed");
            h.assertValueEqual(state.blessingIntrinsicPower(player.getUUID()), 70d, "named intrinsic scores were replaced by effect level");

            fixture.blessings(17, 999);
            var replay = RewardClaimService.INSTANCE.issue(player, GOD, rewardSource, offer(new AdaptiveBlessingRewardEntry(UPGRADE)));
            h.assertValueEqual(replay.status(), RewardClaimService.Status.ALREADY_CLAIMED, "receipt replay granted again");
            h.assertValueEqual(state.find(result.claimId()).orElseThrow(), claim, "resource reload changed frozen blessing");
            h.assertValueEqual(state.blessingIntrinsicPower(player.getUUID()), 70d, "reload changed acquired intrinsic score");
            var restored = RewardClaimState.load(state.save(new CompoundTag(), player.registryAccess()), player.registryAccess());
            h.assertTrue(restored.isWritable(), "real acquisition save/load rejected: " + restored.rejectionReason());
            h.assertValueEqual(restored.find(result.claimId()).orElseThrow(), claim, "frozen XI changed after save/load");
            h.assertValueEqual(restored.acquiredBlessingsFor(player.getUUID()), state.acquiredBlessingsFor(player.getUUID()), "named ownership changed after load");

            Items.MILK_BUCKET.finishUsingItem(new ItemStack(Items.MILK_BUCKET), player.serverLevel(), player);
            h.assertFalse(player.hasEffect(MobEffects.DAMAGE_BOOST), "milk did not clear active temporary/permanent effect");
            h.assertValueEqual(state.blessingIntrinsicPower(player.getUUID()), 70d, "milk removed named intrinsic ownership");
            player.server.getCommands().getDispatcher().execute("mythblessing reapply", player.createCommandSourceStack().withPermission(0));
            h.assertValueEqual(player.getEffect(MobEffects.DAMAGE_BOOST).getAmplifier(), 10, "ordinary command reselected instead of restoring frozen XI");
            h.assertTrue(player.getEffect(MobEffects.DAMAGE_BOOST).isInfiniteDuration(), "restored XI is not permanent");

            h.assertFalse(RewardClaimService.INSTANCE.issue(player, FOREIGN, source("foreign"), offer(new AdaptiveBlessingRewardEntry(UPGRADE))).succeeded(),
                    "another god granted a foreign adaptive definition");
            h.assertFalse(RewardClaimService.INSTANCE.issue(player, id("unregistered_god"), source("unknown_god"), offer(new AdaptiveBlessingRewardEntry(UPGRADE))).succeeded(),
                    "unregistered god granted adaptive blessing");
            h.assertFalse(RewardClaimService.INSTANCE.issue(player, GOD, source("missing"), offer(new AdaptiveBlessingRewardEntry(id("missing_definition")))).succeeded(),
                    "missing adaptive definition was accepted");
            h.assertValueEqual(state.acquiredBlessingsFor(player.getUUID()).size(), 2, "rejected source created ownership");
        });
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = "empty", batch = "adaptive_reward_gameplay", timeoutTicks = 200)
    public static void luckyClaimsDeliverComponentsQuantityAndRarityThroughRealInventory(GameTestHelper h) {
        ready(h, fixture -> {
            var player = fixture.connect(UUID.randomUUID(), "LuckyOwner");
            player.addEffect(new MobEffectInstance(MobEffects.LUCK, 1200, 16));
            var source = source("quantity_counter");
            var result = RewardClaimService.INSTANCE.issue(player, GOD, source,
                    offer(new LuckyRewardEntry(QUANTITY), new LuckyRewardEntry(COUNTER)));
            h.assertTrue(result.succeeded(), "lucky bundle failed: " + result.reason());
            var state = RewardClaimState.get(player.server);
            var claim = state.find(result.claimId()).orElseThrow();
            var quantity = (ResolvedLuckyRewardEntry) claim.automaticRewards().get(0);
            var counter = (ResolvedLuckyRewardEntry) claim.automaticRewards().get(1);
            h.assertValueEqual(quantity.selection().level(), 17, "actual luck effect not captured");
            h.assertValueEqual(quantity.selection().reward().count(), 13, "opted-in quantity did not receive XVII bonus");
            h.assertValueEqual(counter.selection().reward().count(), 5, "countermeasure quantity changed without opt-in");
            h.assertValueEqual(count(player, quantity.selection().reward()), 13, "authored item/components were not delivered");
            h.assertValueEqual(count(player, counter.selection().reward()), 5, "countermeasure components/count were not delivered");
            h.assertTrue(RewardPowerState.get(player.server).history(player.getUUID()).orElseThrow().complete(), "normal item delivery damaged acquisition coverage");

            var pool = LuckDefinitions.INSTANCE.find(RARITY).orElseThrow();
            ResourceLocation raritySource = null;
            // Find a reproducible draw crossing the common/rare boundary, instead of accepting a flaky random outcome.
            for (int i = 0; i < 256; i++) {
                var candidateSource = id("rarity_sensitive/" + i);
                long seed = selectionSeed(player.getUUID(), candidateSource, RARITY);
                if (LuckSelector.select(pool, 0, seed, LuckModel.DEFAULT).candidateId().equals("common")
                        && LuckSelector.select(pool, 17, seed, LuckModel.DEFAULT).candidateId().equals("rare")) {
                    raritySource = candidateSource; break;
                }
            }
            h.assertTrue(raritySource != null, "fixture did not contain a luck-sensitive rarity draw");
            h.assertTrue(LuckDefinitions.INSTANCE.model().rewardWeight(1, 5, 17) > LuckDefinitions.INSTANCE.model().rewardWeight(1, 0, 17),
                    "rare quality does not increase weight");
            var rareResult = RewardClaimService.INSTANCE.issue(player, GOD, raritySource, offer(new LuckyRewardEntry(RARITY)));
            h.assertTrue(rareResult.succeeded(), "rare item claim failed: " + rareResult.reason());
            var rare = (ResolvedLuckyRewardEntry) state.find(rareResult.claimId()).orElseThrow().automaticRewards().getFirst();
            h.assertValueEqual(rare.selection().candidateId(), "rare", "live pipeline ignored luck rarity weights");
            h.assertValueEqual(count(player, rare.selection().reward()), 1, "rare component variant did not reach inventory");
            var unreceipted = new ResolvedLuckyRewardEntry(id("unreceipted"), new LuckyRewardSelection(item("gold_ingot", 404, 4), 17, "forged"));
            h.assertFalse(RewardExecutionService.grant(player, GOD, List.of(unreceipted), RewardGrantPurpose.QUEST).granted(), "unreceipted lucky result delivered");
            h.assertValueEqual(count(player, unreceipted.selection().reward()), 0, "unreceipted grant mutated inventory");
            h.assertFalse(RewardClaimService.INSTANCE.issue(player, GOD, source("missing_pool"), offer(new LuckyRewardEntry(id("missing_pool")))).succeeded(),
                    "missing lucky pool was accepted");
            RewardClaimService.INSTANCE.issue(player, GOD, source, offer(new LuckyRewardEntry(QUANTITY), new LuckyRewardEntry(COUNTER)));
            h.assertValueEqual(count(player, quantity.selection().reward()), 13, "receipt replay duplicated lucky item");
            h.assertValueEqual(count(player, counter.selection().reward()), 5, "receipt replay duplicated countermeasure");
        });
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = "empty", batch = "adaptive_reward_gameplay", timeoutTicks = 200)
    public static void offlineQueueFreezesOnLoginAndChoiceIgnoresLaterLuckAndPoolReload(GameTestHelper h) {
        ready(h, fixture -> {
            UUID playerId = UUID.randomUUID();
            var source = source("offline_choice");
            var offer = new ResolvedQuestReward("Frozen lucky choice", List.of(), List.of(
                    new RewardChoiceOption(TAKE, "Lucky", List.of(new LuckyRewardEntry(QUANTITY))),
                    new RewardChoiceOption(LEAVE, "Counter", List.of(new LuckyRewardEntry(COUNTER)))));
            var state = RewardClaimState.get(h.getLevel().getServer());
            h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayer(playerId) == null, "recipient unexpectedly online");
            var queued = RewardClaimService.INSTANCE.queueBatch(h.getLevel().getServer(), GOD, source, Map.of(playerId, offer));
            h.assertTrue(queued.succeeded(), "offline queue failed: " + queued.reason());
            var pending = state.findBySource(playerId, source).orElseThrow();
            h.assertFalse(pending.automaticGranted(), "offline queue marked delivered");
            h.assertTrue(pending.choices().getFirst().rewards().getFirst() instanceof LuckyRewardEntry, "offline queue guessed active luck");
            var queuedRestored = RewardClaimState.load(state.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
            h.assertTrue(queuedRestored.isWritable(), "offline placeholder did not survive persistence");
            h.assertValueEqual(queuedRestored.find(pending.claimId()).orElseThrow(), pending, "offline queue changed on load");

            var player = fixture.connect(playerId, "QueuedLucky");
            var frozen = state.find(pending.claimId()).orElseThrow();
            h.assertTrue(frozen.pendingChoice(), "real login did not resolve queued claim");
            var choice = (ResolvedLuckyRewardEntry) frozen.choices().getFirst().rewards().getFirst();
            h.assertValueEqual(choice.selection().level(), 0, "fresh login captured nonexistent luck");
            h.assertValueEqual(choice.selection().reward().count(), 10, "initial choice quantity incorrect");
            h.assertValueEqual(count(player, choice.selection().reward()), 0, "pending option delivered before selection");
            var restored = RewardClaimState.load(state.save(new CompoundTag(), player.registryAccess()), player.registryAccess());
            h.assertTrue(restored.isWritable(), "frozen choice failed persistence: " + restored.rejectionReason());
            h.assertValueEqual(restored.find(frozen.claimId()).orElseThrow(), frozen, "pending choice rerolled on load");
            player.addEffect(new MobEffectInstance(MobEffects.LUCK, 1200, 16));
            fixture.reloadLuckyQuantity();
            RewardClaimService.INSTANCE.deliverQueued(player);
            h.assertValueEqual(state.find(frozen.claimId()).orElseThrow(), frozen, "delivery retry changed frozen choices");
            var selected = RewardClaimService.INSTANCE.choose(player, frozen.claimId(), TAKE);
            h.assertTrue(selected.succeeded(), "frozen choice grant failed: " + selected.reason());
            h.assertValueEqual(count(player, choice.selection().reward()), 10, "later luck or pool reload altered frozen item/components/count");
            h.assertValueEqual(count(player, item("diamond", 909, 1)), 0, "new pool variant replaced frozen item");
            var repeated = RewardClaimService.INSTANCE.choose(player, frozen.claimId(), TAKE);
            h.assertValueEqual(repeated.status(), RewardClaimService.Status.ALREADY_CLAIMED, "choice replay not idempotent");
            RewardClaimService.INSTANCE.deliverQueued(player);
            h.assertValueEqual(count(player, choice.selection().reward()), 10, "choice or delivery retry duplicated inventory");
        });
    }

    private static int count(ServerPlayer player, NpcRewardEntry reward) {
        var variant = reward.createStack(player.registryAccess());
        return java.util.stream.IntStream.range(0, player.getInventory().getContainerSize()).mapToObj(player.getInventory()::getItem)
                .filter(stack -> ItemStack.isSameItemSameComponents(stack, variant)).mapToInt(ItemStack::getCount).sum();
    }
    private static NpcRewardEntry item(String item, int model, int count) {
        var components = new CompoundTag(); components.putInt("minecraft:custom_model_data", model);
        return new NpcRewardEntry(ResourceLocation.withDefaultNamespace(item), count, components);
    }
    private static ResolvedQuestReward offer(RewardEntry... rewards) { return new ResolvedQuestReward("test", List.of(rewards), List.of()); }
    private static ResourceLocation source(String name) { return id(name + "/" + UUID.randomUUID()); }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mythictrpg", "gameplay_test/" + path); }
    private static long selectionSeed(UUID player, ResourceLocation source, ResourceLocation pool) {
        long result = 0xcbf29ce484222325L;
        for (byte value : (player + "|" + GOD + "|" + source + "|automatic|0|" + pool).getBytes(StandardCharsets.UTF_8)) {
            result ^= value & 255; result *= 0x100000001b3L;
        }
        return result;
    }
    private interface CheckedTest { void run(Fixture fixture) throws Exception; }
    private static void ready(GameTestHelper h, CheckedTest test) {
        h.startSequence().thenWaitUntil(() -> {
            try {
                var ready = CombatPowerRuntime.class.getDeclaredMethod("captureReady", MinecraftServer.class); ready.setAccessible(true);
                h.assertTrue((boolean) ready.invoke(null, h.getLevel().getServer()), "waiting for acquisition fence");
            } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
        }).thenExecute(() -> {
            try (var fixture = new Fixture(h)) { fixture.install(); test.run(fixture); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        }).thenSucceed();
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final Field godField, blessingField, luckField, powerField;
        final Object oldGod, oldBlessings, oldLuck, oldPower;
        final Map<ServerPlayer, EmbeddedChannel> connections = new LinkedHashMap<>();
        Fixture(GameTestHelper helper) throws Exception {
            this.helper = helper;
            godField = field(GodDefinitionManager.class, "snapshot"); blessingField = field(BlessingDefinitions.class, "snapshot");
            luckField = field(LuckDefinitions.class, "snapshot"); powerField = field(CombatPowerPolicyManager.class, "current");
            oldGod = godField.get(GodDefinitionManager.INSTANCE); oldBlessings = blessingField.get(BlessingDefinitions.INSTANCE);
            oldLuck = luckField.get(LuckDefinitions.INSTANCE); oldPower = powerField.get(CombatPowerPolicyManager.INSTANCE);
        }
        void install() throws Exception {
            var original = (GodDefinitionManager.ProgressionSnapshot) oldGod;
            var definitions = new HashMap<>(original.definitions());
            var definition = new GodDefinition(1, Component.literal("Adaptive test"), id("origin"), id("faction"), Set.of(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(GodRank.HIGH));
            definitions.put(GOD, definition); definitions.put(FOREIGN, definition);
            godField.set(GodDefinitionManager.INSTANCE, new GodDefinitionManager.ProgressionSnapshot(definitions,
                    GodUnlockDependencyIndex.build(definitions), GodAppearanceIndex.build(definitions), original.generation() + 1));
            blessings(1, 40);
            var quantity = new LuckyItemPool(QUANTITY, List.of(new LuckyItemCandidate("quantity", item("emerald", 101, 1), 10, 10, 2, 1, true)));
            var counter = new LuckyItemPool(COUNTER, List.of(new LuckyItemCandidate("counter", item("feather", 303, 1), 5, 5, 2, 1, false)));
            var rarity = new LuckyItemPool(RARITY, List.of(
                    new LuckyItemCandidate("common", item("iron_ingot", 201, 1), 1, 1, 0, 1, false),
                    new LuckyItemCandidate("rare", item("diamond", 202, 1), 1, 1, 5, 1, false)));
            luckField.set(LuckDefinitions.INSTANCE, new LuckDefinitions.Snapshot(Map.of(QUANTITY, quantity, COUNTER, counter, RARITY, rarity), LuckModel.DEFAULT));
            var policy = new CombatPowerPolicy("test:adaptive_gameplay", CombatPowerPolicy.Aggregation.SUM_SLOT_BEST,
                    0, 1, 1, 1, 0.5, List.of(), Map.of(), Map.of(), List.of(), CombatPowerPolicyManager.initialProposalModel());
            powerField.set(CombatPowerPolicyManager.INSTANCE, new CombatPowerPolicyManager.Loaded(policy, "READY"));
        }
        void blessings(int increment, double intrinsic) throws Exception {
            var definition = new BlessingDefinition(UPGRADE, GOD, intrinsic,
                    List.of(new BlessingCandidate("strength_upgrade", STRENGTH, BlessingCandidate.Mode.UPGRADE, increment)), BlessingDefinition.Fallback.NONE);
            blessingField.set(BlessingDefinitions.INSTANCE, new BlessingDefinitions.Snapshot(Map.of(UPGRADE, definition),
                    Map.of(GOD, new GodBlessingPolicy(GOD, Map.of(STRENGTH, new GodBlessingPolicy.EffectRule(17, 0)))),
                    Map.of(GodRank.HIGH, new BlessingRankPolicy(GodRank.HIGH, Map.of(STRENGTH, 17)))));
        }
        void reloadLuckyQuantity() throws Exception {
            var pools = new HashMap<>(LuckDefinitions.INSTANCE.snapshot().pools());
            pools.put(QUANTITY, new LuckyItemPool(QUANTITY, List.of(new LuckyItemCandidate("replacement", item("diamond", 909, 1), 64, 64, 5, 1, true))));
            luckField.set(LuckDefinitions.INSTANCE, new LuckDefinitions.Snapshot(pools, new LuckModel(1, 1, 1, 63)));
        }
        ServerPlayer connect(UUID uuid, String name) {
            var cookie = CommonListenerCookie.createInitial(new GameProfile(uuid, name), false);
            var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); var channel = new EmbeddedChannel(connection);
            connections.put(player, channel); NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie); player.setGameMode(GameType.SURVIVAL);
            var history = RewardPowerState.get(player.server).history(uuid).orElseThrow();
            helper.assertTrue(history.complete(), "fresh join did not establish complete acquisition history: " + history.reason());
            return player;
        }
        @Override public void close() throws Exception {
            try {
                for (var entry : connections.entrySet()) {
                    try { entry.getKey().server.getPlayerList().remove(entry.getKey()); }
                    finally { entry.getValue().finishAndReleaseAll(); }
                }
            } finally {
                godField.set(GodDefinitionManager.INSTANCE, oldGod); blessingField.set(BlessingDefinitions.INSTANCE, oldBlessings);
                luckField.set(LuckDefinitions.INSTANCE, oldLuck); powerField.set(CombatPowerPolicyManager.INSTANCE, oldPower);
            }
        }
        private static Field field(Class<?> owner, String name) throws Exception { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
    }
}
