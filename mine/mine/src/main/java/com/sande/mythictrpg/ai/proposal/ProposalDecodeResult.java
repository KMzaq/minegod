package com.sande.mythictrpg.ai.proposal;

import java.util.Optional;

/** Result of decoding an untrusted LLM wire proposal into a typed, game-safe concept. */
public record ProposalDecodeResult(Status status, Optional<AiGameProposal> proposal, String reason) {
    public ProposalDecodeResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        proposal = proposal == null ? Optional.empty() : proposal;
        reason = reason == null ? "" : reason.trim();
        if (status == Status.ACCEPTED && proposal.isEmpty()) {
            throw new IllegalArgumentException("Accepted proposal decode requires a typed proposal");
        }
        if (status != Status.ACCEPTED && proposal.isPresent()) {
            throw new IllegalArgumentException("Only accepted proposal decodes may carry a typed proposal");
        }
    }

    public static ProposalDecodeResult accepted(AiGameProposal proposal) {
        return new ProposalDecodeResult(Status.ACCEPTED, Optional.of(proposal), "");
    }

    public static ProposalDecodeResult rejected(String reason) {
        return new ProposalDecodeResult(Status.REJECTED, Optional.empty(), reason);
    }

    public static ProposalDecodeResult notApplicable() {
        return new ProposalDecodeResult(Status.NOT_APPLICABLE, Optional.empty(), "");
    }

    public enum Status {
        ACCEPTED,
        REJECTED,
        NOT_APPLICABLE
    }
}
