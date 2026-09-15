package com.sande.mythictrpg.interaction.rule;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

public record InteractionRule(int schemaVersion, ResourceLocation signalType,
        List<InteractionRuleBinding> bindings) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public InteractionRule {
        Objects.requireNonNull(signalType, "signalType");
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported interaction rule schema version " + schemaVersion);
        }
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException("Interaction rule bindings must not be empty");
        }
    }
}
