package com.sande.mythictrpg.quest.dynamic;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/** Persisted authoritative instance. FTB object IDs are display mirrors only. */
public record GeneratedQuestInstance(UUID instanceId, ResourceLocation templateId, UUID playerId,
        ResourceLocation godId, String title, String summary, ResourceLocation observationTypeId,
        ResourceLocation subjectId, int requiredCount, int progress, ResourceLocation rewardTableId,
        int rewardTier, boolean catchUpApplied, long createdGameTime, long expiresGameTime,
        long ftbQuestId, long ftbMarkerQuestId, long ftbTaskId) {
    public GeneratedQuestInstance {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(godId, "godId");
        Objects.requireNonNull(observationTypeId, "observationTypeId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(rewardTableId, "rewardTableId");
        title = bounded(title, "title", 120);
        summary = bounded(summary, "summary", 600);
        if (requiredCount < 1 || requiredCount > 256 || progress < 0 || progress > requiredCount) {
            throw new IllegalArgumentException("invalid generated quest progress");
        }
        if (rewardTier < 1 || rewardTier > 100) {
            throw new IllegalArgumentException("invalid generated quest reward tier");
        }
        if (createdGameTime < 0L || expiresGameTime <= createdGameTime) {
            throw new IllegalArgumentException("invalid generated quest lifetime");
        }
    }

    public boolean matches(ResourceLocation observationType, ResourceLocation subject) {
        return observationTypeId.equals(observationType) && subjectId.equals(subject);
    }

    public GeneratedQuestInstance withProgress(int value) {
        return new GeneratedQuestInstance(instanceId, templateId, playerId, godId, title, summary,
                observationTypeId, subjectId, requiredCount, Math.min(requiredCount, value),
                rewardTableId, rewardTier, catchUpApplied, createdGameTime, expiresGameTime,
                ftbQuestId, ftbMarkerQuestId, ftbTaskId);
    }

    public GeneratedQuestInstance withFtbMirror(long questId, long markerQuestId, long taskId) {
        return new GeneratedQuestInstance(instanceId, templateId, playerId, godId, title, summary,
                observationTypeId, subjectId, requiredCount, progress, rewardTableId, rewardTier,
                catchUpApplied, createdGameTime, expiresGameTime, questId, markerQuestId, taskId);
    }

    public boolean hasFtbMirror() {
        return ftbQuestId != 0L && ftbMarkerQuestId != 0L && ftbTaskId != 0L;
    }

    private static String bounded(String value, String field, int maximumCodePoints) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.codePointCount(0, normalized.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }
}
