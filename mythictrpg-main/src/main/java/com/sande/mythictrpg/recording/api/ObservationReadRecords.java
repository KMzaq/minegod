package com.sande.mythictrpg.recording.api;

import com.sande.mythictrpg.ai.experiencecontract.ExperienceView;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.util.*;

/** Already disclosed, personally observed game experience. Not the raw ledger or a gameplay capability. */
public final class ObservationReadRecords {
    private ObservationReadRecords() { }
    public record Entry(SourceRef source, UUID knowledgeReceiptId, String observerGodId,
                        UUID subjectPlayerId, ExperienceView.Event experience) {
        public Entry {
            Objects.requireNonNull(source); Objects.requireNonNull(knowledgeReceiptId);
            new ActorRef(ActorKind.GOD, observerGodId); Objects.requireNonNull(subjectPlayerId); Objects.requireNonNull(experience);
            if (!source.owner().equals("action-ledger-v1")
                    || !Set.of(SourceKind.ACTION_OBSERVED, SourceKind.ACTIVITY_OBSERVED).contains(source.kind())
                    || !source.sourceId().equals(experience.eventId().toString()) || source.revision()!=experience.sourceRevision()
                    || (source.kind()==SourceKind.ACTIVITY_OBSERVED)!=experience.actionType().equals("OBSERVED_ACTIVITY_SUMMARY"))
                throw new IllegalArgumentException("OBSERVATION_SOURCE_MISMATCH");
        }
    }
    public static final class Cursor {
        private Cursor() { }
        public static Cursor unregistered() { return new Cursor(); }
        @Override public String toString() { return "ObservationCursor[opaque]"; }
    }
    public record Page(MemoryReadSession.Status status, List<Entry> entries, Optional<Cursor> next) {
        public Page { Objects.requireNonNull(status); entries=List.copyOf(entries); Objects.requireNonNull(next); }
    }
}
