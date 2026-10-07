package com.sande.mythictrpg.ai.room;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded in-memory room authority. Only the game runtime may call mutators, after resolving
 * current positions, connected regions, God identities and any proposed God decisions.
 * No chat history or memory is copied by this ledger. New mobile merge/split rooms have new UUIDs;
 * a retained fixed room advances revision so old asynchronous work is rejected.
 */
public final class ConversationRoomLedger {
    public static final int MAX_ROOMS = 256;
    public static final int MAX_ROOMS_PER_PLAYER = 64;
    public static final int MAX_INVITATIONS_PER_ROOM = 64;
    public static final double MOBILE_RADIUS_BLOCKS = 16.0;

    /** Assertion issued by the game after its current position/region check. Never model input. */
    public enum Admission { PUBLIC_RANGE }

    public record RoomInvitation(UUID token, UUID roomId, long revision, String inviterGodId,
            UUID invitedPlayerId) {
        public RoomInvitation {
            Objects.requireNonNull(token);
            Objects.requireNonNull(roomId);
            Objects.requireNonNull(invitedPlayerId);
            ConversationRoomSnapshot.validateGodId(inviterGodId);
            if (revision < 1) throw new IllegalArgumentException("Invalid invitation revision");
        }
    }

    public record TurnLease(UUID roomId, long revision, UUID turnId, UUID playerId, String godId, long sequence) {
        /** Legacy fixture/caller has no game-issued ordering proof. Zero is explicitly unknown. */
        public TurnLease(UUID roomId, long revision, UUID turnId, UUID playerId, String godId) {
            this(roomId, revision, turnId, playerId, godId, 0);
        }
        public TurnLease {
            Objects.requireNonNull(roomId);
            Objects.requireNonNull(turnId);
            Objects.requireNonNull(playerId);
            ConversationRoomSnapshot.validateGodId(godId);
            if (revision < 1 || sequence < 0) throw new IllegalArgumentException("Invalid turn revision/sequence");
        }
    }

    private final Map<UUID, ConversationRoomSnapshot> rooms = new LinkedHashMap<>();
    private final Map<UUID, UUID> selectedPrivate = new HashMap<>();
    private final Map<UUID, RoomInvitation> invitations = new LinkedHashMap<>();
    private final Map<UUID, TurnLease> pendingTurns = new HashMap<>();
    private final Map<UUID, Long> turnSequences = new HashMap<>();

    public synchronized ConversationRoomSnapshot create(RoomType type, UUID creator,
            Collection<String> godIds, String regionId, RecordingScope recordingScope) {
        Objects.requireNonNull(creator, "creator");
        Objects.requireNonNull(godIds, "godIds");
        if (rooms.size() >= MAX_ROOMS) throw rejected("Room capacity reached");
        ensurePlayerRoomCapacity(creator);
        var room = new ConversationRoomSnapshot(UUID.randomUUID(), allocateCode(Set.of(), Set.of()),
                type, 1, Set.of(creator), new LinkedHashSet<>(godIds), regionId, recordingScope);
        if (room.type().isPublic()) {
            ensurePublicPlayerAvailable(creator, null);
            for (String god : room.godIds()) ensurePublicGodAvailable(god, null);
        }
        if (type == RoomType.PUBLIC_FIXED && rooms.values().stream().anyMatch(existing ->
                existing.type() == RoomType.PUBLIC_FIXED && existing.regionId().equals(room.regionId()))) {
            throw rejected("Connected region already has a fixed room");
        }
        rooms.put(room.roomId(), room);
        if (type == RoomType.PRIVATE) selectedPrivate.put(creator, room.roomId());
        return room;
    }

    /** Private rooms can only be joined through an invitation capability. */
    public synchronized ConversationRoomSnapshot join(UUID roomId, long expectedRevision, UUID playerId,
            Admission admission) {
        var room = require(roomId, expectedRevision);
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(admission, "admission");
        if (!room.type().isPublic()) throw rejected("Private room requires a God invitation");
        ensurePublicPlayerAvailable(playerId, roomId);
        return addPlayer(room, playerId);
    }

