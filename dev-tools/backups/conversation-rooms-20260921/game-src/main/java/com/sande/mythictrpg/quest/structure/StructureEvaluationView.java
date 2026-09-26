package com.sande.mythictrpg.quest.structure;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read-only condition-engine view of authoritative structure evaluation history. */
public interface StructureEvaluationView {
    boolean isReady();

    Optional<StoredStructureEvaluation> best(UUID playerId, ResourceLocation policyId,
            long maximumAgeTicks);

    default boolean matches(UUID playerId, ResourceLocation policyId, int minimumScore,
            double minimumBuildScore, long maximumAgeTicks, Map<String, Double> minimumFeatures) {
        return best(playerId, policyId, maximumAgeTicks).filter(value ->
                value.score() >= minimumScore && value.buildScore() >= minimumBuildScore
                && minimumFeatures.entrySet().stream().allMatch(feature ->
                value.features().getOrDefault(feature.getKey(), 0.0D) >= feature.getValue())).isPresent();
    }

    static StructureEvaluationView unavailable() {
        return new StructureEvaluationView() {
            @Override public boolean isReady() { return false; }
            @Override public Optional<StoredStructureEvaluation> best(UUID playerId,
                    ResourceLocation policyId, long maximumAgeTicks) { return Optional.empty(); }
        };
    }
}
