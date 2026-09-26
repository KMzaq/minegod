package com.sande.mythictrpg.gameplay.sampling;

import com.sande.mythictrpg.gameplay.stat.PlayerGameplayStatisticsView;

import java.util.Objects;
import java.util.UUID;

public record SamplingPlayerView(UUID playerId, PlayerGameplayStatisticsView statistics) {
    public SamplingPlayerView {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(statistics, "statistics");
    }
}
