package com.sande.mythictrpg.data.god;

import com.sande.mythictrpg.condition.api.ConditionResult;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public record IdentificationEvaluation(
        ResourceLocation godId,
        Optional<IdentificationPolicy> policy,
        Optional<ConditionResult> conditionResult,
        boolean eligible,
        IdentificationEvaluationReason reason
) {
    public IdentificationEvaluation {
        policy = policy == null ? Optional.empty() : policy;
        conditionResult = conditionResult == null ? Optional.empty() : conditionResult;
    }
}
