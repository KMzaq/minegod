package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import java.util.*;

/** Exact current utterance + game-resolved character ID. Fallible reading, never proof of a decision. */
public record CharacterGoalSignal(PurposeEvidence.Goal goal,PurposeEvidence.Signal signal,UUID messageId,String quote) {
    public static final String SUBJECT="mythictrpg:character_goal_signal";
    public CharacterGoalSignal {
        Objects.requireNonNull(goal);Objects.requireNonNull(messageId);
        if(goal.kind()==PurposeEvidence.Kind.DO_ACTIVITY||!Set.of(PurposeEvidence.Signal.ASK,PurposeEvidence.Signal.DESIRE,
                PurposeEvidence.Signal.CONSIDER,PurposeEvidence.Signal.DISOWN,PurposeEvidence.Signal.WITHDRAW,
                PurposeEvidence.Signal.PAUSE).contains(signal)||quote==null||quote.isBlank()||quote.length()>256)
            throw new IllegalArgumentException("character goal signal");
    }
    public Map<String,String> payload() {return Map.of("target",goal.targetId(),"kind",goal.kind().name(),"signal",signal.name(),"message",messageId.toString(),"quote",quote);}
    public static CharacterGoalSignal parse(Map<String,String> p) {
        if(!p.keySet().equals(Set.of("target","kind","signal","message","quote")))throw new IllegalArgumentException("character goal shape");
        return new CharacterGoalSignal(new PurposeEvidence.Goal(PurposeEvidence.Kind.valueOf(p.get("kind")),p.get("target")),
                PurposeEvidence.Signal.valueOf(p.get("signal")),UUID.fromString(p.get("message")),p.get("quote"));
    }
    public PurposeEvidence evidence(ActionRecord source) {
        if(source.event().type()!=ActionRecord.Type.PLAYER_CHARACTER_SIGNAL||!source.event().payload().equals(payload()))
            throw new IllegalArgumentException("character source mismatch");
        return new PurposeEvidence(source.worldId(),source.event().actorId(),source.event().occurrenceId(),source.sequence(),
                PurposeLedger.episodeOf(source),goal,PurposeEvidence.Origin.SELF_SPEECH,PurposeEvidence.Attribution.DIRECT_SELF,
                signal,PurposeEvidence.Context.DIRECTED,false,false,quote);
    }
}
