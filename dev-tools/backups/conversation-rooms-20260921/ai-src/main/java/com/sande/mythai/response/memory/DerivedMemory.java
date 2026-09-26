package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Verbatim derived index, not model-authored facts. Human interpretation is deliberately not inferred. */
public final class DerivedMemory {
    public static final String EXTRACTOR="verbatim-hints-v1";
    public enum Kind { RAW_STATEMENT, PLAN_OR_PROMISE_HINT, REPORTED_OR_CONDITIONAL_HINT }
    public record Note(int schemaVersion,UUID sourceId,String sourceHash,MemoryJournal.Key key,Set<UUID> audience,
                       String extractor,Kind kind,String quote,long recordedAt,String eventDate,boolean important) {
        public Note {
            audience=Set.copyOf(audience);Objects.requireNonNull(sourceId);Objects.requireNonNull(key);Objects.requireNonNull(kind);
            if(schemaVersion!=1||!EXTRACTOR.equals(extractor)||sourceHash==null||!sourceHash.matches("[A-Fa-f0-9]{64}")
                    ||quote==null||quote.isBlank()||quote.length()>300||recordedAt<0||eventDate==null||eventDate.length()>32
                    ||audience.isEmpty()||!audience.contains(key.player()))throw new IllegalArgumentException("derived note");
        }
        public String identity() { return sourceId+"/"+sourceHash+"/"+extractor; }
    }
    private static final Gson JSON=new Gson();
    private DerivedMemory() {}
    public static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static String fingerprint(MemoryJournal.Entry e) {
        // Explicit stable ordering: Set iteration must not change hashes across JVM restarts.
        String value=JSON.toJson(List.of(e.id().toString(),e.key().world().toString(),e.key().god(),e.key().player().toString(),
                e.session().toString(),e.turn(),e.source().name(),e.audience().stream().map(UUID::toString).sorted().toList(),e.occurredAt(),e.text(),e.important()));
        // Owner-only legacy fingerprints remain stable; a multi-God disclosure scope is evidence too.
        if (!e.godAudience().equals(Set.of(e.key().god()))) value += "\ngodAudience=" + JSON.toJson(e.godAudience().stream().sorted().toList());
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static Note project(MemoryJournal.Entry e,RecallSettings.TimeBasis basis) {
        if(e.source()!=MemoryJournal.Source.PLAYER_STATEMENT)throw new IllegalArgumentException("not a personal statement");
        Kind kind=e.text().matches(".*(다고 했|라고 했|라면|다면|농담|장난|취소|변경|말고).*")?Kind.REPORTED_OR_CONDITIONAL_HINT:
                RecallSearch.looksLikePlan(e.text())&&!RecallQuery.explicitRecall(e.text())?Kind.PLAN_OR_PROMISE_HINT:Kind.RAW_STATEMENT;
        var date=kind==Kind.PLAN_OR_PROMISE_HINT?RecallSearch.date(e.text(),e.occurredAt(),basis):null;
        return new Note(1,e.id(),fingerprint(e),e.key(),e.audience(),EXTRACTOR,kind,e.text().substring(0,Math.min(300,e.text().length())),
                e.occurredAt(),date==null?"UNSPECIFIED":date.toString(),e.important());
    }
    public static boolean valid(Note note,MemoryJournal.Entry source,Set<UUID> audience) {
        return source!=null && source.source()==MemoryJournal.Source.PLAYER_STATEMENT && note.sourceId().equals(source.id())
                &&note.key().equals(source.key())&&note.audience().equals(source.audience())&&source.audience().containsAll(audience)
                &&note.sourceHash().equals(fingerprint(source))&&source.text().startsWith(note.quote());
    }
    /** Explicit reviewed links only. A new statement alone never deletes/replaces the old raw evidence. */
    public record ReviewedLink(Note older,Note newer,String relation) {
        public ReviewedLink {
            if(!Set.of("CORRECTS","CONTRADICTS","CANCELS","ALSO_PLANNED").contains(relation)
                    ||!older.key().equals(newer.key())||!older.audience().equals(newer.audience())
                    ||older.sourceId().equals(newer.sourceId())||older.recordedAt()>newer.recordedAt())throw new IllegalArgumentException("cross-scope/invalid correction link");
        }
    }
}
