package com.sande.mythictrpg.ai.api;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

/** Server-verified evaluation result supplied only for NPC narration. */
public record AiQuestEvaluationContext(UUID playerId, ResourceLocation npcId,
        ResourceLocation questId, int score, int passingScore, boolean passed,
        OptionalInt rewardTier, String evaluationSummary, List<String> grantedRewards) {
    public AiQuestEvaluationContext {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(questId, "questId");
        rewardTier = rewardTier == null ? OptionalInt.empty() : rewardTier;
        evaluationSummary = evaluationSummary == null ? "" : evaluationSummary.trim();
        grantedRewards = List.copyOf(Objects.requireNonNull(grantedRewards, "grantedRewards"));
        if (score < 0 || score > 100 || passingScore < 1 || passingScore > 100
                || passed != rewardTier.isPresent() || (!passed && !grantedRewards.isEmpty())) {
            throw new IllegalArgumentException("Inconsistent quest evaluation context");
        }
    }
}
