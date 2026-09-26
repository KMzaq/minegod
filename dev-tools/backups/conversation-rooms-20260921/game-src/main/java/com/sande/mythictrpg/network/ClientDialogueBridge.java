package com.sande.mythictrpg.network;

import java.util.Objects;
import java.util.function.Consumer;

/** Common-safe bridge installed by client initialization without referencing client classes here. */
public final class ClientDialogueBridge {
    private static volatile Consumer<ClientDialoguePayload> receiver;

    private ClientDialogueBridge() {
    }

    public static void install(Consumer<ClientDialoguePayload> newReceiver) {
        receiver = Objects.requireNonNull(newReceiver, "newReceiver");
    }

    public static boolean accept(ClientDialoguePayload payload) {
        Consumer<ClientDialoguePayload> current = receiver;
        if (current == null) {
            return false;
        }
        current.accept(payload);
        return true;
    }
}