    /** The runtime must first validate that this participating God actually approved the invite. */
    public synchronized RoomInvitation invite(UUID roomId, long expectedRevision, String inviterGodId,
            UUID invitedPlayerId) {
        var room = require(roomId, expectedRevision);
        Objects.requireNonNull(invitedPlayerId, "invitedPlayerId");
        if (room.type() != RoomType.PRIVATE || !room.godIds().contains(inviterGodId)) {
            throw rejected("Only a participating God may invite to a private room");
        }
        if (room.playerIds().contains(invitedPlayerId)) throw rejected("Player already participates");
        Optional<RoomInvitation> prior = invitations.values().stream().filter(invite ->
                invite.roomId().equals(roomId) && invite.invitedPlayerId().equals(invitedPlayerId)).findFirst();
        if (prior.isPresent()) return prior.get();
        if (invitations.values().stream().filter(invite -> invite.roomId().equals(roomId)).count()
                >= MAX_INVITATIONS_PER_ROOM) throw rejected("Invitation capacity reached");
        var invitation = new RoomInvitation(UUID.randomUUID(), roomId, room.revision(), inviterGodId, invitedPlayerId);
        invitations.put(invitation.token(), invitation);
        return invitation;
    }

    /** Capabilities are single-use and expire on any room revision, end, merge or split. */
    public synchronized ConversationRoomSnapshot acceptInvitation(RoomInvitation invitation, UUID acceptingPlayerId) {
        Objects.requireNonNull(invitation, "invitation");
        if (!invitation.equals(invitations.get(invitation.token()))
                || !invitation.invitedPlayerId().equals(acceptingPlayerId)) {
            throw rejected("Invitation is missing or belongs to another player");
        }
        var room = require(invitation.roomId(), invitation.revision());
        if (room.type() != RoomType.PRIVATE || !room.godIds().contains(invitation.inviterGodId())) {
            throw rejected("Inviting God no longer participates");
        }
        var joined = addPlayer(room, acceptingPlayerId);
        invitations.remove(invitation.token());
        selectedPrivate.put(acceptingPlayerId, room.roomId());
        return joined;
    }

    public synchronized List<RoomInvitation> invitationsFor(UUID playerId) {
        return invitations.values().stream().filter(invite -> invite.invitedPlayerId().equals(playerId)).toList();
    }

    public synchronized ConversationRoomSnapshot selectPrivate(UUID playerId, UUID roomId) {
        var room = require(roomId);
        if (room.type() != RoomType.PRIVATE || !room.playerIds().contains(playerId)) {
            throw rejected("Player is not a participant of this private room");
        }
        selectedPrivate.put(playerId, roomId);
        return room;
    }

    public synchronized void deselectPrivate(UUID playerId) {
        selectedPrivate.remove(playerId);
    }

    /** Leaving the final player closes the room and releases every God, region and display code. */
    public synchronized Optional<ConversationRoomSnapshot> leave(UUID roomId, long expectedRevision, UUID playerId) {
        var room = require(roomId, expectedRevision);
        if (!room.playerIds().contains(playerId)) throw rejected("Player does not participate");
        if (room.playerIds().size() == 1) {
            remove(roomId);
            return Optional.empty();
        }
        var players = new LinkedHashSet<>(room.playerIds());
        players.remove(playerId);
        var updated = changed(room, players, room.godIds());
        replace(updated);
        selectedPrivate.remove(playerId, roomId);
        return Optional.of(updated);
    }

    public synchronized void end(UUID roomId, long expectedRevision) {
        require(roomId, expectedRevision);
        remove(roomId);
    }

    /** Game-authorized identity admission; there is no model-facing participant mutation port. */
    public synchronized ConversationRoomSnapshot addGod(UUID roomId, long expectedRevision, String godId) {
        var room = require(roomId, expectedRevision);
        ConversationRoomSnapshot.validateGodId(godId);
        if (room.godIds().contains(godId)) return room;
        if (room.type().isPublic()) ensurePublicGodAvailable(godId, roomId);
        var gods = new LinkedHashSet<>(room.godIds());
        gods.add(godId);
        var updated = changed(room, room.playerIds(), gods);
        replace(updated);
        return updated;
    }

