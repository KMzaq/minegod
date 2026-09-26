package com.sande.mythictrpg.ai.experiencecontract;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.gameplay.watch.*;
import com.sande.mythictrpg.rumor.RumorSavedData;
import net.minecraft.server.MinecraftServer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Portable references to existing observations, never permission to create a watch or reconstruct unobserved facts. */
public final class ExperienceRoomEvidence {
    public static final String KIND = "WATCH_OBSERVATION_V1";
    private static final Gson JSON = new Gson();
    private static final Map<MinecraftServer, Map<Scope, Map<RoomEvidenceReference, BooleanSupplier>>> PREPARED = new WeakHashMap<>();
    private record Descriptor(UUID worldId, String godId, UUID subjectId, UUID observationId, String sha256) {
        Descriptor {
            Objects.requireNonNull(worldId); Objects.requireNonNull(subjectId); Objects.requireNonNull(observationId);
            net.minecraft.resources.ResourceLocation.parse(godId);
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid watch fingerprint");
        }
    }
    private record Scope(UUID room, long revision, UUID anchor, String god, Set<UUID> players, UUID world) { }
    private record CacheKey(Scope scope, RoomEvidenceReference reference) { }
    private ExperienceRoomEvidence() { }

    /** Only the game lease issuer calls this using an audience-projected snapshot with checked raw receipts. */
    static Map<UUID, RoomEvidenceReference> capture(AsyncGodWatch.ReadSnapshot snapshot) {
        var refs = new LinkedHashMap<UUID, RoomEvidenceReference>();
        for (var proof : snapshot.view().proofs()) {
            var eligibility = snapshot.eligibilityRefs().get(proof.id());
            if (eligibility == null) throw new IllegalArgumentException("Missing watch eligibility evidence");
            var descriptor = new Descriptor(proof.worldId(), proof.observerGodId(), proof.observerTarget(), proof.id(),
                    fingerprint(proof, eligibility));
            refs.put(proof.id(), new RoomEvidenceReference(KIND, JSON.toJson(descriptor)));
        }
        return Map.copyOf(refs);
    }

    /** Fresh selected observations already have a game-issued lease; register its memory-only guard for this request. */
    public static List<RoomEvidenceReference> references(MinecraftServer server, Request request, ExperienceLease lease,
            Set<UUID> included) {
        requireThread(server);
        if (included.isEmpty()) return List.of();
        var scope = scope(server, request).orElseThrow(() -> new IllegalArgumentException("Unsupported observation audience"));
        Set<UUID> ids = Set.copyOf(included);
        if (ids.size() > 16 || !lease.current(ids) || !lease.portableEvidence().keySet().containsAll(ids))
            throw new IllegalArgumentException("Unproven selected observation");
        var result = new ArrayList<RoomEvidenceReference>();
        for (UUID id : ids.stream().sorted().toList()) {
            var ref = lease.portableEvidence().get(id);
            var descriptor = descriptor(ref);
            if (!matches(scope, descriptor)) throw new IllegalArgumentException("Foreign observation source");
            put(server, new CacheKey(scope, ref), () -> lease.current(Set.of(id)));
            result.add(ref);
        }
        return List.copyOf(result);
    }

