package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public sealed interface DamageTypeCriterion
        permits DamageTypeCriterion.Exact, DamageTypeCriterion.Any, DamageTypeCriterion.Missing {
    record Exact(ResourceLocation id) implements DamageTypeCriterion {
        public Exact {
            Objects.requireNonNull(id, "id");
        }
    }

    enum Any implements DamageTypeCriterion {
        INSTANCE
    }

    enum Missing implements DamageTypeCriterion {
        INSTANCE
    }
}
