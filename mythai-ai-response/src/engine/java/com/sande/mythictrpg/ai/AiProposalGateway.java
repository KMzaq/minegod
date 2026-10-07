package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.proposal.GameProposalValidationFeedback;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Boundary between narrative AI and game authority. The default validator never mutates the game.
 * The gameplay owner may install a validator that maps accepted proposal data to its own APIs.
 */
public final class AiProposalGateway {
    public static final AiProposalGateway INSTANCE = new AiProposalGateway();

    private volatile ProposalValidator validator = ProposalValidator.rejecting();

    private AiProposalGateway() {
    }

    public void installValidator(ProposalValidator replacement) {
        validator = Objects.requireNonNull(replacement, "replacement");
    }

    public ProposalDecision submit(AiDialogueModels.ProposalEnvelope envelope) {
        ProposalDecision decision = validator.validate(envelope);
        MythicTrpg.LOGGER.info("AI proposal {} for session {}: {} ({})",
                envelope.proposal().type(), envelope.sessionId(), decision.status(), decision.reason());
        return decision;
    }

    @FunctionalInterface
    public interface ProposalValidator {
        ProposalDecision validate(AiDialogueModels.ProposalEnvelope envelope);

        static ProposalValidator rejecting() {
            return envelope -> ProposalDecision.rejected("No game proposal validator is installed");
        }
    }

    public record ProposalDecision(Status status, String reason, Map<String, String> allowedAdjustments) {
        public ProposalDecision {
            Objects.requireNonNull(status, "status");
            reason = reason == null ? "" : reason;
            allowedAdjustments = allowedAdjustments == null ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(allowedAdjustments));
        }

        /** Compatibility constructor for validators that only return an accept/reject reason. */
        public ProposalDecision(Status status, String reason) {
            this(status, reason, Map.of());
        }

        public static ProposalDecision rejected(String reason) {
            return new ProposalDecision(Status.REJECTED, reason);
        }

        public static ProposalDecision accepted(String reason) {
            return new ProposalDecision(Status.ACCEPTED, reason);
        }

        public static ProposalDecision modified(String reason, Map<String, String> allowedAdjustments) {
            return new ProposalDecision(Status.MODIFIED, reason, allowedAdjustments);
        }

        /**
         * The game owner may put this value into the next {@code QuestRewardContext}. It is feedback for dialogue,
         * not an AI-side persistence write or a replacement for the game validator's own record.
         */
        public GameProposalValidationFeedback asConversationFeedback(String proposalType) {
            return new GameProposalValidationFeedback(proposalType, switch (status) {
                case ACCEPTED -> GameProposalValidationFeedback.Status.ACCEPTED;
                case REJECTED -> GameProposalValidationFeedback.Status.REJECTED;
                case MODIFIED -> GameProposalValidationFeedback.Status.MODIFIED;
            }, reason, allowedAdjustments);
        }
    }

    public enum Status {
        ACCEPTED,
        REJECTED,
        MODIFIED
    }
}
