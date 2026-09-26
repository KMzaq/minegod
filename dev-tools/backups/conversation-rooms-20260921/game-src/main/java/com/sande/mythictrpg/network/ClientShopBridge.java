package com.sande.mythictrpg.network;

import java.util.Objects;
import java.util.function.Consumer;

/** Keeps client shop classes out of the dedicated-server class path. */
public final class ClientShopBridge {
    private static volatile Consumer<ShopCatalogPayload> receiver;

    private ClientShopBridge() {
    }

    public static void install(Consumer<ShopCatalogPayload> newReceiver) {
        receiver = Objects.requireNonNull(newReceiver, "newReceiver");
    }

    public static boolean accept(ShopCatalogPayload payload) {
        Consumer<ShopCatalogPayload> current = receiver;
        if (current == null) return false;
        current.accept(payload);
        return true;
    }
}
