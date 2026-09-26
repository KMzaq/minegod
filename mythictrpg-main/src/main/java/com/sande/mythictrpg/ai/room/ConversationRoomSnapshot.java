package com.sande.mythictrpg.ai.room;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable game-owned participation authority. Viewers of public output are deliberately absent:
 * seeing a message does not admit a player or God to its conversation or to private memory.
 * regionId is an opaque game-issued connected-region identity including its dimension.
 */
public record ConversationRoomSnapshot(UUID roomId, String code, RoomType type, long revision,
        Set<UUID> playerIds, Set<String> godIds, String regionId, RecordingScope recordingScope) {
    public static final int MAX_PLAYERS = 64;
    public static final int MAX_GODS = 16;

    public ConversationRoomSnapshot {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(recordingScope, "recordingScope");
        code = Objects.requireNonNull(code, "code");
        if (!code.matches("[A-Z]{1,8}") || revision < 1) {
            throw new IllegalArgumentException("Invalid room code or revision");
        }
        playerIds = orderedCopy(playerIds);
        godIds = orderedCopy(godIds);
        if (playerIds.isEmpty() || playerIds.size() > MAX_PLAYERS
                || godIds.isEmpty() || godIds.size() > MAX_GODS) {
            throw new IllegalArgumentException("Room requires 1..64 players and 1..16 Gods");
        }
        for (String godId : godIds) validateGodId(godId);
        regionId = regionId == null ? "" : regionId;
        if (regionId.length() > 512 || (type == RoomType.PUBLIC_FIXED) != !regionId.isBlank()) {
            throw new IllegalArgumentException("Only a fixed room has a connected-region identity");
        }
    }

    static String validateGodId(String godId) {
        if (godId == null || godId.isBlank() || godId.length() > 128 || !godId.equals(godId.trim())) {
            throw new IllegalArgumentException("Invalid God identity");
        }
        return godId;
    }

    private static <T> Set<T> orderedCopy(Set<T> source) {
        Objects.requireNonNull(source, "participants");
        if (source.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Null participant");
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
