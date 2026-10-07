package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.recording.api.ProducerCapability;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Game-owned room publication adapter. No AI dependency, model call, replay or knowledge authorization. */
public final class RoomRecordingCapture {
    static final String PRODUCER = "room-publication-v2";
    static final int RECEIPT_BATCH = 256, MAX_RECEIPTS = 4096;
    public record Outcome(Status rawStatus, int committedReceipts, int plannedReceipts, String reason) {
        public boolean complete() { return (rawStatus == Status.STORED || rawStatus == Status.DUPLICATE)
                && committedReceipts == plannedReceipts && reason.equals("COMPLETE"); }
    }
    private final WorldRecordingService store;
    private final ProducerCapability producer;
    private final Thread gameThread = Thread.currentThread();
    private final java.util.function.Supplier<String> memoryMode;

    RoomRecordingCapture(WorldRecordingService store) {
        this(store, "UNKNOWN");
    }
    RoomRecordingCapture(WorldRecordingService store, String memoryMode) {
        this(store, () -> memoryMode);
    }
    RoomRecordingCapture(WorldRecordingService store, java.util.function.Supplier<String> memoryMode) {
        this.store = Objects.requireNonNull(store);
        this.memoryMode = memoryMode;
        producer = store.registerProducer(PRODUCER, Set.of("ROOM_PUBLIC", "ROOM_PRIVATE"), Set.of());
    }
    void gap(String reason, long count) { store.captureGap(producer, reason, count); }

