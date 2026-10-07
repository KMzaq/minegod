package com.sande.mythictrpg.ai.proposal;

import com.sande.mythictrpg.ai.AiDialogueModels;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Converts the compact JSON wire proposal returned by the local LLM into Quest/Reward DTOs. This is a schema and
 * game-constraint guard only: it neither invokes game registries nor executes accepted proposals.
 */
public final class QuestRewardProposalDecoder {
    public ProposalDecodeResult decode(AiDialogueModels.Proposal raw, AiDialogueModels.SessionSnapshot session,
            QuestRewardContext gameContext) {
        if (raw == null) {
            return ProposalDecodeResult.rejected("Proposal is null");
        }
        if (QuestProposal.TYPE.equals(raw.type())) {
            return decodeQuest(raw, session, gameContext == null ? QuestRewardContext.safeDefaults() : gameContext);
        }
        if (RewardProposal.TYPE.equals(raw.type())) {
            return decodeReward(raw, session, gameContext == null ? QuestRewardContext.safeDefaults() : gameContext);
        }
        return ProposalDecodeResult.notApplicable();
    }

    private ProposalDecodeResult decodeQuest(AiDialogueModels.Proposal raw, AiDialogueModels.SessionSnapshot session,
            QuestRewardContext gameContext) {
        try {
            Map<String, String> parameters = raw.parameters();
            List<ResourceLocation> givers = giverIds(required(parameters, "giverNpcIds"), session);
            List<UUID> participants = targetPlayers(raw.targetParticipantIds(), session);
            boolean shared = strictBoolean(required(parameters, "shared"), "shared");
            QuestConcept concept = new QuestConcept(raw.title(), optional(parameters, "objectiveType"),
                    required(parameters, "targetConcept"), optional(parameters, "concreteItemId"),
                    nonNegativeInt(optional(parameters, "suggestedAmount"), "suggestedAmount"),
                    nonNegativeInt(optional(parameters, "difficulty"), "difficulty"));
            QuestProposal proposal = new QuestProposal(givers, participants, shared, concept,
                    required(parameters, "narrativeReason"), optional(parameters, "collaborationReason"));
            String safetyError = validateQuestConstraints(proposal, gameContext.constraints().quest());
            return safetyError.isEmpty() ? ProposalDecodeResult.accepted(proposal)
                    : ProposalDecodeResult.rejected(safetyError);
        } catch (IllegalArgumentException exception) {
            return ProposalDecodeResult.rejected(exception.getMessage());
        }
    }

    private ProposalDecodeResult decodeReward(AiDialogueModels.Proposal raw, AiDialogueModels.SessionSnapshot session,
            QuestRewardContext gameContext) {
        try {
            Map<String, String> parameters = raw.parameters();
            List<UUID> recipients = targetPlayers(raw.targetParticipantIds(), session);
            RewardNegotiationDecision decision = enumValue(required(parameters, "negotiationDecision"),
                    RewardNegotiationDecision.class, "negotiationDecision");
            String category = optional(parameters, "category");
            Optional<RewardConcept> concept = category.isEmpty() ? Optional.empty() : Optional.of(new RewardConcept(category,
                    required(parameters, "theme"), required(parameters, "description"), optional(parameters, "suggestedName"),
                    optional(parameters, "concreteItemId"), nonNegativeInt(optional(parameters, "powerLevel"), "powerLevel")));
            RewardProposal proposal = new RewardProposal(recipients, decision, concept,
                    required(parameters, "narrativeReason"));
            String safetyError = validateRewardConstraints(proposal, gameContext.constraints().reward());
            return safetyError.isEmpty() ? ProposalDecodeResult.accepted(proposal)
                    : ProposalDecodeResult.rejected(safetyError);
        } catch (IllegalArgumentException exception) {
            return ProposalDecodeResult.rejected(exception.getMessage());
        }
    }

