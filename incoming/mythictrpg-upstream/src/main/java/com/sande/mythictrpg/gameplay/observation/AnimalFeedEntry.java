package com.sande.mythictrpg.gameplay.observation;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record AnimalFeedEntry(ResourceLocation foodItemId, FeedingOutcome outcome, int count)
        implements Comparable<AnimalFeedEntry> {
    public AnimalFeedEntry {
        Objects.requireNonNull(foodItemId, "foodItemId");
        Objects.requireNonNull(outcome, "outcome");
        if (count <= 0) {
            throw new IllegalArgumentException("Animal feeding count must be positive");
        }
    }

    @Override
    public int compareTo(AnimalFeedEntry other) {
        int foodComparison = foodItemId.compareTo(other.foodItemId);
        return foodComparison != 0 ? foodComparison : outcome.compareTo(other.outcome);
    }
}
