package com.sande.mythictrpg.condition.builtin;

import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionScope;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public record ItemEverObtainedCondition(ResourceLocation typeId, ConditionScope conditionScope,
        ResourceLocation item) implements ConditionNode {
    @Override
    public Optional<ConditionScope> scope() {
        return Optional.of(conditionScope);
    }
}
