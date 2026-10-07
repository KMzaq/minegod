package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.context.ConversationTurnContextSnapshot;
import com.sande.mythictrpg.ai.proposal.ProposalDecodeResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Opt-in operational diagnostics. It intentionally never logs the system prompt or hidden source data; verbose
 * structured output is bounded and available only while {@code debugLogging} is enabled.
 */
final class AiConversationDiagnostics {
    private AiConversationDiagnostics() {
    }

    static void turnPrepared(ConversationTurnContextSnapshot turn, AiDialogueModels.ConversationContext context,
            AiDialogueConfig.Settings settings) {
        if (!settings.debugLogging()) {
            return;
        }
        MythicTrpg.LOGGER.debug("AI turn prepared: session={} interaction={} turn={} request={} participants={} "
                        + "speaker={} relationships={} examples={} memories={} knowledge={} reactionGuidelines={} "
                        + "questReward={} feedbackCount={}",
                turn.sessionId(), turn.session().interactionId().map(Object::toString).orElse("none"), turn.turnId(),
                turn.requestId(), participants(turn.session()), turn.triggeringParticipantId(),
                context.relationshipsByPlayerParticipantId().keySet(), exampleIds(context), memoryIds(context),
                knowledgeIds(context), reactionGuidelineIds(context), context.questRewardContext().constraints(),
                context.questRewardContext().validationFeedback().size());
    }

    static void llmFinished(ConversationTurnContextSnapshot turn,
            LocalLlmRequestScheduler.ScheduledResult<AiDialogueModels.StructuredAiResult> scheduled,
            Throwable failure, AiDialogueConfig.Settings settings) {
        if (!settings.debugLogging()) {
            return;
        }
        if (failure != null) {
            MythicTrpg.LOGGER.debug("AI LLM request failed: session={} turn={} request={} failure={}", turn.sessionId(),
                    turn.turnId(), turn.requestId(), failure.getClass().getSimpleName());
            return;
        }
        AiDialogueModels.StructuredAiResult result = scheduled == null ? null : scheduled.value();
        MythicTrpg.LOGGER.debug("AI LLM request finished: session={} turn={} request={} queueWaitMs={} requestMs={} "
                        + "structuredResult={}", turn.sessionId(), turn.turnId(), turn.requestId(),
                scheduled == null ? -1L : scheduled.queueWait().toMillis(),
                scheduled == null ? -1L : scheduled.requestDuration().toMillis(), abbreviated(String.valueOf(result), 2_000));
    }

    static void proposalDecoded(ConversationTurnContextSnapshot turn, AiDialogueModels.Proposal proposal,
            ProposalDecodeResult result, AiDialogueConfig.Settings settings) {
        if (!settings.debugLogging()) {
            return;
        }
        MythicTrpg.LOGGER.debug("AI proposal decoded: session={} turn={} type={} status={} reason={}", turn.sessionId(),
                turn.turnId(), proposal == null ? "null" : proposal.type(), result.status(), abbreviated(result.reason(), 300));
    }

    static void proposalDecision(ConversationTurnContextSnapshot turn, AiDialogueModels.Proposal proposal,
            AiProposalGateway.ProposalDecision decision, AiDialogueConfig.Settings settings) {
        if (!settings.debugLogging()) {
            return;
        }
        MythicTrpg.LOGGER.debug("AI proposal validated externally: session={} turn={} type={} status={} reason={} adjustments={}",
                turn.sessionId(), turn.turnId(), proposal.type(), decision.status(), abbreviated(decision.reason(), 300),
                decision.allowedAdjustments());
    }

    private static String participants(AiDialogueModels.SessionSnapshot session) {
        return session.participants().stream().map(participant -> participant.participantId() + ":" + participant.state())
                .collect(Collectors.joining(","));
    }

    private static Map<String, List<String>> exampleIds(AiDialogueModels.ConversationContext context) {
        return context.dialogueExamplesByDivineParticipantId().entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue().stream().map(example -> example.exampleId()).toList()));
    }

    private static Map<String, List<String>> memoryIds(AiDialogueModels.ConversationContext context) {
        return context.memoriesByDivineParticipantId().entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue().stream().map(memory -> memory.memoryId()).toList()));
    }

    private static Map<String, List<String>> knowledgeIds(AiDialogueModels.ConversationContext context) {
        return context.knowledgeByDivineParticipantId().entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue().stream().map(knowledge -> knowledge.id()).toList()));
    }

    private static Map<String, List<String>> reactionGuidelineIds(AiDialogueModels.ConversationContext context) {
        return context.reactionGuidelinesByDivineParticipantId().entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue().stream().map(guideline -> guideline.id()).toList()));
    }

    private static String abbreviated(String value, int maximum) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ');
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum) + "…";
    }

}
