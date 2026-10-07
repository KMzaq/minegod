package com.sande.mythictrpg.recording.api;

import java.time.Instant;
import java.util.*;

/** Similarity-ranked native RAW prefixes, not claims of complete recall or authoritative interpretation. */
public final class SemanticReadRecords {
    private SemanticReadRecords() { }
    public record Entry(UUID messageId, RecordingRecords.ActorRef speaker, Instant occurredAt, String text,
            double similarity, int coveredCharacters, int totalCharacters) {
        public Entry {
            Objects.requireNonNull(messageId);Objects.requireNonNull(speaker);Objects.requireNonNull(occurredAt);Objects.requireNonNull(text);
            if(text.isBlank()||text.length()!=coveredCharacters||coveredCharacters<1||coveredCharacters>1600||totalCharacters<coveredCharacters
                    ||!Double.isFinite(similarity)||similarity< -1||similarity>1)throw new IllegalArgumentException("SEMANTIC_READ_ENTRY");
        }
        public boolean excerpt(){return coveredCharacters<totalCharacters;}
    }
    public static final class Cursor {
        private Cursor() { }
        public static Cursor unregistered(){return new Cursor();}
        @Override public String toString(){return "SemanticCursor[opaque]";}
    }
    public record Page(MemoryReadSession.Status status,List<Entry> entries,Optional<Cursor> next) {
        public Page {Objects.requireNonNull(status);entries=List.copyOf(entries);Objects.requireNonNull(next);}
    }
}
