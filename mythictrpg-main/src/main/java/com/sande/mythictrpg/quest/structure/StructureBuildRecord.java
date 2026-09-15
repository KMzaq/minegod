package com.sande.mythictrpg.quest.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One locked quest region and its sparse, current placement ledger. */
public final class StructureBuildRecord {
    private final UUID ownerId;
    private final ResourceLocation questId;
    private final StructureRegion region;
    private final Set<UUID> eligibleContributors;
    private final long startedAtGameTick;
    private final Map<Long, PlacementRecord> placements = new LinkedHashMap<>();
    private final Map<UUID, PlacementRecord> decorations = new LinkedHashMap<>();
    private long lastEvaluationTick = Long.MIN_VALUE;

    public StructureBuildRecord(UUID ownerId, ResourceLocation questId, StructureRegion region,
            Set<UUID> eligibleContributors, long startedAtGameTick) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.questId = Objects.requireNonNull(questId, "questId");
        this.region = Objects.requireNonNull(region, "region");
        LinkedHashSet<UUID> contributors = new LinkedHashSet<>(eligibleContributors);
        contributors.add(ownerId);
        this.eligibleContributors = Set.copyOf(contributors);
        this.startedAtGameTick = startedAtGameTick;
    }

    public UUID ownerId() { return ownerId; }
    public ResourceLocation questId() { return questId; }
    public StructureRegion region() { return region; }
    public Set<UUID> eligibleContributors() { return eligibleContributors; }
    public long startedAtGameTick() { return startedAtGameTick; }
    public Map<Long, PlacementRecord> placements() { return Map.copyOf(placements); }
    public Map<UUID, PlacementRecord> decorations() { return Map.copyOf(decorations); }
    public long lastEvaluationTick() { return lastEvaluationTick; }
    void markEvaluated(long tick) { lastEvaluationTick = tick; }
    void restoreLastEvaluationTick(long tick) { lastEvaluationTick = tick; }

    boolean record(BlockPos pos, PlacementRecord placement, int hardLimit) {
        if (!region.contains(pos) || !eligibleContributors.contains(placement.placerId())) return false;
        if (hardLimit > 0 && !placements.containsKey(pos.asLong()) && placements.size() >= hardLimit) return false;
        placements.put(pos.asLong(), placement);
        return true;
    }

    boolean remove(BlockPos pos) { return placements.remove(pos.asLong()) != null; }
    boolean recordDecoration(UUID entityId, PlacementRecord placement) {
        if (!eligibleContributors.contains(placement.placerId())) return false;
        decorations.put(entityId, placement);
        return true;
    }
    boolean removeDecoration(UUID entityId) { return decorations.remove(entityId) != null; }

    void restorePlacement(long position, PlacementRecord record) { placements.put(position, record); }
    void restoreDecoration(UUID entityId, PlacementRecord record) { decorations.put(entityId, record); }
}
