package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.api.AiConversationEngineRouter;
import com.sande.mythictrpg.ai.api.AiQuestEvaluationContext;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.server.DialoguePresentationService;
import com.sande.mythictrpg.quest.reward.NpcRewardTable;
import com.sande.mythictrpg.quest.reward.NpcRewardTableManager;
import com.sande.mythictrpg.quest.reward.QuestRewardResolver;
import com.sande.mythictrpg.quest.reward.ResolvedQuestReward;
import com.sande.mythictrpg.quest.reward.RewardClaimService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Public server-authoritative boundary for future structure/artifact analyzers.
 * An analyzer supplies only a score and evidence summary; this gateway decides
 * pass/fail, reward tier, completion, and actual reward execution.
 */
public final class QuestEvaluationGateway {
    private QuestEvaluationGateway() {
    }

    public static QuestEvaluationResult submit(ServerPlayer player, ResourceLocation questId,
            ResourceLocation evaluatorNpcId, int score, String evidenceSummary) {
        if (!player.server.isSameThread()) {
            throw new IllegalStateException("Quest evaluation must run on the server thread");
        }
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("Evaluation score must be between 0 and 100");
        }
        String summary = bounded(evidenceSummary, 1200);
        FtbQuestBinding binding = FtbQuestBindingManager.INSTANCE.find(questId).orElse(null);
        QuestEvaluationPolicy policy = binding == null ? null : binding.evaluationPolicy().orElse(null);
        if (policy == null) {
            return result(QuestEvaluationResult.Status.NOT_EVALUATION_QUEST, questId, score,
                    OptionalInt.empty(), "Quest has no evaluation policy");
        }
        QuestAssignment assignment = MythicQuestState.get(player.server).assignmentsFor(player.getUUID()).stream()
                .filter(candidate -> candidate.questId().equals(questId)).findFirst().orElse(null);
        if (assignment == null) {
            return result(QuestEvaluationResult.Status.NOT_ASSIGNED, questId, score,
                    OptionalInt.empty(), "Quest is not assigned to this player");
        }
        if (binding.participation().isPresent() && MythicQuestState.get(player.server).participationRun(questId)
                .filter(run -> run.accepting(player.getUUID(), player.server.overworld().getGameTime())).isEmpty())
            return result(QuestEvaluationResult.Status.COMPLETION_FAILED, questId, score,
                    OptionalInt.empty(), "Participation entry is already submitted or closed");
        if (!binding.acceptsCompletionNpc(assignment.giverGodId(), evaluatorNpcId)) {
            return result(QuestEvaluationResult.Status.WRONG_NPC, questId, score,
                    OptionalInt.empty(), "Evaluator NPC does not own this assignment");
        }
        NpcRewardTable table = policy.rewardTableId().flatMap(NpcRewardTableManager.INSTANCE::find).orElse(null);
        if (policy.rewardTableId().isPresent() && table == null) {
            return result(QuestEvaluationResult.Status.REWARD_TABLE_MISSING, questId, score,
                    OptionalInt.empty(), "Reward table is not loaded");
        }
        if (table != null && (!table.npcId().equals(assignment.giverGodId())
                || policy.maximumRewardTier() > table.maximumTier())) {
            return result(QuestEvaluationResult.Status.INVALID_REWARD_RANGE, questId, score,
                    OptionalInt.empty(), "Quest reward range is outside the NPC reward table");
        }
        if (table == null && binding.rewardPolicy().map(value -> value.mode()
                != com.sande.mythictrpg.quest.reward.QuestRewardPolicy.Mode.REPLACE).orElse(true)) {
            return result(QuestEvaluationResult.Status.REWARD_TABLE_MISSING, questId, score,
                    OptionalInt.empty(), "A table-less evaluation requires direct REPLACE rewards");
        }

        if (!policy.passes(score)) {
            com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents.evaluation(player,questId,evaluatorNpcId,score,false,"NOT_ELIGIBLE");
            AiQuestEvaluationContext context = new AiQuestEvaluationContext(player.getUUID(), evaluatorNpcId,
                    questId, score, policy.passingScore(), false, OptionalInt.empty(), summary, List.of());
            narrate(player, context, "[평가 결과: 재도전이 필요합니다]");
            return result(QuestEvaluationResult.Status.REVISION_REQUIRED, questId, score,
                    OptionalInt.empty(), "Evaluation score did not reach the passing score");
        }

