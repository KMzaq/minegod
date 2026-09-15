package com.sande.mythictrpg.ai.agent;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Stable identity for a configured NPC agent. */
public record NpcIdentity(ResourceLocation id, String name) {
    public NpcIdentity {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("NPC agent name must not be blank");
        }
        name = name.trim();
    }
}
