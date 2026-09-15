package com.sande.mythictrpg.condition.builtin;

import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionScope;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public record GodUnlockedCondition(ResourceLocation typeId, ConditionScope conditionScope,
        ResourceLocation god) implements ConditionNode {
    @Override
    public Optional<ConditionScope> scope() {
        return Optional.of(conditionScope);
    }
}
