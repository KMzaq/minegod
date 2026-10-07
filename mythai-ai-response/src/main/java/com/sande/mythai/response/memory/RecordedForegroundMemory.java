package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.recording.server.NativeRoomEvidence;
import com.sande.mythictrpg.recording.server.RecordedMemoryAccess;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.*;

/** Explicit native speech preparation. Not invoked by the production engine or SHADOW comparison; NEW stays blocked. */
public final class RecordedForegroundMemory {
    private RecordedForegroundMemory() { }

    /** Game-issued request -> scoped plan -> actual pages -> whole-page rendering -> durable seal -> owner preparation. */
    public static CompletableFuture<Optional<Prepared>> prepare(MinecraftServer server, Request request) {
        if (!live(server, request)) return CompletableFuture.completedFuture(Optional.empty());
        var result = new CompletableFuture<Optional<Prepared>>();
        try {
            var plan = RoomMemoryBridge.planRecall(server, request).flatMap(value -> RecordedRecallQuery.prepare(request, Optional.of(value)));
            var access = plan.isEmpty() ? Optional.<com.sande.mythictrpg.recording.api.MemoryReadSession>empty() : RecordedMemoryAccess.open(server, request);
            if (access.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
            // First bounded slice is lexical speech. Do not start embedding/model/Watch/Rumor work here.
            RecordedRetrievalCoordinator.collectNativeSpeech(access.orElseThrow(), request, plan.orElseThrow(), Optional.empty(),
                    new RecordedRetrievalCoordinator.Options(8, 32768, 4, 2, 15000, false, false), server::execute)
                    .whenComplete((collection, failure) -> resume(server, result, () -> {
                        if (failure != null || collection == null || !live(server, request) || !collection.current()) { result.complete(Optional.empty()); return; }
                        var selected = RecordedNativeSpeechPrompt.render(collection, new RecordedNativeSpeechPrompt.Budget(8, 32768));
                        result.whenComplete((value, failed) -> { if (failed != null || value == null || value.isEmpty()) selected.cancel(); });
                        selected.seal(RecordedRetrievalBundle.Scope.of(request)).whenComplete((sealed, sealFailure) -> resume(server, result, () -> {
                            if (sealFailure != null || sealed == null || sealed.isEmpty() || !live(server, request)) { result.complete(Optional.empty()); return; }
                            var payload = sealed.orElseThrow().payloadFor(RecordedRetrievalBundle.Scope.of(request));
                            if (payload.isEmpty()) { result.complete(Optional.empty()); return; }
                            var reference = payload.orElseThrow().evidence();
                            NativeRoomEvidence.prepare(server, request, List.of(reference)).whenComplete((ready, prepareFailure) -> resume(server, result, () -> {
                                if (prepareFailure != null || !Boolean.TRUE.equals(ready) || !live(server, request)
                                        || !sealed.orElseThrow().currentFor(RecordedRetrievalBundle.Scope.of(request))
                                        || !NativeRoomEvidence.current(server, request, reference)) { result.complete(Optional.empty()); return; }
                                result.complete(Optional.of(new Prepared(server, RecordedRetrievalBundle.Scope.of(request), sealed.orElseThrow(), reference)));
                            }));
                        }));
                    }));
        } catch (RuntimeException unavailable) { result.complete(Optional.empty()); }
        return result.completeOnTimeout(Optional.empty(), 30, TimeUnit.SECONDS);
    }

    public static final class Prepared {
        private final MinecraftServer owner;
        private final RecordedRetrievalBundle.Scope scope;
        private final RecordedNativeSpeechPrompt.Sealed sealed;
        private final RoomEvidenceReference reference;
        private Prepared(MinecraftServer owner, RecordedRetrievalBundle.Scope scope, RecordedNativeSpeechPrompt.Sealed sealed, RoomEvidenceReference reference) {
            this.owner = owner; this.scope = scope; this.sealed = sealed; this.reference = reference;
        }
        /** Obtain text and exact portable dependency together immediately before the model submission. */
        public Optional<RecordedNativeSpeechPrompt.Payload> payloadFor(MinecraftServer server, Request request) {
            if (server != owner || !live(server, request) || !scope.equals(RecordedRetrievalBundle.Scope.of(request))
                    || !NativeRoomEvidence.current(server, request, reference)) return Optional.empty();
            return sealed.payloadFor(scope);
        }
        /** Fresh bounded validation after slow generation, still on the SAME request; never extends the old read lease. */
        public CompletableFuture<Boolean> revalidateForPublication(MinecraftServer server, Request request) {
            if (server != owner || !live(server, request) || !scope.equals(RecordedRetrievalBundle.Scope.of(request)))
                return CompletableFuture.completedFuture(false);
            return NativeRoomEvidence.prepare(server, request, List.of(reference));
        }
        @Override public String toString() { return "PreparedNativeSpeech[opaque]"; }
    }
    /** Additive typed path. Keeps fallible interpretations and their speech in ONE game-issued proof.
     * No automatic fallback to speech-only, embedding/model invocation, legacy lookup or NEW activation. */
    public static CompletableFuture<Optional<PreparedInterpretations>> prepareInterpretations(MinecraftServer server,Request request){
        if(!live(server,request))return CompletableFuture.completedFuture(Optional.empty());
        var result=new CompletableFuture<Optional<PreparedInterpretations>>();
        try{
            var plan=RoomMemoryBridge.planRecall(server,request).flatMap(value->RecordedRecallQuery.prepare(request,Optional.of(value)));
            var access=plan.isEmpty()?Optional.<com.sande.mythictrpg.recording.api.MemoryReadSession>empty():RecordedMemoryAccess.open(server,request);
            if(access.isEmpty())return CompletableFuture.completedFuture(Optional.empty());
            RecordedRetrievalCoordinator.collectNativeInterpretations(access.orElseThrow(),request,plan.orElseThrow(),Optional.empty(),
                    new RecordedRetrievalCoordinator.Options(8,32768,4,2,15000,false,false),server::execute)
                    .whenComplete((collection,failure)->resume(server,result,()->{
                        if(failure!=null||collection==null||!live(server,request)||!collection.current()){result.complete(Optional.empty());return;}
                        var selected=RecordedNativeInterpretationPrompt.render(collection,new RecordedNativeInterpretationPrompt.Budget(8,32768));
                        result.whenComplete((value,failed)->{if(failed!=null||value==null||value.isEmpty())selected.cancel();});
                        selected.seal(RecordedRetrievalBundle.Scope.of(request)).whenComplete((sealed,sealFailure)->resume(server,result,()->{
                            if(sealFailure!=null||sealed==null||sealed.isEmpty()||!live(server,request)){result.complete(Optional.empty());return;}
                            var payload=sealed.orElseThrow().payloadFor(RecordedRetrievalBundle.Scope.of(request));
                            if(payload.isEmpty()){result.complete(Optional.empty());return;}
                            var reference=payload.orElseThrow().evidence();
                            NativeRoomEvidence.prepare(server,request,List.of(reference)).whenComplete((ready,prepareFailure)->resume(server,result,()->{
                                if(prepareFailure!=null||!Boolean.TRUE.equals(ready)||!live(server,request)
                                        ||!sealed.orElseThrow().currentFor(RecordedRetrievalBundle.Scope.of(request))
                                        ||!NativeRoomEvidence.current(server,request,reference)){result.complete(Optional.empty());return;}
                                result.complete(Optional.of(new PreparedInterpretations(server,RecordedRetrievalBundle.Scope.of(request),sealed.orElseThrow(),reference)));
                            }));
                        }));
                    }));
        }catch(RuntimeException unavailable){result.complete(Optional.empty());}
        return result.completeOnTimeout(Optional.empty(),30,TimeUnit.SECONDS);
    }
    public static final class PreparedInterpretations {
        private final MinecraftServer owner;
        private final RecordedRetrievalBundle.Scope scope;
        private final RecordedNativeInterpretationPrompt.Sealed sealed;
        private final RoomEvidenceReference reference;
        private PreparedInterpretations(MinecraftServer owner,RecordedRetrievalBundle.Scope scope,
                RecordedNativeInterpretationPrompt.Sealed sealed,RoomEvidenceReference reference){
            this.owner=owner;this.scope=scope;this.sealed=sealed;this.reference=reference;
        }
        public Optional<RecordedNativeInterpretationPrompt.Payload> payloadFor(MinecraftServer server,Request request){
            if(server!=owner||!live(server,request)||!scope.equals(RecordedRetrievalBundle.Scope.of(request))
                    ||!NativeRoomEvidence.current(server,request,reference))return Optional.empty();
            return sealed.payloadFor(scope);
        }
        /** Same immutable candidate versions and source closure; does not silently re-extract or replace them. */
        public CompletableFuture<Boolean> revalidateForPublication(MinecraftServer server,Request request){
            if(server!=owner||!live(server,request)||!scope.equals(RecordedRetrievalBundle.Scope.of(request)))
                return CompletableFuture.completedFuture(false);
            return NativeRoomEvidence.prepare(server,request,List.of(reference));
        }
        @Override public String toString(){return "PreparedNativeInterpretations[opaque]";}
    }
    private static boolean live(MinecraftServer server, Request request) {
        return server != null && server.isSameThread() && ConversationRooms.INSTANCE.memoryReadCurrent(server, request);
    }
    private static <T> void resume(MinecraftServer server, CompletableFuture<Optional<T>> result, Runnable action) {
        try { server.execute(() -> {
            if (result.isDone()) return;
            try { action.run(); } catch (RuntimeException unavailable) { result.complete(Optional.empty()); }
        }); } catch (RuntimeException stopped) { result.complete(Optional.empty()); }
    }
}
