package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure projection of game-certified speech, never a detector or an inferred knowledge grant. */
public final class RoomListeningMemory {
    private RoomListeningMemory() { }

    public static boolean matches(RoomDialogueEvent event, Request request, String role, String text) {
        return event != null && event.recordingScope().recordingAllowed() && request.recording()
                && event.roomId().equals(request.roomId()) && event.revision() == request.revision()
                && event.turnId().filter(request.turnId()::equals).isPresent()
                && event.role().equals(role) && event.text().equals(text)
                && event.speakerId().equals("NPC".equals(role) ? request.speakerGodId().toString() : request.playerId().toString())
                && event.godIds().equals(request.godIds().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()))
                && !event.heardGodIds().isEmpty();
    }

    /** Returns no row when the old 16-player journal cannot safely represent the received scope. */
    public static Optional<MemoryJournal.Entry> entry(RoomDialogueEvent event, Request request,
            ConversationMemoryContext context, long turn, String role, String text) {
        if (!matches(event, request, role, text) || context == null || turn < 0
                || "PLAYER".equals(role) && request.secondary()
                || !context.interactionId().equals(request.roomId()) || !context.playerId().equals(request.playerId())
                || !event.heardGodIds().contains(context.godId()) || context.audience().size() > 16)
            return Optional.empty();
        // A HUD receipt does not identify which pages arrived, so it cannot permit future disclosure of full text.
        Set<UUID> audience = new HashSet<>();
        event.deliveries().forEach((id, delivery) -> { if (delivery.chatDispatched()) audience.add(id); });
        if ("PLAYER".equals(role)) audience.add(request.playerId()); // The author knows the accepted input.
        audience.retainAll(context.audience());
        if (!audience.contains(context.playerId()) || audience.isEmpty() || audience.size() > 16) return Optional.empty();
        if ("NPC".equals(role) && !event.heardGodIds().contains(event.speakerId())) return Optional.empty();
        var key = new MemoryJournal.Key(context.worldId(), context.godId(), context.playerId());
        UUID id = UUID.nameUUIDFromBytes(("room-speech:" + event.messageId() + ":" + context.worldId() + ":"
                + context.godId() + ":" + context.playerId()).getBytes(StandardCharsets.UTF_8));
        var source = "PLAYER".equals(role) ? MemoryJournal.Source.PLAYER_STATEMENT
                : context.readOnly() ? MemoryJournal.Source.HEARSAY_NPC : MemoryJournal.Source.NPC_UTTERANCE;
        // The journal remains bounded; the full original is owned by the separate transcript/archive path.
        int end = Math.min(1200, text.length());
        if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return Optional.of(new MemoryJournal.Entry(id, key, context.generation(), turn, source, Set.copyOf(audience),
                event.occurredAtUtc(), text.substring(0, end), false, event.heardGodIds(),
                "NPC".equals(role) ? event.speakerId() : ""));
    }
}
