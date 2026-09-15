package com.sande.mythictrpg.data.god;

import com.sande.mythictrpg.condition.api.ConditionDependency;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionScope;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Immutable reverse index used to select conditional Gods after a dependency change. */
public final class GodUnlockDependencyIndex {
    private static final GodUnlockDependencyIndex EMPTY = new GodUnlockDependencyIndex(Map.of(), Set.of(), Set.of());

    private final Map<ConditionDependency, Set<ResourceLocation>> byDependency;
    private final Set<ResourceLocation> conditionalGods;
    private final Set<ResourceLocation> playerTargetGods;

    private GodUnlockDependencyIndex(Map<ConditionDependency, Set<ResourceLocation>> byDependency,
            Set<ResourceLocation> conditionalGods, Set<ResourceLocation> playerTargetGods) {
        this.byDependency = byDependency;
        this.conditionalGods = conditionalGods;
        this.playerTargetGods = playerTargetGods;
    }

    public static GodUnlockDependencyIndex empty() {
        return EMPTY;
    }

    public static GodUnlockDependencyIndex build(Map<ResourceLocation, GodDefinition> definitions) {
        Map<ConditionDependency, Set<ResourceLocation>> mutable = new LinkedHashMap<>();
        Set<ResourceLocation> conditional = new LinkedHashSet<>();
        Set<ResourceLocation> playerTarget = new LinkedHashSet<>();

        definitions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            GodDefinition definition = entry.getValue();
            definition.unlockConditions().ifPresent(root -> {
                ResourceLocation godId = entry.getKey();
                conditional.add(godId);
                if (requiresPlayerTarget(root)) {
                    playerTarget.add(godId);
                }
                definition.unlockDependencies().forEach(dependency ->
                        mutable.computeIfAbsent(dependency, ignored -> new LinkedHashSet<>()).add(godId));
            });
        });

        Map<ConditionDependency, Set<ResourceLocation>> immutable = new LinkedHashMap<>();
        mutable.forEach((dependency, gods) -> immutable.put(dependency, Set.copyOf(gods)));
        return new GodUnlockDependencyIndex(Map.copyOf(immutable), Set.copyOf(conditional),
                Set.copyOf(playerTarget));
    }

    public Set<ResourceLocation> candidates(ConditionDependency dependency) {
        return byDependency.getOrDefault(dependency, Set.of());
    }

    public Set<ResourceLocation> conditionalGods() {
        return conditionalGods;
    }

    public boolean requiresPlayerTarget(ResourceLocation godId) {
        return playerTargetGods.contains(godId);
    }

    public Map<ConditionDependency, Set<ResourceLocation>> byDependency() {
        return byDependency;
    }

    private static boolean requiresPlayerTarget(ConditionNode node) {
        if (node.scope().filter(scope -> scope == ConditionScope.PLAYER).isPresent()) {
            return true;
        }
        return node.children().stream().anyMatch(GodUnlockDependencyIndex::requiresPlayerTarget);
    }
}
