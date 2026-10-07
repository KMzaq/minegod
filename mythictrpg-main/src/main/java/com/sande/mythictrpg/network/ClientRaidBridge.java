package com.sande.mythictrpg.network;

import java.util.function.Consumer;

/** Does not reference client classes on dedicated servers. */
public final class ClientRaidBridge {
    private static volatile Consumer<RaidPagePayload> receiver;
    private ClientRaidBridge() { }
    public static void install(Consumer<RaidPagePayload> value) { receiver = java.util.Objects.requireNonNull(value); }
    public static void accept(RaidPagePayload value) { var current = receiver; if (current != null) current.accept(value); }
}
