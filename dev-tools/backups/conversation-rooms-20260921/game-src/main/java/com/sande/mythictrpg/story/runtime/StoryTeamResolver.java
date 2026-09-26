package com.sande.mythictrpg.story.runtime;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Version-pinned FTB Teams boundary. Membership is frozen when an event instance starts. */
public final class StoryTeamResolver {
    private StoryTeamResolver() {}

    public static TeamSnapshot resolve(ServerPlayer player) {
        try {
            if (FTBTeamsAPI.api().isManagerLoaded()) {
                var team = FTBTeamsAPI.api().getManager().getTeamForPlayer(player).orElse(null);
                if (team != null) {
                    Set<UUID> members = new LinkedHashSet<>(team.getMembers());
                    members.add(player.getUUID());
                    return new TeamSnapshot(team.getTeamId(), members);
                }
            }
        } catch (RuntimeException ignored) {
            // A stable solo scope is safer than failing an otherwise personal event.
        }
        return new TeamSnapshot(player.getUUID(), Set.of(player.getUUID()));
    }

    public record TeamSnapshot(UUID stableTeamId, Set<UUID> frozenMembers) {
        public TeamSnapshot {
            if (stableTeamId == null) throw new IllegalArgumentException("Story team ID is missing");
            frozenMembers = Set.copyOf(frozenMembers);
            if (frozenMembers.isEmpty()) throw new IllegalArgumentException("Story team cannot be empty");
        }
    }
}
