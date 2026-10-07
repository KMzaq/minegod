package com.sande.mythictrpg.recording.api;

import java.time.Instant;
import java.util.*;

/** Grounded candidate interpretations, never world facts, executed actions or relationship values. */
public final class InterpretationReadRecords {
    private InterpretationReadRecords() { }
    public enum Authority { CANDIDATE }
    public record Quote(String sourceAlias, UUID messageId, RecordingRecords.ActorRef actualActor,
            Instant occurredAt, String text) {
        public Quote {
            alias(sourceAlias); Objects.requireNonNull(messageId); Objects.requireNonNull(actualActor);
            Objects.requireNonNull(occurredAt); Objects.requireNonNull(text);
            if (text.isBlank() || text.length() > 2000) throw new IllegalArgumentException("INTERPRETATION_QUOTE");
        }
    }
    /** Direction is explicit. CANCELS means a grounded cancellation claim, not an executed game cancellation. */
    public record Link(String newerAlias, String olderAlias, ProjectionRecords.Relation relation) {
        public Link { alias(newerAlias); alias(olderAlias); Objects.requireNonNull(relation);
            if (newerAlias.equals(olderAlias)) throw new IllegalArgumentException("INTERPRETATION_LINK"); }
    }
    /** Java UTF-16 prefix coverage of every extraction input, including unquoted context. */
    public record Coverage(String sourceAlias, UUID messageId, int coveredCharacters, int totalCharacters) {
        public Coverage { alias(sourceAlias); Objects.requireNonNull(messageId);
            if (coveredCharacters < 1 || totalCharacters < coveredCharacters) throw new IllegalArgumentException("INTERPRETATION_COVERAGE"); }
        public boolean excerpt() { return coveredCharacters < totalCharacters; }
    }
    public record Entry(UUID memoryId, ProjectionRecords.Layer layer, ProjectionRecords.ClaimKind kind,
            String extractorVersion, List<Quote> quotes, List<Link> links, List<Coverage> inputs) {
        public Entry {
            Objects.requireNonNull(memoryId); Objects.requireNonNull(layer); Objects.requireNonNull(kind);
            ProjectionRecords.version(extractorVersion); quotes = List.copyOf(quotes); links = List.copyOf(links); inputs = List.copyOf(inputs);
            if (quotes.isEmpty() || quotes.size() > 8 || links.size() > 2 || inputs.isEmpty() || inputs.size() > 6
                    || inputs.stream().map(Coverage::sourceAlias).distinct().count() != inputs.size())
                throw new IllegalArgumentException("INTERPRETATION_ENTRY");
            var aliases = inputs.stream().map(Coverage::sourceAlias).collect(java.util.stream.Collectors.toSet());
            if (quotes.stream().anyMatch(q -> !aliases.contains(q.sourceAlias()))
                    || links.stream().anyMatch(l -> !aliases.contains(l.newerAlias()) || !aliases.contains(l.olderAlias())))
                throw new IllegalArgumentException("INTERPRETATION_BINDING");
        }
        public Authority authority() { return Authority.CANDIDATE; }
    }
    /** Registered only by its issuing session; not a message ID, SQL cursor or reusable archive capability. */
    public static final class Cursor {
        private Cursor() { }
        public static Cursor unregistered() { return new Cursor(); }
        @Override public String toString() { return "InterpretationCursor[opaque]"; }
    }
    public record Page(MemoryReadSession.Status status, List<Entry> entries, Optional<Cursor> next) {
        public Page { Objects.requireNonNull(status); entries = List.copyOf(entries); Objects.requireNonNull(next); }
    }
    private static void alias(String value) {
        if (value == null || !value.matches("e[0-5]")) throw new IllegalArgumentException("INTERPRETATION_ALIAS");
    }
}
