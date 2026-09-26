package com.sande.mythictrpg.interaction.content;

import java.util.List;
import java.util.Objects;

/** Server-domain content. It intentionally contains no client payload or resolved speaker name. */
public record PreparedInteractionContent(List<PreparedDialogueTurn> turns) {
    public static final int MAX_TURNS = 16;

    public PreparedInteractionContent {
        turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
    }
}
