package com.sande.mythictrpg.condition.api;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

public interface ConditionNode {
    ResourceLocation typeId();

    default Optional<ConditionScope> scope() {
        return Optional.empty();
    }

    default List<ConditionNode> children() {
        return List.of();
    }
}
