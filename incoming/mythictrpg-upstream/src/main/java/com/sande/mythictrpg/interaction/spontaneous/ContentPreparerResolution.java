package com.sande.mythictrpg.interaction.spontaneous;

import com.sande.mythictrpg.interaction.content.InteractionContentPreparer;

import java.util.Objects;
import java.util.Optional;

public record ContentPreparerResolution(Status status,
        Optional<InteractionContentPreparer> preparer) {
    public ContentPreparerResolution {
        Objects.requireNonNull(status, "status");
        preparer = Objects.requireNonNull(preparer, "preparer");
        if ((status == Status.AVAILABLE) != preparer.isPresent()) {
            throw new IllegalArgumentException("AVAILABLE requires exactly one content preparer");
        }
    }

    public static ContentPreparerResolution available(InteractionContentPreparer preparer) {
        return new ContentPreparerResolution(Status.AVAILABLE,
                Optional.of(Objects.requireNonNull(preparer, "preparer")));
    }

    public static ContentPreparerResolution unavailable() {
        return new ContentPreparerResolution(Status.UNAVAILABLE, Optional.empty());
    }

    public enum Status {
        AVAILABLE,
        UNAVAILABLE
    }
}
