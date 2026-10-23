package com.sande.mythictrpg.understanding;

import java.util.*;

/** Source-bound interpretation, never an authoritative mental state or an execution permission. */
public record PurposeEvidence(UUID worldId, UUID playerId, UUID sourceId, long sourceOrder,
        UUID episodeId, Goal goal, Origin origin, Attribution attribution, Signal signal,
        Context context, boolean consequentialChoice, boolean test, String sourceDescription) {
    public enum Kind { MEET, LEARN_ABOUT, DO_ACTIVITY }
    public record Goal(Kind kind, String targetId) {
        public Goal {
            Objects.requireNonNull(kind);
            if (targetId == null || targetId.length() > 256 || !targetId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
                throw new IllegalArgumentException("PURPOSE_TARGET_NOT_RESOLVED");
        }
    }
    public enum Origin { SELF_SPEECH, GAME_ACTION, RUMOR }
    public enum Attribution { DIRECT_SELF, OTHER_PERSON, QUOTED, HYPOTHETICAL, UNRESOLVED }
    public enum Signal { ASK, DESIRE, CONSIDER, DECIDE, PAUSE, WITHDRAW, DISOWN, RESUME,
        CHOOSE, EXPLORE, PREPARE, PURSUE, COMPLETE }
    /** DIRECTED is a game-authored target link, not a conclusion drawn from a place/item name. */
    public enum Context { DIRECTED, AMBIGUOUS, FORCED, SYSTEM_SUGGESTED, MISSING }
    public PurposeEvidence {
        Objects.requireNonNull(worldId); Objects.requireNonNull(playerId); Objects.requireNonNull(sourceId);
        Objects.requireNonNull(episodeId); Objects.requireNonNull(goal); Objects.requireNonNull(origin);
        Objects.requireNonNull(attribution); Objects.requireNonNull(signal); Objects.requireNonNull(context);
        if (sourceOrder < 1 || sourceDescription == null || sourceDescription.length() > 1000)
            throw new IllegalArgumentException("PURPOSE_EVIDENCE_BOUNDS");
        boolean action = switch (signal) { case CHOOSE, EXPLORE, PREPARE, PURSUE, COMPLETE -> true; default -> false; };
        if (action != (origin == Origin.GAME_ACTION) || consequentialChoice && (signal != Signal.CHOOSE
                || origin != Origin.GAME_ACTION || context != Context.DIRECTED))
            throw new IllegalArgumentException("PURPOSE_SIGNAL_ORIGIN");
        if (origin == Origin.GAME_ACTION && attribution != Attribution.DIRECT_SELF)
            throw new IllegalArgumentException("PURPOSE_ACTION_ACTOR");
    }
    /** Only directly attributed source evidence can change a player's own purpose. */
    public boolean usable() {
        return !test && attribution == Attribution.DIRECT_SELF && origin != Origin.RUMOR;
    }
    public String identity() { return sourceId + "/" + goal.kind() + "/" + goal.targetId(); }
}
