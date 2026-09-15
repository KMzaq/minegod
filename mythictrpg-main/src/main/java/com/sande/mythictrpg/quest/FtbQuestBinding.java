package com.sande.mythictrpg.quest;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.sande.mythictrpg.quest.reward.QuestRewardPolicy;

/**
 * Datapack-owned mapping only. Quest text, tasks and rewards remain in the FTB
 * quest book; world progress and completion authority remain in MythicTRPG.
 */
public record FtbQuestBinding(
        ResourceLocation questId,
        long ftbQuestId,
        long assignmentQuestId,
        QuestCompletionMode completionMode,
        Set<ResourceLocation> completionNpcIds,
        ResourceLocation progressTrackId,
        int progressOnClear,
        QuestNarrativeRole narrativeRole,
        int minimumAffinity,
        Optional<QuestReminderPolicy> reminderPolicy,
        Optional<QuestEvaluationPolicy> evaluationPolicy,
        Optional<ResourceLocation> structureEvaluationPolicyId,
        Optional<QuestRewardPolicy> rewardPolicy
) {
    public FtbQuestBinding {
        Objects.requireNonNull(questId, "questId");
        Objects.requireNonNull(completionMode, "completionMode");
        Objects.requireNonNull(progressTrackId, "progressTrackId");
        narrativeRole = narrativeRole == null ? QuestNarrativeRole.SIDE : narrativeRole;
        completionNpcIds = completionNpcIds == null ? Set.of() : Set.copyOf(completionNpcIds);
        reminderPolicy = reminderPolicy == null ? Optional.empty() : reminderPolicy;
        evaluationPolicy = evaluationPolicy == null ? Optional.empty() : evaluationPolicy;
        structureEvaluationPolicyId = structureEvaluationPolicyId == null ? Optional.empty() : structureEvaluationPolicyId;
        rewardPolicy = rewardPolicy == null ? Optional.empty() : rewardPolicy;
        if (ftbQuestId == assignmentQuestId) {
            throw new IllegalArgumentException("FTB quest and assignment marker IDs must differ for " + questId);
        }
        if (progressOnClear < 0 || progressOnClear > 100) {
            throw new IllegalArgumentException("progressOnClear must be between 0 and 100 for " + questId);
        }
        if (minimumAffinity < -1000 || minimumAffinity > 1000) {
            throw new IllegalArgumentException("minimumAffinity must be between -1000 and 1000 for " + questId);
        }
        if (evaluationPolicy.isPresent() && completionMode == QuestCompletionMode.AUTO) {
            throw new IllegalArgumentException("Evaluation quests require an NPC completion mode for " + questId);
        }
        if (structureEvaluationPolicyId.isPresent() && evaluationPolicy.isEmpty()) {
            throw new IllegalArgumentException("Structure evaluation requires an evaluation policy for " + questId);
        }
    }

    public String ftbQuestCode() {
        return code(ftbQuestId);
    }

    public String assignmentQuestCode() {
        return code(assignmentQuestId);
    }

    public boolean acceptsNpc(ResourceLocation npcId) {
        return completionNpcIds.contains(npcId);
    }

    /** The giver may always verify a return quest; configured IDs are additional delegates. */
    public boolean acceptsCompletionNpc(ResourceLocation giverNpcId, ResourceLocation npcId) {
        return giverNpcId.equals(npcId) || acceptsNpc(npcId);
    }

    private static String code(long value) {
        return String.format("%016X", value);
    }
}
