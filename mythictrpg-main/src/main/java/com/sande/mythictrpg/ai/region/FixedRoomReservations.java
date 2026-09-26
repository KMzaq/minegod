package com.sande.mythictrpg.ai.region;

import com.sande.mythictrpg.ai.room.RecordingScope;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-thread pre-commit leases. These reserve capacity and region handles without creating a room. */
public final class FixedRoomReservations {
    public record Reservation(UUID interactionId, UUID playerId, List<String> godIds,
                              RecordingScope recordingScope, ConnectedBiomeRegions.Region region) {}

    private final ConnectedBiomeRegions regions;
    private final int maxRooms;
    private final Map<UUID, Reservation> pending = new LinkedHashMap<>();

    public FixedRoomReservations(ConnectedBiomeRegions regions, int maxRooms) {
        this.regions = Objects.requireNonNull(regions);
        if (maxRooms < 1) throw new IllegalArgumentException("Invalid room capacity");
        this.maxRooms = maxRooms;
    }

    public boolean hasCapacity(int activeRooms) { return activeRooms >= 0 && activeRooms + pending.size() < maxRooms; }

    public boolean participantsAvailable(UUID player, Collection<String> gods) {
        return pending.values().stream().noneMatch(r -> r.playerId().equals(player)
                || r.godIds().stream().anyMatch(gods::contains));
    }

    public boolean regionAvailable(ConnectedBiomeRegions.Cell cell) {
        return pending.values().stream().allMatch(r -> regions.membership(r.region(), cell)
                == ConnectedBiomeRegions.Membership.DIFFERENT);
    }

    /** Active-room ownership and active fixed-region checks are the caller's responsibility. */
    public Optional<Reservation> reserve(UUID interactionId, UUID player, Collection<String> gods,
            RecordingScope recording, ConnectedBiomeRegions.Cell origin, int activeRooms) {
        Objects.requireNonNull(interactionId); Objects.requireNonNull(player); Objects.requireNonNull(recording);
        var ids = List.copyOf(gods);
        if (ids.isEmpty() || ids.stream().anyMatch(String::isBlank) || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("Invalid God participants");
        }
        if (pending.containsKey(interactionId) || !hasCapacity(activeRooms)
                || !participantsAvailable(player, ids) || !regionAvailable(origin)) return Optional.empty();
        var region = regions.open(origin);
        if (region == null) return Optional.empty();
        var reservation = new Reservation(interactionId, player, ids, recording, region);
        pending.put(interactionId, reservation);
        return Optional.of(reservation);
    }

    public Optional<Reservation> find(UUID interactionId) { return Optional.ofNullable(pending.get(interactionId)); }

    /** Transfer the region to a successfully created room. Idempotent final release then does nothing. */
    public boolean consume(Reservation reservation) {
        return reservation != null && pending.remove(reservation.interactionId(), reservation);
    }

    public void release(Reservation reservation) {
        if (consume(reservation)) regions.release(reservation.region());
    }

    public void clear() {
        for (var reservation : List.copyOf(pending.values())) release(reservation);
    }

    public int size() { return pending.size(); }
}
