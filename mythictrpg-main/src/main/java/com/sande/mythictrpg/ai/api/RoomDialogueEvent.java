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
        List<RoomEvidenceReference> evidenceRefs, Set<UUID> sourceMessageIds, long turnSequence) {
    /** Binary/source compatibility for existing producers; zero means ordering was not supplied. */
    public RoomDialogueEvent(UUID messageId, UUID roomId, long revision, Optional<UUID> turnId,
            RoomType roomType, RecordingScope recordingScope, String role, String speakerId, String text,
            Set<String> godIds, Map<UUID, String> participantNames, Map<UUID, Delivery> deliveries, long occurredAtUtc,
            Set<String> heardGodIds, UUID worldId, Set<UUID> fullTextReceiverIds,
            List<RoomEvidenceReference> evidenceRefs, Set<UUID> sourceMessageIds) {
        this(messageId, roomId, revision, turnId, roomType, recordingScope, role, speakerId, text, godIds, participantNames,
                deliveries, occurredAtUtc, heardGodIds, worldId, fullTextReceiverIds, evidenceRefs, sourceMessageIds, 0);
    }
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
        if (revision < 0 || occurredAtUtc < 0 || turnSequence < 0 || turnId.isEmpty() && turnSequence != 0
                || text.isBlank() || participantNames.size() > 64
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
    /** Exact server-side presentation projection. Parts are the original ordered plan, not just successful counts. */
    public record DispatchView(String plainText, List<String> parts, Set<Integer> dispatchedParts,
            Map<Integer, UUID> transportMessageIds) {
        public DispatchView {
            Objects.requireNonNull(plainText); parts = List.copyOf(parts); dispatchedParts = Set.copyOf(dispatchedParts);
            transportMessageIds = Map.copyOf(transportMessageIds);
            int partCount = parts.size();
            if (parts.isEmpty() || parts.size() > 4096 || !String.join("", parts).equals(plainText)
                    || dispatchedParts.isEmpty() || dispatchedParts.stream().anyMatch(index -> index < 0 || index >= partCount)
                    || !dispatchedParts.containsAll(transportMessageIds.keySet()))
                throw new IllegalArgumentException("Invalid exact dispatch view");
        }
        public boolean complete() { return dispatchedParts.size() == parts.size(); }
    }
    public record Delivery(String playerName, boolean chatDispatched, int hudPagesDispatched,
            Optional<DispatchView> chatView, Optional<DispatchView> hudView) {
        /** Legacy compatibility: counts remain usable by old consumers, never fabricated into exact archive views. */
        public Delivery(String playerName, boolean chatDispatched, int hudPagesDispatched) {
            this(playerName, chatDispatched, hudPagesDispatched, Optional.empty(), Optional.empty());
        }
        public Delivery {
            Objects.requireNonNull(playerName); Objects.requireNonNull(chatView); Objects.requireNonNull(hudView);
            if (hudPagesDispatched < 0 || !chatDispatched && hudPagesDispatched == 0)
                throw new IllegalArgumentException("No successful dispatch");
            if (chatView.isPresent() && (!chatDispatched || !chatView.get().complete() || chatView.get().parts().size() != 1)
                    || hudView.isPresent() && hudView.get().dispatchedParts().size() != hudPagesDispatched)
                throw new IllegalArgumentException("Dispatch count differs from exact view");
        }
    }
    public RoomDialogueEvent withDeliveries(Map<UUID, Delivery> actual) {
        return new RoomDialogueEvent(messageId, roomId, revision, turnId, roomType, recordingScope, role,
                speakerId, text, godIds, participantNames, actual, occurredAtUtc, heardGodIds, worldId,
                completeRecipients(text, actual), evidenceRefs, sourceMessageIds, turnSequence);
    }

    /** Explicit virtual-NPC hearing, issued by the live game room after publication. */
    public RoomDialogueEvent withHeardGods(Set<String> heard) {
        return new RoomDialogueEvent(messageId, roomId, revision, turnId, roomType, recordingScope, role,
                speakerId, text, godIds, participantNames, deliveries, occurredAtUtc, heard, worldId,
                fullTextReceiverIds, evidenceRefs, sourceMessageIds, turnSequence);
    }
    public RoomDialogueEvent withWorld(UUID world) {
        return new RoomDialogueEvent(messageId, roomId, revision, turnId, roomType, recordingScope, role,
                speakerId, text, godIds, participantNames, deliveries, occurredAtUtc, heardGodIds, world,
                fullTextReceiverIds, evidenceRefs, sourceMessageIds, turnSequence);
    }
    public RoomDialogueEvent withEvidence(List<RoomEvidenceReference> references, Set<UUID> sources) {
        return new RoomDialogueEvent(messageId, roomId, revision, turnId, roomType, recordingScope, role,
                speakerId, text, godIds, participantNames, deliveries, occurredAtUtc, heardGodIds, worldId,
                fullTextReceiverIds, references, sources, turnSequence);
    }
    public RoomDialogueEvent withTurnSequence(long sequence) {
        return new RoomDialogueEvent(messageId, roomId, revision, turnId, roomType, recordingScope, role,
                speakerId, text, godIds, participantNames, deliveries, occurredAtUtc, heardGodIds, worldId,
                fullTextReceiverIds, evidenceRefs, sourceMessageIds, sequence);
    }
    private static Set<UUID> completeRecipients(String text, Map<UUID, Delivery> deliveries) {
        int total = com.sande.mythictrpg.ai.room.RoomHudText.pages(text).size();
        var complete = new LinkedHashSet<UUID>();
        deliveries.forEach((id, receipt) -> {
            boolean chatComplete = receipt.chatDispatched() && (receipt.chatView().isEmpty() || receipt.chatView().get().plainText().endsWith(text));
            boolean hudComplete = receipt.hudView().map(view -> view.complete() && view.plainText().equals(text))
                    .orElse(receipt.hudPagesDispatched() == total);
            if (chatComplete || hudComplete) complete.add(id);
        });
        return Set.copyOf(complete);
    }
}
