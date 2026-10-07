package com.sande.mythictrpg.ai.proposal;

import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.relationship.RelationshipChangeProposal;
import com.sande.mythictrpg.ai.vouch.PendingVouchInteraction;
import com.sande.mythictrpg.ai.vouch.VouchStance;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single allow-listed decoder for all proposal types currently supported by the conversation protocol. It translates
 * untrusted flat LLM JSON into typed data only; accepted values remain subject to the external game validator.
 */
public final class StructuredProposalDecoder {
    private final QuestRewardProposalDecoder questReward = new QuestRewardProposalDecoder();

    public ProposalDecodeResult decode(AiDialogueModels.Proposal raw, AiDialogueModels.SessionSnapshot session,
            QuestRewardContext questRewardContext) {
        return decode(raw, session, questRewardContext, java.util.Optional.empty(), null);
    }

    /**
     * Decodes a proposal against the immutable turn snapshot. A vouch resolution additionally requires the same
     * pending interaction and the named sponsor to be the player who actually supplied this turn.
     */
    public ProposalDecodeResult decode(AiDialogueModels.Proposal raw, AiDialogueModels.SessionSnapshot session,
            QuestRewardContext questRewardContext, java.util.Optional<PendingVouchInteraction> pendingVouch,
            UUID triggeringPlayerId) {
        if (raw == null) {
            return ProposalDecodeResult.rejected("Proposal is null");
        }
        if (QuestProposal.TYPE.equals(raw.type()) || RewardProposal.TYPE.equals(raw.type())) {
            return questReward.decode(raw, session, questRewardContext);
        }
        if (RelationshipChangeProposal.TYPE.equals(raw.type())) {
            return decodeRelationshipChange(raw, session);
        }
        if (RequestJudgmentProposal.TYPE.equals(raw.type())) {
            return decodeRequestJudgment(raw, session, triggeringPlayerId);
        }
        if (VouchRequestProposal.TYPE.equals(raw.type())) {
            return decodeVouch(raw, session);
        }
        if (VouchResolutionProposal.TYPE.equals(raw.type())) {
            return decodeVouchResolution(raw, session, pendingVouch, triggeringPlayerId);
        }
        return ProposalDecodeResult.rejected("Proposal type is not allow-listed: " + raw.type());
    }

    private static ProposalDecodeResult decodeRelationshipChange(AiDialogueModels.Proposal raw,
            AiDialogueModels.SessionSnapshot session) {
        try {
            Map<String, String> parameters = raw.parameters();
            ResourceLocation npcId = presentDivine(required(parameters, "npcId"), session);
            UUID target = exactlyOneActivePlayer(raw.targetParticipantIds(), session, "targetParticipantIds");
            UUID declaredTarget = UUID.fromString(required(parameters, "targetPlayerId"));
            if (!target.equals(declaredTarget)) {
                throw new IllegalArgumentException("targetPlayerId must match the proposal target participant");
            }
            RelationshipChangeProposal proposal = new RelationshipChangeProposal(npcId.toString(), target.toString(),
                    parseChanges(required(parameters, "changes")), required(parameters, "reason"));
            return ProposalDecodeResult.accepted(proposal);
        } catch (IllegalArgumentException exception) {
            return ProposalDecodeResult.rejected(exception.getMessage());
        }
    }

    private static ProposalDecodeResult decodeRequestJudgment(AiDialogueModels.Proposal raw,
            AiDialogueModels.SessionSnapshot session, UUID triggeringPlayerId) {
        try {
            if (triggeringPlayerId == null) {
                throw new IllegalArgumentException("A request judgment requires a triggering player turn");
            }
            Map<String, String> parameters = raw.parameters();
            ResourceLocation npcId = presentDivine(required(parameters, "npcId"), session);
            UUID requester = exactlyOneActivePlayer(raw.targetParticipantIds(), session, "targetParticipantIds");
            UUID declaredRequester = activePlayer(required(parameters, "requesterPlayerId"), session);
            if (!requester.equals(declaredRequester) || !requester.equals(triggeringPlayerId)) {
                throw new IllegalArgumentException("A request judgment must target the player who supplied this turn");
            }
            RequestKind kind = enumValue(required(parameters, "requestKind"), RequestKind.class, "requestKind");
            RequestDisposition disposition = enumValue(required(parameters, "disposition"), RequestDisposition.class,
                    "disposition");
            if (disposition == RequestDisposition.ASK_FOR_VOUCH && activePlayerCount(session) < 2) {
                throw new IllegalArgumentException("ASK_FOR_VOUCH requires another active player in this session");
            }
            return ProposalDecodeResult.accepted(new RequestJudgmentProposal(npcId, requester, kind, disposition,
                    required(parameters, "reason")));
        } catch (IllegalArgumentException exception) {
            return ProposalDecodeResult.rejected(exception.getMessage());
        }
    }

    private static ProposalDecodeResult decodeVouch(AiDialogueModels.Proposal raw, AiDialogueModels.SessionSnapshot session) {
        try {
            Map<String, String> parameters = raw.parameters();
            ResourceLocation npcId = presentDivine(required(parameters, "npcId"), session);
            UUID beneficiary = exactlyOneActivePlayer(raw.targetParticipantIds(), session, "targetParticipantIds");
            UUID declaredBeneficiary = UUID.fromString(required(parameters, "beneficiaryPlayerId"));
            UUID sponsor = activePlayer(required(parameters, "sponsorPlayerId"), session);
            if (!beneficiary.equals(declaredBeneficiary)) {
                throw new IllegalArgumentException("beneficiaryPlayerId must match the proposal target participant");
            }
            return ProposalDecodeResult.accepted(new VouchRequestProposal(npcId, sponsor, beneficiary,
                    required(parameters, "reason")));
        } catch (IllegalArgumentException exception) {
            return ProposalDecodeResult.rejected(exception.getMessage());
        }
    }