    public synchronized Optional<ConversationRoomSnapshot> removeGod(UUID roomId, long expectedRevision, String godId) {
        var room = require(roomId, expectedRevision);
        if (!room.godIds().contains(godId)) throw rejected("God does not participate");
        if (room.godIds().size() == 1) {
            remove(roomId);
            return Optional.empty();
        }
        var gods = new LinkedHashSet<>(room.godIds());
        gods.remove(godId);
        var updated = changed(room, room.playerIds(), gods);
        replace(updated);
        return Optional.of(updated);
    }

    /** Runtime calls only after detecting touching mobile rooms or mobile entry into a fixed region. */
    public synchronized ConversationRoomSnapshot merge(UUID firstId, long firstRevision,
            UUID secondId, long secondRevision) {
        var first = require(firstId, firstRevision);
        var second = require(secondId, secondRevision);
        if (firstId.equals(secondId) || !first.type().isPublic() || !second.type().isPublic()
                || (first.type() == RoomType.PUBLIC_FIXED && second.type() == RoomType.PUBLIC_FIXED)) {
            throw rejected("Only distinct public rooms with at least one mobile room may merge");
        }
        if (first.recordingScope() != second.recordingScope()) {
            throw rejected("Different recording scopes cannot merge");
        }
        var players = new LinkedHashSet<>(first.playerIds());
        players.addAll(second.playerIds());
        var gods = new LinkedHashSet<>(first.godIds());
        gods.addAll(second.godIds());
        ConversationRoomSnapshot fixed = first.type() == RoomType.PUBLIC_FIXED ? first
                : second.type() == RoomType.PUBLIC_FIXED ? second : null;
        var merged = fixed == null
                ? new ConversationRoomSnapshot(UUID.randomUUID(), allocateCode(Set.of(firstId, secondId), Set.of()),
                    RoomType.PUBLIC_MOBILE, 1, players, gods, "", first.recordingScope())
                : changed(fixed, players, gods);
        remove(firstId);
        remove(secondId);
        rooms.put(merged.roomId(), merged);
        return merged;
    }

    public synchronized List<ConversationRoomSnapshot> split(UUID roomId, long expectedRevision,
            List<Set<UUID>> serverComponents, Map<String, Integer> validatedGodDestinations) {
        return split(roomId, expectedRevision, serverComponents, validatedGodDestinations, -1);
    }

