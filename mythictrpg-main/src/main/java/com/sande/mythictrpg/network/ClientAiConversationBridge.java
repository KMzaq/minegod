package com.sande.mythictrpg.network;

import java.util.Objects;
import java.util.function.Consumer;

/** Common-side indirection that avoids loading client HUD classes on a dedicated server. */
public final class ClientAiConversationBridge {
    private static volatile Consumer<AiConversationStatePayload> receiver;

    private ClientAiConversationBridge() {
    }

    public static void install(Consumer<AiConversationStatePayload> newReceiver) {
        receiver = Objects.requireNonNull(newReceiver, "newReceiver");
    }

    public static boolean accept(AiConversationStatePayload payload) {
        Consumer<AiConversationStatePayload> current = receiver;
        if (current == null) {
            return false;
        }
        current.accept(payload);
        return true;
    }
}