    private static ProposalDecodeResult decodeVouchResolution(AiDialogueModels.Proposal raw,
            AiDialogueModels.SessionSnapshot session, java.util.Optional<PendingVouchInteraction> pendingVouch,
            UUID triggeringPlayerId) {
        try {
            PendingVouchInteraction pending = pendingVouch == null ? null : pendingVouch.orElse(null);
            if (pending == null) {
                throw new IllegalArgumentException("There is no pending vouch request to resolve");
            }
            if (!pending.sponsorPlayerId().equals(triggeringPlayerId)) {
                throw new IllegalArgumentException("Only the named sponsor may resolve this vouch request");
            }
            Map<String, String> parameters = raw.parameters();
            ResourceLocation npcId = presentDivine(required(parameters, "npcId"), session);
            UUID beneficiary = exactlyOneActivePlayer(raw.targetParticipantIds(), session, "targetParticipantIds");
            UUID declaredSponsor = activePlayer(required(parameters, "sponsorPlayerId"), session);
            UUID declaredBeneficiary = UUID.fromString(required(parameters, "beneficiaryPlayerId"));
            if (!npcId.equals(pending.npcId()) || !declaredSponsor.equals(pending.sponsorPlayerId())
                    || !beneficiary.equals(pending.beneficiaryPlayerId())
                    || !declaredBeneficiary.equals(pending.beneficiaryPlayerId())) {
                throw new IllegalArgumentException("Vouch resolution does not match the pending interaction");
            }
            VouchStance stance = VouchStance.valueOf(required(parameters, "stance").toUpperCase(java.util.Locale.ROOT));
            return ProposalDecodeResult.accepted(new VouchResolutionProposal(npcId, declaredSponsor, beneficiary,
                    stance, required(parameters, "reason")));
        } catch (IllegalArgumentException exception) {
            return ProposalDecodeResult.rejected(exception.getMessage());
        }
    }

    private static ResourceLocation presentDivine(String rawId, AiDialogueModels.SessionSnapshot session) {
        ResourceLocation parsed;
        try {
            parsed = ResourceLocation.parse(rawId);
        } catch (Exception exception) {
            throw new IllegalArgumentException("npcId is not a valid ResourceLocation: " + rawId);
        }
        boolean present = session.participants().stream().anyMatch(participant -> participant.kind()
                == AiDialogueModels.ParticipantKind.DIVINE && parsed.equals(participant.godId()));
        if (!present) {
            throw new IllegalArgumentException("npcId is not a divine participant in this session: " + parsed);
        }
        return parsed;
    }

    private static UUID exactlyOneActivePlayer(List<String> participantIds, AiDialogueModels.SessionSnapshot session,
            String name) {
        if (participantIds == null || participantIds.size() != 1) {
            throw new IllegalArgumentException(name + " must contain exactly one active player participant");
        }
        AiDialogueModels.Participant participant = session.participants().stream()
                .filter(candidate -> candidate.participantId().equals(participantIds.getFirst())).findFirst().orElse(null);
        if (participant == null || participant.kind() != AiDialogueModels.ParticipantKind.PLAYER
                || participant.playerId() == null || participant.state() != AiDialogueModels.ParticipantState.ACTIVE) {
            throw new IllegalArgumentException(name + " must name an active player participant");
        }
        return participant.playerId();
    }

    private static UUID activePlayer(String rawUuid, AiDialogueModels.SessionSnapshot session) {
        UUID playerId;
        try {
            playerId = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Player ID is not a UUID: " + rawUuid);
        }
        boolean active = session.participants().stream().anyMatch(participant -> participant.kind()
                == AiDialogueModels.ParticipantKind.PLAYER && playerId.equals(participant.playerId())
                && participant.state() == AiDialogueModels.ParticipantState.ACTIVE);
        if (!active) {
            throw new IllegalArgumentException("Player is not an active session participant: " + playerId);
        }
        return playerId;
    }

    private static int activePlayerCount(AiDialogueModels.SessionSnapshot session) {
        return (int) session.participants().stream().filter(participant -> participant.kind()
                == AiDialogueModels.ParticipantKind.PLAYER && participant.playerId() != null
                && participant.state() == AiDialogueModels.ParticipantState.ACTIVE).count();
    }

    private static <T extends Enum<T>> T enumValue(String raw, Class<T> type, String name) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception exception) {
            throw new IllegalArgumentException(name + " is not an allowed value: " + raw);
        }
    }

    private static Map<String, Integer> parseChanges(String raw) {
        Map<String, Integer> changes = new LinkedHashMap<>();
        for (String entry : raw.split(",")) {
            String[] parts = entry.trim().split(":", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("changes must use axis:delta entries");
            }
            int delta;
            try {
                delta = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Relationship change delta must be an integer: " + parts[1]);
            }
            if (changes.put(parts[0].trim(), delta) != null) {
                throw new IllegalArgumentException("Relationship change axis is duplicated: " + parts[0].trim());
            }
        }
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("changes must not be blank");
        }
        return Map.copyOf(changes);
    }

    private static String required(Map<String, String> parameters, String name) {
        String value = parameters == null ? null : parameters.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Proposal parameter '" + name + "' must not be blank");
        }
        return value.trim();
    }
}
