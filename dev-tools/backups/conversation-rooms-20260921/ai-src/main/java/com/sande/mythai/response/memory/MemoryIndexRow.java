package com.sande.mythai.response.memory;

import java.util.*;

/** Persistent evidence-bound metadata. Model labels/relations are candidates, never authoritative replacements. */
public record MemoryIndexRow(int version,MemoryJournal.Entry source,String sourceHash,String fingerprint,
        float[] vector,String extractor,String kind,List<Link> links) {
    public record Link(UUID older,String olderHash,String relation) {
        public Link { Objects.requireNonNull(older);if(olderHash==null||!olderHash.matches("[a-f0-9]{64}")
                ||!Set.of("CORRECTS","CONTRADICTS","CANCELS","ALSO_PLANNED","REPORTS_FULFILLMENT").contains(relation))throw new IllegalArgumentException("link"); }
    }
    public MemoryIndexRow {
        Objects.requireNonNull(source);links=List.copyOf(links);vector=vector==null?new float[0]:vector.clone();
        if(version!=2||source.source()!=MemoryJournal.Source.PLAYER_STATEMENT||!DerivedMemory.fingerprint(source).equals(sourceHash)
                ||fingerprint==null||extractor==null||extractor.length()>100||!Set.of("UNPROCESSED","SELF_CLAIM","PLAN_OR_PROMISE","REPORTED","CONDITIONAL","JOKE","OTHER").contains(kind)
                ||links.size()>2||vector.length>4096)throw new IllegalArgumentException("index evidence");
        if(vector.length>0)new SemanticIndex.Vector(fingerprint,vector);
        if(links.stream().anyMatch(l->l.older().equals(source.id())))throw new IllegalArgumentException("self correction");
    }
    @Override public float[] vector(){return vector.clone();}
    public String identity(){return source.id()+"/"+sourceHash+"/"+fingerprint+"/"+extractor;}
    public boolean current(MemoryJournal.ReadView raw) {
        return raw.ready()&&!raw.failed()&&source.key().equals(raw.key())&&raw.audience().contains(raw.key().player())
                &&source.audience().containsAll(raw.audience())&&source.godAudience().containsAll(raw.godAudience())&&!raw.pending().contains(source.id())
                &&raw.entries().contains(source);
    }
    public boolean linkCurrent(Link link,MemoryJournal.ReadView raw) {
        return raw.entries().stream().anyMatch(e->e.id().equals(link.older())&&!raw.pending().contains(e.id())
                &&e.key().equals(source.key())&&e.audience().equals(source.audience())&&e.godAudience().equals(source.godAudience())&&e.source()==MemoryJournal.Source.PLAYER_STATEMENT
                &&e.occurredAt()<=source.occurredAt()&&DerivedMemory.fingerprint(e).equals(link.olderHash()));
    }
}
