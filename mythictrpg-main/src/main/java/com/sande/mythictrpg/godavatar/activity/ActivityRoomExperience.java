package com.sande.mythictrpg.godavatar.activity;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.rumor.RumorSavedData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BiFunction;

/** Read-only room projection of the existing activity bank; no fabricated player or conversation journal. */
public final class ActivityRoomExperience {
    public static final String KIND = "NPC_ACTIVITY_EXPERIENCE_V1";
    private static final Gson JSON = new Gson();
    private static final Set<String> FIELDS = Set.of("worldId", "godId", "projections");

    public record Snapshot(NpcActivityMemory.View view, List<RoomEvidenceReference> references) {
        public Snapshot {
            Objects.requireNonNull(view); references = List.copyOf(references);
            if (references.size() != 1) throw new IllegalArgumentException("Activity evidence budget");
        }
    }
    private record Projection(UUID eventId, String projectionHash) {
        private Projection {
            Objects.requireNonNull(eventId);
            if (projectionHash == null || !projectionHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid activity evidence");
        }
    }
    private record Descriptor(UUID worldId, String godId, List<Projection> projections) {
        private Descriptor {
            Objects.requireNonNull(worldId); projections = List.copyOf(projections);
            if (godId == null || !ResourceLocation.parse(godId).toString().equals(godId)
                    || projections.isEmpty() || projections.size() > NpcActivityMemory.MAX_EVENTS_PER_GOD
                    || projections.stream().map(Projection::eventId).distinct().count() != projections.size())
                throw new IllegalArgumentException("Invalid activity evidence bundle");
        }
    }
    private ActivityRoomExperience() { }

    /** Called on the game thread for each speaker, after its real room turn and audience have been issued. */
    public static Optional<Snapshot> capture(MinecraftServer server, Request request) {
        if (!permitted(server, request)) return Optional.empty();
        // Public rooms can exceed this optional bank's audience budget; omit experience, not the dialogue.
        if (request.audiencePlayerIds().size() > 256) return Optional.empty();
        var state = NpcActivityWorldState.get(server);
        var world = RumorSavedData.get(server);
        if (!state.ready() || !world.ready()) return Optional.empty();
        var bank = state.activityMemory();
        var gods = gods(request);
        var view = bank.view(request.speakerGodId().toString(), gods, request.audiencePlayerIds(), request.currentText());
        if (view.experiences().isEmpty()) return Optional.empty();
        var selected = new LinkedHashMap<UUID, NpcActivityMemory.Memory>();
        view.experiences().forEach(memory -> selected.put(memory.eventId(), memory));
        // The affect may have been interpreted from more inputs than this query selected. All remain dependencies.
        if (view.affect() != null) for (UUID id : view.affect().sourceEventIds()) {
            var source = bank.project(view.godId(), id, gods, request.audiencePlayerIds());
            if (source.isEmpty()) return Optional.empty();
            selected.putIfAbsent(id, source.orElseThrow());
        }
        if (selected.size() > NpcActivityMemory.MAX_EVENTS_PER_GOD) return Optional.empty();
        return Optional.of(new Snapshot(view, List.of(reference(world.worldId(), view.godId(), selected.values()))));
    }

    /** Portable source check: unrelated bank writes/restarts do not revoke an unchanged surviving projection. */
    public static boolean current(MinecraftServer server, Request request, RoomEvidenceReference reference) {
        if (!permitted(server, request) || reference == null || !KIND.equals(reference.kind())) return false;
        try {
            var state = NpcActivityWorldState.get(server);
            var world = RumorSavedData.get(server);
            if (!state.ready() || !world.ready()) return false;
            return current(world.worldId(), reference, (owner, event) -> state.activityMemory()
                    .project(owner, event, gods(request), request.audiencePlayerIds()));
        } catch (RuntimeException unavailable) { return false; }
    }

    private static boolean permitted(MinecraftServer server, Request request) {
        return server != null && request != null && server.isSameThread()
                && policyAllows(MemoryFoundationSettings.mode(), request.recording(),
                    ConversationRooms.INSTANCE.memoryReadCurrent(server, request));
    }
    static boolean policyAllows(MemoryFoundationSettings.Mode mode, boolean recording, boolean currentTurn) {
        return mode != null && mode != MemoryFoundationSettings.Mode.OFF && recording && currentTurn;
    }
    private static Set<String> gods(Request request) {
        return request.godIds().stream().map(ResourceLocation::toString).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    static RoomEvidenceReference reference(UUID world, String owner, NpcActivityMemory.Memory memory) {
        return reference(world, owner, List.of(memory));
    }
    static RoomEvidenceReference reference(UUID world, String owner, Collection<NpcActivityMemory.Memory> memories) {
        return new RoomEvidenceReference(KIND, JSON.toJson(new Descriptor(world, owner, memories.stream()
                .map(memory -> new Projection(memory.eventId(), fingerprint(memory)))
                .sorted(Comparator.comparing(projection -> projection.eventId().toString())).toList())));
    }
    static boolean current(UUID world, RoomEvidenceReference reference,
            BiFunction<String, UUID, Optional<NpcActivityMemory.Memory>> projection) {
        try {
            if (reference == null || !KIND.equals(reference.kind()) || reference.payload().length() > 16000) return false;
            var json = JsonParser.parseString(reference.payload()).getAsJsonObject();
            if (!json.keySet().equals(FIELDS)) return false;
            var descriptor = JSON.fromJson(json, Descriptor.class);
            if (!world.equals(descriptor.worldId())) return false;
            // Owner belongs to the original experience, not necessarily the current speaker. Actual hearing
            // of a subsequent utterance is checked separately by the existing room-memory source graph.
            return descriptor.projections().stream().allMatch(source -> projection.apply(descriptor.godId(), source.eventId())
                    .filter(memory -> memory.eventId().equals(source.eventId())
                            && fingerprint(memory).equals(source.projectionHash())).isPresent());
        } catch (RuntimeException invalid) { return false; }
    }
    private static String fingerprint(NpcActivityMemory.Memory memory) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.toJson(memory).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
