package com.sande.mythictrpg.gameplay.activity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlayerActivityService {
    public static final PlayerActivityService INSTANCE = new PlayerActivityService();

    private PlayerActivityService() {
    }

    public PlayerActivityView view(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return PlayerActivityRuntimeState.viewIfPresent(server);
    }

    public Optional<PlayerActivitySnapshot> find(MinecraftServer server, UUID playerId) {
        return view(server).find(playerId);
    }

    public void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            PlayerActivityRuntimeState.discard(server);
            return;
        }
        PlayerActivityRuntimeState state = PlayerActivityRuntimeState.get(server);
        long gameTime = server.overworld().getGameTime();
        for (ServerPlayer player : players) {
            state.observe(PlayerActivityRuntimeState.sample(player), gameTime);
        }
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        resetActive(event.getEntity());
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerActivityRuntimeState.removePlayerIfPresent(player.server, player.getUUID());
        }
    }

    public void onPlayerClone(PlayerEvent.Clone event) {
        resetActive(event.getEntity());
    }

    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        resetActive(event.getEntity());
    }

    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        resetActive(event.getEntity());
    }

    private void resetActive(net.minecraft.world.entity.player.Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            MinecraftServer server = serverPlayer.server;
            PlayerActivityRuntimeState.get(server).resetActive(PlayerActivityRuntimeState.sample(serverPlayer),
                    server.overworld().getGameTime());
        }
    }
}
