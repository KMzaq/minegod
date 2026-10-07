package com.sande.mythictrpg.recording.api;

import com.google.gson.Gson;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import com.sande.mythictrpg.rumor.NativeRumorReadAccess;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A currently received allegation with the game's current assessment, never direct observation or world truth. */
public final class RumorReadRecords {
    private static final Gson JSON=new Gson();
    private RumorReadRecords() { }
    public record Entry(SourceRef source,UUID knowledgeReceiptId,UUID lineageId,String recipientGodId,
                        UUID subjectPlayerId,String claim,String epithet,Set<UUID> disclosureAudience,
                        String reception,NativeRumorReadAccess.Assessment assessment) {
        public Entry {
            Objects.requireNonNull(source);Objects.requireNonNull(knowledgeReceiptId);Objects.requireNonNull(lineageId);
            new ActorRef(ActorKind.GOD,recipientGodId);Objects.requireNonNull(subjectPlayerId);Objects.requireNonNull(claim);Objects.requireNonNull(epithet);
            disclosureAudience=Set.copyOf(disclosureAudience);Objects.requireNonNull(assessment);
            if(source.kind()!=SourceKind.RUMOR_RECEIVED||!source.owner().equals("rumor-saved-data-v1")||source.revision()<1
                    ||!UUID.fromString(source.sourceId()).toString().equals(source.sourceId())||claim.isBlank()||claim.length()>300||epithet.length()>60
                    ||disclosureAudience.isEmpty()||disclosureAudience.size()>16||!disclosureAudience.contains(subjectPlayerId)
                    ||!Set.of("CAUTIOUS","INTERESTED").contains(reception))throw new IllegalArgumentException("RUMOR_READ_ENTRY");
        }
    }
    public static final class Cursor {
        private Cursor() { }
        public static Cursor unregistered(){return new Cursor();}
        @Override public String toString(){return "RumorCursor[opaque]";}
    }
    public record Page(MemoryReadSession.Status status,List<Entry> entries,Optional<Cursor> next) {
        public Page {Objects.requireNonNull(status);entries=List.copyOf(entries);Objects.requireNonNull(next);}
    }
    /** Complete typed-card wire size; Optional and UUID fields are explicitly serialized. */
    public static int wireByteSize(Entry entry) {
        var assessment=new LinkedHashMap<String,Object>();assessment.put("availability",entry.assessment().availability().name());
        assessment.put("outcome",entry.assessment().outcome().map(Enum::name).orElse(null));assessment.put("version",entry.assessment().version());
        return size(entry.source(),entry.knowledgeReceiptId(),entry.lineageId(),entry.recipientGodId(),entry.subjectPlayerId(),
                entry.claim(),entry.epithet(),entry.disclosureAudience(),entry.reception(),assessment);
    }
    /** Reserve for the largest current assessment/reception before the game attaches them. */
    public static int maximumWireByteSize(SourceRef source,UUID receipt,UUID lineage,String god,UUID subject,String claim,String epithet,Set<UUID> audience) {
        return size(source,receipt,lineage,god,subject,claim,epithet,audience,"UNSPECIFIED",
                Map.of("availability","UNASSESSED","outcome","RETRACTED","version",Long.MAX_VALUE));
    }
    private static int size(SourceRef source,UUID receipt,UUID lineage,String god,UUID subject,String claim,String epithet,Set<UUID> audience,String reception,Map<String,Object> assessment) {
        var card=new LinkedHashMap<String,Object>();
        card.put("source",Map.of("worldId",source.worldId().toString(),"datasetId",source.datasetId().toString(),"kind",source.kind().name(),
                "owner",source.owner(),"sourceId",source.sourceId(),"revision",source.revision(),"hash",source.hash()));
        card.put("knowledgeReceiptId",receipt.toString());card.put("lineageId",lineage.toString());card.put("recipientGodId",god);card.put("subjectPlayerId",subject.toString());
        card.put("claim",claim);card.put("epithet",epithet);card.put("disclosureAudience",audience.stream().map(UUID::toString).sorted().toList());
        card.put("reception",reception);card.put("assessment",assessment);return JSON.toJson(card).getBytes(StandardCharsets.UTF_8).length;
    }
}