    /**
     * Components must be a complete disjoint game-computed player partition. God destinations are
     * proposals already validated by the game; they must assign every existing God exactly once,
     * using -1 for an explicit departure. A Godless component leaves the conversation and produces
     * no child room; when every God departs the source closes without any children. For fixed-room
     * departures, retainedFixedComponent identifies the group still inside the origin region;
     * -1 releases the fixed region. Other surviving components become mobile with fresh UUIDs.
     */
    public synchronized List<ConversationRoomSnapshot> split(UUID roomId, long expectedRevision,
            List<Set<UUID>> serverComponents, Map<String, Integer> validatedGodDestinations,
            int retainedFixedComponent) {
        var room = require(roomId, expectedRevision);
        Objects.requireNonNull(serverComponents, "serverComponents");
        Objects.requireNonNull(validatedGodDestinations, "validatedGodDestinations");
        if (!room.type().isPublic() || serverComponents.isEmpty()
                || serverComponents.size() > ConversationRoomSnapshot.MAX_PLAYERS
                || retainedFixedComponent < -1 || retainedFixedComponent >= serverComponents.size()
                || (retainedFixedComponent >= 0 && room.type() != RoomType.PUBLIC_FIXED)) {
            throw rejected("Invalid public split");
        }
        var components = new ArrayList<Set<UUID>>();
        var allPlayers = new HashSet<UUID>();
        for (Set<UUID> component : serverComponents) {
            if (component == null || component.isEmpty() || component.stream().anyMatch(Objects::isNull)) {
                throw rejected("Split component must contain players");
            }
            var copy = new LinkedHashSet<>(component);
            for (UUID player : copy) if (!allPlayers.add(player)) throw rejected("Overlapping split components");
            components.add(copy);
        }
        if (!allPlayers.equals(room.playerIds()) || !validatedGodDestinations.keySet().equals(room.godIds())) {
            throw rejected("Split cannot add or omit participants");
        }
        var godsByComponent = new ArrayList<Set<String>>();
        for (int i = 0; i < components.size(); i++) godsByComponent.add(new LinkedHashSet<>());
        for (String god : room.godIds()) {
            Integer destination = validatedGodDestinations.get(god);
            if (destination == null || destination < -1 || destination >= components.size()) {
                throw rejected("Invalid God destination");
            }
            if (destination >= 0) godsByComponent.get(destination).add(god);
        }
        long children = godsByComponent.stream().filter(gods -> !gods.isEmpty()).count();
        if (rooms.size() - 1 + children > MAX_ROOMS) throw rejected("Room capacity reached");
        var results = new ArrayList<ConversationRoomSnapshot>();
        var reservedCodes = new HashSet<String>();
        if (retainedFixedComponent >= 0 && !godsByComponent.get(retainedFixedComponent).isEmpty()) {
            reservedCodes.add(room.code());
        }
        for (int i = 0; i < components.size(); i++) {
            if (godsByComponent.get(i).isEmpty()) continue;
            boolean fixed = i == retainedFixedComponent;
            String code = fixed ? room.code() : allocateCode(Set.of(roomId), reservedCodes);
            var child = new ConversationRoomSnapshot(fixed ? roomId : UUID.randomUUID(), code,
                    fixed ? RoomType.PUBLIC_FIXED : RoomType.PUBLIC_MOBILE,
                    fixed ? Math.incrementExact(room.revision()) : 1, components.get(i), godsByComponent.get(i),
                    fixed ? room.regionId() : "", room.recordingScope());
            reservedCodes.add(code);
            results.add(child);
        }
        remove(roomId);
        for (var child : results) rooms.put(child.roomId(), child);
        return List.copyOf(results);
    }

    /** A newer input supersedes the previous pending turn even without a membership revision. */
    public synchronized TurnLease beginTurn(UUID roomId, long expectedRevision, UUID playerId, String godId) {
        var room = require(roomId, expectedRevision);
        if (!room.playerIds().contains(playerId) || !room.godIds().contains(godId)) {
            throw rejected("Turn participants do not belong to this room");
        }
        long sequence = Math.incrementExact(turnSequences.getOrDefault(roomId, 0L));
        var lease = new TurnLease(roomId, expectedRevision, UUID.randomUUID(), playerId, godId, sequence);
        turnSequences.put(roomId, sequence);
        pendingTurns.put(roomId, lease);
        return lease;
    }

    public synchronized boolean isCurrent(TurnLease lease) {
        if (lease == null || !lease.equals(pendingTurns.get(lease.roomId()))) return false;
        var room = rooms.get(lease.roomId());
        return room != null && room.revision() == lease.revision()
                && room.playerIds().contains(lease.playerId()) && room.godIds().contains(lease.godId());
    }

    public synchronized boolean finishTurn(TurnLease lease) {
        if (!isCurrent(lease)) return false;
        pendingTurns.remove(lease.roomId());
        return true;
    }

    /** Includes completed turns so an asynchronous observer can reject an older room turn. */
    public synchronized long latestTurnSequence(UUID roomId) {
        return turnSequences.getOrDefault(roomId, 0L);
    }

    public synchronized Optional<ConversationRoomSnapshot> find(UUID roomId) {
        return Optional.ofNullable(rooms.get(roomId));
    }

    public synchronized Optional<ConversationRoomSnapshot> findByCode(String code) {
        if (code == null) return Optional.empty();
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return rooms.values().stream().filter(room -> room.code().equals(normalized)).findFirst();
    }

    public synchronized List<ConversationRoomSnapshot> activeRooms() {
        return List.copyOf(rooms.values());
    }

