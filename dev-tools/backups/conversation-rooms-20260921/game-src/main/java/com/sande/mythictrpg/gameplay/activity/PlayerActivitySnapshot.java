package com.sande.mythictrpg.gameplay.activity;

import java.util.Objects;

public record PlayerActivitySnapshot(PlayerActivityState state,
                                     long lastActivityGameTick,
                                     long inactiveTicks) {
    public PlayerActivitySnapshot {
        Objects.requireNonNull(state, "state");
        if (lastActivityGameTick < 0L) {
            throw new IllegalArgumentException("lastActivityGameTick must be non-negative");
        }
        if (inactiveTicks < 0L) {
            throw new IllegalArgumentException("inactiveTicks must be non-negative");
        }
    }
}
