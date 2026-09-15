package com.sande.mythictrpg.relation;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class GodRelationQueryService {
    private GodRelationQueryService() {
    }

    /** Returns only directed records between participants already authorized by the server. */
    public static List<GodRelationContextEntry> contextFor(MinecraftServer server,
            List<ResourceLocation> participantIds) {
        List<ResourceLocation> participants = new ArrayList<>(new LinkedHashSet<>(participantIds));
        if (participants.size() < 2 || participants.size() > 8) {
            return List.of();
        }
        DynamicGodRelationState state = DynamicGodRelationState.get(server);
        if (!state.isReady()) {
            return List.of();
        }
        List<GodRelationContextEntry> result = new ArrayList<>();
        for (ResourceLocation source : participants) {
            for (ResourceLocation target : participants) {
                if (source.equals(target)) {
                    continue;
                }
                state.find(source, target).ifPresent(value -> result.add(new GodRelationContextEntry(
                        source, target, value.score(), value.tags().stream().map(Enum::name).sorted().toList(),
                        value.revision(), value.lastCauseId())));
            }
        }
        return List.copyOf(result);
    }
}

