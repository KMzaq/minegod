package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.power.CombatPowerRuntime;
import com.sande.mythictrpg.quest.reward.blessing.BlessingDefinitions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Converts only opted-in rewards to immutable acquisitions inside the existing claim workflow. */
public final class AdaptiveBlessingService {
    private AdaptiveBlessingService() { }

    public static boolean unresolved(ResolvedQuestReward reward) {
        return unresolved(reward.automaticRewards()) || reward.choices().stream().anyMatch(choice -> unresolved(choice.rewards()));
    }
    private static boolean unresolved(List<RewardEntry> rewards) {
        return rewards.stream().anyMatch(AdaptiveBlessingRewardEntry.class::isInstance);
    }

    public static ResolvedQuestReward resolve(ServerPlayer player, ResourceLocation godId, ResolvedQuestReward reward) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Blessing selection requires server thread");
        if (!unresolved(reward)) return reward;
        var god = GodDefinitionManager.INSTANCE.find(godId)
                .orElseThrow(() -> new IllegalArgumentException("Blessing God is not registered: " + godId));
        var rank = god.rank().orElseThrow(() -> new IllegalArgumentException("God rank must be authored for adaptive blessings: " + godId));
        Map<ResourceLocation, Integer> baseline = new HashMap<>();
        // Temporary potions never raise the acquisition baseline. Milk does not erase permanent ownership.
        RewardClaimState.get(player.server).ownedPermanentBlessingLevels(player.getUUID())
                .forEach((effect, amplifier) -> baseline.put(effect, amplifier + 1));
        var scorer = CombatPowerRuntime.previewScorer(player, promisedItems(reward.automaticRewards()));
        var automatic = resolveBundle(godId, rank, reward.automaticRewards(), baseline, scorer);
        var choices = new ArrayList<RewardChoiceOption>();
        for (var choice : reward.choices()) {
            var choiceBaseline = new HashMap<>(baseline);
            var promised = new ArrayList<NpcRewardEntry>(promisedItems(automatic));
            promised.addAll(promisedItems(choice.rewards()));
            choices.add(new RewardChoiceOption(choice.optionId(), choice.displayName(),
                    resolveBundle(godId, rank, choice.rewards(), choiceBaseline, CombatPowerRuntime.previewScorer(player, promised))));
        }
        return new ResolvedQuestReward(reward.selectionTitle(), automatic, choices);
    }

    private static List<NpcRewardEntry> promisedItems(List<RewardEntry> entries) {
        var result = new ArrayList<NpcRewardEntry>();
        for (var entry : entries) {
            if (entry instanceof NpcRewardEntry item) result.add(item);
            if (entry instanceof ResolvedLuckyRewardEntry lucky) result.add(lucky.selection().reward());
        }
        return result;
    }

    private static List<RewardEntry> resolveBundle(ResourceLocation godId,
            com.sande.mythictrpg.quest.reward.blessing.GodRank rank, List<RewardEntry> rewards,
            Map<ResourceLocation, Integer> baseline, ToDoubleFunction<Map<ResourceLocation, Integer>> scorer) {
        // Account for all promised fixed permanent effects, including those listed after an adaptive entry.
        rewards.forEach(reward -> include(reward, baseline));
        var result = new ArrayList<RewardEntry>();
        var definitions = new java.util.HashSet<ResourceLocation>();
        for (var reward : rewards) {
            if (reward instanceof AdaptiveBlessingRewardEntry adaptive) {
                if (!definitions.add(adaptive.definitionId())) throw new IllegalArgumentException("Duplicate blessing definition in one reward bundle");
                var selection = BlessingDefinitions.INSTANCE.select(adaptive.definitionId(), godId, rank, Map.copyOf(baseline), scorer);
                var resolved = selection.resolved().orElseThrow(() -> new IllegalStateException("가호 선택 보류: " + selection.reason()));
                reward = new ResolvedBlessingRewardEntry(resolved);
                include(reward, baseline);
            }
            result.add(reward);
        }
        return List.copyOf(result);
    }

    private static void include(RewardEntry reward, Map<ResourceLocation, Integer> levels) {
        if (reward instanceof BlessingRewardEntry blessing && blessing.permanent())
            levels.merge(blessing.effectId(), blessing.amplifier() + 1, Math::max);
        if (reward instanceof ResolvedBlessingRewardEntry resolved)
            resolved.blessing().effectId().ifPresent(effect -> levels.merge(effect, resolved.blessing().level(), Math::max));
    }
}
