package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import java.util.*;

/** Projects the game-issued dispatch once, without needing an LLM turn or a personal-memory binding. */
final class RoomMemoryProjection {
    private RoomMemoryProjection() { }
    static Optional<RoomMemoryEvidence.Receipt> receipt(RoomDialogueEvent event) {
        if (event.worldId() == null || event.heardGodIds().isEmpty()) return Optional.empty();
        var players = new HashSet<>(event.fullTextReceiverIds());
        if (event.role().equals("PLAYER")) players.add(UUID.fromString(event.speakerId()));
        return Optional.of(new RoomMemoryEvidence.Receipt(event.messageId(), event.worldId(), event.roomId(), event.revision(),
                players, event.heardGodIds(), event.roomType().isPublic(), event.evidenceRefs(), event.sourceMessageIds()));
    }
    static Optional<RoomMemoryStore.Record> persistent(RoomDialogueEvent event, boolean globalRecordingEnabled) {
        if (!globalRecordingEnabled || !event.recordingScope().recordingAllowed()) return Optional.empty();
        return receipt(event).map(receipt -> new RoomMemoryStore.Record(event.messageId(), event.worldId(), event.roomId(),
                event.revision(), event.occurredAtUtc(), event.role(), event.speakerId(), event.role().equals("PLAYER")
                    ? event.participantNames().get(UUID.fromString(event.speakerId())) : event.speakerId(),
                event.text(), event.roomType().isPublic(), receipt.players(), event.heardGodIds(), event.evidenceRefs(), event.sourceMessageIds()));
    }
}
