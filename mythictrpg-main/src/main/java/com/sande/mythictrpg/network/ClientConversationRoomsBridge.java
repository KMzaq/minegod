package com.sande.mythictrpg.network;

import java.util.function.Consumer;

/** Dedicated-server-safe handoff; never loads client classes on the server. */
public final class ClientConversationRoomsBridge {
    private static Consumer<ConversationRoomsPayload> receiver;
    private ClientConversationRoomsBridge() {}
    public static void install(Consumer<ConversationRoomsPayload> value) { receiver=value; }
    public static void accept(ConversationRoomsPayload value) { if(receiver!=null)receiver.accept(value); }
}
