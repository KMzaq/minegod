package com.sande.mythictrpg.ai;

import com.google.gson.GsonBuilder;
import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import com.sande.mythictrpg.understanding.*;
import com.sande.mythictrpg.gameplay.watch.GodWatchRuntime;
import com.sande.mythictrpg.recording.api.NativeMemoryEvidence;
import com.sande.mythictrpg.recording.server.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Isolated real game/engine/SQLite path. A separately requested live run replaces ONLY the transport. */
@GameTestHolder("mythai_native_dialogue")
@PrefixGameTestTemplate(false)
public final class NativeDialogueGameTests {
    private static final boolean LIVE = Boolean.getBoolean("mythai.fixture.liveDialogue");
    private static final boolean STATISTICS = Boolean.getBoolean("mythai.fixture.supportStatistics");
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:demeter");
    private static Fixture active;
    @GameTest(templateNamespace="mythai_native_dialogue", template="empty", timeoutTicks=900000, batch="native_dialogue")
    public static void actualNativeRecallAndObservedSupport(GameTestHelper h) {
        if (active != null) throw new IllegalStateException("isolated fixture required");
        active = new Fixture(h); h.onEachTick(active::tick);
    }
    @AfterBatch(batch="native_dialogue") public static void cleanup(ServerLevel level) { if (active != null) active.close(); }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h; final MinecraftServer server;
        final List<ServerPlayer> players = new ArrayList<>();
        final List<io.netty.channel.embedded.EmbeddedChannel> channels = new ArrayList<>();
        final List<Map<String,Object>> turns = new ArrayList<>();
        final Observer observer = new Observer(); final Tap client = new Tap(observer);
        final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(LIVE ? 25 : 3);
        Field engineField, clientField; Object originalEngine, originalClient;
        ServerPlayer owner, peer; ConversationRoomSnapshot room;
        CompletableFuture<?> watch; int phase, expected; boolean installed, closed;
        CompletableFuture<ActionRecord> enrollment,sharing;
        CompletableFuture<PurposeSelfView.Snapshot> goals;
        CompletableFuture<com.sande.mythictrpg.gameplay.watch.AsyncGodWatch.ReadSnapshot> actionBarrier;
        Fixture(GameTestHelper h) { this.h=h; server=h.getLevel().getServer(); }
        void tick() {
            if (closed) return;
            // Live model calls need real tick cadence for game-issued admission leases, not accelerated test time.
            java.util.concurrent.locks.LockSupport.parkNanos(LIVE?50_000_000:1_000_000);
            try {
                require(System.nanoTime()<deadline,"native dialogue wall-clock deadline; phase="+phase);
                if (phase==0) {
                    var raw=ActionLedgerService.current(server); var store=RecordingRuntime.current(server).orElse(null);
                    if(raw==null||raw.ledger()==null||!raw.ledger().ready().isDone()||GodWatchRuntime.current(server)==null
                            ||store==null||store.health().state()!=WorldRecordingService.State.READY)return;
                    require(RecordingRuntime.nativeDialogueEnabled(server),"explicit NEW fixture required");
                    require(server.getServerDirectory().toAbsolutePath().normalize().toString().replace('\\','/').contains("/build/"),"build-only world");
                    require(AiDialogueConfig.INSTANCE.settings().ollamaModel().equals("gemma4:12b"),"current model must remain gemma4:12b");
                    owner=connect("NativeOwner"); peer=connect("NativePeer");
                    // The first attach clears the old engine lifecycle. Install transport instrumentation AFTER it.
                    ConversationRooms.INSTANCE.memberships(owner);
                    engineField=field(RoomConversationEngineRouter.class,"engine"); clientField=field(MythAiRoomConversationEngine.class,"llm");
                    originalEngine=engineField.get(RoomConversationEngineRouter.INSTANCE); originalClient=clientField.get(MythAiRoomConversationEngine.INSTANCE);
                    require(originalEngine==MythAiRoomConversationEngine.INSTANCE,"real engine is installed");
                    engineField.set(RoomConversationEngineRouter.INSTANCE,observer); clientField.set(MythAiRoomConversationEngine.INSTANCE,client); installed=true;
                    if(STATISTICS){seedStatistics();phase=-1;return;}
                    reopen(peer,RoomType.PRIVATE); say(peer,"내 비밀 쉼터 이름은 검은유리정원이야."); phase=1; return;
                }
                if(phase==-1) {
                    if(!sharing.isDone())return;sharing.join();MinecraftActivityCapture.controlChanged(owner,enrollment.join());
                    reopen(peer,RoomType.PRIVATE);say(peer,"내 비밀 쉼터 이름은 검은유리정원이야.");phase=1;return;
                }
                if(phase==1 && settled()) { reopen(owner,RoomType.PRIVATE); say(owner,"내 쉼터 이름은 별빛밀밭이야. 기억해 둬."); phase=2; return; }
                if(phase==2 && settled()) { reopen(owner,RoomType.PRIVATE); say(owner,"내 쉼터 이름이 뭐였지?"); phase=3; return; }
                if(phase==3 && settled()) {
                    checkLast("별빛밀밭",true,false); say(owner,"디오니소스를 만나보고 싶어. 아직 만나러 가기로 결정한 건 아니야."); phase=4; return;
                }
                if(phase==4 && settled()) { say(owner,"아폴론을 만나러 갈 계획은 민수의 계획이야. 내 계획이 아니야."); phase=5; return; }
                if(phase==5 && settled()) { reopen(owner,RoomType.PRIVATE); say(owner,"내가 디오니소스에 대해서 뭐라고 했었지?"); phase=6; return; }
                if(phase==6 && settled()) {
                    checkLast("만나보고 싶어. 아직 만나러 가기로 결정한 건 아니야.",true,false); reopen(owner,RoomType.PRIVATE);
                    say(owner,"아폴론을 만나러 갈 계획은 누구의 계획이었지?"); phase=7; return;
                }
                if(phase==7 && settled()) {
                    checkLast("민수",true,false);
                    if(STATISTICS&&LIVE){goals=ActionLedgerService.current(server).ledger().understandingSnapshot(owner.getUUID());phase=70;return;}
                    watch=GodWatchRuntime.current(server).startTrial(server,GOD,owner,16); phase=8; return;
                }
                if(phase==70) {
                    if(!goals.isDone())return;
                    var projected=goals.join().purposes().project(ActionLedgerService.current(server).worldId(),owner.getUUID(),e->true).goals();
                    require(projected.stream().anyMatch(g->g.goal().targetId().equals("mythictrpg:aphrodite")&&Set.of(PurposeState.Intention.DESIRED,PurposeState.Intention.CONSIDERING).contains(g.statedIntention())),"actual LLM wish must reach this player's durable goal without becoming decision");
                    require(projected.stream().noneMatch(g->g.goal().targetId().equals("mythictrpg:fortuna")&&g.lifecycle()==PurposeState.Lifecycle.ACTIVE),"other person's plan cannot become an active owner goal");
                    require(projected.stream().noneMatch(g->g.goal().kind()==PurposeEvidence.Kind.LEARN_ABOUT),"recalling earlier words is not a new character-learning goal");
                    watch=GodWatchRuntime.current(server).startTrial(server,GOD,owner,16);phase=8;return;
                }
                if(phase==8 && watch.isDone()) {
                    watch.join(); var pos=owner.blockPosition();
                    for(int i=1;i<=3;i++) {
                        var crop=pos.offset(i,0,0);
                        h.getLevel().setBlockAndUpdate(crop.below(),Blocks.FARMLAND.defaultBlockState());
                        h.getLevel().setBlockAndUpdate(crop,Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE,7));
                        require(owner.gameMode.destroyBlock(crop),"actual mature crop removal failed");
                    }
                    var runtime=ActionLedgerService.current(server);
                    actionBarrier=GodWatchRuntime.current(server).gateway().read(new com.sande.mythictrpg.gameplay.watch.WatchContract.Audience(
                            runtime.worldId(),new com.sande.mythictrpg.gameplay.watch.WatchContract.Key(GOD.toString(),owner.getUUID()),
                            Set.of(owner.getUUID()),new com.sande.mythictrpg.gameplay.watch.WatchContract.Ref("fixture-durable-barrier",1)));
                    phase=9; return;
                }
                if(phase==9) {
                    // Ordering barrier on the SAME real watch worker; no invented observation/lease/proof.
                    if (!actionBarrier.isDone()) return;
                    require(actionBarrier.join().view().available() && actionBarrier.join().view().proofs().size()>=3,
                            "all actual crop observations must durably finish before recall test");
                    reopen(owner,RoomType.PRIVATE); say(owner,"오늘 하늘이 참 맑네. 잠깐 이야기하자."); phase=10; return;
                }
                if(phase==10 && settled()) {
                    checkNoSupport(); say(owner,"민수는 농사일을 좋아한다던데. 나는 잘 모르겠어."); phase=11; return;
                }
                if(phase==11 && settled()) {
                    checkNoSupport(); say(owner,"농사라면 어떤 도구를 쓰면 돼?"); phase=12; return;
                }
                if(phase==12 && settled()) {
                    checkNoSupport(); say(owner,"내가 어떤 행동을 하는 걸 봤어? 네가 직접 본 것만 알려줘."); phase=120; return;
                }
                if(phase==120 && settled()) {
                    checkLast("[OBSERVED_PLAYER_SUPPORT]",false,true);
                    require(!client.lastGeneration().contains("playerSharedSupport"),"observation recall must not disclose cumulative profile");
                    say(owner,"네가 본 내 행동으로는 어떤 활동에 관심 있어 보였어?"); phase=13; return;
                }
                if(phase==13 && settled()) {
                    checkPersonalizedSupport();
                    say(owner,"그건 됐고 오늘은 네 이야기를 듣고 싶어."); phase=14; return;
                }
                if(phase==14 && settled()) {
                    checkNoSupport(); say(owner,"네가 본 내 최근 활동을 참고해서 내가 해볼 만한 활동을 두 가지 추천해 줘."); phase=15; return;
                }
                if(phase==15 && settled()) {
                    checkPersonalizedSupport();
                    say(owner,"농사를 좋아한다고 단정하지는 마. 오늘은 그냥 수확한 거야."); phase=16; return;
                }
                if(phase==16 && settled()) {
                    checkNoSupport(); reopen(owner,RoomType.PUBLIC_MOBILE); say(owner,"내 쉼터 이름이 뭐였지?"); phase=17; return;
                }
                if(phase==17 && settled()) {
                    String prompt=client.lastGeneration();
                    require(!prompt.contains("별빛밀밭")&&!prompt.contains("검은유리정원"),"private memory leaked into public prompt");
                    require(!prompt.contains("[OBSERVED_PLAYER_SUPPORT]"),"private watch facts leaked into public prompt");
                    require(!prompt.contains("playerSharedSupport"),"private cumulative statistics leaked into public prompt");
                    save();
                    long failures=observer.results.stream().filter(r->!r.failure().isBlank()).count();
                    close();
                    if(failures>0)h.fail("Native dialogue completed all turns; "+failures+" real engine rejection(s), see native-dialogue-results.json");
                    else h.succeed();
                }
            } catch(Exception|AssertionError error) { try{save();}catch(Exception ignored){} close();h.fail("Native dialogue phase "+phase+": "+error); }
        }
        void checkNoSupport() {
            require(!client.lastGeneration().contains("[OBSERVED_PLAYER_SUPPORT]"),"unrequested support leaked into ordinary conversation");
            require(!client.lastGeneration().contains("playerSharedSupport"),"unrequested cumulative statistics leaked into ordinary conversation");
            require(!client.lastGeneration().contains("POSSIBLE_INTEREST_ONLY"),"unrequested interest hypothesis");
        }
        void checkPersonalizedSupport() {
            require(!client.lastGeneration().contains("[OBSERVED_PLAYER_SUPPORT]"),"cumulative help must not substitute a recent witness sample");
            if(STATISTICS)checkLast("playerSharedSupport",false,false);
            else checkNoSupport();
        }
        void seedStatistics() {
            // Explicit synthetic evidence in a disposable build world; it is NOT operating-player history.
            var runtime=ActionLedgerService.current(server);
            enrollment=runtime.ledger().submit(statisticDraft(ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,"mythictrpg:player_understanding",Map.of("operation","START"))).durable();
            for(int i=0;i<3;i++)runtime.ledger().submit(statisticDraft(ActionRecord.Type.PLAYER_ACTIVITY_FACT,ActivityEvidence.SUBJECT,
                    new ActivityEvidence(ActivityEvidence.Signal.CRAFT_ITEM,4,0,0).payload()));
            sharing=runtime.ledger().submit(statisticDraft(ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,"mythictrpg:player_understanding",Map.of("key",SupportSharing.KEY,"operation","ALLOW"))).durable();
        }
        ActionRecord.Draft statisticDraft(ActionRecord.Type type,String subject,Map<String,String> payload) {
            return new ActionRecord.Draft(UUID.randomUUID(),UUID.randomUUID(),1,"mythictrpg:detail/"+type.name().toLowerCase(Locale.ROOT),1,
                    owner.getUUID(),new ActionRecord.Subject("ACTIVITY",subject,null),System.currentTimeMillis(),0,0,
                    "minecraft:overworld",new ActionRecord.Position(0,64,0),type,"COMPLETED",payload,"mythictrpg:admin_only_unprojected",
                    new ActionRecord.Details(null,"AT_TRANSITION",null,Set.of(owner.getUUID())));
        }
        void checkLast(String required,boolean nativeProof,boolean observationProof) {
            String prompt=client.lastGeneration();
            if (!prompt.contains(required)) Thread.getAllStackTraces().forEach((thread,stack)-> {
                if(thread.getName().toLowerCase(Locale.ROOT).matches(".*(ledger|watch|record).*"))
                    System.out.println("SUPPORT_DIAGNOSTIC "+thread.getName()+" "+thread.getState()+" "+Arrays.toString(stack));
            });
            require(prompt.contains(required),"production prompt omitted required source: "+required);
            require(!prompt.contains("검은유리정원"),"other player's private source leaked");
            // Continue live diagnostics after a rejected turn, but the final GameTest still FAILS.
            // There was no NPC publication to certify; never pretend rejected text was delivered.
            if(LIVE&&!observer.results.getLast().failure().isBlank())return;
            var published=observer.published.getLast();
            if(nativeProof)require(published.evidenceRefs().stream().anyMatch(ref->ref.kind().equals(NativeMemoryEvidence.KIND)),"NPC publication lacks native dependency");
            if(observationProof)require(published.evidenceRefs().stream().anyMatch(ref->ref.kind().equals("WATCH_OBSERVATION_V1")),"NPC publication lacks observed action dependency");
        }
        boolean settled() {
            if(observer.results.size()<expected)return false;
            var result=observer.results.getLast();
            if(!LIVE)require(result.failure().isBlank(),"real engine rejection: "+result.failure());
            boolean published=observer.published.stream().anyMatch(e->e.turnId().filter(observer.current.turnId()::equals).isPresent());
            return (LIVE&&!result.failure().isBlank()||published) && !ConversationRooms.INSTANCE.memoryReadCurrent(server,observer.current);
        }
        void say(ServerPlayer player,String text) {
            // These are actually registered game targets; the older recall-only scenario can mention unknown names.
            if(STATISTICS)text=text.replace("디오니소스","아프로디테").replace("아폴론을","포르투나를").replace("아폴론","포르투나");
            try { require(clientField.get(MythAiRoomConversationEngine.INSTANCE)==client,"fixture transport was replaced; refuse unintended model call"); }
            catch(IllegalAccessException invalid){throw new IllegalStateException(invalid);}
            expected++; observer.started=System.nanoTime();
            if(room.type().isPublic()) ConversationRooms.INSTANCE.publicText(player,text);
            else ConversationRooms.INSTANCE.privateText(player,text);
            require(observer.requests.size()==expected,"game did not issue the expected turn");
        }
        void reopen(ServerPlayer player,RoomType type) {
            if(room!=null)for(var p:players)for(var old:List.copyOf(ConversationRooms.INSTANCE.memberships(p)))
                ConversationRooms.INSTANCE.leave(p,old,"FIXTURE_REOPEN");
            room=ConversationRooms.INSTANCE.create(player,type,List.of(GOD),RecordingScope.STANDARD);
            if(type==RoomType.PRIVATE)ConversationRooms.INSTANCE.selectPrivate(player,room.roomId());
        }
        ServerPlayer connect(String name) {
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(),name),false);
            var p=new ServerPlayer(server,h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var c=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channels.add(new io.netty.channel.embedded.EmbeddedChannel(c));
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(c);
            players.add(p);server.getPlayerList().placeNewPlayer(c,p,cookie);
            if(STATISTICS)p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            p.setPos(h.absolutePos(new net.minecraft.core.BlockPos(2,5,2)).getCenter());
            return p;
        }
        void save() throws Exception {
            var root=server.getServerDirectory();
            Files.writeString(root.resolve("native-dialogue-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                    "live",LIVE,"statistics",STATISTICS,"model",AiDialogueConfig.INSTANCE.settings().ollamaModel(),"turns",observer.records,"calls",client.calls)));
        }
        public void close() {
            if(closed)return;closed=true;
            try {ConversationRooms.INSTANCE.clear();for(var p:players)server.getPlayerList().remove(p);for(var c:channels)c.finishAndReleaseAll();
                if(installed){engineField.set(RoomConversationEngineRouter.INSTANCE,originalEngine);clientField.set(MythAiRoomConversationEngine.INSTANCE,originalClient);}
            }catch(Exception error){throw new IllegalStateException(error);}finally{client.close();active=null;}
        }
    }
    private static final class Observer implements RoomConversationEngine {
        final RoomConversationEngine delegate=MythAiRoomConversationEngine.INSTANCE;
        final List<Request> requests=new ArrayList<>(); final List<Result> results=new ArrayList<>();
        final List<RoomDialogueEvent> published=new ArrayList<>(); final List<Map<String,Object>> records=new ArrayList<>();
        Request current; long started;
        public CompletableFuture<Result> respond(Request r) {current=r;requests.add(r);return delegate.respond(r).whenComplete((answer,error)->{
            if(answer!=null){results.add(answer);records.add(Map.of("input",r.currentText(),"player",r.playerName(),"public",r.publicRoom(),
                    "milliseconds",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),"failure",answer.failure(),
                    "speech",answer.speech(),"proposals",answer.proposalsJson()));}
        });}
        public CompletableFuture<Boolean> prepareRecordedEvidence(Request r,List<RoomEvidenceReference> refs){return delegate.prepareRecordedEvidence(r,refs);}
        public boolean recordedEvidenceCurrent(Request r,List<RoomEvidenceReference> refs){return delegate.recordedEvidenceCurrent(r,refs);}
        public CompletableFuture<SplitResult> chooseSplit(SplitRequest r){return delegate.chooseSplit(r);}
        public void invalidate(UUID room){delegate.invalidate(room);}
        public void delivered(Request r,Result result){delegate.delivered(r,result);}
        public RoomDialogueEvent preparePublication(RoomDialogueEvent e){return delegate.preparePublication(e);}
        public void dialogueObserved(RoomDialogueEvent e){delegate.dialogueObserved(e);}
        public void dialoguePublished(RoomDialogueEvent e){delegate.dialoguePublished(e);if(e.role().equals("NPC"))published.add(e);}
        public void stop(){delegate.stop();}
    }
    private static final class Tap implements LocalLlmClient {
        final Observer observer; final LocalLlmClient live=LIVE?new LocalOllamaClient():null;
        final List<Map<String,Object>> calls=new ArrayList<>(); String last="";
        Tap(Observer observer){this.observer=observer;}
        String lastGeneration(){return last;}
        public LocalLlmRequestScheduler.ScheduledRequest<AiDialogueModels.StructuredAiResult> submit(UUID id,List<AiDialogueModels.OllamaMessage> messages,AiDialogueConfig.Settings settings){
            last=new GsonBuilder().create().toJson(messages);calls.add(Map.of("kind","GENERATION","messages",messages));
            if(LIVE){observeWire(id,"GENERATION");return live.submit(id,messages,settings);}
            return done(id,new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech(observer.current.speakerGodId().toString(),"그 이야기는 기억해 두지.",List.of())),"",List.of()));
        }
        public LocalLlmRequestScheduler.ScheduledRequest<ConversationIntent> submitIntent(UUID id,List<AiDialogueModels.OllamaMessage> messages,AiDialogueConfig.Settings settings){
            calls.add(Map.of("kind","INTENT","messages",messages));
            require(messages.stream().noneMatch(m->m.content().contains("[OBSERVED_PLAYER_SUPPORT]")),"classifier must run before observation retrieval");
            if(LIVE) {observeWire(id,"INTENT");return live.submitIntent(id,messages,settings);}
            // Offline transport fixture only; production obtains this optional hint from its existing classifier.
            String text=observer.current.currentText();
            var use=text.equals("내가 어떤 행동을 하는 걸 봤어? 네가 직접 본 것만 알려줘.")
                    ? com.sande.mythictrpg.ai.intent.SupportConsultation.Use.OBSERVED_ACTION_RECALL
                    : text.equals("네가 본 내 행동으로는 어떤 활동에 관심 있어 보였어?")
                    ? com.sande.mythictrpg.ai.intent.SupportConsultation.Use.SELF_ASSESSMENT
                    : text.equals("네가 본 내 최근 활동을 참고해서 내가 해볼 만한 활동을 두 가지 추천해 줘.")
                    ? com.sande.mythictrpg.ai.intent.SupportConsultation.Use.PERSONALIZED_SUGGESTION
                    : com.sande.mythictrpg.ai.intent.SupportConsultation.Use.NONE;
            return done(id,new ConversationIntent(Set.of(),Set.of(),Set.of(),com.sande.mythictrpg.ai.intent.ConversationAct.UNSPECIFIED,
                    95,ConversationIntent.Source.LOCAL_LLM,com.sande.mythictrpg.ai.intent.TurnInterpretation.empty(),
                    new com.sande.mythictrpg.ai.intent.SupportConsultation(use,text)));
        }
        public LocalLlmRequestScheduler.ScheduledRequest<PlayerIntentPlan.Plan> submitUnderstanding(UUID id,PlayerIntentPlan.Input input,AiDialogueConfig.Settings settings){
            if(LIVE)return live.submitUnderstanding(id,input,settings);throw new UnsupportedOperationException("offline auxiliary");
        }
        public LocalLlmRequestScheduler.ScheduledRequest<RoomDialogueGrounding.Review> submitReview(UUID id,List<AiDialogueModels.OllamaMessage> messages,AiDialogueConfig.Settings settings){
            calls.add(Map.of("kind","REVIEW","messages",messages));
            if(LIVE){observeWire(id,"REVIEW");return live.submitReview(id,messages,settings);}
            return done(id,RoomDialogueGrounding.Review.accepted());
        }
        void observeWire(UUID id,String stage) {
            // Explicit isolated live fixture only: raw wire is not retained by ordinary production callers.
            var trace=new java.util.concurrent.CopyOnWriteArrayList<Map<String,String>>();
            calls.add(Map.of("kind","WIRE","stage",stage,"trace",trace));
            LocalOllamaClient.installWireObserver(id,new LocalOllamaClient.WireObserver(){
                public void onRequest(String body){trace.add(Map.of("request",body));}
                public void onResponse(String body){trace.add(Map.of("response",body));}
                public void onFailure(Throwable error){trace.add(Map.of("failure",error.toString()));}
            });
        }
        public void close(){if(live!=null)live.close();}
    }
    private static <T> LocalLlmRequestScheduler.ScheduledRequest<T> done(UUID id,T value){return new LocalLlmRequestScheduler.ScheduledRequest<>(id,Instant.now(),CompletableFuture.completedFuture(new LocalLlmRequestScheduler.ScheduledResult<>(value,Duration.ZERO,Duration.ZERO)));}
    private static Field field(Class<?> c,String name)throws Exception{var f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void require(boolean okay,String message){if(!okay)throw new AssertionError(message);}
}
