package com.sande.mythictrpg.recording.api;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** A game-issued, turn/speaker/audience-bound read port. Not an archive browser or gameplay capability. */
public interface MemoryReadSession {
    enum Status { FOUND, EMPTY, PARTIAL, UNAVAILABLE, STALE }
    /** Exact immutable actual-speaker selection, not names, subjects, observers or disclosure authority. */
    record ActorSelection(Optional<RecordingRecords.ActorKind> kind,Set<RecordingRecords.ActorRef> include,
                          Set<RecordingRecords.ActorRef> exclude) {
        public static final ActorSelection ANY=new ActorSelection(Optional.empty(),Set.of(),Set.of());
        public ActorSelection {
            Objects.requireNonNull(kind);include=Set.copyOf(include);exclude=Set.copyOf(exclude);
            if(include.size()+exclude.size()>16||!Collections.disjoint(include,exclude))throw new IllegalArgumentException("INVALID_ACTOR_SELECTION");
        }
        public boolean isAny(){return kind.isEmpty()&&include.isEmpty()&&exclude.isEmpty();}
        public boolean matches(RecordingRecords.ActorRef actor){return actor!=null&&kind.map(k->k==actor.kind()).orElse(true)
                &&(include.isEmpty()||include.contains(actor))&&!exclude.contains(actor);}
    }
    record Query(String text, Optional<Instant> fromInclusive, Optional<Instant> untilExclusive,ActorSelection actorSelection) {
        public Query(String text,Optional<Instant> fromInclusive,Optional<Instant> untilExclusive){this(text,fromInclusive,untilExclusive,ActorSelection.ANY);}
        public Query {
            Objects.requireNonNull(text); Objects.requireNonNull(fromInclusive); Objects.requireNonNull(untilExclusive);Objects.requireNonNull(actorSelection);
            if (text.length() > 8192 || fromInclusive.isPresent() && untilExclusive.isPresent()
                    && !fromInclusive.get().isBefore(untilExclusive.get())) throw new IllegalArgumentException("INVALID_MEMORY_QUERY");
        }
    }
    record Budget(int rows, int utf8Bytes) {
        public Budget { if (rows < 1 || rows > 8 || utf8Bytes < 256 || utf8Bytes > 65536) throw new IllegalArgumentException("MEMORY_READ_BUDGET"); }
    }
    record Entry(UUID messageId, RecordingRecords.ActorRef speaker, Instant occurredAt, String text, boolean excerpt) { }
    /** Opaque registered scan continuation, not a promise of more hits. Fixed bounded paging hides denied-source counts. */
    final class Cursor {
        private Cursor() { }
        public static Cursor unregistered() { return new Cursor(); }
        @Override public String toString() { return "MemoryCursor[opaque]"; }
    }
    record Page(Status status, List<Entry> entries, Optional<Cursor> next) {
        public Page { Objects.requireNonNull(status); entries = List.copyOf(entries); Objects.requireNonNull(next); }
    }
    CompletableFuture<Page> query(Query query, Optional<Cursor> cursor, Budget budget);
    /** Game thread, immediately before using a previously returned page. Fabricated/foreign pages are rejected. */
    boolean current(Page page);
    /** Native candidate interpretations of this session's issued raw seeds; unsupported implementations fail closed. */
    default CompletableFuture<InterpretationReadRecords.Page> interpretations(Page issuedRawSeeds,
            Optional<InterpretationReadRecords.Cursor> cursor, Budget budget) {
        return CompletableFuture.completedFuture(new InterpretationReadRecords.Page(Status.UNAVAILABLE, List.of(), Optional.empty()));
    }
    /** Native candidates grounded in this session's actual semantic page, never a fabricated raw-page adapter. */
    default CompletableFuture<InterpretationReadRecords.Page> interpretations(SemanticReadRecords.Page issuedSemanticSeeds,
            Optional<InterpretationReadRecords.Cursor> cursor, Budget budget) {
        return CompletableFuture.completedFuture(new InterpretationReadRecords.Page(Status.UNAVAILABLE, List.of(), Optional.empty()));
    }
    /** Game thread immediately before use, just like raw pages. Candidates are not authoritative facts. */
    default boolean current(InterpretationReadRecords.Page page) { return false; }
    /** Optional semantic candidates. Exact query/vector binding and all source permissions remain game-owned. */
    default CompletableFuture<SemanticReadRecords.Page> semantic(Query query, EmbeddingRecords.QueryVector vector,
            Optional<SemanticReadRecords.Cursor> cursor, Budget budget) {
        return CompletableFuture.completedFuture(new SemanticReadRecords.Page(Status.UNAVAILABLE, List.of(), Optional.empty()));
    }
    default boolean current(SemanticReadRecords.Page page) { return false; }
    /** Optional personally observed game projections, never raw game facts or permission to begin observing. */
    default CompletableFuture<ObservationReadRecords.Page> observations(Query query,
            Optional<ObservationReadRecords.Cursor> cursor, Budget budget) {
        return CompletableFuture.completedFuture(new ObservationReadRecords.Page(Status.UNAVAILABLE, List.of(), Optional.empty()));
    }
    default boolean current(ObservationReadRecords.Page page) { return false; }
    /** Optional received claims, with current game-owned assessment; never independent world facts. */
    default CompletableFuture<RumorReadRecords.Page> rumors(Query query,
            Optional<RumorReadRecords.Cursor> cursor, Budget budget) {
        return CompletableFuture.completedFuture(new RumorReadRecords.Page(Status.UNAVAILABLE, List.of(), Optional.empty()));
    }
    default boolean current(RumorReadRecords.Page page) { return false; }
    /** Game-issued speech provenance only. Whole actual pages, never caller-selected/fabricated IDs.
     * Supported content leaves require their actual owner's current permission; this is not a truth grant.
     * Does not authorize interpretations, observed events, rumors or unsupported external dependencies. */
    default CompletableFuture<Optional<NativeMemorySeal>> seal(List<Page> issuedRawPages,
            List<SemanticReadRecords.Page> issuedSemanticPages) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
    default boolean current(NativeMemorySeal seal) { return false; }
    /** Additive typed issuance from whole actual pages of this session, never caller IDs.
     * The default remains unsupported; a game store/strict reader must register real issuance.
     * Implementations check the live projection-generation fence and commit-time full-input manifest.
     * Candidates never become speech facts; this does not grant Watch/Rumor or gameplay authority. */
    default CompletableFuture<Optional<NativeInterpretationSeal>> sealInterpretations(List<Page> issuedRawPages,
            List<SemanticReadRecords.Page> issuedSemanticPages,List<InterpretationReadRecords.Page> issuedInterpretationPages) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
    default boolean current(NativeInterpretationSeal seal) { return false; }
}