    /** currentScope is resolved by the live game before admission, never on a completion worker. */
    CompletionStage<Outcome> capture(RoomDialogueEvent event, boolean currentScope) {
        if (Thread.currentThread() != gameThread) throw new IllegalStateException("ROOM_CAPTURE_REQUIRES_GAME_THREAD");
        if (!event.recordingScope().recordingAllowed()) return failed("RECORDING_DISABLED", false);
        if (!currentScope || event.worldId() == null || !store.worldId().equals(event.worldId())) return failed("STALE_ROOM_CAPTURE_SCOPE", true);
        if (event.turnId().isPresent() && event.turnSequence() == 0) return failed("UNKNOWN_TURN_SEQUENCE", true);
        if ((long) event.deliveries().size() * 2 + event.heardGodIds().size() > MAX_RECEIPTS) return failed("ROOM_RECEIPT_BUDGET", true);
        var receipts = new ArrayList<DeliveryReceipt>();
        boolean missingViews = false;
        long projectedBytes = event.text().getBytes(StandardCharsets.UTF_8).length;
        for (var entry : new TreeMap<>(event.deliveries()).entrySet()) {
            var recipient = new ActorRef(ActorKind.PLAYER, entry.getKey().toString());
            var delivery = entry.getValue();
            if (delivery.chatDispatched()) {
                if (delivery.chatView().isPresent()) receipts.add(receipt(event, recipient, "CHAT", delivery.chatView().get()));
                else missingViews = true;
            }
            if (delivery.hudPagesDispatched() > 0) {
                if (delivery.hudView().isPresent() && delivery.hudView().get().plainText().equals(event.text()))
                    receipts.add(receipt(event, recipient, "HUD", delivery.hudView().get()));
                else missingViews = true;
            }
            if (delivery.chatView().isPresent()) projectedBytes += delivery.chatView().get().plainText().getBytes(StandardCharsets.UTF_8).length;
            if (delivery.hudView().isPresent()) projectedBytes += delivery.hudView().get().plainText().getBytes(StandardCharsets.UTF_8).length;
            if (projectedBytes > WorldRecordingService.QUEUE_BYTES) return failed("ROOM_VIEW_BYTES_BUDGET", true);
        }
        // The virtual God's receipt is issued only from the game's explicit heardGodIds, never room membership alone.
        var heard = new RoomDialogueEvent.DispatchView(event.text(), List.of(event.text()), Set.of(0), Map.of());
        for (String god : new TreeSet<>(event.heardGodIds())) receipts.add(receipt(event, new ActorRef(ActorKind.GOD, god), "GAME_HEARD", heard));
        var participants = new HashSet<ActorRef>();
        event.participantNames().keySet().forEach(id -> participants.add(new ActorRef(ActorKind.PLAYER, id.toString())));
        event.godIds().forEach(id -> participants.add(new ActorRef(ActorKind.GOD, id)));
        var audience = new HashSet<ActorRef>();
        // Only exact full views establish an archive audience. Legacy page counts cannot silently establish one.
        for (var receipt : receipts) if (receipt.status() == DeliveryStatus.SERVER_DISPATCHED
                && (receipt.recipient().kind() == ActorKind.GOD || event.fullTextReceiverIds().contains(UUID.fromString(receipt.recipient().id())))) audience.add(receipt.recipient());
        var names = new TreeMap<String, String>(); event.participantNames().forEach((id, name) -> names.put(id.toString(), name));
        var transports = new TreeMap<String, Map<Integer, UUID>>();
        event.deliveries().forEach((id, delivery) -> delivery.hudView().filter(view -> !view.transportMessageIds().isEmpty())
                .ifPresent(view -> transports.put(id + "/HUD", view.transportMessageIds())));
        var context = new PublicationContext(store.runtimeEpoch(), event.revision(), event.recordingScope().name(), participants, audience, names,
                event.evidenceRefs().stream().map(ref -> new EvidencePointer(ref.kind(), ref.payload())).toList(), event.sourceMessageIds(),
                transports, "UNKNOWN_ORIGINAL_GAME_TICK_DAYTIME_DIMENSION", memoryMode.get());
        var envelope = new ConversationEnvelope(event.worldId(), store.datasetId().orElseThrow(), event.roomId(),
                event.roomType().isPublic() ? "ROOM_PUBLIC" : "ROOM_PRIVATE", event.roomType().isPublic() ? "PUBLIC_SPEECH" : "ACTUAL_LISTENERS_ONLY",
                event.revision(), event.revision(), true, false, "room-publication-v2");
        var raw = new RawMessage(event.messageId(), event.turnId(), event.turnSequence(),
                new ActorRef(event.role().equals("PLAYER") ? ActorKind.PLAYER : ActorKind.GOD, event.speakerId()), event.text(),
                Instant.ofEpochMilli(event.occurredAtUtc()), event.role().equals("PLAYER") ? MessageKind.ACCEPTED_INPUT : MessageKind.DELIVERED_OUTPUT,
                event.messageId().toString(), context);
        if (missingViews) store.captureGap(producer, "EXACT_DELIVERY_VIEW_UNAVAILABLE");
        final boolean omitted = missingViews;
        var immutable = List.copyOf(receipts);
        int firstCount = Math.min(RECEIPT_BATCH, immutable.size());
        CompletionStage<Outcome> result = store.capture(producer, envelope, raw, immutable.subList(0, firstCount)).thenApply(write ->
                new Outcome(write.status(), success(write.status()) ? firstCount : 0, immutable.size(), success(write.status())
                        ? omitted ? "EXACT_DELIVERY_VIEW_UNAVAILABLE" : "COMPLETE" : write.reasonCode()));
        for (int offset = firstCount; offset < immutable.size(); offset += RECEIPT_BATCH) {
            int index = offset / RECEIPT_BATCH;
            var batch = new DeliveryBatch(id(event.messageId(), "batch/" + index), event.messageId(), event.messageId() + "/batch/" + index,
                    immutable.subList(offset, Math.min(offset + RECEIPT_BATCH, immutable.size())));
            result = result.thenCompose(previous -> {
                if (!success(previous.rawStatus()) || !(previous.reason().equals("COMPLETE") || previous.reason().equals("EXACT_DELIVERY_VIEW_UNAVAILABLE")))
                    return CompletableFuture.completedFuture(previous);
                return store.recordDeliveries(producer, batch).thenApply(write -> success(write.status())
                        ? new Outcome(previous.rawStatus(), previous.committedReceipts() + batch.deliveries().size(), previous.plannedReceipts(), previous.reason())
                        : new Outcome(previous.rawStatus(), previous.committedReceipts(), previous.plannedReceipts(), "PARTIAL_RECEIPTS_" + write.status()));
            });
        }
        return result;
    }
    private CompletionStage<Outcome> failed(String reason, boolean gap) {
        if (gap) store.captureGap(producer, reason);
        return CompletableFuture.completedFuture(new Outcome(Status.UNAVAILABLE, 0, 0, reason));
    }
    private static boolean success(Status status) { return status == Status.STORED || status == Status.DUPLICATE; }
    private static DeliveryReceipt receipt(RoomDialogueEvent event, ActorRef recipient, String kind, RoomDialogueEvent.DispatchView view) {
        return new DeliveryReceipt(id(event.messageId(), recipient.key() + "/" + kind), recipient, kind, Instant.ofEpochMilli(event.occurredAtUtc()),
                event.revision(), view.complete() ? DeliveryStatus.SERVER_DISPATCHED : DeliveryStatus.PARTIAL_DISPATCH,
                new DeliveryView(view.plainText(), view.parts()), view.dispatchedParts());
    }
    private static UUID id(UUID message, String discriminator) {
        return UUID.nameUUIDFromBytes((message + "/" + discriminator).getBytes(StandardCharsets.UTF_8));
    }
}
