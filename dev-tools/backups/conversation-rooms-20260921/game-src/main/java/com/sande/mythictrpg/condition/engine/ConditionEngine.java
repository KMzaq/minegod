package com.sande.mythictrpg.condition.engine;

import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.ConditionDependency;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.registry.ConditionType;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;

import java.util.LinkedHashSet;
import java.util.Set;

public final class ConditionEngine {
    public static final ConditionEngine INSTANCE = new ConditionEngine(ConditionTypeRegistry.INSTANCE);

    private final ConditionTypeRegistry registry;

    public ConditionEngine(ConditionTypeRegistry registry) {
        this.registry = registry;
    }

    public ConditionResult evaluate(ConditionNode node, ConditionContext context) {
        ConditionType<?> type = registry.find(node.typeId())
                .orElseThrow(() -> new IllegalStateException("Unregistered compiled condition type: " + node.typeId()));
        return type.evaluate(node, context, this::evaluate);
    }

    public Set<ConditionDependency> dependencies(ConditionNode root) {
        Set<ConditionDependency> result = new LinkedHashSet<>();
        collectDependencies(root, result);
        return Set.copyOf(result);
    }

    private void collectDependencies(ConditionNode node, Set<ConditionDependency> result) {
        ConditionType<?> type = registry.find(node.typeId())
                .orElseThrow(() -> new IllegalStateException("Unregistered compiled condition type: " + node.typeId()));
        result.addAll(type.dependencies(node));
        node.children().forEach(child -> collectDependencies(child, result));
    }
}
