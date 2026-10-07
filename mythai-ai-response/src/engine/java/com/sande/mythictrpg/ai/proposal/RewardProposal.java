package com.sande.mythictrpg.ai.proposal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A non-binding reward/negotiation idea. It never creates an item, applies a blessing, or alters a player inventory.
 * Refusal and request-for-more-work outcomes may deliberately carry no reward concept.
 */
public record RewardProposal(List<UUID> recipientPlayerIds, RewardNegotiationDecision decision,
        Optional<RewardConcept> concept, String narrativeReason) implements AiGameProposal {
    public static final String TYPE = "reward_proposal";

    public RewardProposal {
        if (recipientPlayerIds == null || recipientPlayerIds.isEmpty()) {
            throw new IllegalArgumentException("recipientPlayerIds must not be empty");
        }
        LinkedHashSet<UUID> recipients = new LinkedHashSet<>();
        for (UUID recipient : recipientPlayerIds) {
            recipients.add(Objects.requireNonNull(recipient, "recipientPlayerId"));
        }
        recipientPlayerIds = List.copyOf(recipients);
        decision = Objects.requireNonNull(decision, "decision");
        concept = concept == null ? Optional.empty() : concept;
        narrativeReason = requireText(narrativeReason, "narrativeReason");
        if (concept.isEmpty() && decision != RewardNegotiationDecision.REJECT
                && decision != RewardNegotiationDecision.NEGOTIATE
                && decision != RewardNegotiationDecision.ASK_FOR_MORE) {
            throw new IllegalArgumentException("A reward concept is required for " + decision);
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
