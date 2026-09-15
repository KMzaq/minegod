package com.sande.mythictrpg.condition.api;

import java.util.Set;
import java.util.UUID;

public interface ServerStateView {
    Set<UUID> onlinePlayerIds();
}
