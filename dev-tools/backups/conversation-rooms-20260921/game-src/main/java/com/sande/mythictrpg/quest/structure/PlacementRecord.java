package com.sande.mythictrpg.quest.structure;

import java.util.Objects;
import java.util.UUID;

public record PlacementRecord(UUID placerId, long placedAtGameTick, PlacementSource source) {
    public PlacementRecord {
        Objects.requireNonNull(placerId, "placerId");
        Objects.requireNonNull(source, "source");
    }
}
