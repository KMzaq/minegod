package com.sande.mythictrpg.data.god;

import com.sande.mythictrpg.condition.api.ConditionDependency;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.Set;

/** Static god data. The resource ID is owned by the repository, not this model. */
public final class GodDefinition {
    private final int schemaVersion;
    private final Component displayName;
    private final ResourceLocation origin;
    private final ResourceLocation faction;
    private final Set<ResourceLocation> categories;
    private final Optional<ConditionNode> unlockConditions;
    private final Optional<ConditionNode> appearanceConditions;
    private final Optional<ConditionNode> identificationConditions;

    public GodDefinition(int schemaVersion, Component displayName, ResourceLocation origin,
            ResourceLocation faction, Set<ResourceLocation> categories,
            Optional<ConditionNode> unlockConditions, Optional<ConditionNode> appearanceConditions,
            Optional<ConditionNode> identificationConditions) {
        this.schemaVersion = schemaVersion;
        this.displayName = displayName.copy();
        this.origin = origin;
        this.faction = faction;
        this.categories = Set.copyOf(categories);
        this.unlockConditions = unlockConditions;
        this.appearanceConditions = appearanceConditions;
        this.identificationConditions = identificationConditions;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public Component displayName() {
        return displayName.copy();
    }

    public ResourceLocation origin() {
        return origin;
    }

    public ResourceLocation faction() {
        return faction;
    }

    public Set<ResourceLocation> categories() {
        return categories;
    }

    public Optional<ConditionNode> unlockConditions() {
        return unlockConditions;
    }

    public Optional<ConditionNode> appearanceConditions() {
        return appearanceConditions;
    }

    public Optional<ConditionNode> identificationConditions() {
        return identificationConditions;
    }

    public UnlockPolicy unlockPolicy() {
        return unlockConditions.isPresent() ? UnlockPolicy.CONDITION_REQUIRED : UnlockPolicy.NOT_REQUIRED;
    }

    public AppearancePolicy appearancePolicy() {
        return appearanceConditions.isPresent() ? AppearancePolicy.CONDITION_DRIVEN : AppearancePolicy.EXPLICIT_ONLY;
    }

    public IdentificationPolicy identificationPolicy() {
        return identificationConditions.isPresent()
                ? IdentificationPolicy.CONDITION_DRIVEN : IdentificationPolicy.EXPLICIT_ONLY;
    }

    public Set<ConditionDependency> unlockDependencies() {
        return dependencies(unlockConditions);
    }

    public Set<ConditionDependency> appearanceDependencies() {
        return dependencies(appearanceConditions);
    }

    public Set<ConditionDependency> identificationDependencies() {
        return dependencies(identificationConditions);
    }

    private static Set<ConditionDependency> dependencies(Optional<ConditionNode> condition) {
        return condition.map(ConditionEngine.INSTANCE::dependencies).orElseGet(Set::of);
    }
}
