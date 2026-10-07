package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Player-only discourse planning. No journal, query, model, or read-authority acquisition. Game-thread confined. */
final class RoomRecallPlanner {
    private record Key(UUID world, UUID room, long revision, UUID player, String god,
                       boolean publicRoom, Set<String> gods, Set<UUID> audience) { }
    private record State(UUID requestTurn, String input, long number, RecallQuery query) { }
    private final LinkedHashMap<Key, State> states = new LinkedHashMap<>();
    private MemoryFoundationSettings.Mode mode;

    Optional<RecallQuery> plan(UUID world, Request request, MemoryFoundationSettings.Mode requestedMode, long now) {
        Objects.requireNonNull(world); Objects.requireNonNull(request); Objects.requireNonNull(requestedMode);
        if (mode != requestedMode) { states.clear(); mode = requestedMode; }
        if (mode == MemoryFoundationSettings.Mode.OFF) return Optional.empty();
        var memory = request.speakerState().memoryContext();
        if (memory != null && !world.equals(memory.worldId())) return Optional.empty();
        var key = new Key(world, request.roomId(), request.revision(), request.playerId(), request.speakerGodId().toString(),
                request.publicRoom(), request.godIds().stream().map(Object::toString).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                Set.copyOf(request.audiencePlayerIds()));
        String input = request.currentText().trim();
        UUID generation = UUID.nameUUIDFromBytes((request.roomId() + ":" + request.revision()).getBytes(StandardCharsets.UTF_8));
        var scope = new RecallQuery.Scope(new MemoryJournal.Key(world, key.god(), request.playerId()), generation, key.audience());
        // A secondary turn may contain another NPC's latest speech. It is not a player recall question.
        // Preserve optional literal lookup without reading, advancing, clearing or replacing player focus.
        if (request.secondary()) return Optional.of(new RecallQuery(input, false, false, now, null, scope));
        var previous = states.get(key);
        if (previous != null && previous.requestTurn().equals(request.turnId()))
            return previous.input().equals(input) ? Optional.of(previous.query()) : Optional.empty();
        long number = previous == null ? 1 : previous.number() + 1;
        var query = RecallQuery.plan(scope, input, number, now, previous == null ? null : previous.query().focus());
        states.put(key, new State(request.turnId(), input, number, query));
        while (states.size() > 4096) states.remove(states.keySet().iterator().next());
        return Optional.of(query);
    }

    void clear() { states.clear(); mode = null; }
    int size() { return states.size(); }
}
