package com.sande.mythictrpg.raid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Temporary region tickets, never edits the world's persistent forced-chunk set. */
final class RaidChunkTickets {
    private static final TicketType<UUID> TYPE = TicketType.create("mythictrpg_raid", UUID::compareTo);
    private static final int DISTANCE = 2;
    private final Map<UUID, Held> held = new HashMap<>();

    boolean isHeld(UUID attemptId) { return held.containsKey(attemptId); }

    /** A loaded block chunk can still have hidden or pending entity sections for another server tick. */
    boolean readyForEntities(UUID attemptId) {
        Held ticket = held.get(attemptId);
        return ticket != null && ticket.chunks.stream().allMatch(chunk ->
                ticket.level.areEntitiesLoaded(chunk.toLong())
                        && ticket.level.isPositionEntityTicking(new BlockPos(
                                chunk.getMinBlockX(), ticket.level.getMinBuildHeight(), chunk.getMinBlockZ())));
    }

    boolean acquire(UUID attemptId, ServerLevel level, AABB bounds) {
        if (held.containsKey(attemptId)) return false;
        List<ChunkPos> chunks = chunks(bounds);
        var ticket = new Held(level, chunks);
        try {
            for (ChunkPos chunk : chunks) {
                level.getChunkSource().addRegionTicket(TYPE, chunk, DISTANCE, attemptId);
                ticket.added++;
            }
            // Load block data now; callers must wait for readyForEntities before spawning into this region.
            for (ChunkPos chunk : chunks) level.getChunk(chunk.x, chunk.z);
            held.put(attemptId, ticket);
            return true;
        } catch (RuntimeException failure) {
            releaseTicket(attemptId, ticket);
            return false;
        }
    }

    void release(UUID attemptId) {
        Held ticket = held.remove(attemptId);
        if (ticket != null) releaseTicket(attemptId, ticket);
    }

    void releaseAll() {
        for (UUID attemptId : List.copyOf(held.keySet())) release(attemptId);
    }

    private static void releaseTicket(UUID attemptId, Held ticket) {
        for (int index = 0; index < ticket.added; index++)
            ticket.level.getChunkSource().removeRegionTicket(TYPE, ticket.chunks.get(index), DISTANCE, attemptId);
    }

    private static List<ChunkPos> chunks(AABB bounds) {
        int minX = ((int) Math.floor(bounds.minX)) >> 4, maxX = ((int) Math.floor(bounds.maxX)) >> 4;
        int minZ = ((int) Math.floor(bounds.minZ)) >> 4, maxZ = ((int) Math.floor(bounds.maxZ)) >> 4;
        List<ChunkPos> result = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            result.add(new ChunkPos(x, z));
        return List.copyOf(result);
    }

    private static final class Held {
        private final ServerLevel level;
        private final List<ChunkPos> chunks;
        private int added;
        private Held(ServerLevel level, List<ChunkPos> chunks) {
            this.level = level;
            this.chunks = chunks;
        }
    }
}
