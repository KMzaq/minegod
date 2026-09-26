package com.sande.mythai.response.memory;

import java.time.Duration;
import java.util.Locale;

/** Retrieval/admission hints only, never a fact, relationship change, or resolved-promise decision. */
public final class MemorySalience {
    private MemorySalience() { }
    public static boolean protectedCandidate(MemoryJournal.Entry entry) {
        if(entry.important() || entry.source()==MemoryJournal.Source.GAME_CONFIRMED)return true;
        if(entry.source()!=MemoryJournal.Source.PLAYER_STATEMENT)return false;
        String text=entry.text();
        return !RecallQuery.explicitRecall(text) && !RecallQuery.bareFollowUp(text)
                && !text.matches("(?is).*(농담|장난|라면|다면|라고 했|다고 했|친구가|만약|jok(e|ing)|if |they said).*" )
                && (RecallSearch.looksLikePlan(text) || text.matches("(?is).*(정정|취소|계획.*변경|약속.*지켰|약속.*완료|correction|cancelled|fulfilled).*"));
    }
    public static int adjustment(MemoryJournal.Entry entry,long now) {
        int priority=entry.important()?10:entry.source()==MemoryJournal.Source.GAME_CONFIRMED?8:protectedCandidate(entry)?6:0;
        if(entry.source()==MemoryJournal.Source.NPC_UTTERANCE)priority-=8;
        long age=Math.max(0,now-entry.occurredAt())/Duration.ofDays(7).toMillis();
        return priority-(protectedCandidate(entry)?0:(int)Math.min(12,age));
    }
    public static boolean searchable(MemoryJournal.Entry entry,long now,boolean explicit) {
        return entry.occurredAt()<=now && (explicit || protectedCandidate(entry)
                || now-entry.occurredAt()<=Duration.ofDays(30).toMillis());
    }
    public static String daylightCondition(String text) {
        String value=text.toLowerCase(Locale.ROOT);
        boolean sunrise=value.matches("(?s).*(해가?\\s*뜨|해\\s*뜰|일출|sunrise).*");
        boolean sunset=value.matches("(?s).*(해가?\\s*지|해\\s*질|일몰|sunset).*");
        return sunrise&&sunset?"MINECRAFT_DAYLIGHT_UNRESOLVED":sunrise?"MINECRAFT_SUNRISE":sunset?"MINECRAFT_SUNSET":"";
    }
}
