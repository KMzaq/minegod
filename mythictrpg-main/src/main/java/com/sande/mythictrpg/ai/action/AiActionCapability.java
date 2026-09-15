package com.sande.mythictrpg.ai.action;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

/** Read-only prompt capability; it never authorizes execution by itself. */
public record AiActionCapability(ResourceLocation actionType, Optional<ResourceLocation> templateId,
        String promptSummary) {
    public AiActionCapability {
        Objects.requireNonNull(actionType, "actionType");
        templateId = templateId == null ? Optional.empty() : templateId;
        promptSummary = Objects.requireNonNull(promptSummary, "promptSummary").trim();
    }
}
