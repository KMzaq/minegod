package com.sande.mythictrpg.recording.api;

import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.util.*;

/** Vectors are non-authoritative indexes of explicitly covered native RAW text. */
public final class EmbeddingRecords {
    private EmbeddingRecords() { }
    public static final String ENCODER_VERSION = "native-prefix-1600-v1";
    public enum Status { CLAIMED, EMPTY, STORED, DUPLICATE, STALE, REJECTED, FULL, UNAVAILABLE, DEFERRED }
    public enum WorkOutcome { DEFERRED, FAILED }
    public record ModelSpace(String modelName,String modelDigest,int dimensions,String encoderVersion) {
        public ModelSpace {
            if(modelName==null||!modelName.matches("[a-zA-Z0-9_.:/-]{1,160}")||modelDigest==null||!modelDigest.matches("[a-f0-9]{64}")
                    ||dimensions<1||dimensions>4096||!ENCODER_VERSION.equals(encoderVersion))throw new IllegalArgumentException("EMBEDDING_MODEL_SPACE");
        }
        public String fingerprint(){return RecordingRecords.sha256(modelName+"\n"+modelDigest+"\n"+dimensions+"\n"+encoderVersion);}
    }
    public record Work(EmbeddingWorkToken token,ModelSpace modelSpace,UUID messageId,SourceRef source,
            UUID knowledgeReceiptId,String receiptHash,ActorRef actualActor,String observerGodId,Set<ActorRef> audience,
            String disclosureHash,String text,String inputHash,int coveredCharacters,int totalCharacters,long leaseDeadlineEpochMillis) {
        public Work {
            Objects.requireNonNull(token);Objects.requireNonNull(modelSpace);Objects.requireNonNull(messageId);Objects.requireNonNull(source);
            Objects.requireNonNull(knowledgeReceiptId);hash(receiptHash);Objects.requireNonNull(actualActor);new ActorRef(ActorKind.GOD,observerGodId);
            audience=Set.copyOf(audience);hash(disclosureHash);hash(inputHash);Objects.requireNonNull(text);
            if(text.isBlank()||text.length()>1600||text.length()!=coveredCharacters||totalCharacters<coveredCharacters
                    ||!inputHash.equals(RecordingRecords.sha256(text))||audience.isEmpty()||audience.size()>256
                    ||!audience.contains(new ActorRef(ActorKind.GOD,observerGodId))||leaseDeadlineEpochMillis<=0)
                throw new IllegalArgumentException("EMBEDDING_WORK");
        }
        public boolean excerpt(){return coveredCharacters<totalCharacters;}
    }
    public record QueryVector(ModelSpace modelSpace,String inputHash,float[] values) {
        public QueryVector {Objects.requireNonNull(modelSpace);hash(inputHash);values=vector(values,modelSpace.dimensions());}
        @Override public float[] values(){return values.clone();}
    }
    public record ClaimResult(Status status,Optional<Work> work,String reasonCode) {
        public ClaimResult {Objects.requireNonNull(status);Objects.requireNonNull(work);ProjectionRecords.reason(reasonCode);
            if((status==Status.CLAIMED)!=work.isPresent())throw new IllegalArgumentException("EMBEDDING_CLAIM");}
    }
    public record Result(Status status,String reasonCode) {
        public Result {Objects.requireNonNull(status);ProjectionRecords.reason(reasonCode);}
    }
    public static float[] vector(float[] values,int dimensions) {
        Objects.requireNonNull(values);if(values.length!=dimensions)throw new IllegalArgumentException("EMBEDDING_DIMENSIONS");
        float[] copy=values.clone();double norm=0;for(float value:copy){if(!Float.isFinite(value))throw new IllegalArgumentException("EMBEDDING_NONFINITE");norm+=(double)value*value;}
        if(norm<=0)throw new IllegalArgumentException("EMBEDDING_ZERO_VECTOR");return copy;
    }
    public static String vectorHash(byte[] bytes){
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static String prefix(String raw){
        Objects.requireNonNull(raw);int end=Math.min(1600,raw.length());
        if(end>0&&end<raw.length()&&Character.isHighSurrogate(raw.charAt(end-1))&&Character.isLowSurrogate(raw.charAt(end)))end--;
        return raw.substring(0,end);
    }
    private static void hash(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("EMBEDDING_HASH");}
}
