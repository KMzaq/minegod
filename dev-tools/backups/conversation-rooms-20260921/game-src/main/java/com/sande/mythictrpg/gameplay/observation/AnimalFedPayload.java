package com.sande.mythictrpg.gameplay.observation;

import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AnimalFedPayload(ResourceLocation entityTypeId, List<AnimalFeedEntry> entries)
        implements GameplayObservationPayload {
    public static final int MAX_ENTRIES = 64;

    public AnimalFedPayload {
        Objects.requireNonNull(entityTypeId, "entityTypeId");
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Animal feeding payload must contain at least one entry");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many animal feeding entries: " + entries.size());
        }

        var uniqueEntries = new HashSet<EntryKey>();
        for (AnimalFeedEntry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (!uniqueEntries.add(new EntryKey(entry.foodItemId(), entry.outcome()))) {
                throw new IllegalArgumentException("Duplicate animal feeding entry: "
                        + entry.foodItemId() + " / " + entry.outcome());
            }
        }
        entries = entries.stream().sorted().toList();
    }

    @Override
    public Optional<ResourceLocation> subjectId() {
        return Optional.of(entityTypeId);
    }

    private record EntryKey(ResourceLocation foodItemId, FeedingOutcome outcome) {
    }
}
