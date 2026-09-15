package com.sande.mythictrpg.interaction.content;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public record ContentValidationResult(Optional<ValidatedInteractionContent> content,
        Optional<ResourceLocation> rejectionReason) {
    public ContentValidationResult {
        content = content == null ? Optional.empty() : content;
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        if (content.isPresent() == rejectionReason.isPresent()) {
            throw new IllegalArgumentException("Content validation must either accept or reject");
        }
    }

    public static ContentValidationResult accepted(ValidatedInteractionContent content) {
        return new ContentValidationResult(Optional.of(content), Optional.empty());
    }

    public static ContentValidationResult rejected(ResourceLocation reason) {
        return new ContentValidationResult(Optional.empty(), Optional.of(reason));
    }
}