        int rewardTier = policy.rewardTierForScore(score);
        QuestRewardResolver.Resolution resolution = QuestRewardResolver.resolve(
                assignment.giverGodId(), binding.rewardPolicy(), table == null ? Optional.empty() : Optional.of(
                        new QuestRewardResolver.TableTier(policy.rewardTableId().orElseThrow(), rewardTier)));
        if (resolution.status() != QuestRewardResolver.Status.RESOLVED) {
            return result(QuestEvaluationResult.Status.INVALID_REWARD_RANGE, questId, score,
                    OptionalInt.empty(), resolution.reason());
        }
        ResolvedQuestReward resolvedReward = resolution.reward().orElseThrow();
        RewardClaimService.Result preflight = RewardClaimService.INSTANCE.preflight(
                player, questId, resolvedReward);
        if (!preflight.succeeded()) {
            return result(QuestEvaluationResult.Status.INVALID_REWARD_RANGE, questId, score,
                    OptionalInt.empty(), preflight.reason());
        }
        if (binding.participation().isPresent()) {
            QuestOperationResult submitted = QuestParticipationService.INSTANCE.evaluate(player, binding, evaluatorNpcId, score);
            var status = switch (submitted.status()) {
                case COMPLETED -> QuestEvaluationResult.Status.COMPLETED;
                case SUBMITTED -> QuestEvaluationResult.Status.SUBMITTED;
                default -> QuestEvaluationResult.Status.COMPLETION_FAILED;
            };
            com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents.evaluation(player, questId, evaluatorNpcId, score,
                    status == QuestEvaluationResult.Status.COMPLETED || status == QuestEvaluationResult.Status.SUBMITTED,
                    status.name());
            return result(status, questId, score, OptionalInt.empty(), submitted.reason());
        }
        QuestOperationResult completion = QuestRuntimeService.INSTANCE.completeEvaluation(
                player, binding, evaluatorNpcId);
        if (completion.status() != QuestOperationResult.Status.COMPLETED) {
            return result(QuestEvaluationResult.Status.COMPLETION_FAILED, questId, score,
                    OptionalInt.empty(), completion.reason());
        }
        RewardClaimService.Result issued = RewardClaimService.INSTANCE.issue(
                player, assignment.giverGodId(), questId, resolvedReward);
        if (!issued.succeeded()) {
            MythicTrpg.LOGGER.error("Evaluation quest {} completed but reward claim failed: {}",
                    questId, issued.reason());
        }
        List<String> granted = new java.util.ArrayList<>(resolvedReward.automaticRewards().stream()
                .map(com.sande.mythictrpg.quest.reward.RewardEntry::description).toList());
        if (!resolvedReward.choices().isEmpty()) {
            granted.add("player reward choice pending");
        }
        com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents.evaluation(player,questId,evaluatorNpcId,score,true,
                !issued.succeeded()?"ISSUE_FAILED":resolvedReward.choices().isEmpty()?"ISSUE_ACCEPTED":"ISSUE_ACCEPTED_CHOICE_PENDING");
        AiQuestEvaluationContext context = new AiQuestEvaluationContext(player.getUUID(), evaluatorNpcId,
                questId, score, policy.passingScore(), true, OptionalInt.of(rewardTier), summary, granted);
        narrate(player, context, "[퀘스트가 완료되었습니다]");
        MythicTrpg.LOGGER.info("Evaluation quest {} completed by {} at score {}, reward tier {}",
                questId, player.getUUID(), score, rewardTier);
        return result(QuestEvaluationResult.Status.COMPLETED, questId, score,
                OptionalInt.of(rewardTier), "");
    }

    private static void narrate(ServerPlayer player, AiQuestEvaluationContext context, String fallback) {
        if (!AiConversationEngineRouter.INSTANCE.onQuestEvaluated(context)) {
            DialoguePresentationService.INSTANCE.sendTo(player,
                    GodDialogueRequest.literal(context.npcId(), fallback));
        }
    }

    private static QuestEvaluationResult result(QuestEvaluationResult.Status status,
            ResourceLocation questId, int score, OptionalInt tier, String reason) {
        return new QuestEvaluationResult(status, questId, score, tier, reason);
    }

    private static String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
