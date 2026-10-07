package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.rumor.RumorSavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.function.BooleanSupplier;

/** References to the old journals remain revocable; session-only leases are never silently upgraded. */
final class LegacyRoomEvidence {
    static final String JOURNAL = "LEGACY_JOURNAL_V1", RUMOR = "LEGACY_RUMOR_V1", OPAQUE = "LIVE_LEASE_V1";
    private static final Gson JSON = new Gson();
    private static final Map<UUID,BooleanSupplier> LIVE = new LinkedHashMap<>();
    record Source(UUID world, String god, UUID subject, String mode, UUID source, String fingerprint) {
        Source {
            Objects.requireNonNull(world); Objects.requireNonNull(subject); Objects.requireNonNull(source);
            new MemoryJournal.Key(world, god, subject);
            if (!Set.of("PERSONAL", "RUMOR_TEST").contains(mode) || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid legacy evidence");
        }
    }
    private LegacyRoomEvidence() { }
    static List<RoomEvidenceReference> references(ServerPlayer player, DialogueMemoryBridge.Turn turn) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Legacy evidence requires game thread");
        var refs = new ArrayList<RoomEvidenceReference>();
        String mode = turn.context() != null && turn.context().readOnly() ? "RUMOR_TEST" : "PERSONAL";
        for (var entry : turn.selected()) refs.add(new RoomEvidenceReference(JOURNAL, JSON.toJson(new Source(entry.key().world(),
                entry.key().god(), entry.key().player(), mode, entry.id(), DerivedMemory.fingerprint(entry)))));
        if (turn.hasRumors()) {
            var c = Objects.requireNonNull(turn.context());
            for (var rumor : turn.rumors()) refs.add(new RoomEvidenceReference(RUMOR, JSON.toJson(new Source(c.worldId(), c.godId(),
                    c.playerId(), "RUMOR_TEST", rumor.rootId(), DerivedMemory.hash(JSON.toJson(rumor))))));
        }
        if (!turn.inherited().isEmpty()) {
            var evidence = List.copyOf(turn.inherited());
            UUID token = UUID.randomUUID();
            LIVE.put(token, () -> player.server.isSameThread() && evidence.stream().allMatch(ExperienceHistory.Reference::current));
            while (LIVE.size() > 32768) LIVE.remove(LIVE.keySet().iterator().next());
            refs.add(new RoomEvidenceReference(OPAQUE, token.toString()));
        }
        return List.copyOf(refs);
    }
    static boolean handles(RoomEvidenceReference reference) { return Set.of(JOURNAL, RUMOR, OPAQUE).contains(reference.kind()); }
    static boolean current(MinecraftServer server, Request request, RoomEvidenceReference reference) {
        if (!server.isSameThread()) return false;
        if (OPAQUE.equals(reference.kind())) {
            var guard = LIVE.get(UUID.fromString(reference.payload())); return guard != null && guard.getAsBoolean();
        }
        if (MemoryFoundationSettings.mode() == MemoryFoundationSettings.Mode.OFF || request.publicRoom()) return false;
        var source = JSON.fromJson(reference.payload(), Source.class);
        if (!MemoryFoundationSettings.mode().name().equals(source.mode())) return false;
        var state = RumorSavedData.get(server);
        if (!state.ready() || !state.worldId().equals(source.world())) return false;
        if (JOURNAL.equals(reference.kind())) {
            // Legacy entries have no PUBLIC declaration. Reuse only within their original player/God audience.
            return journalCurrent(DialogueMemoryBridge.roomLegacyJournal(server), source, request.audiencePlayerIds(),
                    request.godIds().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()));
        }
        if (RUMOR.equals(reference.kind())) {
            // A quoted origin God's stance stays attributed to that God. Each current God must already
            // have received the same claim; this check creates neither receipts nor shared beliefs.
            return com.sande.mythictrpg.rumor.RoomRumorAccess.referenced(server, source.subject(), source.god(),
                    request.audiencePlayerIds(), request.godIds().stream().map(Object::toString)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()), request.publicRoom()).stream()
                    .anyMatch(row -> row.rootId().equals(source.source()) && DerivedMemory.hash(JSON.toJson(row)).equals(source.fingerprint()));
        }
        return false;
    }
    static boolean journalCurrent(MemoryJournal journal, Source source, Set<UUID> audience, Set<String> godAudience) {
        var gods = new HashSet<>(godAudience); gods.add(source.god());
        if (gods.size() > 16 || !audience.contains(source.subject())) return false;
        var view = journal.readView(new MemoryJournal.Key(source.world(), source.god(), source.subject()), audience, gods);
        return view.ready() && !view.failed() && view.entries().stream().anyMatch(row -> row.id().equals(source.source())
                && DerivedMemory.fingerprint(row).equals(source.fingerprint()));
    }
    static void clear() { LIVE.clear(); }
}
