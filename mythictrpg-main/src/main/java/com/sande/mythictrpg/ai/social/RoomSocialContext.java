package com.sande.mythictrpg.ai.social;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.room.ConversationRoomSnapshot;
import com.sande.mythictrpg.ai.room.RoomType;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/** Immutable, audience-filtered social inputs, separate from AI interpretations and actual game actions. */
public final class RoomSocialContext {
    public static final int MAX_AUDIENCE = 1024;
    public static final int MAX_PROVIDERS = 8;
    public static final int MAX_VISIBLE_FACTS = 12;
    public static final int MAX_PROMPT_CHARACTERS = 10000;
    public static final String UNASSESSED_EMOTION = "E_UNASSESSED";
    public enum Purpose { TURN, SYSTEM_SPLIT_CONTEXT }
    private static final Gson JSON = new Gson();
    private static final Map<MinecraftServer, Registry> REGISTRIES = new WeakHashMap<>();
    private RoomSocialContext() { }

    private static final class Registry {
        long revision = 1;
        final Map<ResourceLocation, RoomSocialContextProvider> providers = new LinkedHashMap<>();
    }

    /** Server integrations register authoritative read adapters, never model-authored facts. No adapters means UNKNOWN. */
    public static void register(MinecraftServer server, ResourceLocation id, RoomSocialContextProvider provider) {
        requireGameThread(server);
        Objects.requireNonNull(id); Objects.requireNonNull(provider);
        var registry = REGISTRIES.computeIfAbsent(server, ignored -> new Registry());
        if (registry.providers.containsKey(id) || registry.providers.size() >= MAX_PROVIDERS)
            throw new IllegalArgumentException("Duplicate or excessive social context provider: " + id);
        registry.providers.put(id, provider);
        registry.revision++;
    }

    public static void unregister(MinecraftServer server, ResourceLocation id) {
        requireGameThread(server);
        var registry = REGISTRIES.get(server);
        if (registry != null && registry.providers.remove(id) != null) registry.revision++;
    }

    /** Server shutdown only. Resetting a conversation must not unregister integrations for that server. */
    public static void clear(MinecraftServer server) {
        requireGameThread(server);
        REGISTRIES.remove(server);
    }

    public static Snapshot capture(MinecraftServer server, ConversationRoomSnapshot room, ResourceLocation speakerGodId,
            UUID currentPlayerId, Set<UUID> audiencePlayerIds) {
        requireGameThread(server);
        var scope = Scope.of(room, speakerGodId, currentPlayerId, audiencePlayerIds);
        return capture(server, scope);
    }

    public static Snapshot captureForSplit(MinecraftServer server, ConversationRoomSnapshot room,
            ResourceLocation speakerGodId, Set<UUID> audiencePlayerIds) {
        requireGameThread(server);
        return capture(server, Scope.forSplit(room, speakerGodId, audiencePlayerIds));
    }

    private static Snapshot capture(MinecraftServer server, Scope scope) {
        var policy = AffinityTierPolicy.load(server.getServerDirectory().resolve(AffinityTierPolicy.CONFIG_PATH));
        var data = PlayerMythDataService.get(server);
        if (!data.isReady()) throw new IllegalStateException("Game affinity data unavailable");
        var registry = REGISTRIES.get(server);
        return capture(scope, policy, id -> data.find(id).map(profile -> {
                    Integer value = profile.affinities().get(scope.speakerGodId());
                    return value == null ? Affinity.defaultZero() : new Affinity(value, false);
                }).orElseGet(Affinity::defaultZero),
                registry == null ? 1 : registry.revision, registry == null ? Map.of() : Map.copyOf(registry.providers));
    }

    /** Includes affinity, interpretation policy, provider revisions, evidence and disclosure changes. */
    public static boolean isCurrent(MinecraftServer server, Snapshot expected, ConversationRoomSnapshot room,
            UUID currentPlayerId, Set<UUID> audiencePlayerIds) {
        requireGameThread(server);
        return isCurrent(expected, () -> capture(server, room, expected.scope().speakerGodId(), currentPlayerId, audiencePlayerIds));
    }

    static boolean isCurrent(Snapshot expected, Supplier<Snapshot> recapture) {
        if (expected == null) return false;
        try {
            return expected.equals(recapture.get());
        } catch (RuntimeException unavailable) {
            return false; // Provider/config errors cannot authorize a response built from earlier facts.
        }
    }

    public static boolean isCurrentForSplit(MinecraftServer server, Snapshot expected, ConversationRoomSnapshot room,
            Set<UUID> audiencePlayerIds) {
        requireGameThread(server);
        return isCurrent(expected, () -> captureForSplit(server, room, expected.scope().speakerGodId(), audiencePlayerIds));
    }

