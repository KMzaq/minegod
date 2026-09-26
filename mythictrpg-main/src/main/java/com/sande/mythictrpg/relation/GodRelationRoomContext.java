package com.sande.mythictrpg.relation;

import com.sande.mythictrpg.ai.room.ConversationRoomSnapshot;
import com.sande.mythictrpg.ai.room.RoomType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Game-owned projection of one room speaker's current attitudes towards its other participants.
 * The current relation schema has no secret/public attitude field: score and tags are demeanor
 * inputs in either room type. Cause IDs, transition IDs, history and other Gods' private attitudes
 * are deliberately absent from this contract. Secret relation facts require a separate disclosure
 * policy; being in a private room must never be interpreted as permission to expose those facts.
 */
public final class GodRelationRoomContext {
    private GodRelationRoomContext() { }

    /** Call on the game thread with the current ledger room and its complete authorized audience. */
    public static Snapshot capture(MinecraftServer server, ConversationRoomSnapshot room,
            ResourceLocation speakerGodId, Set<UUID> audiencePlayerIds) {
        requireGameThread(server);
        return capture(DynamicGodRelationState.get(server), room, speakerGodId, audiencePlayerIds);
    }

    /** Rebuild on the game thread just before using an asynchronous response or split decision. */
    public static boolean isCurrent(MinecraftServer server, Snapshot expected,
            ConversationRoomSnapshot currentRoom, Set<UUID> currentAudiencePlayerIds) {
        requireGameThread(server);
        return isCurrent(DynamicGodRelationState.get(server), expected, currentRoom, currentAudiencePlayerIds);
    }

    static Snapshot capture(GodRelationView state, ConversationRoomSnapshot room,
            ResourceLocation speakerGodId, Set<UUID> audiencePlayerIds) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(room, "room");
        Objects.requireNonNull(speakerGodId, "speakerGodId");
        Set<UUID> audience = Set.copyOf(audiencePlayerIds);
        var participants = room.godIds().stream().map(ResourceLocation::parse)
                .sorted(Comparator.comparing(ResourceLocation::toString)).toList();
        requireScope(room.type(), room.playerIds(), participants, speakerGodId, audience);
        var attitudes = new ArrayList<Attitude>();
        boolean available = state.isReady();
        if (available) for (ResourceLocation target : participants) {
            if (target.equals(speakerGodId)) continue;
            var value = state.find(speakerGodId, target);
            if (value.isPresent()) {
                var relation = value.orElseThrow();
                if (!relation.key().equals(new GodRelationKey(speakerGodId, target)))
                    throw new IllegalArgumentException("Relation source returned a different direction");
                attitudes.add(new Attitude(target, relation.score(), relation.tags(), relation.revision(), true));
            } else {
                // A missing direction is the existing neutral default, not proof of historical friendship.
                attitudes.add(new Attitude(target, 0, Set.of(), 0, false));
            }
        }
        return new Snapshot(room.roomId(), room.revision(), room.type(), speakerGodId,
                participants, room.playerIds(), audience, available, attitudes);
    }

    static boolean isCurrent(GodRelationView state, Snapshot expected,
            ConversationRoomSnapshot currentRoom, Set<UUID> currentAudiencePlayerIds) {
        if (expected == null || currentRoom == null || currentAudiencePlayerIds == null) return false;
        try {
            return expected.equals(capture(state, currentRoom, expected.speakerGodId(), currentAudiencePlayerIds));
        } catch (IllegalArgumentException invalidScope) {
            return false;
        }
    }

    private static void requireGameThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) throw new IllegalStateException("God relation room snapshot requires the game thread");
    }

    private static void requireScope(RoomType type, Set<UUID> players, List<ResourceLocation> gods,
            ResourceLocation speaker, Set<UUID> audience) {
        if (players.isEmpty() || players.size() > ConversationRoomSnapshot.MAX_PLAYERS
                || gods.isEmpty() || gods.size() > ConversationRoomSnapshot.MAX_GODS
                || new HashSet<>(gods).size() != gods.size() || !gods.contains(speaker)
                || !audience.containsAll(players)
                || type == RoomType.PRIVATE && !audience.equals(players))
            throw new IllegalArgumentException("Invalid God relation room audience or participants");
    }

    /** No causal/provenance text is carried across the AI boundary. */
    public record Attitude(ResourceLocation targetGodId, int score, Set<GodRelationTag> tags,
            long revision, boolean recorded) {
        public Attitude {
            Objects.requireNonNull(targetGodId, "targetGodId");
            tags = Set.copyOf(tags);
            if (score < GodRelationSnapshot.MIN_SCORE || score > GodRelationSnapshot.MAX_SCORE || revision < 0
                    || !recorded && (score != 0 || !tags.isEmpty() || revision != 0))
                throw new IllegalArgumentException("Invalid projected God attitude");
            GodRelationRules.requireCompatible(tags);
        }
    }

    /** Metadata is a revalidation token; only promptText should be included in the model prompt. */
    public record Snapshot(UUID roomId, long roomRevision, RoomType roomType,
            ResourceLocation speakerGodId, List<ResourceLocation> participantGodIds,
            Set<UUID> participantPlayerIds, Set<UUID> audiencePlayerIds,
            boolean available, List<Attitude> attitudes) {
        public Snapshot {
            Objects.requireNonNull(roomId, "roomId");
            Objects.requireNonNull(roomType, "roomType");
            Objects.requireNonNull(speakerGodId, "speakerGodId");
            participantGodIds = List.copyOf(participantGodIds);
            participantPlayerIds = Set.copyOf(participantPlayerIds);
            audiencePlayerIds = Set.copyOf(audiencePlayerIds);
            attitudes = List.copyOf(attitudes);
            if (roomRevision < 1) throw new IllegalArgumentException("Invalid room revision");
            requireScope(roomType, participantPlayerIds, participantGodIds, speakerGodId, audiencePlayerIds);
            Set<ResourceLocation> expectedTargets = new HashSet<>(participantGodIds);
            expectedTargets.remove(speakerGodId);
            if (!available && !attitudes.isEmpty() || available
                    && (attitudes.size() != expectedTargets.size()
                    || !expectedTargets.equals(attitudes.stream().map(Attitude::targetGodId)
                            .collect(java.util.stream.Collectors.toSet()))))
                throw new IllegalArgumentException("Projected attitudes do not match the room participants");
        }

        public String promptText() {
            if (!available || attitudes.stream().noneMatch(Attitude::recorded)) return "";
            var text = new StringBuilder("[CURRENT_GOD_ATTITUDES]\n")
                    .append("Game-owned current demeanor of this speaker towards other Gods in this room. ")
                    .append("Directions are independent. Use these states for reactions and tone; do not invent ")
                    .append("their causes, past events, secret facts, relationship tiers or additional participants. ")
                    .append("Static background relations remain separate.\n");
            for (var attitude : attitudes) if (attitude.recorded()) {
                text.append(speakerGodId).append(" -> ").append(attitude.targetGodId())
                        .append(": score=").append(attitude.score()).append("; stateTags=")
                        .append(attitude.tags().stream().map(Enum::name).sorted().toList()).append('\n');
            }
            return text.toString();
        }
    }
}
