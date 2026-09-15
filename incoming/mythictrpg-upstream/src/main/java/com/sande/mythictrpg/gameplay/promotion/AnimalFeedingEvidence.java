package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.gameplay.observation.AnimalFeedEntry;
import com.sande.mythictrpg.gameplay.observation.AnimalFedPayload;
import com.sande.mythictrpg.gameplay.observation.FeedingOutcome;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record AnimalFeedingEvidence(
        ResourceLocation entityTypeId,
        List<AnimalFeedEntry> matchingEntries
) implements GameplayActionEvidence {
    public AnimalFeedingEvidence {
        Objects.requireNonNull(entityTypeId, "entityTypeId");
        Objects.requireNonNull(matchingEntries, "matchingEntries");
        if (matchingEntries.isEmpty()) {
            throw new IllegalArgumentException("Animal feeding evidence must contain matching entries");
        }
        if (matchingEntries.size() > AnimalFedPayload.MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many animal feeding evidence entries: "
                    + matchingEntries.size());
        }
        var uniqueEntries = new HashSet<EntryKey>();
        for (AnimalFeedEntry entry : matchingEntries) {
            Objects.requireNonNull(entry, "matching entry");
            if (!uniqueEntries.add(new EntryKey(entry.foodItemId(), entry.outcome()))) {
                throw new IllegalArgumentException("Duplicate animal feeding evidence entry: "
                        + entry.foodItemId() + " / " + entry.outcome());
            }
        }
        matchingEntries = matchingEntries.stream().sorted().toList();
    }

    private record EntryKey(ResourceLocation foodItemId, FeedingOutcome outcome) {
    }
}
