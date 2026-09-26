package com.sande.mythictrpg.economy;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;

public final class CurrencyService {
    public static final String DISPLAY_NAME = "골드";
    public static final CurrencyService INSTANCE = new CurrencyService();

    private CurrencyService() {
    }

    public long balance(MinecraftServer server, UUID playerId) {
        return CurrencyState.get(server).balance(playerId);
    }

    public boolean credit(MinecraftServer server, UUID playerId, long amount) {
        return CurrencyState.get(server).credit(playerId, amount);
    }

    public boolean debit(MinecraftServer server, UUID playerId, long amount) {
        return CurrencyState.get(server).debit(playerId, amount);
    }

    public boolean set(MinecraftServer server, UUID playerId, long amount) {
        return CurrencyState.get(server).setBalance(playerId, amount);
    }
}