    /** Pure projection seam used by offline tests; runtime callers must use the game-thread entry point. */
    static Snapshot capture(Scope scope, AffinityTierPolicy policy, Function<UUID, Affinity> affinity,
            long providerRegistryRevision, Map<ResourceLocation, RoomSocialContextProvider> providers) {
        Objects.requireNonNull(scope); Objects.requireNonNull(policy); Objects.requireNonNull(affinity);
        if (providerRegistryRevision < 1 || providers.size() > MAX_PROVIDERS)
            throw new IllegalArgumentException("Invalid social provider registry");
        var relationships = scope.participantPlayerIds().stream().sorted().map(id -> {
            var value = Objects.requireNonNull(affinity.apply(id), "Missing affinity lookup result");
            return new Relationship(id, value.value(), policy.tier(value.value()),
                    value.defaulted() ? "GAME_DEFAULT_ZERO" : "STORED_GAME_AFFINITY");
        }).toList();
        var revisions = new LinkedHashMap<ResourceLocation, Long>();
        var visible = new ArrayList<RoomSocialContextProvider.Fact>();
        for (var entry : providers.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            RoomSocialContextProvider.ProviderSnapshot supplied;
            try {
                supplied = Objects.requireNonNull(entry.getValue().capture(scope), "Provider returned null");
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("Social context provider unavailable: " + entry.getKey(), invalid);
            }
            if (!scope.equals(supplied.scope()) || !entry.getKey().equals(supplied.providerId()))
                throw new IllegalArgumentException("Foreign social provider scope or identity");
            revisions.put(entry.getKey(), supplied.revision());
            for (var fact : supplied.facts()) {
                if (!scope.participantPlayerIds().contains(fact.subjectPlayerId())
                        || !fact.knownByGodIds().contains(scope.speakerGodId()) || !fact.publication().permits(scope)) continue;
                visible.add(fact);
                if (visible.size() > MAX_VISIBLE_FACTS) throw new IllegalArgumentException("Too many published social facts");
            }
        }
        visible.sort(Comparator.comparing((RoomSocialContextProvider.Fact fact) -> fact.sourceId().toString())
                .thenComparing(RoomSocialContextProvider.Fact::id));
        var result = new Snapshot(scope, policy, providerRegistryRevision, revisions, relationships, visible);
        result.promptText(); // Fail closed if mandatory scoped data cannot fit the bounded contract.
        return result;
    }

    private static void requireGameThread(MinecraftServer server) {
        Objects.requireNonNull(server);
        if (!server.isSameThread()) throw new IllegalStateException("Social context requires the game thread");
    }

    public record Scope(UUID roomId, long roomRevision, RoomType roomType, ResourceLocation speakerGodId,
            UUID currentPlayerId, Set<UUID> participantPlayerIds, Set<ResourceLocation> participantGodIds,
            Set<UUID> audiencePlayerIds, Purpose purpose) {
        public Scope(UUID roomId, long roomRevision, RoomType roomType, ResourceLocation speakerGodId,
                UUID currentPlayerId, Set<UUID> participantPlayerIds, Set<ResourceLocation> participantGodIds,
                Set<UUID> audiencePlayerIds) {
            this(roomId, roomRevision, roomType, speakerGodId, currentPlayerId, participantPlayerIds,
                    participantGodIds, audiencePlayerIds, Purpose.TURN);
        }
        public Scope {
            Objects.requireNonNull(roomId); Objects.requireNonNull(roomType); Objects.requireNonNull(speakerGodId);
            Objects.requireNonNull(purpose);
            participantPlayerIds = Set.copyOf(participantPlayerIds); participantGodIds = Set.copyOf(participantGodIds);
            audiencePlayerIds = Set.copyOf(audiencePlayerIds);
            if (roomRevision < 1 || participantPlayerIds.isEmpty() || participantPlayerIds.size() > ConversationRoomSnapshot.MAX_PLAYERS
                    || participantGodIds.isEmpty() || participantGodIds.size() > ConversationRoomSnapshot.MAX_GODS
                    || (purpose == Purpose.TURN) != (currentPlayerId != null)
                    || currentPlayerId != null && !participantPlayerIds.contains(currentPlayerId)
                    || !participantGodIds.contains(speakerGodId)
                    || !audiencePlayerIds.containsAll(participantPlayerIds) || audiencePlayerIds.size() > MAX_AUDIENCE
                    || roomType == RoomType.PRIVATE && !audiencePlayerIds.equals(participantPlayerIds))
                throw new IllegalArgumentException("Invalid social room scope");
        }
        public static Scope of(ConversationRoomSnapshot room, ResourceLocation god, UUID player, Set<UUID> audience) {
            return new Scope(room.roomId(), room.revision(), room.type(), god, player, room.playerIds(),
                    room.godIds().stream().map(ResourceLocation::parse).collect(java.util.stream.Collectors.toSet()), audience);
        }
        public static Scope forSplit(ConversationRoomSnapshot room, ResourceLocation god, Set<UUID> audience) {
            return new Scope(room.roomId(), room.revision(), room.type(), god, null, room.playerIds(),
                    room.godIds().stream().map(ResourceLocation::parse).collect(java.util.stream.Collectors.toSet()),
                    audience, Purpose.SYSTEM_SPLIT_CONTEXT);
        }
    }

