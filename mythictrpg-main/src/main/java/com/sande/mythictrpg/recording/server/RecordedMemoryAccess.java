package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.recording.api.MemoryReadSession;
import com.sande.mythictrpg.recording.api.InterpretationReadRecords;
import com.sande.mythictrpg.recording.api.SemanticReadRecords;
import com.sande.mythictrpg.recording.api.ObservationReadRecords;
import com.sande.mythictrpg.recording.api.RumorReadRecords;
import com.sande.mythictrpg.recording.api.EmbeddingRecords;
import com.sande.mythictrpg.recording.api.NativeMemoryEvidence;
import com.sande.mythictrpg.recording.api.NativeMemorySeal;
import com.sande.mythictrpg.recording.api.NativeInterpretationEvidence;
import com.sande.mythictrpg.recording.api.NativeInterpretationSeal;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import com.sande.mythictrpg.rumor.NativeRumorReadAccess;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Only this game issuer binds a read session to a live, game-issued room request. SHADOW never changes dialogue. */
public final class RecordedMemoryAccess {
    private RecordedMemoryAccess() { }
    public static Optional<MemoryReadSession> open(MinecraftServer server, Request request) {
        if (!server.isSameThread()) throw new IllegalStateException("Memory authority requires game thread");
        var mode = MemoryFoundationSettings.mode();
        if (mode == MemoryFoundationSettings.Mode.OFF || !ConversationRooms.INSTANCE.memoryReadCurrent(server, request)) return Optional.empty();
        var store = RecordingRuntime.current(server).orElse(null);
        var dataset = readableDataset(store);
        if (dataset.isEmpty()) return Optional.empty();
        var player = server.getPlayerList().getPlayer(request.playerId());
        var room = ConversationRooms.INSTANCE.memberships(player).stream().filter(r -> r.roomId().equals(request.roomId())).findFirst().orElseThrow();
        var audience = new HashSet<ActorRef>();
        request.audiencePlayerIds().forEach(p -> audience.add(new ActorRef(ActorKind.PLAYER, p.toString())));
        request.godIds().forEach(g -> audience.add(new ActorRef(ActorKind.GOD, g.toString())));
        var scope = new RecordedRoomSearch.Scope(dataset.orElseThrow(), request.speakerGodId().toString(), audience,
                request.publicRoom(), room.recordingScope().name(), mode.name());
        var engine = RoomConversationEngineRouter.INSTANCE.engine();
        return Optional.of(new Session(store, scope, server::isSameThread, task -> server.execute(task),
                () -> MemoryFoundationSettings.mode() == mode && RoomConversationEngineRouter.INSTANCE.engine() == engine
                        && RecordingRuntime.current(server).filter(s -> s == store).isPresent()
                        && ConversationRooms.INSTANCE.memoryReadCurrent(server, request),
                refs -> engine.prepareRecordedEvidence(request, refs), refs -> engine.recordedEvidenceCurrent(request, refs),
                () -> RecordingRuntime.embeddingEnabled(server), root -> NativeRumorReadAccess.read(server, request, root),request));
    }
    /** Initialization can return an unavailable service without a dataset; optional reads must remain absent. */
    static Optional<UUID> readableDataset(WorldRecordingService store) {
        if (store == null || !store.shadowReadsEnabled()) return Optional.empty();
        var health = store.health();
        return Set.of(WorldRecordingService.State.READY, WorldRecordingService.State.FULL).contains(health.state())
                ? health.datasetId() : Optional.empty();
    }

