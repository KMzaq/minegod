package com.sande.mythictrpg.ai;

import com.mojang.authlib.GameProfile;
import com.sande.mythai.response.memory.DerivedSettings;
import com.sande.mythai.response.memory.MemoryIndexSettings;
import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.lang.reflect.Field;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Real room -> production AI -> real action -> final speech. Only LLM transport is scripted. */
@GameTestHolder("mythai_gm_action_narration")
@PrefixGameTestTemplate(false)
public final class RoomGmActionNarrationGameTests {
    private static final String BATCH="gm_action_narration_runtime";
    private static final ResourceLocation GOD=ResourceLocation.parse("mythictrpg:demeter");
    private static final String DRAFT="내 입장은 정했어.", FINAL="좋아. 네 말은 기억하겠어.";
    private static Fixture active;
    @GameTest(templateNamespace="mythai_gm_action_narration",template="empty",timeoutTicks=2000,batch=BATCH)
    public static void realActionPrecedesFinalSpeechExactlyOnce(GameTestHelper helper) {
        if(active!=null)throw new IllegalStateException("Serial isolated fixture required");
        var fixture=new Fixture(helper);active=fixture;
        try{fixture.start();helper.onEachTick(fixture::tick);}catch(Throwable failure){fixture.fail(failure);}
    }
    @AfterBatch(batch=BATCH)
    public static void restore(ServerLevel level){if(active!=null)active.close();}
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;final MinecraftServer server;
        final Observer observer=new Observer();final ScriptedLlm llm=new ScriptedLlm(observer);
        Field clientField,engineField,installedField,modeField;
        Object previousClient,previousEngine,previousMode;
        boolean previousInstalled,clientChanged,engineChanged,modeChanged,roomsCreated,closed,finalReleased;
        ServerPlayer player;EmbeddedChannel channel;UUID roomId;
        Fixture(GameTestHelper h){helper=h;server=h.getLevel().getServer();}
        void start()throws Exception {
            check(RoomConversationEngineRouter.INSTANCE.engine()==MythAiRoomConversationEngine.INSTANCE,"Production engine missing");
            var config=server.getServerDirectory().resolve("config/mythictrpg");
            check(!MemoryIndexSettings.load(config.resolve("ai-memory-index.json")).enabled()
                &&!DerivedSettings.load(config.resolve("ai-derived-memory.json")).semanticRetrieval(),"Offline workers must be disabled");
            var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"GmNarrationProbe"),false);
            player=new ServerPlayer(server,helper.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new Connection(PacketFlow.SERVERBOUND);channel=new EmbeddedChannel(connection);
            NetworkRegistry.configureMockConnection(connection);server.getPlayerList().placeNewPlayer(connection,player,cookie);
            player.setPos(helper.absolutePos(BlockPos.ZERO).getCenter());
            ConversationRooms.INSTANCE.memberships(player);
            modeField=field(MemoryFoundationSettings.class,"mode");previousMode=modeField.get(null);
            modeField.set(null,MemoryFoundationSettings.Mode.PERSONAL);modeChanged=true;
            PlayerMythDataService.get(server).setAffinity(player.getUUID(),GOD,0);
            clientField=field(MythAiRoomConversationEngine.class,"llm");previousClient=clientField.get(MythAiRoomConversationEngine.INSTANCE);
            clientField.set(MythAiRoomConversationEngine.INSTANCE,llm);clientChanged=true;
            engineField=field(RoomConversationEngineRouter.class,"engine");installedField=field(RoomConversationEngineRouter.class,"installed");
            previousEngine=engineField.get(RoomConversationEngineRouter.INSTANCE);previousInstalled=installedField.getBoolean(RoomConversationEngineRouter.INSTANCE);
            engineField.set(RoomConversationEngineRouter.INSTANCE,observer);engineChanged=true;installedField.setBoolean(RoomConversationEngineRouter.INSTANCE,true);
            roomId=ConversationRooms.INSTANCE.create(player,RoomType.PUBLIC_MOBILE,List.of(GOD),RecordingScope.STANDARD).roomId();roomsCreated=true;
            check(ConversationRooms.INSTANCE.publicText(player,GOD+" 안녕."),"Live input not routed");
        }
        int affinity(){return PlayerMythDataService.get(server).find(player.getUUID()).orElseThrow().affinities().getOrDefault(GOD,0);}
        void tick(){if(closed)return;try{
            check(observer.results.stream().noneMatch(r->!r.failure().isBlank()),"Engine rejected: "+observer.results);
            check(helper.getTick()<1900,"Async timeout: generations="+llm.generations+",finalCalls="+observer.finalCalls+",results="+observer.results);
            check(observer.published.stream().noneMatch(e->e.text().equals(DRAFT)),"Pre-action draft was published");
            if(llm.finalFuture!=null&&!finalReleased){
                check(observer.finalCalls==1&&llm.generations==2,"Exactly one decision and one final narration required");
                check(affinity()==1,"Real relationship action must execute before final narration");
                check(observer.published.isEmpty()&&observer.delivered==0,"Draft must not publish or commit before final model reply");
                check(observer.finalRequest.actionOutcomes().stream().anyMatch(o->o.actionType().equals("mythictrpg:relationship_change")&&o.status()==RoomConversationEngine.ActionStatus.EXECUTED),"Refreshed request lacks real executed action");
                String scene=llm.finalMessages.stream().map(AiDialogueModels.OllamaMessage::content).reduce("",(a,b)->a+b);
                check(scene.contains("gameConfirmedActionOutcomes")&&scene.contains("applied_delta")&&scene.contains("current_affinity"),"Actual result missing from final model context");
                check(!scene.contains(DRAFT),"Unpublished draft became history or fact");
                finalReleased=true;llm.finalFuture.complete(new LocalLlmRequestScheduler.ScheduledResult<>(draft(FINAL),Duration.ZERO,Duration.ZERO));
            }
            if(observer.delivered==1){
                check(finalReleased&&llm.generations==2&&observer.finalCalls==1,"Unexpected regeneration/decision loop");
                check(affinity()==1,"Final output repeated the action");
                check(observer.published.size()==1&&observer.published.getFirst().text().equals(FINAL)
                    &&observer.published.getFirst().fullTextReceiverIds().contains(player.getUUID()),"Only final speech must reach real game publication");
                check(observer.results.size()==2&&observer.results.getLast().failure().isBlank()&&observer.results.getLast().proposalsJson().equals("[]"),"Finalizer must strip second action proposal");
                close();helper.succeed();
            }
        }catch(Throwable failure){fail(failure);}}
        void fail(Throwable failure){try{close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}helper.fail("Production GM action narration: "+failure);}
        public void close(){if(closed)return;closed=true;Throwable failure=null;
            try{if(roomsCreated)ConversationRooms.INSTANCE.clear();}catch(Throwable e){failure=e;}
            try{if(player!=null)server.getPlayerList().remove(player);}catch(Throwable e){failure=combine(failure,e);}
            try{if(channel!=null)channel.finishAndReleaseAll();}catch(Throwable e){failure=combine(failure,e);}
            try{if(clientChanged)clientField.set(MythAiRoomConversationEngine.INSTANCE,previousClient);}catch(Throwable e){failure=combine(failure,e);}
            try{if(engineChanged){engineField.set(RoomConversationEngineRouter.INSTANCE,previousEngine);installedField.setBoolean(RoomConversationEngineRouter.INSTANCE,previousInstalled);}}catch(Throwable e){failure=combine(failure,e);}
            try{if(modeChanged)modeField.set(null,previousMode);}catch(Throwable e){failure=combine(failure,e);}
            llm.close();if(active==this)active=null;if(failure!=null)throw new IllegalStateException("Cleanup failed",failure);
        }
    }
    private static final class Observer implements RoomConversationEngine {
        final RoomConversationEngine delegate=MythAiRoomConversationEngine.INSTANCE;
        final List<Result> results=new ArrayList<>();final List<RoomDialogueEvent> published=new ArrayList<>();
        Request finalRequest;int finalCalls,delivered;
        public CompletableFuture<Result> respond(Request r){return delegate.respond(r).whenComplete((v,e)->{if(v!=null)results.add(v);});}
        public boolean supportsActionResultDialogue(){return delegate.supportsActionResultDialogue();}
        public CompletableFuture<Result> respondToActionResults(Request r,Result d){finalCalls++;finalRequest=r;return delegate.respondToActionResults(r,d).whenComplete((v,e)->{if(v!=null)results.add(v);});}
        public CompletableFuture<SplitResult> chooseSplit(SplitRequest r){return delegate.chooseSplit(r);}
        public void invalidate(UUID room){delegate.invalidate(room);}
        public void delivered(Request r,Result v){delegate.delivered(r,v);delivered++;}
        public RoomDialogueEvent preparePublication(RoomDialogueEvent e){return delegate.preparePublication(e);}
        public void dialogueObserved(RoomDialogueEvent e){delegate.dialogueObserved(e);if(e.role().equals("NPC"))published.add(e);}
        public void dialoguePublished(RoomDialogueEvent e){delegate.dialoguePublished(e);}
        public void stop(){delegate.stop();}
    }
    private static final class ScriptedLlm implements LocalLlmClient {
        final Observer observer;int generations;boolean closed;
        List<AiDialogueModels.OllamaMessage> finalMessages;
        CompletableFuture<LocalLlmRequestScheduler.ScheduledResult<AiDialogueModels.StructuredAiResult>> finalFuture;
        ScriptedLlm(Observer o){observer=o;}
        public LocalLlmRequestScheduler.ScheduledRequest<AiDialogueModels.StructuredAiResult> submit(UUID id,List<AiDialogueModels.OllamaMessage> messages,AiDialogueConfig.Settings settings){
            check(!closed&&++generations<=2,"Unexpected generation");
            if(generations==1)return completed(id,draft(DRAFT));
            check(observer.finalCalls==1,"Second generation must use actual finalizer API");
            finalMessages=List.copyOf(messages);finalFuture=new CompletableFuture<>();return new LocalLlmRequestScheduler.ScheduledRequest<>(id,Instant.now(),finalFuture);
        }
        public LocalLlmRequestScheduler.ScheduledRequest<ConversationIntent> submitIntent(UUID id,List<AiDialogueModels.OllamaMessage> m,AiDialogueConfig.Settings s){return completed(id,ConversationIntent.heuristicFallback());}
        public LocalLlmRequestScheduler.ScheduledRequest<RoomDialogueGrounding.Review> submitReview(UUID id,List<AiDialogueModels.OllamaMessage> m,AiDialogueConfig.Settings s){return completed(id,RoomDialogueGrounding.Review.accepted());}
        public void close(){closed=true;if(finalFuture!=null&&!finalFuture.isDone())finalFuture.cancel(false);}
    }
    private static AiDialogueModels.StructuredAiResult draft(String speech){
        return new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech(GOD.toString(),speech,List.of())),"",
            List.of(new AiDialogueModels.Proposal("relationship_change","관계","태도에 대한 반응",List.of(),Map.of("affinity_delta","1"))));
    }
    private static <T> LocalLlmRequestScheduler.ScheduledRequest<T> completed(UUID id,T value){return new LocalLlmRequestScheduler.ScheduledRequest<>(id,Instant.now(),CompletableFuture.completedFuture(new LocalLlmRequestScheduler.ScheduledResult<>(value,Duration.ZERO,Duration.ZERO)));}
    private static Field field(Class<?> type,String name)throws Exception{var field=type.getDeclaredField(name);field.setAccessible(true);return field;}
    private static Throwable combine(Throwable prior,Throwable next){if(prior==null)return next;prior.addSuppressed(next);return prior;}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
