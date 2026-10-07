package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.recording.api.MemoryReadSession;
import com.sande.mythictrpg.recording.server.RecordedMemoryAccess;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** One ephemeral comparison, not a foreground prompt, durable evidence, or an action producer. */
public final class RecordedRetrievalShadow {
    record Selection(Set<UUID> messages, Set<UUID> observations, Set<UUID> rumors) {
        Selection { messages=Set.copyOf(messages);observations=Set.copyOf(observations);rumors=Set.copyOf(rumors); }
    }
    private static final AtomicLong completed=new AtomicLong(),unavailable=new AtomicLong(),calls=new AtomicLong();
    private static final AtomicLong rawMatches=new AtomicLong(),semanticMatches=new AtomicLong(),observationMatches=new AtomicLong(),rumorMatches=new AtomicLong();
    private static final AtomicLong partialLanes=new AtomicLong(),unavailableLanes=new AtomicLong(),renderedGroups=new AtomicLong();
    private RecordedRetrievalShadow() { }

    public static void compare(MinecraftServer server,Request request,RoomMemoryBridge.Recall recall,
            Set<UUID> selectedObservations,Set<UUID> selectedRumors) {
        if(!server.isSameThread())return;
        boolean external=!request.publicRoom()&&request.godIds().size()==1;
        var options=new RecordedRetrievalCoordinator.Options(8,32768,4,2,15000,external,
                external&&MemoryFoundationSettings.mode()==MemoryFoundationSettings.Mode.RUMOR_TEST);
        compare(()->RecordedMemoryAccess.open(server,request),request,recall.query(),
                new Selection(recall.sourceMessageIds(),selectedObservations,selectedRumors),
                plan->RecordedEmbeddingService.queryVector(server,plan),options,server::execute);
    }

    /** Optional boundary is injectable; it never constructs a game read capability. */
    static void compare(Supplier<Optional<MemoryReadSession>> opening,Request request,Optional<RecallQuery> carried,
            Selection selected,Function<RecordedRecallQuery.Prepared,CompletableFuture<Optional<RecordedRetrievalCoordinator.SemanticQuery>>> vector,
            RecordedRetrievalCoordinator.Options options,Consumer<Runnable> dispatch) {
        try {
            var plan=RecordedRecallQuery.prepare(request,carried);
            if(plan.isEmpty())return;
            var issued=opening.get();if(issued.isEmpty())return;
            CompletableFuture<Optional<RecordedRetrievalCoordinator.SemanticQuery>> embedding;
            try { embedding=plan.get().semanticEligible()?Objects.requireNonNull(vector.apply(plan.get())):
                    CompletableFuture.completedFuture(Optional.empty()); }
            catch(RuntimeException failed){embedding=CompletableFuture.completedFuture(Optional.empty());}
            // Does not delay legacy generation. A timed-out optional backend cannot open a second session later.
            embedding.completeOnTimeout(Optional.empty(),5,TimeUnit.SECONDS).whenComplete((value,failure)->{
                try { dispatch.accept(()->{
                    try {
                        var optional=failure==null&&value!=null?value:Optional.<RecordedRetrievalCoordinator.SemanticQuery>empty();
                        RecordedRetrievalCoordinator.collect(issued.get(),request,plan.get(),optional,options,dispatch)
                                .whenComplete((bundle,readFailure)->{
                                    try { dispatch.accept(()->{
                                        if(readFailure!=null||bundle==null){unavailable.incrementAndGet();return;}
                                        observe(bundle,RecordedRetrievalBundle.Scope.of(request),selected);
                                    }); }catch(RuntimeException failed){unavailable.incrementAndGet();}
                                });
                    }catch(RuntimeException failed){unavailable.incrementAndGet();}
                }); }catch(RuntimeException failed){unavailable.incrementAndGet();}
            });
        }catch(RuntimeException failed){unavailable.incrementAndGet();}
    }

    static void observe(RecordedRetrievalBundle bundle,RecordedRetrievalBundle.Scope expected,Selection selected){
        try {
            if(!expected.equals(bundle.scope())||!bundle.current()){unavailable.incrementAndGet();return;}
            var lanes=List.of(bundle.raw(),bundle.semantic(),bundle.interpretations(),bundle.observations(),bundle.rumors());
            if(lanes.stream().anyMatch(l->l.status()==MemoryReadSession.Status.STALE)
                    ||lanes.stream().noneMatch(l->Set.of(MemoryReadSession.Status.FOUND,MemoryReadSession.Status.EMPTY,
                    MemoryReadSession.Status.PARTIAL).contains(l.status()))){unavailable.incrementAndGet();return;}
            long raw=count(bundle.raw().entries().stream().map(MemoryReadSession.Entry::messageId).toList(),selected.messages());
            long semantic=count(bundle.semantic().entries().stream().map(e->e.messageId()).toList(),selected.messages());
            long observations=count(bundle.observations().entries().stream().map(e->e.experience().observationId()).toList(),selected.observations());
            long rumors=count(bundle.rumors().entries().stream().map(e->UUID.fromString(e.source().sourceId())).toList(),selected.rumors());
            var rendered=RecordedMemoryPrompt.render(bundle,new RecordedMemoryPrompt.Budget(16,32768));
            // Validate the renderer without sending, persisting or logging its private content.
            if(rendered.contentFor(expected).isEmpty()||!bundle.current()){unavailable.incrementAndGet();return;}
            completed.incrementAndGet();calls.addAndGet(bundle.calls());rawMatches.addAndGet(raw);semanticMatches.addAndGet(semantic);
            observationMatches.addAndGet(observations);rumorMatches.addAndGet(rumors);renderedGroups.addAndGet(rendered.groups());
            partialLanes.addAndGet(lanes.stream().filter(l->l.status()==MemoryReadSession.Status.PARTIAL).count());
            unavailableLanes.addAndGet(lanes.stream().filter(l->l.status()==MemoryReadSession.Status.UNAVAILABLE).count());
        }catch(RuntimeException failed){unavailable.incrementAndGet();}
    }
    private static long count(List<UUID> found,Set<UUID> selected){return found.stream().distinct().filter(selected::contains).count();}
    /** Counts only. No private content, root/actor/God IDs, assessment, or individual-room diagnostics. */
    public static Map<String,Long> diagnostics(){return Map.of("boundedCompleted",completed.get(),"unavailable",unavailable.get(),
            "readCalls",calls.get(),"rawMatches",rawMatches.get(),"semanticMatches",semanticMatches.get(),
            "observationMatches",observationMatches.get(),"rumorMatches",rumorMatches.get(),
            "partialLanes",partialLanes.get(),"unavailableLanes",unavailableLanes.get(),"renderedGroups",renderedGroups.get());}
}
