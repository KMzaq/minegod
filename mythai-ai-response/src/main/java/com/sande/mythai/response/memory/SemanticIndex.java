package com.sande.mythai.response.memory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Model-independent prepared vectors. No installed backend, downloading, global top-k, or game authority. */
public final class SemanticIndex {
    public record Vector(String modelFingerprint,float[] values) {
        public Vector {
            Objects.requireNonNull(modelFingerprint);values=values.clone();
            if(modelFingerprint.isBlank()||modelFingerprint.length()>160||values.length<1||values.length>4096)throw new IllegalArgumentException("vector identity/dimension");
            double norm=0;for(float v:values){if(!Float.isFinite(v))throw new IllegalArgumentException("nonfinite vector");norm+=(double)v*v;}
            if(norm<=0)throw new IllegalArgumentException("zero vector");
        }
        @Override public float[] values(){return values.clone();}
        public double cosine(Vector other) {
            if(!modelFingerprint.equals(other.modelFingerprint)||values.length!=other.values.length)return Double.NaN;
            double dot=0,a=0,b=0;for(int i=0;i<values.length;i++){dot+=(double)values[i]*other.values[i];a+=(double)values[i]*values[i];b+=(double)other.values[i]*other.values[i];}
            return dot/Math.sqrt(a*b);
        }
    }
    public interface Provider {
        String fingerprint();
        /** Must return promptly; completion may be asynchronous. Runtime providers require separate model approval. */
        CompletableFuture<Vector> embed(String query);
    }
    public record Row(DerivedMemory.Note note,Vector vector) {}
    private final List<Row> rows;
    public SemanticIndex(List<Row> rows) { if(rows.size()>50000)throw new IllegalArgumentException("index capacity");this.rows=List.copyOf(rows); }
    public static final SemanticIndex EMPTY=new SemanticIndex(List.of());
    public List<Row> allowed(MemoryJournal.ReadView raw) {
        if(!raw.ready()||raw.failed()||raw.audience().isEmpty()||!raw.audience().contains(raw.key().player()))return List.of();
        Map<UUID,MemoryJournal.Entry> sources=new HashMap<>();raw.entries().forEach(e->sources.put(e.id(),e));
        return rows.stream().filter(r->r.note.key().equals(raw.key())&&!raw.pending().contains(r.note.sourceId())
                &&DerivedMemory.valid(r.note,sources.get(r.note.sourceId()),raw.audience())).toList();
    }
}
