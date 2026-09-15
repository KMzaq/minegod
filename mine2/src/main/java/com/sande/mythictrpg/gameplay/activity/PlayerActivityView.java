package com.sande.mythictrpg.gameplay.activity;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface PlayerActivityView {
    PlayerActivityView UNAVAILABLE = playerId -> {
        java.util.Objects.requireNonNull(playerId, "playerId");
        return Optional.empty();
    };

    Optional<PlayerActivitySnapshot> find(UUID playerId);
}
