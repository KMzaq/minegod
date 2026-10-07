package com.sande.mythictrpg.ai.proposal;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * A non-binding request for a player to vouch for another player to one present NPC. The game owns any social effect
 * or later relationship change; this value merely preserves the conversation's proposal data.
 */
public record VouchRequestProposal(ResourceLocation npcId, UUID sponsorPlayerId, UUID beneficiaryPlayerId,
        String reason) implements AiGameProposal {
    public static final String TYPE = "vouch_request";

    public VouchRequestProposal {
        npcId = Objects.requireNonNull(npcId, "npcId");
        sponsorPlayerId = Objects.requireNonNull(sponsorPlayerId, "sponsorPlayerId");
        beneficiaryPlayerId = Objects.requireNonNull(beneficiaryPlayerId, "beneficiaryPlayerId");
        if (sponsorPlayerId.equals(beneficiaryPlayerId)) {
            throw new IllegalArgumentException("A player cannot vouch for themselves");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        reason = reason.trim();
    }

    @Override
    public String type() {
        return TYPE;
    }
}
