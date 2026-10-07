package com.sande.mythictrpg.ai.proposal;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * An AI-owned explanation of how an NPC intends to answer a request. It cannot grant, create, or change anything;
 * a separate typed Quest/Reward/relationship proposal is still needed for the game to consider an action.
 */
public record RequestJudgmentProposal(ResourceLocation npcId, UUID requesterPlayerId, RequestKind requestKind,
        RequestDisposition disposition, String reason) implements AiGameProposal {
    public static final String TYPE = "request_judgment";

    public RequestJudgmentProposal {
        npcId = Objects.requireNonNull(npcId, "npcId");
        requesterPlayerId = Objects.requireNonNull(requesterPlayerId, "requesterPlayerId");
        requestKind = Objects.requireNonNull(requestKind, "requestKind");
        disposition = Objects.requireNonNull(disposition, "disposition");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        reason = reason.trim();
        if (reason.codePointCount(0, reason.length()) > 240) {
            throw new IllegalArgumentException("reason is too long");
        }
    }

    @Override
    public String type() {
        return TYPE;
    }
}
