package com.sande.mythictrpg.quest.reward;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@GameTestHolder("mythictrpg_quest_participation")
@PrefixGameTestTemplate(false)
public final class ParticipationRewardGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:fortuna");
    private static RewardClaim claim(UUID player, ResourceLocation source, int amount) {
        return new RewardClaim(UUID.randomUUID(), player, GOD, source, "공동 보상", List.of(new AffinityRewardEntry(amount)),
                List.of(), false, Optional.empty(), 0);
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void batchPersistsWithoutOverwritingFrozenRewards(GameTestHelper h) {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(); var source = ResourceLocation.parse("mythictrpg:batch_test");
        var state = new RewardClaimState(); state.createBatch(List.of(claim(a, source, 10), claim(b, source, 20)));
        var loaded = RewardClaimState.load(state.save(new CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertValueEqual(loaded.undeliveredFor(a).size(), 1, "offline receipt lost");
        loaded.createBatch(List.of(claim(a, source, 50), claim(b, source, 60)));
        h.assertValueEqual(((AffinityRewardEntry) loaded.findBySource(a, source).orElseThrow().automaticRewards().getFirst()).amount(),
                10, "repeat settlement changed frozen reward");
        loaded.markAutomaticGranted(loaded.findBySource(a, source).orElseThrow().claimId());
        h.assertValueEqual(loaded.undeliveredFor(a).size(), 0, "delivered receipt still queued");
        h.assertValueEqual(loaded.undeliveredFor(b).size(), 1, "one recipient claimed another's reward");
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE, timeoutTicks = 200)
    public static void fullQueueRejectsWholeBatchWithoutPartialReceipts(GameTestHelper h) {
        var state = new RewardClaimState(); var source = ResourceLocation.parse("mythictrpg:full_batch");
        List<RewardClaim> entries = new ArrayList<>();
        for (int i = 0; i < 4095; i++) entries.add(claim(new UUID(1, i), source, 1));
        state.createBatch(entries);
        UUID a = new UUID(2, 1), b = new UUID(2, 2);
        h.assertTrue(!state.canCreateBatch(Set.of(a, b), source), "batch capacity not checked");
        try { state.createBatch(List.of(claim(a, source, 10), claim(b, source, 10))); h.fail("full batch accepted"); }
        catch (IllegalStateException expected) { }
        h.assertTrue(state.findBySource(a, source).isEmpty() && state.findBySource(b, source).isEmpty(), "partial batch written");
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void queuedRewardDeliversOnceAndInvalidBatchDoesNothing(GameTestHelper h) {
        var server = h.getLevel().getServer(); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var source = ResourceLocation.parse("mythictrpg:delivery_" + UUID.randomUUID());
        var reward = new ResolvedQuestReward("보상", List.of(new AffinityRewardEntry(20)), List.of());
        var invalid = new ResolvedQuestReward("오류", List.of(), List.of());
        Map<UUID, ResolvedQuestReward> batch = new LinkedHashMap<>(); batch.put(a, reward); batch.put(b, invalid);
        h.assertTrue(!RewardClaimService.INSTANCE.queueBatch(server, GOD, source, batch).succeeded(), "invalid reward accepted");
        h.assertTrue(RewardClaimState.get(server).findBySource(a, source).isEmpty(), "valid sibling created before validation completed");
        h.assertTrue(RewardClaimService.INSTANCE.queueBatch(server, GOD, source, Map.of(a, reward, b, reward)).succeeded(), "queue failed");
        var player = new FakePlayer(h.getLevel(), new GameProfile(a, "QueueTest"));
        RewardClaimService.INSTANCE.deliverQueued(player); RewardClaimService.INSTANCE.deliverQueued(player);
        h.assertValueEqual(PlayerMythDataService.get(server).find(a).orElseThrow().affinities().get(GOD), 20, "automatic reward duplicated");
        h.assertValueEqual(RewardClaimState.get(server).undeliveredFor(b).size(), 1, "offline sibling lost");
        h.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void temporaryBalanceLimitDoesNotConsumeRewardReceipt(GameTestHelper h) {
        var player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "CappedReward"));
        var source = ResourceLocation.parse("mythictrpg:balance_" + UUID.randomUUID());
        var reward = new ResolvedQuestReward("보상", List.of(new CurrencyRewardEntry(10)), List.of());
        com.sande.mythictrpg.economy.CurrencyService.INSTANCE.set(player.server, player.getUUID(),
                com.sande.mythictrpg.economy.CurrencyState.MAX_BALANCE);
        RewardClaimService.INSTANCE.queueBatch(player.server, GOD, source, Map.of(player.getUUID(), reward));
        RewardClaimService.INSTANCE.deliverQueued(player);
        h.assertTrue(!RewardClaimState.get(player.server).findBySource(player.getUUID(), source).orElseThrow().automaticGranted(),
                "failed payout consumed receipt");
        com.sande.mythictrpg.economy.CurrencyService.INSTANCE.set(player.server, player.getUUID(), 0);
        RewardClaimService.INSTANCE.deliverQueued(player); RewardClaimService.INSTANCE.deliverQueued(player);
        h.assertValueEqual(com.sande.mythictrpg.economy.CurrencyService.INSTANCE.balance(player.server, player.getUUID()), 10L,
                "retry lost or duplicated payout");
        h.succeed();
    }
}
