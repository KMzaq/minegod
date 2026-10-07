package com.sande.mythictrpg.quest.reward;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.power.CombatPowerRuntime;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Synthetic grants only. Covers the actual milk, command, logout and PlayerList respawn paths. */
@GameTestHolder("mythictrpg_blessings")
@PrefixGameTestTemplate(false)
public final class PermanentBlessingGameTests {
    private static final String EMPTY = "empty";
    private static final ResourceLocation GOD = id("fortuna"), SPEED = ResourceLocation.parse("minecraft:speed");
    private PermanentBlessingGameTests() { }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = EMPTY, timeoutTicks = 300)
    public static void strictCodecOwnershipMigrationAndPrunableClaims(GameTestHelper helper) {
        BlessingRewardEntry blessing = (BlessingRewardEntry) RewardEntryCodec.parse(JsonParser.parseString(
                "{\"type\":\"blessing\",\"effectId\":\"minecraft:speed\",\"durationTicks\":-1,\"amplifier\":1}")
                .getAsJsonObject(), "fixture");
        helper.assertTrue(blessing.permanent(), "-1 must mean permanent ownership");
        helper.assertValueEqual(RewardEntryCodec.load(RewardEntryCodec.save(blessing)), blessing, "permanent reward NBT");
        CompoundTag fractional = RewardEntryCodec.save(blessing); fractional.putDouble("durationTicks", -1.5);
        expectRejected(helper, () -> RewardEntryCodec.load(fractional), "fractional persisted duration became permanent");
        expectRejected(helper, () -> new BlessingRewardEntry(SPEED, -2, 1), "unsupported negative duration");
        expectRejected(helper, () -> RewardEntryCodec.parse(JsonParser.parseString(
                "{\"type\":\"blessing\",\"effectId\":\"minecraft:instant_health\",\"durationTicks\":-1,\"amplifier\":0}")
                .getAsJsonObject(), "fixture"), "instant permanent blessing accepted");
        helper.assertFalse(RewardExecutionService.validate(List.of(new BlessingRewardEntry(
                ResourceLocation.parse("minecraft:instant_health"), -1, 0)), RewardGrantPurpose.QUEST).allowed(),
                "constructor bypass accepted an instant effect");

        UUID player = UUID.randomUUID();
        var state = new RewardClaimState();
        RewardClaim first = claim(player, id("permanent/first"), blessing, 0);
        state.create(first);
        helper.assertTrue(state.ownedPermanentBlessingLevels(player).isEmpty(), "queued reward already granted ownership");
        state.markAutomaticGranted(first.claimId()); state.markAutomaticGranted(first.claimId());
        helper.assertValueEqual(state.permanentBlessingsFor(player).size(), 1, "automatic receipt repeated ownership");
        var owned = state.permanentBlessingsFor(player).getFirst();
        helper.assertValueEqual(owned.claimId(), Optional.of(first.claimId()), "claim provenance");
        helper.assertValueEqual(owned.sourceId(), first.sourceId(), "source provenance");
        helper.assertValueEqual(owned.godId(), GOD, "God provenance");
        helper.assertTrue(state.ownedPermanentBlessingLevels(UUID.randomUUID()).isEmpty(), "foreign player ownership leaked");
        state.suppressBlessing(player, SPEED);
        for (int i = 1; i <= 4096; i++) {
            RewardClaim disposable = new RewardClaim(UUID.randomUUID(), player, GOD, id("prune/" + i), "fixture",
                    List.of(), List.of(), true, Optional.empty(), i);
            state.create(disposable);
        }
        helper.assertTrue(state.find(first.claimId()).isEmpty(), "fixture did not prune completed receipt");
        helper.assertFalse(state.canCreate(player, first.sourceId()), "pruning reopened permanent reward source");
        CompoundTag saved = state.save(new CompoundTag(), helper.getLevel().registryAccess());
        RewardClaimState restored = RewardClaimState.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(restored.isWritable(), "new state rejected");
        helper.assertValueEqual(restored.permanentBlessingsFor(player), state.permanentBlessingsFor(player), "pruned ownership lost");
        helper.assertTrue(restored.isBlessingSuppressed(player, SPEED), "milk removal state lost on load");

        CompoundTag legacy = new RewardClaimState().save(new CompoundTag(), helper.getLevel().registryAccess());
        legacy.putInt("dataVersion", 3); legacy.remove("permanentBlessings"); legacy.remove("suppressedBlessings");
        var migrated = RewardClaimState.load(legacy, helper.getLevel().registryAccess());
        helper.assertTrue(migrated.isWritable() && migrated.ownedPermanentBlessingLevels(player).isEmpty(),
                "v3 migration inferred permanent ownership or rejected legacy data");
        CompoundTag broken = saved.copy();
        broken.putInt("dataVersion", RewardClaimState.CURRENT_DATA_VERSION + 1);
        var rejected = RewardClaimState.load(broken, helper.getLevel().registryAccess());
        helper.assertFalse(rejected.isWritable(), "future data version accepted");
        helper.assertValueEqual(rejected.save(new CompoundTag(), helper.getLevel().registryAccess()), broken, "rejected source rewritten");
        helper.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = EMPTY, timeoutTicks = 300)
    public static void milkPersistsAcrossLoginCommandAndDeathRestoreWithoutPowerLoss(GameTestHelper helper) throws Exception {
        try (var fixture = new Connected(helper)) {
            ServerPlayer player = fixture.player;
            var state = RewardClaimState.get(player.server);
            ResourceLocation first = id("milk/" + UUID.randomUUID());
            grant(helper, player, first, new BlessingRewardEntry(SPEED, -1, 0));
            var basePower = CombatPowerRuntime.ownedBlessingPower(player);
            helper.assertTrue(basePower.available() && basePower.score() > 0, "authored blessing power unavailable: " + basePower.evidence());
            grant(helper, player, id("same_level/" + UUID.randomUUID()), new BlessingRewardEntry(SPEED, -1, 0));
            helper.assertValueEqual(CombatPowerRuntime.ownedBlessingPower(player).score(), basePower.score(), "duplicate source doubled power");
            ResourceLocation upgrade = id("upgrade/" + UUID.randomUUID());
            grant(helper, player, upgrade, new BlessingRewardEntry(SPEED, -1, 1));
            grant(helper, player, upgrade, new BlessingRewardEntry(SPEED, -1, 1));
            helper.assertValueEqual(state.permanentBlessingsFor(player.getUUID()).size(), 3, "replayed receipt added ownership");
            helper.assertValueEqual(state.ownedPermanentBlessingLevels(player.getUUID()), Map.of(SPEED, 1), "same effect did not select highest level");
            assertInfinite(helper, player, 1);
            double power = CombatPowerRuntime.ownedBlessingPower(player).score();
            helper.assertTrue(power > basePower.score(), "higher blessing level did not increase authored score");

            drinkMilk(player);
            helper.assertFalse(player.hasEffect(MobEffects.MOVEMENT_SPEED), "milk did not remove infinite effect");
            helper.assertTrue(state.isBlessingSuppressed(player.getUUID(), SPEED), "milk removal not persisted");
            helper.assertValueEqual(CombatPowerRuntime.ownedBlessingPower(player).score(), power, "milk reduced owned blessing power");
            var saved = state.save(new CompoundTag(), player.registryAccess());
            helper.assertTrue(RewardClaimState.load(saved, player.registryAccess()).isBlessingSuppressed(player.getUUID(), SPEED),
                    "saved milk removal lost");
            player = fixture.reconnect();
            helper.assertFalse(player.hasEffect(MobEffects.MOVEMENT_SPEED), "login restored milk-removed blessing");
            helper.assertTrue(state.isBlessingSuppressed(player.getUUID(), SPEED), "login cleared removal record");
            grant(helper, player, id("after_milk/" + UUID.randomUUID()), new BlessingRewardEntry(SPEED, -1, 0));
            helper.assertFalse(player.hasEffect(MobEffects.MOVEMENT_SPEED), "another source bypassed explicit milk removal");

            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 2, 3));
            int command = player.server.getCommands().getDispatcher().execute("mythblessing reapply", player.createCommandSourceStack().withPermission(0));
            helper.assertValueEqual(command, 1, "self command denied an ordinary player");
            helper.assertValueEqual(player.getEffect(MobEffects.MOVEMENT_SPEED).getAmplifier(), 3, "reapply downgraded stronger potion");
            helper.assertFalse(state.isBlessingSuppressed(player.getUUID(), SPEED), "command did not clear suppression");
            // Tick the actual merged effect to exercise vanilla hidden-effect restoration without waiting on player simulation.
            player.getEffect(MobEffects.MOVEMENT_SPEED).tick(player, () -> { });
            player.getEffect(MobEffects.MOVEMENT_SPEED).tick(player, () -> { });
            assertInfinite(helper, player, 1);
            helper.assertValueEqual(CombatPowerRuntime.ownedBlessingPower(player).score(), power, "temporary potion altered owned component");

            drinkMilk(player);
            fixture.player = player.server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            player = fixture.player;
            assertInfinite(helper, player, 1);
            helper.assertFalse(state.isBlessingSuppressed(player.getUUID(), SPEED), "death respawn retained removal suppression");
            helper.assertValueEqual(CombatPowerRuntime.ownedBlessingPower(player).score(), power, "respawn changed owned power");
            drinkMilk(player);
            fixture.player = player.server.getPlayerList().respawn(player, true, Entity.RemovalReason.CHANGED_DIMENSION);
            helper.assertFalse(fixture.player.hasEffect(MobEffects.MOVEMENT_SPEED), "End return restored milk removal");
            helper.assertTrue(state.isBlessingSuppressed(fixture.player.getUUID(), SPEED), "End return cleared removal record");
            player = fixture.player;
            grant(helper, player, id("absorption/" + UUID.randomUUID()), new BlessingRewardEntry(ResourceLocation.parse("minecraft:absorption"), -1, 0));
            player.setAbsorptionAmount(1.0F);
            PermanentBlessingRuntime.reapply(player);
            helper.assertValueEqual(player.getAbsorptionAmount(), 1.0F, "repeated reapply replenished an already-active absorption effect");
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = EMPTY, timeoutTicks = 300)
    public static void selectedAndAuthoredTableOwnershipCannotBeForgedOrRepeated(GameTestHelper helper) {
        try (var fixture = new Connected(helper)) {
            ServerPlayer player = fixture.player;
            var state = RewardClaimState.get(player.server);
            var blessing = new BlessingRewardEntry(SPEED, -1, 0);
            helper.assertFalse(RewardExecutionService.grant(player, GOD, List.of(blessing), RewardGrantPurpose.AI_ACTION).granted(),
                    "uncommitted direct grant created permanent ownership");
            var yes = new RewardChoiceOption(id("permanent_choice"), "Permanent", List.of(blessing));
            var no = new RewardChoiceOption(id("temporary_choice"), "Temporary", List.of(new BlessingRewardEntry(SPEED, 100, 0)));
            var result = RewardClaimService.INSTANCE.issue(player, GOD, id("choice/" + UUID.randomUUID()),
                    new ResolvedQuestReward("fixture", List.of(), List.of(yes, no)));
            helper.assertTrue(result.succeeded(), "choice creation failed: " + result.reason());
            UUID claimId = state.pendingFor(player.getUUID()).getFirst().claimId();
            helper.assertTrue(state.ownedPermanentBlessingLevels(player.getUUID()).isEmpty(), "unselected reward granted ownership");
            var foreign = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "ForeignBlessing"));
            helper.assertFalse(RewardClaimService.INSTANCE.choose(foreign, claimId, yes.optionId()).succeeded(), "foreign player selected reward");
            helper.assertTrue(RewardClaimService.INSTANCE.choose(player, claimId, yes.optionId()).succeeded(), "selection failed");
            RewardClaimService.INSTANCE.choose(player, claimId, no.optionId());
            helper.assertValueEqual(state.permanentBlessingsFor(player.getUUID()).size(), 1, "selection repeated ownership");

            var manager = NpcRewardTableManager.INSTANCE;
            var previous = manager.prepare(player.server.getResourceManager(), null);
            ResourceLocation tableId = id("test/permanent_table");
            var table = new NpcRewardTable(tableId, GOD, Map.of(1, new NpcRewardTier(1, List.of(blessing)),
                    2, new NpcRewardTier(2, List.of(blessing, new AffinityRewardEntry(75)))));
            try {
                manager.apply(new NpcRewardTableManager.Prepared(Map.of(tableId, table), Map.of()), null, null);
                helper.assertFalse(NpcRewardGrantService.grant(player, id("demeter"), tableId, 1, RewardGrantPurpose.AI_ACTION).granted(),
                        "wrong God gained table authority");
                helper.assertFalse(NpcRewardGrantService.grant(player, GOD, tableId, 2, RewardGrantPurpose.AI_ACTION).granted(),
                        "AI_ACTION affinity restriction changed");
                helper.assertValueEqual(state.permanentBlessingsFor(player.getUUID()).size(), 1, "rejected table changed ownership");
                helper.assertTrue(NpcRewardGrantService.grant(player, GOD, tableId, 1, RewardGrantPurpose.AI_ACTION).granted(), "authored AI table grant failed");
                NpcRewardGrantService.grant(player, GOD, tableId, 1, RewardGrantPurpose.AI_ACTION);
                helper.assertValueEqual(state.permanentBlessingsFor(player.getUUID()).size(), 2, "same table duplicated ownership");
                var tableOwned = state.permanentBlessingsFor(player.getUUID()).stream()
                        .filter(value -> value.source() == RewardClaimState.BlessingSource.NPC_TABLE).findFirst().orElseThrow();
                helper.assertTrue(tableOwned.claimId().isEmpty(), "direct table fabricated quest receipt");
                helper.assertValueEqual(tableOwned.sourceId(), id("test/permanent_table/tier_1"), "table provenance missing");
                drinkMilk(player);
                NpcRewardGrantService.grant(player, GOD, tableId, 1, RewardGrantPurpose.AI_ACTION);
                helper.assertFalse(player.hasEffect(MobEffects.MOVEMENT_SPEED), "table replay defeated milk removal");
                CompoundTag saved = state.save(new CompoundTag(), player.registryAccess());
                helper.assertTrue(RewardClaimState.load(saved, player.registryAccess()).isWritable(), "mixed ownership failed round trip");
                ListTag entitlements = saved.getList("permanentBlessings", Tag.TAG_COMPOUND);
                for (int i = entitlements.size() - 1; i >= 0; i--)
                    if (entitlements.getCompound(i).hasUUID("claimId") && entitlements.getCompound(i).getUUID("claimId").equals(claimId)) entitlements.remove(i);
                helper.assertFalse(RewardClaimState.load(saved, player.registryAccess()).isWritable(), "receipt without permanent entitlement accepted");
            } finally { manager.apply(previous, null, null); }
            helper.succeed();
        }
    }

    private static void grant(GameTestHelper helper, ServerPlayer player, ResourceLocation source, BlessingRewardEntry reward) {
        var result = RewardClaimService.INSTANCE.issue(player, GOD, source, new ResolvedQuestReward("fixture", List.of(reward), List.of()));
        helper.assertTrue(result.succeeded(), "permanent grant failed: " + result.reason());
    }
    private static RewardClaim claim(UUID player, ResourceLocation source, BlessingRewardEntry reward, long time) {
        return new RewardClaim(UUID.randomUUID(), player, GOD, source, "fixture", List.of(reward), List.of(), false, Optional.empty(), time);
    }
    private static void drinkMilk(ServerPlayer player) {
        Items.MILK_BUCKET.finishUsingItem(new ItemStack(Items.MILK_BUCKET), player.serverLevel(), player);
    }
    private static void assertInfinite(GameTestHelper helper, ServerPlayer player, int amplifier) {
        var effect = player.getEffect(MobEffects.MOVEMENT_SPEED);
        helper.assertTrue(effect != null && effect.isInfiniteDuration() && effect.getAmplifier() == amplifier,
                "owned highest permanent effect not active");
    }
    private static void expectRejected(GameTestHelper helper, Runnable operation, String message) {
        try { operation.run(); helper.fail(message); } catch (IllegalArgumentException expected) { }
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mythictrpg", path); }

    private static final class Connected implements AutoCloseable {
        final GameTestHelper helper;
        final UUID playerId = UUID.randomUUID();
        final List<io.netty.channel.embedded.EmbeddedChannel> channels = new ArrayList<>();
        ServerPlayer player;
        Connected(GameTestHelper helper) { this.helper = helper; player = connect(); }
        private ServerPlayer connect() {
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                    new com.mojang.authlib.GameProfile(playerId, "BlessingOwner"), false);
            var connected = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channels.add(new io.netty.channel.embedded.EmbeddedChannel(connection));
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            connected.server.getPlayerList().placeNewPlayer(connection, connected, cookie);
            return connected;
        }
        ServerPlayer reconnect() { player.server.getPlayerList().remove(player); player = connect(); return player; }
        @Override public void close() {
            player.server.getPlayerList().remove(player);
            channels.forEach(io.netty.channel.embedded.EmbeddedChannel::finishAndReleaseAll);
        }
    }
}
