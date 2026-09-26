package com.sande.mythictrpg.ai.experiencecontract;

import java.util.*;

/** Read-only, already-disclosed game evidence. Never a raw ledger or an action/reward authority. */
public record ExperienceView(int schemaVersion, boolean available, String reason, List<Event> events, Relationship relationship) {
    public ExperienceView {
        if (schemaVersion != 1) throw new IllegalArgumentException("experience schema");
        Objects.requireNonNull(reason); Objects.requireNonNull(relationship); events = List.copyOf(events);
        if (events.size() > 16 || !available && !events.isEmpty()) throw new IllegalArgumentException("experience budget/availability");
    }
    public static ExperienceView unavailable(String reason) { return new ExperienceView(1, false, reason, List.of(), Relationship.UNKNOWN); }
    public record Event(UUID observationId, UUID eventId, int sourceRevision, String acquisitionKind,
                        String actionType, String subjectType, String outcome, String gameTime) {
        public Event {
            Objects.requireNonNull(observationId); Objects.requireNonNull(eventId);
            if (subjectType == null || outcome == null) throw new IllegalArgumentException("missing experience data");
            boolean crop="MATURE_CROP_REMOVED".equals(actionType)
                    &&subjectType.matches("minecraft:(wheat|carrots|potatoes|beetroots|nether_wart|cocoa)")
                    &&"BLOCK_REMOVED_NOT_ITEM_ACQUISITION".equals(outcome);
            boolean battle="BATTLE_RESULT".equals(actionType)&&Set.of("TARGET_KILLED","VICTORY","DEFEAT","WITHDRAWN","ABORTED").contains(outcome);
            boolean activity="OBSERVED_ACTIVITY_SUMMARY".equals(actionType)
                    &&outcome.matches("VISIBLE_SAMPLES=[1-9][0-9]{0,6};ACTIVITY=(BLOCK_REMOVED|MATURE_CROP_REMOVED|ENTITY_KILLED)");
            if (sourceRevision != 1 || !"DIRECT_WATCH".equals(acquisitionKind)
                    ||subjectType==null||subjectType.length()>256||!subjectType.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                    || !(crop||battle||activity) || gameTime == null || gameTime.length() > 120)
                throw new IllegalArgumentException("unsupported experience");
        }
    }
    /** Raw existing affinity is not an invented relationship tier or a second authoritative database. */
    public record Relationship(boolean available, Integer affinity) {
        public static final Relationship UNKNOWN = new Relationship(false, null);
        public Relationship { if (available != (affinity != null) || affinity != null && (affinity < -1000 || affinity > 1000)) throw new IllegalArgumentException("affinity"); }
    }
}
