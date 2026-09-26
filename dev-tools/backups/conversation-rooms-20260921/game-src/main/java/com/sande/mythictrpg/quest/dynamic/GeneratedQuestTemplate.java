package com.sande.mythictrpg.quest.dynamic;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Authored, SIDE-only mechanics which an AI may select but never alter. */
public record GeneratedQuestTemplate(ResourceLocation id, ResourceLocation godId,
        ResourceLocation observationTypeId, ResourceLocation subjectId, int requiredCount,
        ResourceLocation progressTrackId, int minimumWorldProgress, int maximumWorldProgress,
        ResourceLocation rewardTableId, int baseRewardTier, int maximumRewardTier,
        int catchUpMaximumBonus, long cooldownTicks, long expiresAfterTicks) {
    public GeneratedQuestTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(godId, "godId");
        Objects.requireNonNull(observationTypeId, "observationTypeId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(progressTrackId, "progressTrackId");
        Objects.requireNonNull(rewardTableId, "rewardTableId");
        if (requiredCount < 1 || requiredCount > 256) {
            throw new IllegalArgumentException("requiredCount must be between 1 and 256");
        }
        if (minimumWorldProgress < 0 || maximumWorldProgress > 100
                || minimumWorldProgress > maximumWorldProgress) {
            throw new IllegalArgumentException("world progress range must be within 0..100");
        }
        if (baseRewardTier < 1 || maximumRewardTier < baseRewardTier || maximumRewardTier > 100) {
            throw new IllegalArgumentException("reward tiers must satisfy 1 <= base <= maximum <= 100");
        }
        if (catchUpMaximumBonus < 0 || catchUpMaximumBonus > 1) {
            throw new IllegalArgumentException("catchUpMaximumBonus must be 0 or 1");
        }
        if (cooldownTicks < 0L || cooldownTicks > 2_592_000L) {
            throw new IllegalArgumentException("cooldownTicks must be between 0 and 2592000");
        }
        if (expiresAfterTicks < 1_200L || expiresAfterTicks > 2_592_000L) {
            throw new IllegalArgumentException("expiresAfterTicks must be between 1200 and 2592000");
        }
    }

    public boolean permitsWorldProgress(int progress) {
        return progress >= minimumWorldProgress && progress <= maximumWorldProgress;
    }
}
