package com.sande.mythictrpg.network;

import java.util.Objects;
import java.util.function.Consumer;

/** Common-side indirection that keeps reward screens off dedicated servers. */
public final class ClientRewardChoiceBridge {
    private static volatile Consumer<RewardChoicePayload> receiver;

    private ClientRewardChoiceBridge() {
    }

    public static void install(Consumer<RewardChoicePayload> newReceiver) {
        receiver = Objects.requireNonNull(newReceiver, "newReceiver");
    }

    public static boolean accept(RewardChoicePayload payload) {
        Consumer<RewardChoicePayload> current = receiver;
        if (current == null) {
            return false;
        }
        current.accept(payload);
        return true;
    }
}