    /** A missing entry in a ready game store has the existing authoritative zero default. Unavailability is an error. */
    public record Affinity(int value, boolean defaulted) {
        public Affinity {
            if (value < -1000 || value > 1000 || defaulted && value != 0)
                throw new IllegalArgumentException("Invalid game affinity value");
        }
        public static Affinity defaultZero() { return new Affinity(0, true); }
    }

    public record Relationship(UUID playerId, int affinity, String tier, String source) {
        public Relationship {
            Objects.requireNonNull(playerId);
            if (affinity < -1000 || affinity > 1000 || !AffinityTierPolicy.TAGS.contains(tier)
                    || !Set.of("GAME_DEFAULT_ZERO", "STORED_GAME_AFFINITY").contains(source)
                    || source.equals("GAME_DEFAULT_ZERO") && affinity != 0)
                throw new IllegalArgumentException("Invalid affinity interpretation");
        }
    }

    /** Revalidation metadata stays outside the model's JSON. Only promptText is a disclosure projection. */
    public record Snapshot(Scope scope, AffinityTierPolicy policy, long providerRegistryRevision,
            Map<ResourceLocation, Long> providerRevisions, List<Relationship> relationships,
            List<RoomSocialContextProvider.Fact> facts) {
        public Snapshot {
            Objects.requireNonNull(scope); Objects.requireNonNull(policy);
            providerRevisions = Map.copyOf(providerRevisions); relationships = List.copyOf(relationships); facts = List.copyOf(facts);
            var sources = providerRevisions;
            if (providerRegistryRevision < 1 || providerRevisions.size() > MAX_PROVIDERS || facts.size() > MAX_VISIBLE_FACTS
                    || providerRevisions.values().stream().anyMatch(revision -> revision < 1)
                    || relationships.size() != scope.participantPlayerIds().size()
                    || !relationships.stream().map(Relationship::playerId).collect(java.util.stream.Collectors.toSet())
                        .equals(scope.participantPlayerIds())
                    || relationships.stream().anyMatch(value -> !policy.tier(value.affinity()).equals(value.tier()))
                    || facts.stream().anyMatch(fact -> !sources.containsKey(fact.sourceId())
                        || !scope.participantPlayerIds().contains(fact.subjectPlayerId())
                        || !fact.knownByGodIds().contains(scope.speakerGodId()) || !fact.publication().permits(scope)))
                throw new IllegalArgumentException("Invalid social snapshot ownership or disclosure");
        }
        public String currentPlayerTier() {
            if (scope.purpose() != Purpose.TURN) throw new IllegalStateException("A split decision has no current player tier");
            var relationship = relationships.stream().filter(value -> value.playerId().equals(scope.currentPlayerId())).findFirst().orElseThrow();
            return relationship.tier();
        }
        public String promptText() {
            var data = new LinkedHashMap<String, Object>();
            data.put("schemaVersion", 1);
            data.put("speakerGodId", scope.speakerGodId().toString());
            data.put("purpose", scope.purpose().name());
            if (scope.currentPlayerId() != null) data.put("currentPlayerId", scope.currentPlayerId().toString());
            else data.put("splitContext", "No current player. Every supplied relationship belongs only to its named player; choose a group without assuming one player has priority.");
            data.put("affinityPolicy", Map.of("source", policy.source(), "revision", policy.revision(),
                    "thresholds", policy.thresholds(), "boundaryRule", "four negative upper-inclusive bounds; four positive lower-inclusive bounds",
                    "meaning", "affinity interpretation only; not power, obedience, intimacy entitlement or current emotion"));
            data.put("relationshipAssessment", "GAME_AFFINITY");
            data.put("relationships", relationships.stream().map(value -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("playerId", value.playerId().toString());
                item.put("source", value.source());
                item.put("affinity", value.affinity());
                item.put("tier", value.tier());
                return item;
            }).toList());
            data.put("emotion", Map.of("assessment", "UNASSESSED", "tag", UNASSESSED_EMOTION));
            data.put("unknownFacts", "Unlisted power, patronage, obligation or reputation is UNKNOWN, not weak/none. Facts do not dictate the God's judgement or introduce participants.");
            var assessments = new LinkedHashMap<String, String>();
            for (var kind : RoomSocialContextProvider.Kind.values()) assessments.put(kind.name(),
                    facts.stream().anyMatch(fact -> fact.kind() == kind) ? "PARTIAL_CONFIRMED_EVIDENCE" : "UNKNOWN");
            data.put("factAssessments", assessments);
            data.put("confirmedFacts", facts.stream().map(fact -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("subjectPlayerId", fact.subjectPlayerId().toString()); item.put("kind", fact.kind().name());
                item.put("statement", fact.statement()); item.put("source", fact.sourceId().toString());
                item.put("evidence", fact.evidenceId()); item.put("evidenceRevision", fact.evidenceRevision());
                fact.referencedGodId().ifPresent(god -> item.put("referencedGodId", god.toString()));
                return item;
            }).toList());
            String prompt = "[GAME_SOCIAL_CONTEXT]\n" + JSON.toJson(data);
            if (prompt.length() > MAX_PROMPT_CHARACTERS) throw new IllegalArgumentException("Social prompt budget exceeded");
            return prompt;
        }
    }
}
