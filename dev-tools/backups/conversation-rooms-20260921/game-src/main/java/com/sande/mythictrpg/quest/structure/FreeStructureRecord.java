package com.sande.mythictrpg.quest.structure;

import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A named player/team construction which exists independently of quests. */
public final class FreeStructureRecord {
    private final UUID id;
    private final UUID ownerId;
    private final String name;
    private final StructureRegion region;
    private final Set<UUID> eligibleContributors;
    private final long registeredAtGameTick;
    private final Map<Long, PlacementRecord> placements = new LinkedHashMap<>();
    private final Map<UUID, PlacementRecord> decorations = new LinkedHashMap<>();

    public FreeStructureRecord(UUID id, UUID ownerId, String name, StructureRegion region,
            Set<UUID> contributors, long registeredAtGameTick) {
        this.id = Objects.requireNonNull(id); this.ownerId = Objects.requireNonNull(ownerId);
        this.name = validateName(name); this.region = Objects.requireNonNull(region);
        LinkedHashSet<UUID> frozen = new LinkedHashSet<>(contributors); frozen.add(ownerId);
        this.eligibleContributors = Set.copyOf(frozen);
        this.registeredAtGameTick = registeredAtGameTick;
    }

    public UUID id() { return id; }
    public UUID ownerId() { return ownerId; }
    public String name() { return name; }
    public StructureRegion region() { return region; }
    public Set<UUID> eligibleContributors() { return eligibleContributors; }
    public long registeredAtGameTick() { return registeredAtGameTick; }
    public Map<Long, PlacementRecord> placements() { return Map.copyOf(placements); }
    public Map<UUID, PlacementRecord> decorations() { return Map.copyOf(decorations); }

    boolean record(BlockPos pos, PlacementRecord placement) {
        if (!region.contains(pos) || !eligibleContributors.contains(placement.placerId())) return false;
        placements.put(pos.asLong(), placement); return true;
    }
    boolean remove(BlockPos pos) { return placements.remove(pos.asLong()) != null; }
    boolean recordDecoration(UUID entityId, PlacementRecord placement) {
        if (!eligibleContributors.contains(placement.placerId())) return false;
        decorations.put(entityId, placement); return true;
    }
    void restorePlacement(long pos, PlacementRecord placement) { placements.put(pos, placement); }
    void restoreDecoration(UUID id, PlacementRecord placement) { decorations.put(id, placement); }

    private static String validateName(String raw) {
        String value = Objects.requireNonNull(raw).trim();
        if (value.isEmpty() || value.length() > 48) throw new IllegalArgumentException("건축물 이름은 1~48자여야 합니다");
        return value;
    }
}
