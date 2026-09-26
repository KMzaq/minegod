package com.sande.mythictrpg.ai.action;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** One game-owned allow-list entry for an AI action type. */
public record AiActionDefinition(ResourceLocation actionType, ConfirmationPolicy confirmationPolicy,
        AiActionValidator validator, AiActionExecutor executor) {
    public AiActionDefinition {
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(confirmationPolicy, "confirmationPolicy");
        Objects.requireNonNull(validator, "validator");
        Objects.requireNonNull(executor, "executor");
    }

    public enum ConfirmationPolicy {
        IMMEDIATE,
        PLAYER_CONFIRMATION_REQUIRED
    }
}
