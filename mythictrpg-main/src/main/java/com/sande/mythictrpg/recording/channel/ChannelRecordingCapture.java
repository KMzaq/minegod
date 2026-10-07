package com.sande.mythictrpg.recording.channel;

import com.sande.mythictrpg.recording.api.ProducerCapability;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import com.sande.mythictrpg.recording.server.WorldRecordingService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Accepted channel occurrences, not an event listener or a second chat/knowledge engine. */
public final class ChannelRecordingCapture {
    public enum Channel { PUBLIC_CHAT, PRIVATE_MSG, SCOREBOARD_TEAM, PLAYER_SAY, PLAYER_EMOTE, FTB_TEAM }
    public static final int MAX_RECEIPTS = 256, MAX_BODY_CHARS = 131072;
    public record Dispatch(UUID recipient, String surface, String view, boolean fullOriginal) {
        public Dispatch { Objects.requireNonNull(recipient); Objects.requireNonNull(surface); Objects.requireNonNull(view); }
    }
    public record Occurrence(UUID world, UUID epoch, UUID id, UUID speaker, Channel channel, String body, Instant acceptedAt,
            List<Dispatch> deliveries, String gap) {
        public Occurrence { deliveries = List.copyOf(deliveries); Objects.requireNonNull(gap); }
    }
    /** Mutable only during one synchronous game-thread dispatch. Bounds apply before copying/storing display bodies. */
    public static final class Pending {
        private final UUID world, epoch, id, speaker;
        private final Channel channel;
        private final String body;
        private final Instant acceptedAt = Instant.now();
        private final List<Dispatch> deliveries = new ArrayList<>();
        private long bytes;
        private String gap = "";
        public Pending(UUID world, UUID epoch, UUID id, UUID speaker, Channel channel, String body) {
            this.world = world; this.epoch = epoch; this.id = id; this.speaker = speaker; this.channel = channel;
            this.body = Objects.requireNonNull(body);
            if (body.length() > MAX_BODY_CHARS) gap = "CHANNEL_BODY_BUDGET";
            bytes = (long) body.length() * 3;
        }
        public void dispatched(UUID recipient, String surface, String view, boolean fullOriginal) {
            if (!gap.isEmpty()) return;
            if (deliveries.size() == MAX_RECEIPTS) { gap = "CHANNEL_RECEIPT_BUDGET"; return; }
            if (view.length() > MAX_BODY_CHARS || bytes + (long) view.length() * 3 > WorldRecordingService.QUEUE_BYTES) {
                gap = "CHANNEL_VIEW_BUDGET"; return;
            }
            bytes += (long) view.length() * 3;
            deliveries.add(new Dispatch(recipient, surface, view, fullOriginal));
        }
        public Occurrence freeze() { return new Occurrence(world, epoch, id, speaker, channel, body, acceptedAt, deliveries, gap); }
        public void reject(String reason) { if (gap.isEmpty()) gap = reason; }
    }
    private final WorldRecordingService store;
    private final ProducerCapability producer;
    private final Thread owner = Thread.currentThread();
    private long counter;
    public ChannelRecordingCapture(WorldRecordingService store) {
        this.store = Objects.requireNonNull(store);
        producer = store.registerProducer("accepted-channels-v2", Arrays.stream(Channel.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet()), Set.of());
    }
    public Pending begin(UUID speaker, Channel channel, String body) {
        requireOwner();
        if (body.isEmpty() || store.health().state() != WorldRecordingService.State.READY) return null;
        counter = Math.incrementExact(counter);
        return new Pending(store.worldId(), store.runtimeEpoch(), id(store.runtimeEpoch(), "occurrence/" + counter), speaker, channel, body);
    }
    public void gap(String reason) { store.captureGap(producer, reason); }
    public void gap(String reason, long count) { store.captureGap(producer, reason, count); }
    public CompletionStage<WriteReceipt> capture(Occurrence event, boolean stillCurrent) {
        requireOwner();
        if (!stillCurrent || !store.worldId().equals(event.world()) || !store.runtimeEpoch().equals(event.epoch())) return failed(event, "STALE_CHANNEL_SCOPE");
        if (!event.gap().isEmpty()) return failed(event, event.gap());
        // Reaching this producer means the channel accepted the input, not that anyone received it.
        // Preserve that occurrence with an empty audience/receipt set; never invent an echo or God hearing.
        if (event.deliveries().size() > MAX_RECEIPTS || event.body().length() > MAX_BODY_CHARS) return failed(event, "CHANNEL_SNAPSHOT_BUDGET");
        var speaker = new ActorRef(ActorKind.PLAYER, event.speaker().toString());
        var audience = new HashSet<ActorRef>(); var receipts = new ArrayList<DeliveryReceipt>();
        var surfaces = new LinkedHashMap<String, List<Dispatch>>();
        for (var delivery : event.deliveries()) {
            var recipient = new ActorRef(ActorKind.PLAYER, delivery.recipient().toString());
            if (delivery.fullOriginal()) audience.add(recipient);
            surfaces.computeIfAbsent(recipient.key() + "/" + delivery.surface(), ignored -> new ArrayList<>()).add(delivery);
        }
        for (var entry : surfaces.entrySet()) {
            var group = entry.getValue(); var first = group.getFirst();
            var parts = group.stream().map(Dispatch::view).toList();
            var indices = java.util.stream.IntStream.range(0, parts.size()).boxed().collect(java.util.stream.Collectors.toSet());
            receipts.add(new DeliveryReceipt(id(event.id(), entry.getKey()), new ActorRef(ActorKind.PLAYER, first.recipient().toString()), first.surface(), event.acceptedAt(), 0,
                    DeliveryStatus.SERVER_DISPATCHED, new DeliveryView(String.join("", parts), parts), indices));
        }
        var context = new PublicationContext(store.runtimeEpoch(), 0, "ACTUAL_RECIPIENTS_ONLY", Set.of(speaker), audience, Map.of(), List.of(), Set.of(), Map.of(),
                "UNKNOWN_ORIGINAL_GAME_TICK_DAYTIME_DIMENSION");
        // Each occurrence has its own immutable audience snapshot. It is admitted before that logical conversation closes.
        var envelope = new ConversationEnvelope(store.worldId(), store.datasetId().orElseThrow(), event.id(), event.channel().name(),
                "ACTUAL_RECIPIENTS_ONLY", 0, 0, true, false, "accepted-channels-v2");
        var raw = new RawMessage(event.id(), Optional.empty(), 0, speaker, event.body(), event.acceptedAt(), MessageKind.ACCEPTED_INPUT, event.id().toString(), context);
        return store.capture(producer, envelope, raw, receipts);
    }
    private CompletionStage<WriteReceipt> failed(Occurrence event, String reason) {
        gap(reason); return CompletableFuture.completedFuture(WriteReceipt.failed(Status.UNAVAILABLE, event.id().toString(), reason));
    }
    private void requireOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("CHANNEL_CAPTURE_REQUIRES_GAME_THREAD"); }
    private static UUID id(UUID root, String discriminator) { return UUID.nameUUIDFromBytes((root + "/" + discriminator).getBytes(StandardCharsets.UTF_8)); }
}
