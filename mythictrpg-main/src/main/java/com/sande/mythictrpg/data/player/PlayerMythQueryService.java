package com.sande.mythictrpg.data.player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PlayerMythQueryService {
    boolean isReady();

    Optional<PlayerMythProfile> find(UUID playerId);

    Map<UUID, PlayerMythProfile> activeProfiles();
}
