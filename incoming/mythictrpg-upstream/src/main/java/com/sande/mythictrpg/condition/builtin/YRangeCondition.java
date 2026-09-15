package com.sande.mythictrpg.condition.builtin;

import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionScope;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.OptionalInt;

public record YRangeCondition(ResourceLocation typeId, ConditionScope conditionScope,
        OptionalInt minimum, OptionalInt maximum) implements ConditionNode {
    @Override
    public Optional<ConditionScope> scope() {
        return Optional.of(conditionScope);
    }
}
