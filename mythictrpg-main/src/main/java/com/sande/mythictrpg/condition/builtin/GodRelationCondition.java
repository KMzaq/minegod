package com.sande.mythictrpg.condition.builtin;

import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionScope;
import com.sande.mythictrpg.relation.GodRelationTag;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.Set;

/** Matches one directed, current-world God relation. */
public record GodRelationCondition(ResourceLocation typeId, ConditionScope conditionScope,
        ResourceLocation sourceGodId, ResourceLocation targetGodId, int minimumScore, int maximumScore,
        Set<GodRelationTag> requiredTags, Set<GodRelationTag> forbiddenTags) implements ConditionNode {
    public GodRelationCondition {
        requiredTags = Set.copyOf(requiredTags);
        forbiddenTags = Set.copyOf(forbiddenTags);
    }

    @Override
    public Optional<ConditionScope> scope() {
        return Optional.of(conditionScope);
    }
}

