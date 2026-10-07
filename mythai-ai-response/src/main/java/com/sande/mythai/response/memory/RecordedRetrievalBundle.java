package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Ephemeral typed SHADOW result. Not a portable proof, a prompt grant, or an archive capability. */
public final class RecordedRetrievalBundle {
    public record Scope(UUID roomId, long revision, UUID turnId, UUID playerId, String speakerGodId,
                        boolean publicRoom, Set<String> godIds, Set<UUID> audiencePlayerIds) {
        public Scope {
            Objects.requireNonNull(roomId); Objects.requireNonNull(turnId); Objects.requireNonNull(playerId);
            Objects.requireNonNull(speakerGodId); godIds=Set.copyOf(godIds); audiencePlayerIds=Set.copyOf(audiencePlayerIds);
        }
        public static Scope of(Request request) {
            return new Scope(request.roomId(),request.revision(),request.turnId(),request.playerId(),request.speakerGodId().toString(),
                    request.publicRoom(),request.godIds().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()),request.audiencePlayerIds());
        }
    }
    public record Lane<T>(MemoryReadSession.Status status,List<T> entries,boolean attempted) {
        public Lane { Objects.requireNonNull(status); entries=List.copyOf(entries);
            if((!attempted||status==MemoryReadSession.Status.STALE||status==MemoryReadSession.Status.UNAVAILABLE)&&!entries.isEmpty())
                throw new IllegalArgumentException("UNUSABLE_RETRIEVAL_LANE"); }
    }
    private final Scope scope;
    private final MemoryReadSession.Query query;
    private final Lane<MemoryReadSession.Entry> raw;
    private final Lane<SemanticReadRecords.Entry> semantic;
    private final Lane<InterpretationReadRecords.Entry> interpretations;
    private final Lane<ObservationReadRecords.Entry> observations;
    private final Lane<RumorReadRecords.Entry> rumors;
    private final int utf8Bytes,calls;
    private final BooleanSupplier current;
    RecordedRetrievalBundle(Scope scope,MemoryReadSession.Query query,Lane<MemoryReadSession.Entry> raw,
            Lane<SemanticReadRecords.Entry> semantic,Lane<InterpretationReadRecords.Entry> interpretations,
            Lane<ObservationReadRecords.Entry> observations,Lane<RumorReadRecords.Entry> rumors,
            int utf8Bytes,int calls,BooleanSupplier current) {
        this.scope=Objects.requireNonNull(scope);this.query=Objects.requireNonNull(query);this.raw=Objects.requireNonNull(raw);
        this.semantic=Objects.requireNonNull(semantic);this.interpretations=Objects.requireNonNull(interpretations);
        this.observations=Objects.requireNonNull(observations);this.rumors=Objects.requireNonNull(rumors);
        if(utf8Bytes<0||utf8Bytes>65536||calls<0||calls>8)throw new IllegalArgumentException("RETRIEVAL_BUNDLE_BUDGET");
        this.utf8Bytes=utf8Bytes;this.calls=calls;this.current=Objects.requireNonNull(current);
    }
    public Scope scope(){return scope;} public MemoryReadSession.Query query(){return query;}
    public Lane<MemoryReadSession.Entry> raw(){return raw;} public Lane<SemanticReadRecords.Entry> semantic(){return semantic;}
    public Lane<InterpretationReadRecords.Entry> interpretations(){return interpretations;}
    public Lane<ObservationReadRecords.Entry> observations(){return observations;} public Lane<RumorReadRecords.Entry> rumors(){return rumors;}
    public int utf8Bytes(){return utf8Bytes;} public int calls(){return calls;}
    /** Must be called on the issuing game's dispatcher immediately before any diagnostic/fixture use. */
    public boolean current(){try{return current.getAsBoolean();}catch(RuntimeException unavailable){return false;}}

    private static final Gson JSON=new Gson();
    static int wireByteSize(Object entry){return JSON.toJson(card(entry)).getBytes(StandardCharsets.UTF_8).length;}
    /** Explicit maps avoid Java 21 reflective access to Instant/Optional and retain complete source attribution. */
    static Map<String,Object> card(Object entry) {
        var card=new LinkedHashMap<String,Object>();
        if(entry instanceof MemoryReadSession.Entry e) {
            card.put("messageId",e.messageId().toString());card.put("speaker",actor(e.speaker()));
            card.put("occurredAt",e.occurredAt().toString());card.put("text",e.text());card.put("excerpt",e.excerpt());
        } else if(entry instanceof SemanticReadRecords.Entry e) {
            card.put("messageId",e.messageId().toString());card.put("speaker",actor(e.speaker()));card.put("occurredAt",e.occurredAt().toString());
            card.put("text",e.text());card.put("similarity",e.similarity());card.put("coveredCharacters",e.coveredCharacters());
            card.put("totalCharacters",e.totalCharacters());card.put("excerpt",e.excerpt());
        } else if(entry instanceof InterpretationReadRecords.Entry e) {
            card.put("memoryId",e.memoryId().toString());card.put("authority",e.authority().name());card.put("layer",e.layer().name());
            card.put("kind",e.kind().name());card.put("extractorVersion",e.extractorVersion());
            card.put("quotes",e.quotes().stream().map(q->Map.of("sourceAlias",q.sourceAlias(),"messageId",q.messageId().toString(),
                    "actualActor",actor(q.actualActor()),"occurredAt",q.occurredAt().toString(),"text",q.text())).toList());
            card.put("links",e.links().stream().map(l->Map.of("newerAlias",l.newerAlias(),"olderAlias",l.olderAlias(),"relation",l.relation().name())).toList());
            card.put("inputs",e.inputs().stream().map(i->Map.of("sourceAlias",i.sourceAlias(),"messageId",i.messageId().toString(),
                    "coveredCharacters",i.coveredCharacters(),"totalCharacters",i.totalCharacters())).toList());
        } else if(entry instanceof ObservationReadRecords.Entry e) {
            card.put("source",source(e.source()));card.put("knowledgeReceiptId",e.knowledgeReceiptId().toString());
            card.put("observerGodId",e.observerGodId());card.put("subjectPlayerId",e.subjectPlayerId().toString());
            var v=e.experience();card.put("experience",Map.of("observationId",v.observationId().toString(),"eventId",v.eventId().toString(),
                    "sourceRevision",v.sourceRevision(),"acquisitionKind",v.acquisitionKind(),"actionType",v.actionType(),
                    "subjectType",v.subjectType(),"outcome",v.outcome(),"gameTime",v.gameTime()));
        } else if(entry instanceof RumorReadRecords.Entry e) {
            card.put("source",source(e.source()));card.put("knowledgeReceiptId",e.knowledgeReceiptId().toString());card.put("lineageId",e.lineageId().toString());
            card.put("recipientGodId",e.recipientGodId());card.put("subjectPlayerId",e.subjectPlayerId().toString());card.put("claim",e.claim());card.put("epithet",e.epithet());
            card.put("disclosureAudience",e.disclosureAudience().stream().map(UUID::toString).sorted().toList());card.put("reception",e.reception());
            var assessment=new LinkedHashMap<String,Object>();assessment.put("availability",e.assessment().availability().name());
            assessment.put("outcome",e.assessment().outcome().map(Enum::name).orElse(null));assessment.put("version",e.assessment().version());card.put("assessment",assessment);
        } else throw new IllegalArgumentException("UNSUPPORTED_RETRIEVAL_CARD");
        return Collections.unmodifiableMap(card);
    }
    private static Map<String,Object> actor(ActorRef actor){return Map.of("kind",actor.kind().name(),"id",actor.id());}
    private static Map<String,Object> source(SourceRef source){return Map.of("worldId",source.worldId().toString(),"datasetId",source.datasetId().toString(),
            "kind",source.kind().name(),"owner",source.owner(),"sourceId",source.sourceId(),"revision",source.revision(),"hash",source.hash());}
}
