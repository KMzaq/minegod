package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.interaction.api.InteractionPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record GameplayActionPayload(
        ResourceLocation promotionRuleId,
        ResourceLocation observationTypeId,
        Optional<ResourceLocation> subjectId,
        GameplayActionEvidence evidence
) implements InteractionPayload {
    public GameplayActionPayload {
        Objects.requireNonNull(promotionRuleId, "promotionRuleId");
        Objects.requireNonNull(observationTypeId, "observationTypeId");
        Objects.requireNonNull(subjectId, "subjectId");
        subjectId.ifPresent(id -> Objects.requireNonNull(id, "subjectId value"));
        Objects.requireNonNull(evidence, "evidence");
        if (evidence instanceof VanillaStatMilestoneEvidence vanilla
                && !vanilla.watchId().equals(promotionRuleId)) {
            throw new IllegalArgumentException("Vanilla statistic watch ID must equal promotion rule ID");
        }
    }
}
