package com.sande.mythictrpg.condition.builtin;

import com.sande.mythictrpg.condition.api.ConditionNode;
import net.minecraft.resources.ResourceLocation;

public record AlwaysCondition(ResourceLocation typeId) implements ConditionNode {
}