    /**
     * Prepare persisted references asynchronously on the existing watch worker. Caller resumes on the game thread,
     * then calls current immediately before prompt use/delivery. No disk read or future.join occurs in current.
     */
    public static CompletableFuture<Boolean> prepare(MinecraftServer server, Request request,
            Collection<RoomEvidenceReference> references) {
        requireThread(server);
        var refs = references.stream().filter(ref -> KIND.equals(ref.kind())).distinct().toList();
        if (refs.isEmpty()) return CompletableFuture.completedFuture(true);
        var initial = scope(server, request);
        var runtime = GodWatchRuntime.current(server);
        if (initial.isEmpty() || runtime == null) return CompletableFuture.completedFuture(false);
        var scope = initial.orElseThrow();
        var groups = new LinkedHashMap<UUID, Map<RoomEvidenceReference, Descriptor>>();
        try {
            for (var ref : refs) {
                var value = descriptor(ref);
                if (!matches(scope, value)) return CompletableFuture.completedFuture(false);
                groups.computeIfAbsent(value.subjectId(), ignored -> new LinkedHashMap<>()).put(ref, value);
            }
        } catch (RuntimeException malformed) { return CompletableFuture.completedFuture(false); }
        // The 64 limit belongs to one exact disk query / one newly published event, not an entire historical DAG.
        // Serialize bounded batches so a long lineage cannot overflow the watch worker's bounded queue.
        CompletableFuture<Boolean> sequence = CompletableFuture.completedFuture(true);
        for (var group : groups.entrySet()) {
            var entries = new ArrayList<>(group.getValue().entrySet());
            for (int from = 0; from < entries.size(); from += 64) {
                var batch = new LinkedHashMap<RoomEvidenceReference, Descriptor>();
                for (var entry : entries.subList(from, Math.min(entries.size(), from + 64))) batch.put(entry.getKey(), entry.getValue());
                UUID subject = group.getKey();
                sequence = sequence.thenCompose(previous -> {
                    var prepared = new CompletableFuture<Boolean>();
                    server.execute(() -> prepareBatch(server, request, scope, runtime, subject, batch)
                            .whenComplete((okay, failure) -> prepared.complete(previous && failure == null && Boolean.TRUE.equals(okay))));
                    return prepared;
                });
            }
        }
        return sequence;
    }