    public synchronized Optional<ConversationRoomSnapshot> publicRoomForPlayer(UUID playerId) {
        return rooms.values().stream().filter(room -> room.type().isPublic() && room.playerIds().contains(playerId)).findFirst();
    }

    public synchronized Optional<ConversationRoomSnapshot> publicRoomForGod(String godId) {
        return rooms.values().stream().filter(room -> room.type().isPublic() && room.godIds().contains(godId)).findFirst();
    }

    public synchronized Optional<ConversationRoomSnapshot> selectedPrivateRoom(UUID playerId) {
        return find(selectedPrivate.get(playerId));
    }

    private ConversationRoomSnapshot addPlayer(ConversationRoomSnapshot room, UUID playerId) {
        if (room.playerIds().contains(playerId)) return room;
        ensurePlayerRoomCapacity(playerId);
        var players = new LinkedHashSet<>(room.playerIds());
        players.add(playerId);
        var updated = changed(room, players, room.godIds());
        replace(updated);
        return updated;
    }

    private ConversationRoomSnapshot changed(ConversationRoomSnapshot room, Set<UUID> players, Set<String> gods) {
        return new ConversationRoomSnapshot(room.roomId(), room.code(), room.type(), Math.incrementExact(room.revision()),
                players, gods, room.regionId(), room.recordingScope());
    }

    private ConversationRoomSnapshot require(UUID roomId) {
        var room = rooms.get(roomId);
        if (room == null) throw rejected("Room is not active");
        return room;
    }

    private ConversationRoomSnapshot require(UUID roomId, long expectedRevision) {
        var room = require(roomId);
        if (room.revision() != expectedRevision) throw rejected("Stale room revision");
        return room;
    }

    private void ensurePublicPlayerAvailable(UUID playerId, UUID allowedRoomId) {
        if (publicRoomForPlayer(playerId).filter(room -> !room.roomId().equals(allowedRoomId)).isPresent()) {
            throw rejected("Player already participates in a public room");
        }
    }

    private void ensurePlayerRoomCapacity(UUID playerId) {
        if (rooms.values().stream().filter(room -> room.playerIds().contains(playerId)).count() >= MAX_ROOMS_PER_PLAYER) {
            throw rejected("Player room capacity reached");
        }
    }

    private void ensurePublicGodAvailable(String godId, UUID allowedRoomId) {
        if (publicRoomForGod(godId).filter(room -> !room.roomId().equals(allowedRoomId)).isPresent()) {
            throw rejected("God already participates in a public room");
        }
    }

    private void invalidate(UUID roomId) {
        pendingTurns.remove(roomId);
        invitations.values().removeIf(invite -> invite.roomId().equals(roomId));
    }

    private void replace(ConversationRoomSnapshot room) {
        invalidate(room.roomId());
        rooms.put(room.roomId(), room);
    }

    private void remove(UUID roomId) {
        rooms.remove(roomId);
        turnSequences.remove(roomId); // Only a destroyed identity resets; join/revision does not reset ordering.
        invalidate(roomId);
        selectedPrivate.values().removeIf(selected -> selected.equals(roomId));
    }

    private String allocateCode(Set<UUID> excludedRooms, Set<String> reservedCodes) {
        var used = new HashSet<>(reservedCodes);
        rooms.values().stream().filter(room -> !excludedRooms.contains(room.roomId())).forEach(room -> used.add(room.code()));
        for (int number = 1; number <= MAX_ROOMS; number++) {
            String code = codeFor(number);
            if (!used.contains(code)) return code;
        }
        throw rejected("Room code capacity reached");
    }

    /** One-based spreadsheet alphabet: A..Z, AA..AZ, BA.. . */
    public static String codeFor(int number) {
        if (number < 1 || number > MAX_ROOMS) throw new IllegalArgumentException("Invalid room code ordinal");
        var result = new StringBuilder();
        for (int value = number; value > 0; value /= 26) {
            value--;
            result.append((char) ('A' + value % 26));
        }
        return result.reverse().toString();
    }

    private static IllegalArgumentException rejected(String message) {
        return new IllegalArgumentException(message);
    }
}
