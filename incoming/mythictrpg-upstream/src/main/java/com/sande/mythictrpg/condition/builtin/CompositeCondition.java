package com.sande.mythictrpg.condition.builtin;

import com.sande.mythictrpg.condition.api.ConditionNode;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record CompositeCondition(ResourceLocation typeId, List<ConditionNode> children) implements ConditionNode {
    public CompositeCondition {
        children = List.copyOf(children);
    }
}
