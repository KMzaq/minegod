package com.sande.mythictrpg.quest.dynamic;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import com.sande.mythictrpg.quest.reward.RewardEntry;
import com.sande.mythictrpg.quest.QuestCompletionMode;
import com.sande.mythictrpg.quest.QuestContactLocation;

/** Persisted authoritative instance. FTB object IDs are display mirrors only. */
public record GeneratedQuestInstance(UUID instanceId, ResourceLocation templateId, UUID playerId,
        ResourceLocation godId, String title, String summary, ResourceLocation observationTypeId,
        ResourceLocation subjectId, int requiredCount, int progress, ResourceLocation rewardTableId,
        int rewardTier, boolean catchUpApplied, long createdGameTime, long expiresGameTime,
        long ftbQuestId, long ftbMarkerQuestId, long ftbTaskId,
        Optional<List<RewardEntry>> frozenRewards, QuestCompletionMode completionMode,
        Optional<QuestContactLocation> origin, Optional<QuestContactLocation> destination,
        boolean completionApproved) {
    /** v1 callers retain the legacy table-at-completion contract; new offers always freeze rewards. */
    public GeneratedQuestInstance(UUID instanceId, ResourceLocation templateId, UUID playerId,
            ResourceLocation godId, String title, String summary, ResourceLocation observationTypeId,
            ResourceLocation subjectId, int requiredCount, int progress, ResourceLocation rewardTableId,
            int rewardTier, boolean catchUpApplied, long createdGameTime, long expiresGameTime,
            long ftbQuestId, long ftbMarkerQuestId, long ftbTaskId) {
        this(instanceId, templateId, playerId, godId, title, summary, observationTypeId, subjectId,
                requiredCount, progress, rewardTableId, rewardTier, catchUpApplied, createdGameTime,
                expiresGameTime, ftbQuestId, ftbMarkerQuestId, ftbTaskId, Optional.empty());
    }

    /** Source-compatible callers receive the same conservative NPC-confirmation default. */
    public GeneratedQuestInstance(UUID instanceId, ResourceLocation templateId, UUID playerId,
            ResourceLocation godId, String title, String summary, ResourceLocation observationTypeId,
            ResourceLocation subjectId, int requiredCount, int progress, ResourceLocation rewardTableId,
            int rewardTier, boolean catchUpApplied, long createdGameTime, long expiresGameTime,
            long ftbQuestId, long ftbMarkerQuestId, long ftbTaskId, Optional<List<RewardEntry>> frozenRewards) {
        this(instanceId, templateId, playerId, godId, title, summary, observationTypeId, subjectId,
                requiredCount, progress, rewardTableId, rewardTier, catchUpApplied, createdGameTime,
                expiresGameTime, ftbQuestId, ftbMarkerQuestId, ftbTaskId, frozenRewards,
                QuestCompletionMode.PLAYER_RETURN_TO_NPC, Optional.empty(), Optional.empty(), false);
    }

    public GeneratedQuestInstance {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(godId, "godId");
        Objects.requireNonNull(observationTypeId, "observationTypeId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(rewardTableId, "rewardTableId");
        Objects.requireNonNull(completionMode, "completionMode");
        origin = Objects.requireNonNull(origin, "origin");
        destination = Objects.requireNonNull(destination, "destination");
        if (completionApproved && progress != requiredCount)
            throw new IllegalArgumentException("generated quest approval requires completed objectives");
        if (completionMode == QuestCompletionMode.AUTO && destination.isPresent())
            throw new IllegalArgumentException("AUTO generated quests cannot specify a return location");
        frozenRewards = Objects.requireNonNull(frozenRewards).map(List::copyOf);
        if (frozenRewards.isPresent() && (frozenRewards.orElseThrow().isEmpty()
                || frozenRewards.orElseThrow().size() > 64))
            throw new IllegalArgumentException("invalid frozen generated quest rewards");
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
                ftbQuestId, ftbMarkerQuestId, ftbTaskId, frozenRewards,
                completionMode, origin, destination, completionApproved);
    }

    public GeneratedQuestInstance withFtbMirror(long questId, long markerQuestId, long taskId) {
        return new GeneratedQuestInstance(instanceId, templateId, playerId, godId, title, summary,
                observationTypeId, subjectId, requiredCount, progress, rewardTableId, rewardTier,
                catchUpApplied, createdGameTime, expiresGameTime, questId, markerQuestId, taskId, frozenRewards,
                completionMode, origin, destination, completionApproved);
    }

    public GeneratedQuestInstance withFrozenRewards(List<RewardEntry> rewards) {
        return new GeneratedQuestInstance(instanceId, templateId, playerId, godId, title, summary,
                observationTypeId, subjectId, requiredCount, progress, rewardTableId, rewardTier,
                catchUpApplied, createdGameTime, expiresGameTime, ftbQuestId, ftbMarkerQuestId,
                ftbTaskId, Optional.of(rewards), completionMode, origin, destination, completionApproved);
    }

    GeneratedQuestInstance withContactRules(QuestCompletionMode mode,
            Optional<QuestContactLocation> assignedAt, Optional<QuestContactLocation> returnTo) {
        if (completionApproved) throw new IllegalStateException("approved completion rules are immutable");
        return new GeneratedQuestInstance(instanceId, templateId, playerId, godId, title, summary,
                observationTypeId, subjectId, requiredCount, progress, rewardTableId, rewardTier,
                catchUpApplied, createdGameTime, expiresGameTime, ftbQuestId, ftbMarkerQuestId,
                ftbTaskId, frozenRewards, mode, assignedAt, returnTo, false);
    }

    GeneratedQuestInstance approveCompletion() {
        return new GeneratedQuestInstance(instanceId, templateId, playerId, godId, title, summary,
                observationTypeId, subjectId, requiredCount, progress, rewardTableId, rewardTier,
                catchUpApplied, createdGameTime, expiresGameTime, ftbQuestId, ftbMarkerQuestId,
                ftbTaskId, frozenRewards, completionMode, origin, destination, true);
    }

    public boolean awaitingConfirmation() { return objectivesCompleted() && !completionApproved; }

    /** Reserve the final FTB progress unit for the server's authoritative approval. */
    public int displayMaximum() { return requiredCount + 1; }
    public int displayProgress() { return progress + (completionApproved ? 1 : 0); }
    public String completionStatus() {
        return completionApproved ? "신의 확인 완료"
                : awaitingConfirmation() ? "목표 달성 · 신의 확인 대기" : "목표 진행 중";
    }

    public boolean objectivesCompleted() { return progress == requiredCount; }

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
