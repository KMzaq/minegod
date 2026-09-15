package com.sande.mythictrpg.dialogue.api;

import java.util.OptionalInt;

public record DialogueDisplayOptions(OptionalInt requestedHoldTicks) {
    private static final DialogueDisplayOptions DEFAULT = new DialogueDisplayOptions(OptionalInt.empty());

    public DialogueDisplayOptions {
        if (requestedHoldTicks == null) {
            throw new IllegalArgumentException("requestedHoldTicks must not be null");
        }
    }

    public static DialogueDisplayOptions defaults() {
        return DEFAULT;
    }

    public static DialogueDisplayOptions holdTicks(int ticks) {
        return new DialogueDisplayOptions(OptionalInt.of(ticks));
    }
}
