package com.sande.mythictrpg.ai.api;

import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import java.util.*;

/** Game-issued logical speech, independent of an AI request or per-player HUD packet IDs.
 * SERVER_DISPATCHED evidence only: it neither proves client acknowledgement nor grants AI knowledge.
 * This port is not a durable archive; consumers must report their own persistence failures.
 */
public record RoomDialogueEvent(UUID messageId, UUID roomId, long revision, Optional<UUID> turnId,
        RoomType roomType, RecordingScope recordingScope, String role, String speakerId, String text,
        Set<String> godIds, Map<UUID, String> participantNames, Map<UUID, Delivery> deliveries, long occurredAtUtc,
        Set<String> heardGodIds, UUID worldId, Set<UUID> fullTextReceiverIds,
        List<RoomEvidenceReference> evidenceRefs, Set<UUID> sourceMessageIds) {
    public RoomDialogueEvent(UUID messageId, UUID roomId, long revision, Optional<UUID> turnId,
            RoomType roomType, RecordingScope recordingScope, String role, String speakerId, String text,
            Set<String> godIds, Map<UUID,String> participantNames, Map<UUID,Delivery> deliveries, long occurredAtUtc,
            Set<String> heardGodIds) {
        this(messageId, roomId, revision, turnId, roomType, recordingScope, role, speakerId, text,
                godIds, participantNames, deliveries, occurredAtUtc, heardGodIds, null,
                completeRecipients(text, deliveries), List.of(), Set.of());
    }
    /** Old producers have no explicit God-hearing evidence. Membership alone must not grant memory. */
    public RoomDialogueEvent(UUID messageId, UUID roomId, long revision, Optional<UUID> turnId,
            RoomType roomType, RecordingScope recordingScope, String role, String speakerId, String text,
            Set<String> godIds, Map<UUID,String> participantNames, Map<UUID,Delivery> deliveries, long occurredAtUtc) {
        this(messageId, roomId, revision, turnId, roomType, recordingScope, role, speakerId, text,
                godIds, participantNames, deliveries, occurredAtUtc, Set.of());
    }
    public RoomDialogueEvent {
        Objects.requireNonNull(messageId); Objects.requireNonNull(roomId); Objects.requireNonNull(turnId);
        Objects.requireNonNull(roomType); Objects.requireNonNull(recordingScope);
        Objects.requireNonNull(role); Objects.requireNonNull(speakerId); Objects.requireNonNull(text);
        godIds = Set.copyOf(godIds); participantNames = Map.copyOf(participantNames); deliveries = Map.copyOf(deliveries);
        heardGodIds = Set.copyOf(heardGodIds);
        fullTextReceiverIds = Set.copyOf(fullTextReceiverIds); evidenceRefs = List.copyOf(evidenceRefs);
        sourceMessageIds = Set.copyOf(sourceMessageIds);
        if (revision < 0 || occurredAtUtc < 0 || text.isBlank() || participantNames.size() > 64
                || godIds.isEmpty() || godIds.size() > 16 || !godIds.containsAll(heardGodIds)
                || !Set.of("PLAYER", "NPC").contains(role) || text.length() > 131072
                || evidenceRefs.size() > 64 || sourceMessageIds.size() > 256 || sourceMessageIds.contains(messageId)
                || !completeRecipients(text, deliveries).containsAll(fullTextReceiverIds))
            throw new IllegalArgumentException("Invalid room dialogue event");
        if ("PLAYER".equals(role) && !participantNames.containsKey(UUID.fromString(speakerId))
                || "NPC".equals(role) && !godIds.contains(speakerId)
                || roomType == RoomType.PRIVATE && !participantNames.keySet().containsAll(deliveries.keySet()))
            throw new IllegalArgumentException("Invalid room dialogue speaker/audience");
    }
    public record Delivery(String playerName, boolean chatDispatched, int hudPagesDispatched) {
        public Delivery {
            Objects.requireNonNull(playerName);
            if (hudPagesDispatched < 0 || !chatDispatched && hudPagesDispatched == 0)
                throw new IllegalArgumentException("No successful dispatch");
        }
    }
    public RoomDialogueEvent withDeliveries(Map<UUID, Delivery> actual) {
        return new RoomDialogueEvent(messageId, roomId, revision, turnId, roomType, recordingScope, role,
                speakerId, text, godIds, participantNames, actual, occurredAtUtc, heardGodIds, worldId,
                completeRecipients(text, actual), evidenceRefs, sourceMessageIds);
    }

    /** Explicit virtual-NPC hearing, issued by the live game room after publication. */
    public RoomDialogueEvent withHeardGods(Set<String> heard) {
        return new RoomDialogueEvent(messageId, roomId, revision, turnId, roomType, recordingScope, role,
                speakerId, text, godIds, participantNames, deliveries, occurredAtUtc, heard, worldId,
                fullTextReceiverIds, evidenceRefs, sourceMessageIds);
    }
    public RoomDialogueEvent withWorld(UUID world) {
        return new RoomDialogueEvent(messageId, roomId, revision, turnId, roomType, recordingScope, role,
                speakerId, text, godIds, participantNames, deliveries, occurredAtUtc, heardGodIds, world,
                fullTextReceiverIds, evidenceRefs, sourceMessageIds);
    }
    public RoomDialogueEvent withEvidence(List<RoomEvidenceReference> references, Set<UUID> sources) {
        return new RoomDialogueEvent(messageId, roomId, revision, turnId, roomType, recordingScope, role,
                speakerId, text, godIds, participantNames, deliveries, occurredAtUtc, heardGodIds, worldId,
                fullTextReceiverIds, references, sources);
    }
    private static Set<UUID> completeRecipients(String text, Map<UUID, Delivery> deliveries) {
        int total = com.sande.mythictrpg.ai.room.RoomHudText.pages(text).size();
        var complete = new LinkedHashSet<UUID>();
        deliveries.forEach((id, receipt) -> {
            if (receipt.chatDispatched() || receipt.hudPagesDispatched() == total) complete.add(id);
        });
        return Set.copyOf(complete);
    }
}
