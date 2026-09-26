package com.sande.mythictrpg.ai.api;

import java.util.Objects;

/** Portable dependency, not a fact grant or execution capability. Unknown kinds fail closed. */
public record RoomEvidenceReference(String kind, String payload) {
    public RoomEvidenceReference {
        Objects.requireNonNull(kind); Objects.requireNonNull(payload);
        if (kind.isBlank() || kind.length() > 128 || payload.isBlank() || payload.length() > 65536)
            throw new IllegalArgumentException("Invalid dialogue evidence reference");
    }
}
