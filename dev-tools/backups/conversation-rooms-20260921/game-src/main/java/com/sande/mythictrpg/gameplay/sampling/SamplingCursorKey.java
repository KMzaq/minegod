package com.sande.mythictrpg.gameplay.sampling;

import java.util.Objects;
import java.util.UUID;

public record SamplingCursorKey(UUID playerId, VanillaStatisticSource source) {
    public SamplingCursorKey {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(source, "source");
    }
}
