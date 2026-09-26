package com.sande.mythictrpg.quest.structure;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Version-pinned FTB Teams boundary; no FTB type escapes this class. */
public final class StructureTeamAdapter {
    private StructureTeamAdapter() {}

    public static Set<UUID> frozenContributors(ServerPlayer owner) {
        Set<UUID> result = new LinkedHashSet<>();
        result.add(owner.getUUID());
        try {
            if (FTBTeamsAPI.api().isManagerLoaded()) {
                FTBTeamsAPI.api().getManager().getTeamForPlayer(owner)
                        .ifPresent(team -> result.addAll(team.getMembers()));
            }
        } catch (RuntimeException ignored) {
            // Solo ownership remains valid when FTB Teams is temporarily unavailable.
        }
        return Set.copyOf(result);
    }
}
