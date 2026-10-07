package com.sande.mythictrpg.ai.relationship;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/** Stable identity for a player-to-divine relationship. */
public record RelationshipKey(UUID playerId, ResourceLocation godId) {
    public RelationshipKey {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(godId, "godId");
    }
}
