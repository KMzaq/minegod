package com.sande.mythictrpg.network;

import java.util.Objects;
import java.util.function.Consumer;

/** Common-side indirection keeps client Story screens off dedicated servers. */
public final class ClientStoryChoiceBridge {
    private static volatile Consumer<StoryChoicePagePayload> receiver;

    private ClientStoryChoiceBridge() {}

    public static void install(Consumer<StoryChoicePagePayload> newReceiver) {
        receiver = Objects.requireNonNull(newReceiver, "newReceiver");
    }

    public static boolean accept(StoryChoicePagePayload payload) {
        Consumer<StoryChoicePagePayload> current = receiver;
        if (current == null) return false;
        current.accept(payload);
        return true;
    }
}
