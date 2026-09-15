package com.sande.mythictrpg.ai.relationship;

import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Read-only view used by the AI module. It deliberately exposes no mutation method. */
public interface RelationshipProvider {
    RelationshipMetrics relationship(UUID playerId, ResourceLocation godId);

    CurrentEmotion emotion(UUID playerId, ResourceLocation godId);
}
