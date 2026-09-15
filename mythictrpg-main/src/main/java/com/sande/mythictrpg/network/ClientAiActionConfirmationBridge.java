package com.sande.mythictrpg.network;

import java.util.Objects;
import java.util.function.Consumer;

/** Common-side indirection that keeps client screen classes off dedicated servers. */
public final class ClientAiActionConfirmationBridge {
    private static volatile Consumer<AiActionConfirmationPayload> receiver;

    private ClientAiActionConfirmationBridge() {
    }

    public static void install(Consumer<AiActionConfirmationPayload> newReceiver) {
        receiver = Objects.requireNonNull(newReceiver, "newReceiver");
    }

    public static boolean accept(AiActionConfirmationPayload payload) {
        Consumer<AiActionConfirmationPayload> current = receiver;
        if (current == null) {
            return false;
        }
        current.accept(payload);
        return true;
    }
}
