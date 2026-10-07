package com.sande.mythictrpg.recording.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Immutable storage contracts. None of these DTOs grants knowledge, publication or gameplay authority. */
public final class RecordingRecords {
    private RecordingRecords() { }
    public enum ActorKind { PLAYER, GOD }
    public enum MessageKind { ACCEPTED_INPUT, DELIVERED_OUTPUT, AUTHORED_OUTPUT }
    public enum DeliveryStatus { SERVER_DISPATCHED, PARTIAL_DISPATCH }
    public enum SourceKind { DIALOGUE_DIRECT, ACTION_OBSERVED, ACTIVITY_OBSERVED, RUMOR_RECEIVED, GAME_STATE_SNAPSHOT, DERIVED_SPEECH }
    public enum Status { STORED, DUPLICATE, CONFLICT, FULL, UNAVAILABLE }
    public record ActorRef(ActorKind kind, String id) {
        public ActorRef {
            Objects.requireNonNull(kind); token(id, 256);
            if (kind == ActorKind.PLAYER && !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("NONCANONICAL_PLAYER");
            if (kind == ActorKind.GOD && !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("INVALID_GOD_ID");
        }
        public String key() { return kind + ":" + id; }
    }
    public record ConversationEnvelope(UUID worldId, UUID datasetId, UUID conversationId, String channel,
            String privacyPolicy, long policyRevision, long membershipRevision, boolean recordingAllowed, boolean closed, String adapterVersion) {
        public ConversationEnvelope {
            Objects.requireNonNull(worldId); Objects.requireNonNull(datasetId); Objects.requireNonNull(conversationId);
            token(channel, 128); token(privacyPolicy, 256); token(adapterVersion, 128);
            if (policyRevision < 0 || membershipRevision < 0) throw new IllegalArgumentException("NEGATIVE_REVISION");
        }
    }
    public record EvidencePointer(String kind, String payload) {
        public EvidencePointer { token(kind, 128); Objects.requireNonNull(payload); if (payload.isBlank() || payload.length() > 65536) throw new IllegalArgumentException("INVALID_EVIDENCE_POINTER"); }
    }
    /** Historical publication scope only; portable evidence is NOT automatically a currently valid knowledge grant. */
    public record PublicationContext(UUID runtimeEpoch, long membershipRevision, String recordingPolicy,
            Set<ActorRef> participants, Set<ActorRef> fullAudience, Map<String, String> participantNames,
            List<EvidencePointer> evidence, Set<UUID> sourceMessages, Map<String, Map<Integer, UUID>> transportMessageIds, String gameTimeStatus, String memoryMode) {
        public PublicationContext(UUID runtimeEpoch, long membershipRevision, String recordingPolicy,
                Set<ActorRef> participants, Set<ActorRef> fullAudience, Map<String, String> participantNames,
                List<EvidencePointer> evidence, Set<UUID> sourceMessages, Map<String, Map<Integer, UUID>> transportMessageIds, String gameTimeStatus) {
            this(runtimeEpoch, membershipRevision, recordingPolicy, participants, fullAudience, participantNames, evidence,
                    sourceMessages, transportMessageIds, gameTimeStatus, "UNKNOWN");
        }
        public PublicationContext {
            Objects.requireNonNull(runtimeEpoch); token(recordingPolicy, 64); token(gameTimeStatus, 128);
            if (!Set.of("UNKNOWN", "OFF", "PERSONAL", "RUMOR_TEST").contains(memoryMode)) throw new IllegalArgumentException("INVALID_MEMORY_MODE");
            participants = Set.copyOf(participants); fullAudience = Set.copyOf(fullAudience); participantNames = Map.copyOf(participantNames);
            evidence = List.copyOf(evidence); sourceMessages = Set.copyOf(sourceMessages);
            var transports = new TreeMap<String, Map<Integer, UUID>>();
            transportMessageIds.forEach((key, ids) -> { token(key, 256); if (ids.size() > 4096) throw new IllegalArgumentException("TRANSPORT_BUDGET"); transports.put(key, Map.copyOf(ids)); });
            transportMessageIds = Map.copyOf(transports);
            if (membershipRevision < 0 || participants.size() > 80 || fullAudience.size() > 4096 || participantNames.size() > 64
                    || evidence.size() > 64 || sourceMessages.size() > 256 || transportMessageIds.size() > 4096) throw new IllegalArgumentException("PUBLICATION_CONTEXT_BUDGET");
        }
    }
    public record RawMessage(UUID messageId, Optional<UUID> turnId, long turnSequence, ActorRef speaker, String body,
            Instant occurredAt, MessageKind kind, String occurrenceKey, PublicationContext publicationContext) {
        public RawMessage(UUID messageId, Optional<UUID> turnId, long turnSequence, ActorRef speaker, String body,
                Instant occurredAt, MessageKind kind, String occurrenceKey) {
            this(messageId, turnId, turnSequence, speaker, body, occurredAt, kind, occurrenceKey, null);
        }
        public RawMessage {
            Objects.requireNonNull(messageId); Objects.requireNonNull(turnId); Objects.requireNonNull(speaker);
            Objects.requireNonNull(body); Objects.requireNonNull(occurredAt); Objects.requireNonNull(kind); token(occurrenceKey, 512);
            if (turnSequence < 0 || body.isEmpty()) throw new IllegalArgumentException("INVALID_MESSAGE");
        }
        public String bodyHash() { return sha256(body); }
    }
    /** The exact accepted display projection and its ordered pages, not reconstructed from a page count. */
    public record DeliveryView(String plainText, List<String> parts) {
        public DeliveryView {
            Objects.requireNonNull(plainText); parts = List.copyOf(parts);
            if (parts.isEmpty() || parts.size() > 4096 || parts.stream().anyMatch(Objects::isNull)
                    || !String.join("", parts).equals(plainText)) throw new IllegalArgumentException("INCOMPLETE_DELIVERY_VIEW");
        }
        public String hash() {
            var canonical = new StringBuilder().append(plainText.length()).append(':').append(plainText);
            for (String part : parts) canonical.append(part.length()).append(':').append(part);
            return sha256(canonical.toString());
        }
    }
    public record DeliveryReceipt(UUID receiptId, ActorRef recipient, String deliveryKind, Instant dispatchedAt,
            long audienceRevision, DeliveryStatus status, DeliveryView view, Set<Integer> deliveredParts) {
        public DeliveryReceipt {
            Objects.requireNonNull(receiptId); Objects.requireNonNull(recipient); token(deliveryKind, 64);
            Objects.requireNonNull(dispatchedAt); Objects.requireNonNull(status); Objects.requireNonNull(view);
            deliveredParts = Set.copyOf(deliveredParts);
            if (audienceRevision < 0 || deliveredParts.isEmpty() || deliveredParts.stream().anyMatch(i -> i < 0 || i >= view.parts().size())
                    || (status == DeliveryStatus.SERVER_DISPATCHED) != (deliveredParts.size() == view.parts().size()))
                throw new IllegalArgumentException("INCONSISTENT_DELIVERY_PARTS");
        }
    }
    public record DeliveryBatch(UUID batchId, UUID messageId, String occurrenceKey, List<DeliveryReceipt> deliveries) {
        public DeliveryBatch { Objects.requireNonNull(batchId); Objects.requireNonNull(messageId); token(occurrenceKey, 512); deliveries = receipts(deliveries); }
    }
    public record SourceRef(UUID worldId, UUID datasetId, SourceKind kind, String owner, String sourceId, long revision, String hash) {
        public SourceRef {
            Objects.requireNonNull(worldId); Objects.requireNonNull(datasetId); Objects.requireNonNull(kind);
            token(owner, 256); token(sourceId, 512);
            if (revision < 0 || hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("INVALID_SOURCE_REVISION_HASH");
        }
    }
    public record KnowledgeReceipt(UUID receiptId, String godId, String acquisition, String permittedProjection,
            Set<ActorRef> permittedAudience, long policyRevision) {
        public KnowledgeReceipt {
            Objects.requireNonNull(receiptId); new ActorRef(ActorKind.GOD, godId); token(acquisition, 64);
            Objects.requireNonNull(permittedProjection); permittedAudience = Set.copyOf(permittedAudience);
            if (policyRevision < 0 || permittedAudience.size() > 256) throw new IllegalArgumentException("INVALID_KNOWLEDGE_SCOPE");
        }
    }
    public record SourceCapture(SourceRef source, long durableSourceCursor, List<KnowledgeReceipt> knowledge) {
        public SourceCapture { Objects.requireNonNull(source); knowledge = knowledge.stream().sorted(Comparator.comparing(k -> k.receiptId().toString())).toList(); if (durableSourceCursor < 0 || knowledge.size() > 256) throw new IllegalArgumentException("INVALID_SOURCE_CAPTURE"); }
    }
    public record SourceInvalidation(SourceRef source, long stateVersion, String reasonCode) {
        public SourceInvalidation { Objects.requireNonNull(source); token(reasonCode, 64); if (stateVersion <= 0) throw new IllegalArgumentException("INVALID_STATE_VERSION"); }
    }
    /** Original-store metadata, already durably committed with its source snapshot. Confirmation is not an ingestion checkpoint. */
    public record SourceRegistration(UUID worldId, UUID datasetId, String owner, UUID lineageId, long durableCursor) {
        public SourceRegistration {
            Objects.requireNonNull(worldId); Objects.requireNonNull(datasetId); Objects.requireNonNull(lineageId); token(owner, 128);
            if (durableCursor < 0) throw new IllegalArgumentException("INVALID_SOURCE_REGISTRATION");
        }
    }
    public record SourceRegistrationReceipt(WriteReceipt receipt, OptionalLong cutoverCursor) {
        public SourceRegistrationReceipt { Objects.requireNonNull(receipt); Objects.requireNonNull(cutoverCursor); }
    }
    public record AcquiredKnowledge(KnowledgeReceipt receipt, long acquiredCursor) {
        public AcquiredKnowledge { Objects.requireNonNull(receipt); if (acquiredCursor <= 0) throw new IllegalArgumentException("INVALID_ACQUISITION_CURSOR"); }
    }
    /** Incremental receipts for one immutable source revision; original root birth and receipt acquisition are distinct. */
    public record SourceKnowledgeCapture(SourceRef source, UUID lineageId, long originCursor, long sourceStateCursor, List<AcquiredKnowledge> knowledge) {
        public SourceKnowledgeCapture {
            Objects.requireNonNull(source); Objects.requireNonNull(lineageId);
            knowledge = knowledge.stream().sorted(Comparator.comparing(k -> k.receipt().receiptId().toString())).toList();
            if (originCursor <= 0 || sourceStateCursor < originCursor || knowledge.size() > 256
                    || knowledge.stream().anyMatch(k -> k.acquiredCursor() < sourceStateCursor)
                    || knowledge.stream().map(k -> k.receipt().receiptId()).distinct().count() != knowledge.size())
                throw new IllegalArgumentException("INVALID_INCREMENTAL_KNOWLEDGE");
        }
    }
    /** Permanently withdraws one proof, not the shared source or other Gods' receipts. */
    public record KnowledgeInvalidation(SourceRef source, UUID receiptId, long stateVersion, String reasonCode) {
        public KnowledgeInvalidation {
            Objects.requireNonNull(source); Objects.requireNonNull(receiptId); token(reasonCode, 64);
            if (stateVersion <= 0) throw new IllegalArgumentException("INVALID_STATE_VERSION");
        }
    }
    public record WriteReceipt(Status status, OptionalLong ingestSequence, String recordId, String reasonCode) {
        public WriteReceipt { Objects.requireNonNull(status); Objects.requireNonNull(ingestSequence); Objects.requireNonNull(recordId); token(reasonCode, 64); }
        public static WriteReceipt failed(Status status, String id, String reason) { return new WriteReceipt(status, OptionalLong.empty(), id, reason); }
    }
    public static List<DeliveryReceipt> receipts(List<DeliveryReceipt> values) {
        var copy = values.stream().sorted(Comparator.comparing(r -> r.receiptId().toString())).toList();
        if (copy.size() > 256 || copy.stream().map(DeliveryReceipt::receiptId).distinct().count() != copy.size())
            throw new IllegalArgumentException("INVALID_RECEIPT_SET");
        return copy;
    }
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void token(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("INVALID_TOKEN");
    }
}
