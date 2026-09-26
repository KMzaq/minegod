package com.sande.mythictrpg.quest;

import net.minecraft.resources.ResourceLocation;
import java.util.Optional;

/** Authoritative score-to-reward-range policy for an evaluation quest. */
public record QuestEvaluationPolicy(Optional<ResourceLocation> rewardTableId,
        int minimumRewardTier, int maximumRewardTier, int passingScore) {
    public QuestEvaluationPolicy {
        rewardTableId = rewardTableId == null ? Optional.empty() : rewardTableId;
        if (minimumRewardTier < 1 || maximumRewardTier < minimumRewardTier) {
            throw new IllegalArgumentException("Invalid evaluation reward tier range");
        }
        if (passingScore < 1 || passingScore > 100) {
            throw new IllegalArgumentException("passingScore must be between 1 and 100");
        }
    }

    public boolean passes(int score) {
        return score >= passingScore && score <= 100;
    }

    public int rewardTierForScore(int score) {
        if (!passes(score)) {
            throw new IllegalArgumentException("A failing score has no reward tier");
        }
        if (score == 100 || passingScore == 100) {
            return maximumRewardTier;
        }
        long span = maximumRewardTier - minimumRewardTier;
        return minimumRewardTier + (int) ((long) (score - passingScore) * span / (100 - passingScore));
    }
}
