package com.sande.mythai.response.memory;

import java.util.*;
import java.util.concurrent.*;

/** Rank fusion uses only authorized current raw sources. Any semantic failure retains the lexical result. */
public final class HybridRetrieval {
    public record Result(RecallSearch.Result recall,String semanticState,int candidates) {}
    private HybridRetrieval() {}
    public static Result search(MemoryJournal.ReadView view,RecallSearch.Result base,RecallSettings settings,
                                SemanticIndex index,SemanticIndex.Provider provider,long now,long budgetNanos) {
        return search(view,base,settings,index,provider,now,budgetNanos,0.75);
    }
    public static Result search(MemoryJournal.ReadView view,RecallSearch.Result base,RecallSettings settings,
                                SemanticIndex index,SemanticIndex.Provider provider,long now,long budgetNanos,double minimumSimilarity) {
        if(!Double.isFinite(minimumSimilarity)||minimumSimilarity<0.5||minimumSimilarity>0.95)throw new IllegalArgumentException("semantic threshold");
        if(provider==null||!base.query().explicit())return new Result(base,"DISABLED_OR_NO_PROVIDER",0);
        if(!view.ready()||!view.key().equals(base.query().scope().key())||!view.audience().equals(base.query().scope().audience()))
            return new Result(base,"UNAVAILABLE_SCOPE",0);
        long start=System.nanoTime();
        try {
            var rows=index.allowed(view).stream().filter(r->r.vector().modelFingerprint().equals(provider.fingerprint())).toList();
            if(rows.isEmpty())return new Result(base,"PENDING_INDEX",0);
            long remaining=budgetNanos-(System.nanoTime()-start);if(remaining<=0)return new Result(base,"TIMEOUT",0);
            var future=provider.embed(base.query().text());SemanticIndex.Vector query;
            try{query=future.get(remaining,TimeUnit.NANOSECONDS);}catch(TimeoutException timeout){future.cancel(false);return new Result(base,"TIMEOUT",0);}
            if(!provider.fingerprint().equals(query.modelFingerprint()))return new Result(base,"MODEL_MISMATCH",0);
            record Hit(MemoryJournal.Entry entry,double score) {}
            Map<UUID,MemoryJournal.Entry> sources=new HashMap<>();view.entries().forEach(e->sources.put(e.id(),e));
            Map<UUID,Hit> hits=new HashMap<>();
            for(var row:rows) {
                if(System.nanoTime()-start>budgetNanos)return new Result(base,"TIMEOUT",0);
                var e=sources.get(row.note().sourceId());if(!RecallSearch.semanticEligible(e,base.query(),settings,now))continue;
                double similarity=query.cosine(row.vector());if(!Double.isFinite(similarity)||similarity<minimumSimilarity)continue;
                hits.merge(e.id(),new Hit(e,similarity),(a,b)->a.score()>=b.score()?a:b);
            }
            var semantic=hits.values().stream().sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(h->h.entry().id())).limit(12).toList();
            if(semantic.isEmpty())return new Result(base,"NO_MATCH",0);
            Map<UUID,Double> scores=new HashMap<>();Map<UUID,String> reasons=new HashMap<>();
            for(int i=0;i<base.selected().size();i++) {
                var e=base.selected().get(i);String reason=base.reasons().getOrDefault(e.id(),"");
                if(reason.equals("recent_raw_uncertain"))continue;
                scores.merge(e.id(),1.0/(60+i+1),Double::sum);reasons.put(e.id(),reason);
            }
            for(int i=0;i<semantic.size();i++){var e=semantic.get(i).entry();scores.merge(e.id(),1.0/(60+i+1),Double::sum);reasons.merge(e.id(),"semantic",(a,b)->a+"+"+b);}
            var unique=new LinkedHashMap<String,MemoryJournal.Entry>();
            scores.entrySet().stream().sorted(Map.Entry.<UUID,Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                    .map(e->sources.get(e.getKey())).filter(Objects::nonNull)
                    .forEach(e->unique.putIfAbsent(MemoryJournal.retrievalIdentity(e),e));
            var selected=unique.values().stream().limit(3).sorted(Comparator.comparingLong(MemoryJournal.Entry::occurredAt)).toList();
            var retained=new HashSet<UUID>();selected.forEach(e->retained.add(e.id()));reasons.keySet().retainAll(retained);
            // Similarity alone cannot establish truth, negation, correction, completion, or an unambiguous answer.
            var pending=new HashSet<>(view.pending());pending.retainAll(retained);
            return new Result(new RecallSearch.Result(base.query(),RecallSearch.Status.AMBIGUOUS,selected,reasons,pending,
                    base.elapsedNanos()+System.nanoTime()-start,"hybrid_raw_evidence"),"READY",semantic.size());
        }catch(InterruptedException e){Thread.currentThread().interrupt();return new Result(base,"INTERRUPTED",0);}
        catch(Exception failure){return new Result(base,"UNAVAILABLE",0);}
    }
}
