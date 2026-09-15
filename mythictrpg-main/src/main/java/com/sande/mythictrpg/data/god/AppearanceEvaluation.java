package com.sande.mythictrpg.data.god;

import com.sande.mythictrpg.condition.api.ConditionResult;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public record AppearanceEvaluation(
        ResourceLocation godId,
        Optional<AppearancePolicy> policy,
        boolean effectivelyUnlocked,
        Optional<ConditionResult> conditionResult,
        boolean eligible,
        AppearanceEvaluationReason reason
) {
    public AppearanceEvaluation {
        policy = policy == null ? Optional.empty() : policy;
        conditionResult = conditionResult == null ? Optional.empty() : conditionResult;
    }
}