    /** Package-private injectable boundaries allow real-store tests without minting public authority. */
    static final class Session implements MemoryReadSession {
        private record Position(Query query, RecordedRoomSearch.SearchPosition search) { }
        /** Created only after a public overload validates an actually issued page from this session. */
        private record IssuedSeeds(Object identity,List<UUID> messageIds,BooleanSupplier current) {
            IssuedSeeds { messageIds=List.copyOf(messageIds); }
        }
        private record InterpretationPosition(IssuedSeeds seeds, RecordedInterpretationSearch.Position search) { }
        private record SemanticPosition(Query query, String vectorFingerprint, RecordedSemanticSearch.Position search) { }
        private record ObservationPosition(Query query, RecordedObservationSearch.Position search) { }
        private record RumorPosition(Query query, RecordedRumorSearch.Position search) { }
        private final WorldRecordingService store;
        private final RecordedRoomSearch.Scope scope;
        private final BooleanSupplier gameThread, current;
        private final Consumer<Runnable> dispatch;
        private final Function<List<RoomEvidenceReference>, CompletableFuture<Boolean>> prepare;
        private final Predicate<List<RoomEvidenceReference>> evidenceCurrent;
        private final BooleanSupplier semanticEnabled;
        private final boolean semanticInitiallyEnabled;
        private final Function<UUID,Optional<NativeRumorReadAccess.Snapshot>> rumorRead;
        private final Request issuedRequest;
        private final Map<NativeMemorySeal,BooleanSupplier> seals=new IdentityHashMap<>();
        private final Map<NativeMemoryEvidence.Reference,BooleanSupplier> nativeReferences=new HashMap<>();
        private final Map<NativeInterpretationSeal,BooleanSupplier> interpretationSeals=new IdentityHashMap<>();
        private final Map<NativeInterpretationEvidence.Reference,BooleanSupplier> interpretationReferences=new HashMap<>();
        private boolean sealAttempted;
        private int nativePrepareCalls;
        private final Map<Cursor,Position> cursors = new IdentityHashMap<>();
        private final Map<Page,List<List<RoomEvidenceReference>>> pages = new IdentityHashMap<>();
        private final Set<Page> projectionDependentPages=Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<InterpretationReadRecords.Cursor,InterpretationPosition> interpretationCursors = new IdentityHashMap<>();
        private final Map<InterpretationReadRecords.Page,IssuedSeeds> interpretationPages = new IdentityHashMap<>();
        private final Map<SemanticReadRecords.Cursor,SemanticPosition> semanticCursors = new IdentityHashMap<>();
        private final Set<SemanticReadRecords.Page> semanticPages = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<ObservationReadRecords.Cursor,ObservationPosition> observationCursors=new IdentityHashMap<>();
        private final Map<ObservationReadRecords.Page,List<List<RoomEvidenceReference>>> observationPages=new IdentityHashMap<>();
        private final Map<RumorReadRecords.Cursor,RumorPosition> rumorCursors=new IdentityHashMap<>();
        private final Map<RumorReadRecords.Page,List<NativeRumorReadAccess.Snapshot>> rumorPages=new IdentityHashMap<>();
        private final long watermark, authorityGeneration, projectionGeneration, deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        private CompletableFuture<?> active;
        private int calls;
        Session(WorldRecordingService store, RecordedRoomSearch.Scope scope, BooleanSupplier gameThread, Consumer<Runnable> dispatch,
                BooleanSupplier current, Function<List<RoomEvidenceReference>,CompletableFuture<Boolean>> prepare,
                Predicate<List<RoomEvidenceReference>> evidenceCurrent) {
            this(store,scope,gameThread,dispatch,current,prepare,evidenceCurrent,()->true);
        }
        Session(WorldRecordingService store, RecordedRoomSearch.Scope scope, BooleanSupplier gameThread, Consumer<Runnable> dispatch,
                BooleanSupplier current, Function<List<RoomEvidenceReference>,CompletableFuture<Boolean>> prepare,
                Predicate<List<RoomEvidenceReference>> evidenceCurrent, BooleanSupplier semanticEnabled) {
            this(store,scope,gameThread,dispatch,current,prepare,evidenceCurrent,semanticEnabled,root -> Optional.empty());
        }
        Session(WorldRecordingService store, RecordedRoomSearch.Scope scope, BooleanSupplier gameThread, Consumer<Runnable> dispatch,
                BooleanSupplier current, Function<List<RoomEvidenceReference>,CompletableFuture<Boolean>> prepare,
                Predicate<List<RoomEvidenceReference>> evidenceCurrent, BooleanSupplier semanticEnabled,
                Function<UUID,Optional<NativeRumorReadAccess.Snapshot>> rumorRead) {
            this(store,scope,gameThread,dispatch,current,prepare,evidenceCurrent,semanticEnabled,rumorRead,null);
        }
        Session(WorldRecordingService store, RecordedRoomSearch.Scope scope, BooleanSupplier gameThread, Consumer<Runnable> dispatch,
                BooleanSupplier current, Function<List<RoomEvidenceReference>,CompletableFuture<Boolean>> prepare,
                Predicate<List<RoomEvidenceReference>> evidenceCurrent, BooleanSupplier semanticEnabled,
                Function<UUID,Optional<NativeRumorReadAccess.Snapshot>> rumorRead,Request issuedRequest) {
            this.store = store; this.scope = scope; this.gameThread = gameThread; this.dispatch = dispatch;
            this.current = current; this.prepare = prepare; this.evidenceCurrent = evidenceCurrent; watermark = store.health().highWatermark();
            authorityGeneration = store.authorityGeneration();
            projectionGeneration = store.projectionGeneration();
            this.semanticEnabled=semanticEnabled;semanticInitiallyEnabled=semanticEnabled.getAsBoolean();
            this.rumorRead=Objects.requireNonNull(rumorRead);
            this.issuedRequest=issuedRequest;
        }
        boolean issuedFor(Request request) {
            if(issuedRequest==null||request==null||!valid())return false;
            return issuedRequest.roomId().equals(request.roomId())&&issuedRequest.revision()==request.revision()
                    &&issuedRequest.turnId().equals(request.turnId())&&issuedRequest.playerId().equals(request.playerId())
                    &&issuedRequest.speakerGodId().equals(request.speakerGodId())&&issuedRequest.publicRoom()==request.publicRoom()
                    &&Set.copyOf(issuedRequest.godIds()).equals(Set.copyOf(request.godIds()))
                    &&Set.copyOf(issuedRequest.audiencePlayerIds()).equals(Set.copyOf(request.audiencePlayerIds()));
        }
        @Override public CompletableFuture<Optional<NativeMemorySeal>> seal(List<Page> raw,List<SemanticReadRecords.Page> semantic) {
            if(!issuedFor(issuedRequest)||sealAttempted||active!=null&&!active.isDone())return CompletableFuture.completedFuture(Optional.empty());
            sealAttempted=true; // Separate single bounded issuance, not the 8 diagnostic read calls.
            final List<Page> rawPages;final List<SemanticReadRecords.Page> vectorPages;
            var identities=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());var roots=new HashSet<UUID>();
            try {
                rawPages=List.copyOf(raw);vectorPages=List.copyOf(semantic);
                if(rawPages.size()+vectorPages.size()>NativeMemoryEvidence.MAX_PAGES)return CompletableFuture.completedFuture(Optional.empty());
                for(var page:rawPages){if(!identities.add(page)||!current(page))return CompletableFuture.completedFuture(Optional.empty());
                    page.entries().forEach(e->roots.add(e.messageId()));}
                for(var page:vectorPages){if(!identities.add(page)||!current(page))return CompletableFuture.completedFuture(Optional.empty());
                    page.entries().forEach(e->roots.add(e.messageId()));}
                if(roots.isEmpty()||roots.size()>NativeMemoryEvidence.MAX_ROOTS)return CompletableFuture.completedFuture(Optional.empty());
            }catch(RuntimeException invalid){return CompletableFuture.completedFuture(Optional.empty());}
            BooleanSupplier pagesCurrent=()->rawPages.stream().allMatch(this::current)&&vectorPages.stream().allMatch(this::current);
            var result=new CompletableFuture<Optional<NativeMemorySeal>>();active=result;
            try {store.issueNativeEvidence(scope,issuedRequest,watermark,Set.copyOf(roots)).whenComplete((saved,failure)->nativeResume(result,()->{
                        if(failure!=null||saved==null||saved.isEmpty()||!issuedFor(issuedRequest)||!pagesCurrent.getAsBoolean()) {result.complete(Optional.empty());return;}
                        var issued=saved.orElseThrow();var seal=issued.seal();var owners=issued.ownerReferences();
                        BooleanSupplier stillCurrent=()->issuedFor(issuedRequest)&&pagesCurrent.getAsBoolean()
                                &&(!issued.requiresProjection()||interpretationsValid());
                        prepareOwners(result,owners,stillCurrent,()->{
                        // A timeout/cancel racing registration leaves a dormant identity, never authority.
                        seals.put(seal,()->!result.isCompletedExceptionally()&&result.getNow(Optional.empty()).filter(v->v==seal).isPresent()
                                &&stillCurrent.getAsBoolean()&&proofCurrent(owners));
                        result.complete(Optional.of(seal));
                        },Optional.empty());
            },Optional.empty()));}catch(RuntimeException unavailable){result.complete(Optional.empty());}
            return result.completeOnTimeout(Optional.empty(),2,TimeUnit.SECONDS);
        }
        @Override public boolean current(NativeMemorySeal seal) {
            if(!issuedFor(issuedRequest))return false;
            var guard=seals.get(seal);try{return guard!=null&&guard.getAsBoolean();}catch(RuntimeException unavailable){return false;}
        }
        @Override public CompletableFuture<Optional<NativeInterpretationSeal>> sealInterpretations(List<Page> raw,
                List<SemanticReadRecords.Page> semantic,List<InterpretationReadRecords.Page> interpretations) {
            if(!issuedFor(issuedRequest)||!interpretationsValid()||sealAttempted||active!=null&&!active.isDone())
                return CompletableFuture.completedFuture(Optional.empty());
            sealAttempted=true; // One total RAW-or-typed issuance attempt per issued request.
            final List<Page> rawPages;final List<SemanticReadRecords.Page> vectorPages;
            final List<InterpretationReadRecords.Page> candidatePages;
            var identities=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var roots=new HashSet<UUID>();var allInputs=new HashSet<UUID>();
            var entries=new LinkedHashMap<UUID,InterpretationReadRecords.Entry>();
            try {
                rawPages=List.copyOf(raw);vectorPages=List.copyOf(semantic);candidatePages=List.copyOf(interpretations);
                if(rawPages.size()+vectorPages.size()+candidatePages.size()>NativeInterpretationEvidence.MAX_PAGES)
                    return CompletableFuture.completedFuture(Optional.empty());
                for(var page:rawPages){if(!identities.add(page)||!current(page))return CompletableFuture.completedFuture(Optional.empty());
                    page.entries().forEach(e->roots.add(e.messageId()));}
                for(var page:vectorPages){if(!identities.add(page)||!current(page))return CompletableFuture.completedFuture(Optional.empty());
                    page.entries().forEach(e->roots.add(e.messageId()));}
                for(var page:candidatePages){if(!identities.add(page)||!current(page))return CompletableFuture.completedFuture(Optional.empty());
                    for(var entry:page.entries()){
                        var previous=entries.putIfAbsent(entry.memoryId(),entry);
                        if(previous!=null&&!previous.equals(entry))return CompletableFuture.completedFuture(Optional.empty());
                        entry.inputs().forEach(input->allInputs.add(input.messageId()));
                    }}
                allInputs.addAll(roots);
                if(entries.isEmpty()||entries.size()>NativeInterpretationEvidence.MAX_CANDIDATES
                        ||allInputs.size()>NativeMemoryEvidence.MAX_ROOTS)return CompletableFuture.completedFuture(Optional.empty());
            }catch(RuntimeException invalid){return CompletableFuture.completedFuture(Optional.empty());}
            BooleanSupplier pagesCurrent=()->issuedFor(issuedRequest)&&interpretationsValid()
                    &&rawPages.stream().allMatch(this::current)&&vectorPages.stream().allMatch(this::current)
                    &&candidatePages.stream().allMatch(this::current);
            var result=new CompletableFuture<Optional<NativeInterpretationSeal>>();active=result;
            try {store.issueNativeInterpretationEvidence(scope,issuedRequest,watermark,Set.copyOf(roots),List.copyOf(entries.values()))
                    .whenComplete((saved,failure)->nativeResume(result,()->{
                        if(failure!=null||saved==null||saved.isEmpty()||!pagesCurrent.getAsBoolean()){result.complete(Optional.empty());return;}
                        var issued=saved.orElseThrow();var seal=issued.seal();var owners=issued.ownerReferences();
                        prepareOwners(result,owners,pagesCurrent,()->{
                            interpretationSeals.put(seal,()->!result.isCompletedExceptionally()
                                    &&result.getNow(Optional.empty()).filter(value->value==seal).isPresent()
                                    &&pagesCurrent.getAsBoolean()&&proofCurrent(owners));
                            result.complete(Optional.of(seal));
                        },Optional.empty());
                    },Optional.empty()));
            }catch(RuntimeException unavailable){result.complete(Optional.empty());}
            return result.completeOnTimeout(Optional.empty(),2,TimeUnit.SECONDS);
        }
        @Override public boolean current(NativeInterpretationSeal seal) {
            if(!issuedFor(issuedRequest)||!interpretationsValid())return false;
            var guard=interpretationSeals.get(seal);try{return guard!=null&&guard.getAsBoolean();}catch(RuntimeException unavailable){return false;}
        }
        CompletableFuture<Boolean> prepareNativeReference(NativeMemoryEvidence.Reference reference) {
            if(!issuedFor(issuedRequest)||active!=null&&!active.isDone()||++nativePrepareCalls>64)return CompletableFuture.completedFuture(false);
            if(currentNativeReference(reference))return CompletableFuture.completedFuture(true);
            var result=new CompletableFuture<Boolean>();active=result;
            try {store.validateNativeEvidence(scope,reference).whenComplete((ready,failure)->nativeResume(result,()->{
                    if(failure!=null||ready==null||ready.isEmpty()||!issuedFor(issuedRequest)){result.complete(false);return;}
                    var validated=ready.orElseThrow();var owners=validated.ownerReferences();
                    BooleanSupplier stillCurrent=()->issuedFor(issuedRequest)&&(!validated.requiresProjection()||interpretationsValid());
                    prepareOwners(result,owners,stillCurrent,()->{
                    nativeReferences.put(reference,()->!result.isCompletedExceptionally()&&Boolean.TRUE.equals(result.getNow(false))
                            &&stillCurrent.getAsBoolean()&&proofCurrent(owners));
                    result.complete(true);
                    },false);
            },false));}catch(RuntimeException unavailable){result.complete(false);}
            return result.completeOnTimeout(false,2,TimeUnit.SECONDS);
        }
        CompletableFuture<Boolean> prepareNativeInterpretationReference(NativeInterpretationEvidence.Reference reference) {
            if(!issuedFor(issuedRequest)||!interpretationsValid()||active!=null&&!active.isDone()||++nativePrepareCalls>64)
                return CompletableFuture.completedFuture(false);
            if(currentNativeInterpretationReference(reference))return CompletableFuture.completedFuture(true);
            var result=new CompletableFuture<Boolean>();active=result;
            try {store.validateNativeInterpretationEvidence(scope,reference).whenComplete((ready,failure)->nativeResume(result,()->{
                if(failure!=null||ready==null||ready.isEmpty()||!issuedFor(issuedRequest)||!interpretationsValid()){result.complete(false);return;}
                var owners=ready.orElseThrow().ownerReferences();
                BooleanSupplier stillCurrent=()->issuedFor(issuedRequest)&&interpretationsValid();
                prepareOwners(result,owners,stillCurrent,()->{
                    interpretationReferences.put(reference,()->!result.isCompletedExceptionally()&&Boolean.TRUE.equals(result.getNow(false))
                            &&stillCurrent.getAsBoolean()&&proofCurrent(owners));
                    result.complete(true);
                },false);
            },false));}catch(RuntimeException unavailable){result.complete(false);}
            return result.completeOnTimeout(false,2,TimeUnit.SECONDS);
        }
        private <T> void nativeResume(CompletableFuture<T> result,Runnable action,T rejected){
            try {dispatch.accept(()->{
                if(result.isDone()||active!=result)return;
                try{action.run();}catch(RuntimeException unavailable){result.complete(rejected);}
            });}catch(RuntimeException unavailable){result.complete(rejected);}
        }
        private <T> void prepareOwners(CompletableFuture<T> result,List<RoomEvidenceReference> owners,
                BooleanSupplier stillCurrent,Runnable accepted,T rejected){
            if(result.isDone()||active!=result)return;
            if(!stillCurrent.getAsBoolean()||!RecordedRoomSearch.contentLeaves(owners)){result.complete(rejected);return;}
            if(owners.isEmpty()){if(proofCurrent(owners))accepted.run();else result.complete(rejected);return;}
            prepare.apply(owners).whenComplete((ready,failure)->nativeResume(result,()->{
                if(failure!=null||!Boolean.TRUE.equals(ready)||!stillCurrent.getAsBoolean()||!proofCurrent(owners)){result.complete(rejected);return;}
                accepted.run();
            },rejected));
        }
        boolean currentNativeReference(NativeMemoryEvidence.Reference reference) {
            if(!issuedFor(issuedRequest))return false;
            var guard=nativeReferences.get(reference);try{return guard!=null&&guard.getAsBoolean();}catch(RuntimeException unavailable){return false;}
        }
        boolean currentNativeInterpretationReference(NativeInterpretationEvidence.Reference reference) {
            if(!issuedFor(issuedRequest)||!interpretationsValid())return false;
            var guard=interpretationReferences.get(reference);try{return guard!=null&&guard.getAsBoolean();}catch(RuntimeException unavailable){return false;}
        }
        private boolean valid() {
            try { return gameThread.getAsBoolean() && System.nanoTime() <= deadline && current.getAsBoolean()
                    && authorityGeneration == store.authorityGeneration()
                    && store.readAuthorityStable()
                    && Set.of(WorldRecordingService.State.READY, WorldRecordingService.State.FULL).contains(store.health().state());
            } catch (RuntimeException unavailable) { return false; }
        }
        @Override public CompletableFuture<Page> query(Query query, Optional<Cursor> cursor, Budget budget) {
            if (!valid()) return CompletableFuture.completedFuture(empty(Status.STALE));
            if (active != null && !active.isDone() || ++calls > 8) return CompletableFuture.completedFuture(empty(Status.UNAVAILABLE));
            var position = RecordedRoomSearch.SearchPosition.initial();
            if (cursor.isPresent()) {
                var registered = cursors.get(cursor.get());
                if (registered == null || !registered.query().equals(query)) return CompletableFuture.completedFuture(empty(Status.STALE));
                position = registered.search();
            }
            var result = new CompletableFuture<Page>(); active = result;
            try {
                store.readRoom(scope, query, budget, watermark, position).whenComplete((found, failure) -> resume(result, () -> {
                    if (!valid()) { result.complete(empty(Status.STALE)); return; }
                    if (failure != null || found == null) { result.complete(empty(Status.UNAVAILABLE)); return; }
                    var accepted = new ArrayList<RecordedRoomSearch.Candidate>();
                    prepareNext(found, 0, accepted, query, result);
                }));
            } catch (RuntimeException unavailable) { result.complete(empty(Status.UNAVAILABLE)); }
            return result.completeOnTimeout(empty(Status.UNAVAILABLE), 2, TimeUnit.SECONDS);
        }
        private void resume(CompletableFuture<Page> result, Runnable action) {
            try {
                dispatch.accept(() -> {
                    // Timeout completion is thread-safe. Only the currently active attempt may touch game-thread state.
                    if (result.isDone() || active != result) return;
                    try { action.run(); } catch (RuntimeException unavailable) { result.complete(empty(Status.UNAVAILABLE)); }
                });
            } catch (RuntimeException unavailable) { result.complete(empty(Status.UNAVAILABLE)); }
        }
        private void prepareNext(RecordedRoomSearch.Result found, int index, List<RecordedRoomSearch.Candidate> accepted,
                Query query, CompletableFuture<Page> result) {
            if (result.isDone() || active != result) return;
            if (!valid()) { result.complete(empty(Status.STALE)); return; }
            if (index == found.candidates().size()) {
                accepted.removeIf(c -> !proofCurrent(c.evidence())||c.requiresProjection()&&!interpretationsValid());
                Optional<Cursor> next = Optional.empty();
                // Cursor presence must not reveal excluded private matches. Every query has the same bounded 8-page shape.
                if (calls < 8) { var cursor = Cursor.unregistered(); cursors.put(cursor, new Position(query, found.position())); next = Optional.of(cursor); }
                // Coverage of other source types/old context rows is not complete. No hidden-source counts or reasons escape.
                var page = new Page(Status.PARTIAL, accepted.stream().map(RecordedRoomSearch.Candidate::entry).toList(), next);
                pages.put(page, accepted.stream().map(RecordedRoomSearch.Candidate::evidence).toList());
                if(accepted.stream().anyMatch(RecordedRoomSearch.Candidate::requiresProjection))projectionDependentPages.add(page);
                result.complete(page); return;
            }
            var candidate = found.candidates().get(index);
            try {
                prepare.apply(candidate.evidence()).whenComplete((ready, failure) -> resume(result, () -> {
                    if (valid() && failure == null && Boolean.TRUE.equals(ready) && proofCurrent(candidate.evidence())
                            &&(!candidate.requiresProjection()||interpretationsValid())) accepted.add(candidate);
                    prepareNext(found, index + 1, accepted, query, result);
                }));
            } catch (RuntimeException unavailable) { result.complete(empty(Status.UNAVAILABLE)); }
        }
        @Override public boolean current(Page page) {
            if (!valid()||projectionDependentPages.contains(page)&&!interpretationsValid()) return false;
            var evidence = pages.get(page);
            return evidence != null && evidence.stream().allMatch(this::proofCurrent);
        }
        private boolean interpretationsValid() {
            return valid() && projectionGeneration == store.projectionGeneration() && store.projectionAuthorityStable();
        }
        @Override public CompletableFuture<InterpretationReadRecords.Page> interpretations(Page seeds,
                Optional<InterpretationReadRecords.Cursor> cursor, Budget budget) {
            if(!current(seeds))return CompletableFuture.completedFuture(emptyInterpretations(Status.STALE));
            return interpretations(new IssuedSeeds(seeds,seeds.entries().stream().map(Entry::messageId).toList(),()->current(seeds)),cursor,budget);
        }
        @Override public CompletableFuture<InterpretationReadRecords.Page> interpretations(SemanticReadRecords.Page seeds,
                Optional<InterpretationReadRecords.Cursor> cursor,Budget budget) {
            if(!current(seeds))return CompletableFuture.completedFuture(emptyInterpretations(Status.STALE));
            return interpretations(new IssuedSeeds(seeds,seeds.entries().stream().map(SemanticReadRecords.Entry::messageId).toList(),()->current(seeds)),cursor,budget);
        }
        private CompletableFuture<InterpretationReadRecords.Page> interpretations(IssuedSeeds seeds,
                Optional<InterpretationReadRecords.Cursor> cursor,Budget budget) {
            if (!interpretationsValid() || !seeds.current().getAsBoolean()) return CompletableFuture.completedFuture(emptyInterpretations(Status.STALE));
            if (active != null && !active.isDone() || ++calls > 8) return CompletableFuture.completedFuture(emptyInterpretations(Status.UNAVAILABLE));
            var position = RecordedInterpretationSearch.Position.initial();
            if (cursor.isPresent()) {
                var registered = interpretationCursors.get(cursor.get());
                if (registered == null || registered.seeds().identity() != seeds.identity()) return CompletableFuture.completedFuture(emptyInterpretations(Status.STALE));
                position = registered.search();
            }
            var result = new CompletableFuture<InterpretationReadRecords.Page>(); active = result;
            try {
                store.readInterpretations(scope, seeds.messageIds(), budget, watermark, position)
                        .whenComplete((found, failure) -> {
                    try { dispatch.accept(() -> {
                        if (result.isDone() || active != result) return;
                        try {
                            if (!interpretationsValid() || !seeds.current().getAsBoolean()) { result.complete(emptyInterpretations(Status.STALE)); return; }
                            if (failure != null || found == null) { result.complete(emptyInterpretations(Status.UNAVAILABLE)); return; }
                            Optional<InterpretationReadRecords.Cursor> next = Optional.empty();
                            // Fixed bounded shape, not a disclosure of denied candidate counts.
                            if (calls < 8) { var continuation = InterpretationReadRecords.Cursor.unregistered();
                                interpretationCursors.put(continuation, new InterpretationPosition(seeds, found.position())); next=Optional.of(continuation); }
                            var page = new InterpretationReadRecords.Page(Status.PARTIAL, found.entries(), next);
                            interpretationPages.put(page, seeds); result.complete(page);
                        } catch (RuntimeException unavailable) { result.complete(emptyInterpretations(Status.UNAVAILABLE)); }
                    }); } catch (RuntimeException unavailable) { result.complete(emptyInterpretations(Status.UNAVAILABLE)); }
                });
            } catch (RuntimeException unavailable) { result.complete(emptyInterpretations(Status.UNAVAILABLE)); }
            return result.completeOnTimeout(emptyInterpretations(Status.UNAVAILABLE), 2, TimeUnit.SECONDS);
        }
        @Override public boolean current(InterpretationReadRecords.Page page) {
            if (!interpretationsValid()) return false;
            var seeds = interpretationPages.get(page);
            return seeds != null && seeds.current().getAsBoolean();
        }
        private static InterpretationReadRecords.Page emptyInterpretations(Status status) {
            return new InterpretationReadRecords.Page(status, List.of(), Optional.empty());
        }
        private boolean semanticValid() {
            try { return semanticInitiallyEnabled && semanticEnabled.getAsBoolean() && valid(); }
            catch(RuntimeException unavailable){return false;}
        }
        @Override public CompletableFuture<SemanticReadRecords.Page> semantic(Query query, EmbeddingRecords.QueryVector vector,
                Optional<SemanticReadRecords.Cursor> cursor, Budget budget) {
            if(!semanticInitiallyEnabled)return CompletableFuture.completedFuture(emptySemantic(Status.UNAVAILABLE));
            if(!semanticValid())return CompletableFuture.completedFuture(emptySemantic(Status.STALE));
            if(query.text().isBlank()||query.text().length()>1600
                    ||!com.sande.mythictrpg.recording.api.RecordingRecords.sha256(query.text()).equals(vector.inputHash()))
                return CompletableFuture.completedFuture(emptySemantic(Status.STALE));
            if(active!=null&&!active.isDone()||++calls>8)return CompletableFuture.completedFuture(emptySemantic(Status.UNAVAILABLE));
            String fingerprint=RecordedSemanticSearch.vectorFingerprint(vector);
            var position=RecordedSemanticSearch.Position.initial();
            if(cursor.isPresent()){
                var registered=semanticCursors.get(cursor.get());
                if(registered==null||!registered.query().equals(query)||!registered.vectorFingerprint().equals(fingerprint))
                    return CompletableFuture.completedFuture(emptySemantic(Status.STALE));
                position=registered.search();
            }
            var result=new CompletableFuture<SemanticReadRecords.Page>();active=result;
            try {store.readSemantic(scope,query,vector,budget,watermark,position).whenComplete((found,failure)->{
                try {dispatch.accept(()->{
                    if(result.isDone()||active!=result)return;
                    try {
                        if(!semanticValid()){result.complete(emptySemantic(Status.STALE));return;}
                        if(failure!=null||found==null){result.complete(emptySemantic(Status.UNAVAILABLE));return;}
                        Optional<SemanticReadRecords.Cursor> next=Optional.empty();
                        if(calls<8){var continuation=SemanticReadRecords.Cursor.unregistered();
                            semanticCursors.put(continuation,new SemanticPosition(query,fingerprint,found.position()));next=Optional.of(continuation);}
                        var page=new SemanticReadRecords.Page(Status.PARTIAL,found.entries(),next);
                        semanticPages.add(page);result.complete(page);
                    }catch(RuntimeException unavailable){result.complete(emptySemantic(Status.UNAVAILABLE));}
                });}catch(RuntimeException unavailable){result.complete(emptySemantic(Status.UNAVAILABLE));}
            });}catch(RuntimeException unavailable){result.complete(emptySemantic(Status.UNAVAILABLE));}
            return result.completeOnTimeout(emptySemantic(Status.UNAVAILABLE),2,TimeUnit.SECONDS);
        }
        @Override public boolean current(SemanticReadRecords.Page page){return semanticValid()&&semanticPages.contains(page);}
        private static SemanticReadRecords.Page emptySemantic(Status status){return new SemanticReadRecords.Page(status,List.of(),Optional.empty());}
        @Override public CompletableFuture<ObservationReadRecords.Page> observations(Query query,
                Optional<ObservationReadRecords.Cursor> cursor,Budget budget) {
            if(!valid())return CompletableFuture.completedFuture(emptyObservations(Status.STALE));
            // Historical gameTime is not UTC; do not silently ignore a caller's temporal restriction.
            if(!RecordedObservationSearch.supported(scope)||query.fromInclusive().isPresent()||query.untilExclusive().isPresent()||!query.actorSelection().isAny())
                return CompletableFuture.completedFuture(emptyObservations(Status.UNAVAILABLE));
            if(active!=null&&!active.isDone()||++calls>8)return CompletableFuture.completedFuture(emptyObservations(Status.UNAVAILABLE));
            var position=RecordedObservationSearch.Position.initial();
            if(cursor.isPresent()) {
                var registered=observationCursors.get(cursor.get());
                if(registered==null||!registered.query().equals(query))return CompletableFuture.completedFuture(emptyObservations(Status.STALE));
                position=registered.search();
            }
            var result=new CompletableFuture<ObservationReadRecords.Page>();active=result;
            try {store.readObservations(scope,query,budget,watermark,position).whenComplete((found,failure)->resumeObservations(result,()->{
                if(!valid()){result.complete(emptyObservations(Status.STALE));return;}
                if(failure!=null||found==null){result.complete(emptyObservations(Status.UNAVAILABLE));return;}
                prepareObservations(found,0,new ArrayList<>(),query,result);
            }));}catch(RuntimeException unavailable){result.complete(emptyObservations(Status.UNAVAILABLE));}
            return result.completeOnTimeout(emptyObservations(Status.UNAVAILABLE),2,TimeUnit.SECONDS);
        }
        private void resumeObservations(CompletableFuture<ObservationReadRecords.Page> result,Runnable action) {
            try {dispatch.accept(()->{
                if(result.isDone()||active!=result)return;
                try {action.run();}catch(RuntimeException unavailable){result.complete(emptyObservations(Status.UNAVAILABLE));}
            });}catch(RuntimeException unavailable){result.complete(emptyObservations(Status.UNAVAILABLE));}
        }
        private void prepareObservations(RecordedObservationSearch.Result found,int index,
                List<RecordedObservationSearch.Candidate> accepted,Query query,CompletableFuture<ObservationReadRecords.Page> result) {
            if(result.isDone()||active!=result)return;
            if(!valid()){result.complete(emptyObservations(Status.STALE));return;}
            if(index==found.candidates().size()) {
                accepted.removeIf(c->!proofCurrent(c.evidence()));
                if(!valid()){result.complete(emptyObservations(Status.STALE));return;}
                Optional<ObservationReadRecords.Cursor> next=Optional.empty();
                if(calls<8){var continuation=ObservationReadRecords.Cursor.unregistered();
                    observationCursors.put(continuation,new ObservationPosition(query,found.position()));next=Optional.of(continuation);}
                var page=new ObservationReadRecords.Page(Status.PARTIAL,accepted.stream().map(RecordedObservationSearch.Candidate::entry).toList(),next);
                observationPages.put(page,accepted.stream().map(RecordedObservationSearch.Candidate::evidence).toList());result.complete(page);return;
            }
            var candidate=found.candidates().get(index);
            try {prepare.apply(candidate.evidence()).whenComplete((ready,failure)->resumeObservations(result,()->{
                if(!valid()){result.complete(emptyObservations(Status.STALE));return;}
                if(failure==null&&Boolean.TRUE.equals(ready)&&proofCurrent(candidate.evidence()))accepted.add(candidate);
                prepareObservations(found,index+1,accepted,query,result);
            }));}catch(RuntimeException unavailable){result.complete(emptyObservations(Status.UNAVAILABLE));}
        }
        @Override public boolean current(ObservationReadRecords.Page page) {
            if(!valid()||!RecordedObservationSearch.supported(scope))return false;
            var evidence=observationPages.get(page);
            return evidence!=null&&evidence.stream().allMatch(this::proofCurrent);
        }
        private static ObservationReadRecords.Page emptyObservations(Status status){return new ObservationReadRecords.Page(status,List.of(),Optional.empty());}
        @Override public CompletableFuture<RumorReadRecords.Page> rumors(Query query,
                Optional<RumorReadRecords.Cursor> cursor,Budget budget) {
            if(!valid())return CompletableFuture.completedFuture(emptyRumors(Status.STALE));
            if(!RecordedRumorSearch.supported(scope)||!query.actorSelection().isAny()
                    ||query.fromInclusive().isPresent()||query.untilExclusive().isPresent())
                return CompletableFuture.completedFuture(emptyRumors(Status.UNAVAILABLE));
            if(active!=null&&!active.isDone()||++calls>8)return CompletableFuture.completedFuture(emptyRumors(Status.UNAVAILABLE));
            var position=RecordedRumorSearch.Position.initial();
            if(cursor.isPresent()) {
                var registered=rumorCursors.get(cursor.get());
                if(registered==null||!registered.query().equals(query))return CompletableFuture.completedFuture(emptyRumors(Status.STALE));
                position=registered.search();
            }
            var result=new CompletableFuture<RumorReadRecords.Page>();active=result;
            try {store.readRumors(scope,query,budget,watermark,position).whenComplete((found,failure)->{
                try {dispatch.accept(()->{
                    if(result.isDone()||active!=result)return;
                    try {
                        if(!valid()){result.complete(emptyRumors(Status.STALE));return;}
                        if(failure!=null||found==null){result.complete(emptyRumors(Status.UNAVAILABLE));return;}
                        var entries=new ArrayList<RumorReadRecords.Entry>();
                        var snapshots=new ArrayList<NativeRumorReadAccess.Snapshot>();int bytes=0;
                        for(var candidate:found.candidates()) {
                            var current=rumorRead.apply(UUID.fromString(candidate.source().sourceId()));
                            if(current.isEmpty()||!rumorMatches(candidate,current.orElseThrow()))continue;
                            var snapshot=current.orElseThrow();
                            var entry=new RumorReadRecords.Entry(candidate.source(),candidate.knowledgeReceiptId(),candidate.lineageId(),
                                    candidate.recipientGodId(),candidate.subjectPlayerId(),candidate.claim(),candidate.epithet(),
                                    candidate.disclosureAudience(),snapshot.reception(),snapshot.assessment());
                            bytes=Math.addExact(bytes,RecordedRumorSearch.wireByteSize(entry));
                            if(entries.size()>=budget.rows()||bytes>budget.utf8Bytes()) {
                                result.complete(emptyRumors(Status.UNAVAILABLE));return;
                            }
                            entries.add(entry);snapshots.add(snapshot);
                        }
                        if(!valid()){result.complete(emptyRumors(Status.STALE));return;}
                        // Revalidate whole batch: a later point read cannot launder an earlier stale belief.
                        if(!snapshots.stream().allMatch(this::rumorCurrent)||!valid()){result.complete(emptyRumors(Status.STALE));return;}
                        Optional<RumorReadRecords.Cursor> next=Optional.empty();
                        if(calls<8){var continuation=RumorReadRecords.Cursor.unregistered();
                            rumorCursors.put(continuation,new RumorPosition(query,found.position()));next=Optional.of(continuation);}
                        var page=new RumorReadRecords.Page(Status.PARTIAL,entries,next);
                        rumorPages.put(page,List.copyOf(snapshots));result.complete(page);
                    }catch(RuntimeException unavailable){result.complete(emptyRumors(Status.UNAVAILABLE));}
                });}catch(RuntimeException unavailable){result.complete(emptyRumors(Status.UNAVAILABLE));}
            });}catch(RuntimeException unavailable){result.complete(emptyRumors(Status.UNAVAILABLE));}
            return result.completeOnTimeout(emptyRumors(Status.UNAVAILABLE),2,TimeUnit.SECONDS);
        }
        private boolean rumorMatches(RecordedRumorSearch.Candidate archived,NativeRumorReadAccess.Snapshot now) {
            var source=archived.source();
            return source.worldId().equals(now.worldId())&&archived.lineageId().equals(now.lineageId())
                    &&source.sourceId().equals(now.rootId().toString())&&source.revision()==now.claimRevision()
                    &&source.hash().equals(now.sourceHash())&&archived.subjectPlayerId().equals(now.subjectPlayerId())
                    &&archived.recipientGodId().equals(now.recipientGodId())&&archived.claim().equals(now.claim())
                    &&archived.epithet().equals(now.epithet())&&archived.disclosureAudience().equals(now.disclosureAudience());
        }
        private boolean rumorCurrent(NativeRumorReadAccess.Snapshot snapshot) {
            try{return rumorRead.apply(snapshot.rootId()).filter(snapshot::equals).isPresent();}
            catch(RuntimeException unavailable){return false;}
        }
        @Override public boolean current(RumorReadRecords.Page page) {
            if(!valid()||!RecordedRumorSearch.supported(scope))return false;
            var snapshots=rumorPages.get(page);
            return snapshots!=null&&snapshots.stream().allMatch(this::rumorCurrent)&&valid();
        }
        private static RumorReadRecords.Page emptyRumors(Status status){return new RumorReadRecords.Page(status,List.of(),Optional.empty());}
        private boolean proofCurrent(List<RoomEvidenceReference> references) {
            try { return evidenceCurrent.test(references); } catch (RuntimeException unavailable) { return false; }
        }
        private static Page empty(Status status) { return new Page(status, List.of(), Optional.empty()); }
    }
}
