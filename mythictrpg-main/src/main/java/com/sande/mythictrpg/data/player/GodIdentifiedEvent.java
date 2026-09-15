package com.sande.mythictrpg.data.player;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

import java.util.Objects;
import java.util.UUID;

/** Fired after a player's persistent God-identification state changes. */
public final class GodIdentifiedEvent extends Event {
    private final MinecraftServer server;
    private final UUID playerId;
    private final ResourceLocation godId;

    public GodIdentifiedEvent(MinecraftServer server, UUID playerId, ResourceLocation godId) {
        this.server = Objects.requireNonNull(server, "server");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.godId = Objects.requireNonNull(godId, "godId");
    }

    public MinecraftServer server() {
        return server;
    }

    public UUID playerId() {
        return playerId;
    }

    public ResourceLocation godId() {
        return godId;
    }
}