    private static List<ResourceLocation> giverIds(String rawIds, AiDialogueModels.SessionSnapshot session) {
        Set<ResourceLocation> available = new LinkedHashSet<>();
        for (AiDialogueModels.Participant participant : session.participants()) {
            if (participant.kind() == AiDialogueModels.ParticipantKind.DIVINE && participant.godId() != null) {
                available.add(participant.godId());
            }
        }
        List<ResourceLocation> result = new ArrayList<>();
        for (String raw : commaSeparated(rawIds, "giverNpcIds")) {
            ResourceLocation id;
            try {
                id = ResourceLocation.parse(raw);
            } catch (Exception exception) {
                throw new IllegalArgumentException("giverNpcIds contains an invalid ResourceLocation: " + raw);
            }
            if (!available.contains(id)) {
                throw new IllegalArgumentException("Quest giver is not a divine participant: " + id);
            }
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static List<UUID> targetPlayers(List<String> targetParticipantIds,
            AiDialogueModels.SessionSnapshot session) {
        if (targetParticipantIds == null || targetParticipantIds.isEmpty()) {
            throw new IllegalArgumentException("Proposal requires at least one target player participant");
        }
        List<UUID> result = new ArrayList<>();
        for (String requestedId : targetParticipantIds) {
            AiDialogueModels.Participant participant = session.participants().stream()
                    .filter(candidate -> candidate.participantId().equals(requestedId)).findFirst().orElse(null);
            if (participant == null || participant.kind() != AiDialogueModels.ParticipantKind.PLAYER
                    || participant.playerId() == null || participant.state() != AiDialogueModels.ParticipantState.ACTIVE) {
                throw new IllegalArgumentException("Proposal target is not an active player participant: " + requestedId);
            }
            result.add(participant.playerId());
        }
        return List.copyOf(result);
    }

    private static String validateQuestConstraints(QuestProposal proposal, QuestProposalConstraints constraints) {
        QuestConcept concept = proposal.concept();
        if (!concept.objectiveType().isEmpty() && !constraints.allowsObjectiveType(concept.objectiveType())) {
            return "Quest objective type is not allowed by the game constraint: " + concept.objectiveType();
        }
        if (!concept.concreteItemId().isEmpty() && !constraints.allowsConcreteItemId(concept.concreteItemId())) {
            return "Quest concrete item is not in the game-provided item catalogue: " + concept.concreteItemId();
        }
        if (concept.difficulty() > constraints.maxDifficulty()) {
            return "Quest difficulty exceeds the game-provided maximum of " + constraints.maxDifficulty();
        }
        if (concept.suggestedAmount() > 0 && concept.objectiveType().isEmpty()) {
            return "Quest amount requires a game-allowed objective type";
        }
        return "";
    }

    private static String validateRewardConstraints(RewardProposal proposal, RewardProposalConstraints constraints) {
        if (proposal.concept().isEmpty()) {
            return "";
        }
        RewardConcept concept = proposal.concept().orElseThrow();
        if (!constraints.allowsCategory(concept.category())) {
            return "Reward category is not allowed by the game constraint: " + concept.category();
        }
        if (!concept.concreteItemId().isEmpty() && !constraints.allowsConcreteItemId(concept.concreteItemId())) {
            return "Reward concrete item is not in the game-provided item catalogue: " + concept.concreteItemId();
        }
        if (concept.powerLevel() > constraints.maxPowerLevel()) {
            return "Reward power level exceeds the game-provided maximum of " + constraints.maxPowerLevel();
        }
        return "";
    }

    private static List<String> commaSeparated(String raw, String name) {
        List<String> values = new ArrayList<>();
        for (String value : raw.split(",")) {
            String normalized = value.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(name + " contains a blank value");
            }
            values.add(normalized);
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return List.copyOf(values);
    }

    private static boolean strictBoolean(String raw, String name) {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static int nonNegativeInt(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static <T extends Enum<T>> T enumValue(String raw, Class<T> type, String name) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception exception) {
            throw new IllegalArgumentException(name + " is not an allowed value: " + raw);
        }
    }

    private static String required(Map<String, String> parameters, String name) {
        String value = optional(parameters, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Proposal parameter '" + name + "' must not be blank");
        }
        return value;
    }

    private static String optional(Map<String, String> parameters, String name) {
        if (parameters == null) {
            return "";
        }
        String value = parameters.get(name);
        return value == null ? "" : value.trim();
    }
}
