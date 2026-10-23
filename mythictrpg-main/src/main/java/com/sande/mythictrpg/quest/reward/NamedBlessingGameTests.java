package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.quest.reward.blessing.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NamedBlessingGameTests {
    private static final ResourceLocation GOD = id("fortuna");
    private static final ResourceLocation STRENGTH = ResourceLocation.withDefaultNamespace("strength");
    private static final String EMPTY = "empty";

    @GameTest(templateNamespace = "mythictrpg_blessings", template = EMPTY)
    public static void independentOwnershipSurvivesSaveAndReceiptReplay(GameTestHelper h) {
        var state = new RewardClaimState(); var player = UUID.randomUUID();
        var first = resolved("strong", 5, 100);
        var second = resolved("weak", 3, 25);
        var a = state.create(claim(player, "strong_source", List.of(first)));
        state.markAutomaticGranted(a.claimId()); state.markAutomaticGranted(a.claimId());
        var b = state.create(claim(player, "weak_source", List.of(second)));
        state.markAutomaticGranted(b.claimId());
        h.assertValueEqual(state.acquiredBlessingsFor(player).size(), 2, "ownership collapsed or duplicated");
        h.assertValueEqual(state.ownedPermanentBlessingLevels(player), Map.of(STRENGTH, 4), "weaker source replaced highest");
        h.assertValueEqual(state.blessingIntrinsicPower(player), 125d, "intrinsic point layers were not separate");
        state.suppressBlessing(player, STRENGTH);
        var encoded = state.save(new CompoundTag(), h.getLevel().registryAccess());
        var restored = RewardClaimState.load(encoded, h.getLevel().registryAccess());
        h.assertTrue(restored.isWritable(), "named ownership failed save/load: " + restored.rejectionReason());
        h.assertValueEqual(restored.acquiredBlessingsFor(player), state.acquiredBlessingsFor(player), "resolved acquisition changed");
        h.assertTrue(restored.isBlessingSuppressed(player, STRENGTH), "named milk suppression lost");
        h.assertValueEqual(restored.blessingIntrinsicPower(UUID.randomUUID()), 0d, "foreign player received ownership score");
        encoded.getList("acquiredBlessings", Tag.TAG_COMPOUND).remove(0);
        h.assertFalse(RewardClaimState.load(encoded, h.getLevel().registryAccess()).isWritable(), "missing receipt entitlement accepted");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = EMPTY)
    public static void frozenDecisionCannotChangeFixedRewardsOrReroll(GameTestHelper h) {
        var state = new RewardClaimState(); var player = UUID.randomUUID();
        var placeholder = new AdaptiveBlessingRewardEntry(id("strong"));
        var fixed = new CurrencyRewardEntry(10);
        var pending = state.create(claim(player, "freeze", List.of(placeholder, fixed)));
        expectRejected(h, () -> state.markAutomaticGranted(pending.claimId()), "unresolved reward was marked delivered");
        expectRejected(h, () -> state.freezeAdaptiveRewards(pending.claimId(),
                new ResolvedQuestReward("test", List.of(resolved("strong", 5, 100), new CurrencyRewardEntry(11)), List.of())),
                "fixed reward changed during resolution");
        var saved = state.freezeAdaptiveRewards(pending.claimId(), new ResolvedQuestReward("test",
                List.of(resolved("strong", 5, 100), fixed), List.of()));
        var restored = RewardClaimState.load(state.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        expectRejected(h, () -> restored.freezeAdaptiveRewards(saved.claimId(), new ResolvedQuestReward("test",
                List.of(resolved("strong", 6, 100), fixed), List.of())), "saved decision was selected again");
        restored.markAutomaticGranted(saved.claimId());
        h.assertValueEqual(restored.ownedPermanentBlessingLevels(player), Map.of(STRENGTH, 4), "frozen level not acquired");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = EMPTY)
    public static void resistanceFiveMigratesWithoutDiscardingOwnership(GameTestHelper h) {
        var state = new RewardClaimState(); var player = UUID.randomUUID();
        var resistance = ResourceLocation.withDefaultNamespace("resistance");
        var receipt = state.create(claim(player, "legacy_resistance", List.of(new BlessingRewardEntry(resistance, -1, 3))));
        state.markAutomaticGranted(receipt.claimId());
        var encoded = state.save(new CompoundTag(), h.getLevel().registryAccess());
        encoded.putInt("dataVersion", 4); encoded.remove("acquiredBlessings");
        encoded.getList("permanentBlessings", Tag.TAG_COMPOUND).getCompound(0).putInt("amplifier", 4);
        encoded.getList("claims", Tag.TAG_COMPOUND).getCompound(0).getList("automaticRewards", Tag.TAG_COMPOUND)
                .getCompound(0).putInt("amplifier", 4);
        var migrated = RewardClaimState.load(encoded, h.getLevel().registryAccess());
        h.assertTrue(migrated.isWritable(), "previously valid Resistance V invalidated whole store");
        h.assertValueEqual(migrated.ownedPermanentBlessingLevels(player), Map.of(resistance, 3), "old Resistance V not migrated to IV");
        h.assertValueEqual(migrated.acquiredBlessingsFor(player).size(), 0, "legacy rank/identity was invented");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = EMPTY, timeoutTicks = 200)
    public static void namedClaimAppliesOnceAndMilkCommandRestoresRealEffect(GameTestHelper h) {
        try (var fixture = new Connected(h)) {
            var player = fixture.player;
            var reward = resolved("real_named", 11, 45);
            var source = id("named_real/" + UUID.randomUUID());
            var offer = new ResolvedQuestReward("test", List.of(reward), List.of());
            h.assertFalse(RewardExecutionService.grant(player, GOD, List.of(reward), RewardGrantPurpose.QUEST).granted(),
                    "unreceipted named reward applied");
            var result = RewardClaimService.INSTANCE.issue(player, GOD, source, offer);
            h.assertTrue(result.succeeded(), "named reward failed: " + result.reason());
            h.assertValueEqual(player.getEffect(MobEffects.DAMAGE_BOOST).getAmplifier(), 10, "level XI not applied");
            RewardClaimService.INSTANCE.issue(player, GOD, source, offer);
            var state = RewardClaimState.get(player.server);
            h.assertValueEqual(state.acquiredBlessingsFor(player.getUUID()).size(), 1, "replay duplicated named reward");
            Items.MILK_BUCKET.finishUsingItem(new ItemStack(Items.MILK_BUCKET), player.serverLevel(), player);
            h.assertFalse(player.hasEffect(MobEffects.DAMAGE_BOOST), "milk failed");
            h.assertValueEqual(state.blessingIntrinsicPower(player.getUUID()), 45d, "milk removed ownership power");
            player.server.getCommands().getDispatcher().execute("mythblessing reapply", player.createCommandSourceStack().withPermission(0));
            h.assertValueEqual(player.getEffect(MobEffects.DAMAGE_BOOST).getAmplifier(), 10, "ordinary player's command failed to restore");
            h.assertValueEqual(RewardEntryCodec.load(RewardEntryCodec.save(reward)), reward, "resolved rank/level round trip failed");
            h.succeed();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static ResolvedBlessingRewardEntry resolved(String definition, int level, double score) {
        return new ResolvedBlessingRewardEntry(new ResolvedBlessing(id(definition), GOD, GodRank.HIGH,
                "strength", Optional.of(STRENGTH), level, score));
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = EMPTY)
    public static void raidOriginSurvivesPersistenceAndQuestRaidNamesAreNotExempt(GameTestHelper h) {
        var state = new RewardClaimState(); var player = UUID.randomUUID();
        var direct = new RewardClaim(UUID.randomUUID(), player, GOD, id("raid/direct"), "raid",
                List.of(resolved("raid_origin", 2, 5), new BlessingRewardEntry(ResourceLocation.withDefaultNamespace("speed"), -1, 1)),
                List.of(), false, Optional.empty(), 1, RewardOrigin.RAID_DIRECT);
        state.create(direct); state.markAutomaticGranted(direct.claimId());
        h.assertFalse(state.hasNonRaidBlessing(player), "direct raid blessing blocked no-God-blessing route");
        state.suppressBlessing(player, STRENGTH);
        var encoded = state.save(new CompoundTag(), h.getLevel().registryAccess());
        var restored = RewardClaimState.load(encoded, h.getLevel().registryAccess());
        h.assertTrue(restored.isWritable(), "origin round trip rejected");
        h.assertFalse(restored.hasNonRaidBlessing(player), "origin changed after save");
        h.assertValueEqual(restored.acquiredBlessingsFor(player).getFirst().origin(), RewardOrigin.RAID_DIRECT, "named origin lost");
        h.assertValueEqual(restored.permanentBlessingsFor(player).getFirst().origin(), RewardOrigin.RAID_DIRECT, "fixed origin lost");
        var quest = claim(player, "raid/quest_completion", List.of(resolved("quest_origin", 1, 2)));
        restored.create(quest); restored.markAutomaticGranted(quest.claimId());
        h.assertTrue(restored.hasNonRaidBlessing(player), "raid-named quest was inferred to be a direct raid reward");
        restored.suppressBlessing(player, STRENGTH);
        h.assertTrue(restored.hasNonRaidBlessing(player), "milk erased route history");
        var corrupt = encoded.copy();
        corrupt.getList("acquiredBlessings", Tag.TAG_COMPOUND).getCompound(0).putString("origin", "QUEST");
        h.assertFalse(RewardClaimState.load(corrupt, h.getLevel().registryAccess()).isWritable(), "receipt/entitlement origin mismatch accepted");
        var old = encoded.copy(); old.putInt("dataVersion", 5);
        var migrated = RewardClaimState.load(old, h.getLevel().registryAccess());
        h.assertTrue(migrated.isWritable(), "legacy acquisition rejected");
        h.assertTrue(migrated.hasNonRaidBlessing(player), "uncertain historical origin silently became raid-exempt");
        h.assertValueEqual(migrated.acquiredBlessingsFor(player).getFirst().origin(), RewardOrigin.LEGACY_UNSPECIFIED, "legacy origin invented");
        var npc = new RewardClaimState();
        npc.acquireTableBlessings(player, GOD, id("npc_origin"), 1, List.of(new BlessingRewardEntry(STRENGTH, -1, 0)), 1);
        var impossible = npc.save(new CompoundTag(), h.getLevel().registryAccess());
        impossible.getList("permanentBlessings", Tag.TAG_COMPOUND).getCompound(0).putString("origin", "RAID_DIRECT");
        h.assertFalse(RewardClaimState.load(impossible, h.getLevel().registryAccess()).isWritable(), "NPC table masqueraded as direct raid origin");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = EMPTY)
    public static void declinedDynamicChoiceCannotRerollAfterReceiptPruning(GameTestHelper h) {
        var player = UUID.randomUUID(); var state = new RewardClaimState(); var source = id("declined_dynamic");
        var chosen = id("fixed_choice");
        var claim = state.create(new RewardClaim(UUID.randomUUID(), player, GOD, source, "test", List.of(),
                List.of(new RewardChoiceOption(id("named_choice"), "named", List.of(resolved("declined", 1, 1))),
                        new RewardChoiceOption(chosen, "fixed", List.of(new CurrencyRewardEntry(1)))), false, Optional.empty(), 1));
        state.markAutomaticGranted(claim.claimId()); state.markSelected(claim.claimId(), chosen);
        var encoded = state.save(new CompoundTag(), h.getLevel().registryAccess());
        encoded.getList("claims", Tag.TAG_COMPOUND).clear(); // Same persistence state as completed-receipt pruning.
        var restored = RewardClaimState.load(encoded, h.getLevel().registryAccess());
        h.assertTrue(restored.isWritable(), "settled source without pruned receipt rejected");
        h.assertFalse(restored.canCreate(player, source), "declined dynamic offer could be opened and rerolled again");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = EMPTY)
    public static void fullOwnershipStorageAllowsAlreadyRecordedApplication(GameTestHelper h) {
        var state = new RewardClaimState(); var player = UUID.randomUUID();
        state.acquireTableBlessings(player, GOD, id("capacity_seed"), 1,
                List.of(new BlessingRewardEntry(STRENGTH, -1, 0)), 1);
        var encoded = state.save(new CompoundTag(), h.getLevel().registryAccess());
        var list = encoded.getList("permanentBlessings", Tag.TAG_COMPOUND);
        var seed = list.getCompound(0).copy(); list.clear();
        for (int i = 0; i < 16_383; i++) {
            var entry = seed.copy(); entry.putString("sourceId", id("capacity/" + i).toString()); list.add(entry);
        }
        var loaded = RewardClaimState.load(encoded, h.getLevel().registryAccess());
        h.assertTrue(loaded.isWritable(), "capacity fixture rejected");
        var reward = resolved("capacity_last", 2, 1);
        var claim = loaded.create(claim(player, "capacity_last", List.of(reward)));
        loaded.markAutomaticGranted(claim.claimId());
        h.assertTrue(loaded.canGrantPermanentBlessings(player, List.of(reward)), "recorded final slot could not apply");
        h.assertFalse(loaded.canGrantPermanentBlessings(player, List.of(resolved("capacity_overflow", 3, 1))),
                "new entitlement exceeded capacity");
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_blessings", template = EMPTY)
    public static void crossBundleDuplicatesAndUnresolvedDeliveredChoicesAreRejected(GameTestHelper h) {
        var adaptive = new AdaptiveBlessingRewardEntry(id("same_definition"));
        var choice = new RewardChoiceOption(id("choice"), "choice", List.of(adaptive));
        expectRejected(h, () -> new ResolvedQuestReward("test", List.of(adaptive), List.of(choice)),
                "automatic/choice duplicate was accepted");
        // The same definition in mutually exclusive choices is valid.
        new ResolvedQuestReward("test", List.of(), List.of(choice,
                new RewardChoiceOption(id("other_choice"), "other", List.of(adaptive))));
        var state = new RewardClaimState();
        state.create(new RewardClaim(UUID.randomUUID(), UUID.randomUUID(), GOD, id("corrupt_choice"), "test",
                List.of(), List.of(choice, new RewardChoiceOption(id("valid_choice"), "valid", List.of(new CurrencyRewardEntry(1)))),
                false, Optional.empty(), 1));
        var encoded = state.save(new CompoundTag(), h.getLevel().registryAccess());
        encoded.getList("claims", Tag.TAG_COMPOUND).getCompound(0).putBoolean("automaticGranted", true);
        var restored = RewardClaimState.load(encoded, h.getLevel().registryAccess());
        h.assertFalse(restored.isWritable(), "unresolved choice survived delivered-automatic load");
        h.assertValueEqual(restored.save(new CompoundTag(), h.getLevel().registryAccess()), encoded,
                "rejected raw storage was not preserved");
        h.succeed();
    }
    private static RewardClaim claim(UUID player, String source, List<RewardEntry> rewards) {
        return new RewardClaim(UUID.randomUUID(), player, GOD, id(source), "test", rewards, List.of(), false, Optional.empty(), 1);
    }
    private static void expectRejected(GameTestHelper h, Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        h.fail(message);
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mythictrpg", path); }
    private static final class Connected implements AutoCloseable {
        final ServerPlayer player; final io.netty.channel.embedded.EmbeddedChannel channel;
        Connected(GameTestHelper h) {
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), "NamedBlessing"), false);
            player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
        }
        @Override public void close() { player.server.getPlayerList().remove(player); channel.finishAndReleaseAll(); }
    }
}
