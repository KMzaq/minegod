package com.sande.mythictrpg.quest.structure;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistent, quest-independent evidence that a player or frozen team completed a structure. */
public record StoredStructureEvaluation(String sourceKey, UUID ownerId, Set<UUID> contributors,
        ResourceLocation policyId, ResourceLocation godId, int score, int objectiveScore, double buildScore,
        double environmentScore, long evaluatedAtGameTick, String fingerprint,
        Map<String, Double> features, String evidenceSummary,
        Optional<StructureVisualAssessment> visualAssessment) {
    public StoredStructureEvaluation {
        sourceKey = Objects.requireNonNull(sourceKey).trim();
        Objects.requireNonNull(ownerId); contributors = Set.copyOf(contributors);
        Objects.requireNonNull(policyId); Objects.requireNonNull(godId);
        fingerprint = Objects.requireNonNull(fingerprint); features = Map.copyOf(features);
        evidenceSummary = Objects.requireNonNull(evidenceSummary);
        visualAssessment = visualAssessment == null ? Optional.empty() : visualAssessment;
        if (sourceKey.isEmpty() || score < 0 || score > 100 || objectiveScore < 0 || objectiveScore > 100) {
            throw new IllegalArgumentException("Invalid stored structure evaluation");
        }
    }

    /** Compatibility constructor for objective-only evaluations. */
    public StoredStructureEvaluation(String sourceKey, UUID ownerId, Set<UUID> contributors,
            ResourceLocation policyId, ResourceLocation godId, int score, double buildScore,
            double environmentScore, long evaluatedAtGameTick, String fingerprint,
            Map<String, Double> features, String evidenceSummary) {
        this(sourceKey, ownerId, contributors, policyId, godId, score, score, buildScore,
                environmentScore, evaluatedAtGameTick, fingerprint, features, evidenceSummary, Optional.empty());
    }

    public boolean belongsTo(UUID playerId) {
        return ownerId.equals(playerId) || contributors.contains(playerId);
    }
}