    private static CompletableFuture<Boolean> prepareBatch(MinecraftServer server, Request request, Scope scope,
            GodWatchRuntime runtime, UUID subject, Map<RoomEvidenceReference, Descriptor> batch) {
            var audience = new WatchContract.Audience(scope.world(), new WatchContract.Key(scope.god(), subject),
                    scope.players(), new WatchContract.Ref(scope.room() + "/" + scope.revision(), 1));
            var ids = batch.values().stream().map(Descriptor::observationId).collect(java.util.stream.Collectors.toSet());
            var completion = new CompletableFuture<Boolean>();
            try {
                runtime.gateway().readExact(audience, ids).whenComplete((snapshot, failure) -> server.execute(() -> {
                    if (completion.isDone()) return;
                    try {
                        if (failure != null || GodWatchRuntime.current(server) != runtime
                                || scope(server, request).filter(scope::equals).isEmpty()
                                || !runtime.gateway().current(snapshot, audience)) { completion.complete(false); return; }
                        for (var entry : batch.entrySet()) {
                            var value = entry.getValue();
                            var proof = snapshot.view().proofs().stream().filter(p -> p.id().equals(value.observationId())).findFirst().orElse(null);
                            var eligibility = snapshot.eligibilityRefs().get(value.observationId());
                            if (proof == null || eligibility == null || !value.sha256().equals(fingerprint(proof, eligibility))) {
                                completion.complete(false); return;
                            }
                        }
                        for (var entry : batch.entrySet()) {
                            UUID id = entry.getValue().observationId();
                            var proof = snapshot.view().proofs().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
                            var single = new AsyncGodWatch.ReadSnapshot(audience, new WatchContract.View(true, "READY", List.of(proof)),
                                    Map.of(id, snapshot.eligibilityRefs().get(id)));
                            put(server, new CacheKey(scope, entry.getKey()), () -> GodWatchRuntime.current(server) == runtime
                                    && runtime.gateway().current(single, audience));
                        }
                        completion.complete(true);
                    } catch (RuntimeException unavailable) { completion.complete(false); }
                }));
            } catch (RuntimeException unavailable) { completion.complete(false); }
            return completion.completeOnTimeout(false, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public static boolean current(MinecraftServer server, Request request, RoomEvidenceReference reference) {
        if (!server.isSameThread() || reference == null || !KIND.equals(reference.kind())) return false;
        try {
            var scope = scope(server, request);
            if (scope.isEmpty() || !matches(scope.get(), descriptor(reference))) return false;
            var scopes = PREPARED.get(server);
            var entries = scopes == null ? null : scopes.get(scope.get());
            var guard = entries == null ? null : entries.get(reference);
            return guard != null && guard.getAsBoolean();
        } catch (RuntimeException unavailable) { return false; }
    }

    public static void clear(MinecraftServer server) { requireThread(server); PREPARED.remove(server); }

    private static Optional<Scope> scope(MinecraftServer server, Request request) {
        requireThread(server);
        // Watch proofs certify player disclosure, not disclosure to another God or public broadcast.
        if (!memoryAudienceSupported(request, MemoryFoundationSettings.mode())) return Optional.empty();
        var anchor = server.getPlayerList().getPlayer(request.playerId());
        if (anchor == null || !ConversationRooms.INSTANCE.memoryCurrent(anchor, request.speakerState().memoryContext()))
            return Optional.empty();
        var room = ConversationRooms.INSTANCE.memberships(anchor).stream().filter(value -> value.roomId().equals(request.roomId())
                && value.revision() == request.revision() && !value.type().isPublic()
                && value.godIds().equals(Set.of(request.speakerGodId().toString()))
                && value.playerIds().equals(request.audiencePlayerIds())).findFirst();
        var state = RumorSavedData.get(server);
        if (room.isEmpty() || !state.ready()) return Optional.empty();
        return Optional.of(new Scope(request.roomId(), request.revision(), request.playerId(), request.speakerGodId().toString(),
                Set.copyOf(request.audiencePlayerIds()), state.worldId()));
    }
    private static boolean matches(Scope scope, Descriptor descriptor) {
        return scope.world().equals(descriptor.worldId()) && scope.god().equals(descriptor.godId());
    }
    /** Action read-only test rooms may still READ personal observations; only the memory contract controls that. */
    static boolean memoryAudienceSupported(Request request, MemoryFoundationSettings.Mode mode) {
        var memory = request.speakerState().memoryContext();
        return mode == MemoryFoundationSettings.Mode.PERSONAL && memory != null && !memory.readOnly()
                && !request.publicRoom() && request.godIds().size() == 1 && request.audiencePlayerIds().size() <= 16
                && memory.audience().equals(request.audiencePlayerIds());
    }
    private static void put(MinecraftServer server, CacheKey key, BooleanSupplier current) {
        var scopes = PREPARED.computeIfAbsent(server, ignored -> new HashMap<>());
        scopes.keySet().removeIf(value -> !ConversationRooms.INSTANCE.isCurrent(value.room(), value.revision()));
        scopes.computeIfAbsent(key.scope(), ignored -> new HashMap<>()).put(key.reference(), current);
    }
    private static Descriptor descriptor(RoomEvidenceReference reference) {
        if (!KIND.equals(reference.kind()) || reference.payload().length() > 4096) throw new IllegalArgumentException("Invalid watch evidence");
        var object = JsonParser.parseString(reference.payload()).getAsJsonObject();
        if (!object.keySet().equals(Set.of("worldId", "godId", "subjectId", "observationId", "sha256")))
            throw new IllegalArgumentException("Unknown watch evidence fields");
        return JSON.fromJson(object, Descriptor.class);
    }
    static String fingerprint(WatchContract.Proof proof, WatchContract.Ref eligibility) {
        var object = new JsonObject(); object.add("proof", JSON.toJsonTree(proof)); object.add("eligibility", JSON.toJsonTree(eligibility));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                JSON.toJson(canonical(object)).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var result = new JsonObject();
            value.getAsJsonObject().keySet().stream().sorted().forEach(key -> result.add(key, canonical(value.getAsJsonObject().get(key))));
            return result;
        }
        if (value.isJsonArray()) { var result = new JsonArray(); value.getAsJsonArray().forEach(item -> result.add(canonical(item))); return result; }
        return value;
    }
    private static void requireThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Observation evidence requires the game thread");
    }
}
