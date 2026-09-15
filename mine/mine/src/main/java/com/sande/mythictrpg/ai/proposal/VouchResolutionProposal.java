package com.sande.mythictrpg.ai.proposal;

import com.sande.mythictrpg.ai.vouch.VouchStance;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * A non-binding interpretation of the named sponsor's reply to one pending vouch request.
 * The game may audit it, but only its own validator can make any resulting relationship or gameplay change.
 */
public record VouchResolutionProposal(ResourceLocation npcId, UUID sponsorPlayerId, UUID beneficiaryPlayerId,
        VouchStance stance, String reason) implements AiGameProposal {
    public static final String TYPE = "vouch_resolution";

    public VouchResolutionProposal {
        npcId = Objects.requireNonNull(npcId, "npcId");
        sponsorPlayerId = Objects.requireNonNull(sponsorPlayerId, "sponsorPlayerId");
        beneficiaryPlayerId = Objects.requireNonNull(beneficiaryPlayerId, "beneficiaryPlayerId");
        if (sponsorPlayerId.equals(beneficiaryPlayerId)) {
            throw new IllegalArgumentException("A player cannot resolve their own vouch");
        }
        stance = Objects.requireNonNull(stance, "stance");
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
