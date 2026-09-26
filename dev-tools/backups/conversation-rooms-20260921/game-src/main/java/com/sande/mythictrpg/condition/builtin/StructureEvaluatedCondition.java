package com.sande.mythictrpg.condition.builtin;

import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionScope;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;

/** Matches authoritative free-build or quest-build evaluation history. */
public record StructureEvaluatedCondition(ResourceLocation typeId, ConditionScope conditionScope,
        ResourceLocation policyId, int minimumScore, double minimumBuildScore,
        long maximumAgeTicks, Map<String, Double> minimumFeatures) implements ConditionNode {
    public StructureEvaluatedCondition {
        minimumFeatures = Map.copyOf(minimumFeatures);
    }
    @Override public Optional<ConditionScope> scope() { return Optional.of(conditionScope); }
}
