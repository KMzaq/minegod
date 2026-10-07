package com.sande.mythictrpg.recording.api;

import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.time.Instant;
import java.util.*;

/** Grounded, non-authoritative interpretations. No gameplay, relationship value or knowledge-grant fields. */
public final class ProjectionRecords {
    private ProjectionRecords() { }
    public enum Layer { EVENT, RELATIONSHIP, SUMMARY }
    public enum ClaimKind { DIALOGUE_EPISODE, SPEAKER_CLAIM, INTENTION_OR_PROMISE, REPORTED_CLAIM, CONDITIONAL, JOKE, CORRECTION_OR_EXPLANATION }
    public enum Relation { CORRECTS, CONTRADICTS, CANCELS, ALSO_PLANNED, REPORTS_FULFILLMENT }
    public enum Status { CLAIMED, EMPTY, STORED, DUPLICATE, STALE, REJECTED, FULL, UNAVAILABLE, DEFERRED }
    public enum WorkOutcome { DEFERRED, FAILED, SKIPPED_UNSUPPORTED }
    public record WorkBudget(int maxSources, int maxUtf8Bytes, int leaseSeconds) {
        public WorkBudget {
            if (maxSources < 1 || maxSources > 6 || maxUtf8Bytes < 256 || maxUtf8Bytes > 32768 || leaseSeconds < 1 || leaseSeconds > 120)
                throw new IllegalArgumentException("PROJECTION_WORK_BUDGET");
        }
    }
    public record Evidence(String alias, SourceRef source, UUID knowledgeReceiptId, String receiptHash,
            UUID messageId, UUID conversationId, ActorRef actualActor, String observerGodId, Set<ActorRef> audience,
            String disclosureHash, String recordingPolicy, String memoryMode, Instant occurredAt,
            String text, boolean excerpt, int totalCharacters) {
        public Evidence {
            ProjectionRecords.alias(alias); Objects.requireNonNull(source); Objects.requireNonNull(knowledgeReceiptId); hash(receiptHash);
            Objects.requireNonNull(messageId); Objects.requireNonNull(conversationId); Objects.requireNonNull(actualActor);
            new ActorRef(ActorKind.GOD, observerGodId); audience = Set.copyOf(audience); hash(disclosureHash);
            Objects.requireNonNull(occurredAt); Objects.requireNonNull(text);
            if (audience.isEmpty() || audience.size() > 256 || !audience.contains(new ActorRef(ActorKind.GOD, observerGodId))
                    || !Set.of("STANDARD", "TEST_RECORDING").contains(recordingPolicy) || !Set.of("PERSONAL", "RUMOR_TEST").contains(memoryMode)
                    || text.isEmpty() || text.length() > 32768 || totalCharacters < text.length() || excerpt != (totalCharacters > text.length()))
                throw new IllegalArgumentException("INVALID_PROJECTION_EVIDENCE");
        }
    }
    public record Work(ProjectionWorkToken token, String extractorVersion, List<Evidence> evidence, String targetAlias, long leaseDeadlineEpochMillis) {
        public Work {
            Objects.requireNonNull(token); version(extractorVersion); evidence = List.copyOf(evidence); alias(targetAlias);
            if (evidence.isEmpty() || evidence.size() > 6 || evidence.stream().map(Evidence::alias).distinct().count() != evidence.size()
                    || evidence.stream().noneMatch(e -> e.alias().equals(targetAlias)) || leaseDeadlineEpochMillis <= 0)
                throw new IllegalArgumentException("INVALID_PROJECTION_WORK");
        }
    }
    public record Quote(String sourceAlias, String text) {
        public Quote { alias(sourceAlias); Objects.requireNonNull(text); if (text.isBlank() || text.length() > 2000) throw new IllegalArgumentException("INVALID_GROUNDED_QUOTE"); }
    }
    public record Link(String newerAlias, String olderAlias, Relation relation) {
        public Link { alias(newerAlias); alias(olderAlias); Objects.requireNonNull(relation); if (newerAlias.equals(olderAlias)) throw new IllegalArgumentException("SELF_PROJECTION_LINK"); }
    }
    public record Candidate(Layer layer, ClaimKind kind, List<Quote> quotes, List<Link> links) {
        public Candidate {
            Objects.requireNonNull(layer); Objects.requireNonNull(kind); quotes = List.copyOf(quotes); links = List.copyOf(links);
            if (quotes.isEmpty() || quotes.size() > 8 || links.size() > 2 || new HashSet<>(quotes).size() != quotes.size())
                throw new IllegalArgumentException("INVALID_PROJECTION_CANDIDATE");
        }
    }
    public record ClaimResult(Status status, Optional<Work> work, String reasonCode) {
        public ClaimResult { Objects.requireNonNull(status); Objects.requireNonNull(work); reason(reasonCode); if ((status == Status.CLAIMED) != work.isPresent()) throw new IllegalArgumentException("CLAIM_RESULT"); }
    }
    public record Result(Status status, String reasonCode, int memories) {
        public Result { Objects.requireNonNull(status); reason(reasonCode); if (memories < 0) throw new IllegalArgumentException("PROJECTION_RESULT"); }
    }
    public static void version(String value) { if (value == null || !value.matches("[a-zA-Z0-9_.:/-]{1,160}")) throw new IllegalArgumentException("EXTRACTOR_VERSION"); }
    public static void reason(String value) { if (value == null || !value.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("PROJECTION_REASON"); }
    private static void alias(String value) { if (value == null || !value.matches("e[0-5]")) throw new IllegalArgumentException("PROJECTION_ALIAS"); }
    private static void hash(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("PROJECTION_HASH"); }
}
