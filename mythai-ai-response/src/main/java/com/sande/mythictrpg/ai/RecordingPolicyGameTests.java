package com.sande.mythictrpg.ai;

import com.sande.mythai.response.memory.DerivedSettings;
import com.sande.mythai.response.memory.MemoryIndexSettings;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import com.sande.mythictrpg.ai.api.RoomConversationEngineRouter;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import com.sande.mythictrpg.recording.server.RecordingRuntime;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Dedicated isolated NEW-config fixture. The real runtime rejects before player lookup or any LLM/legacy recall. */
@GameTestHolder("mythai_recording_policy")
@PrefixGameTestTemplate(false)
public final class RecordingPolicyGameTests {
    private static final String BATCH="native_retrieval_new_policy";
    private static Fixture active;
    private RecordingPolicyGameTests() { }

    @GameTest(templateNamespace="mythai_recording_policy",template="empty",timeoutTicks=400,batch=BATCH)
    public static void unsupportedNewRejectsBeforeLegacyAndLlm(GameTestHelper helper) {
        if(active!=null)throw new IllegalStateException("Policy fixture requires an isolated serial batch");
        var fixture=new Fixture(helper);active=fixture;
        helper.onEachTick(fixture::tick);
    }
    @AfterBatch(batch=BATCH)
    public static void restoreAfterTimeout(ServerLevel level) { if(active!=null)active.close(); }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final TrapClient trap=new TrapClient();
        Field clientField;Object previousClient;boolean replaced,closed;
        Fixture(GameTestHelper helper){this.helper=helper;server=helper.getLevel().getServer();}
        void tick(){
            if(closed)return;
            try {
                String state=RecordingRuntime.retrievalState(server);
                if(state.equals("RETRIEVAL_CONFIG_LOADING")){
                    check(helper.getTick()<300,"Retrieval bootstrap did not resolve NEW fixture config");
                    LockSupport.parkNanos(1_000_000);return;
                }
                check(state.equals("BLOCKED_CONTRACT_NOT_READY"),"Fixture requires an explicit NEW config: "+state);
                check(RecordingRuntime.retrievalForegroundBlocked(server),"Unsupported NEW must not become legacy fallback");
                check(RoomConversationEngineRouter.INSTANCE.engine()==MythAiRoomConversationEngine.INSTANCE,"Real AI engine not installed");
                check(!RecordingRuntime.projectionEnabled(server)&&!RecordingRuntime.embeddingEnabled(server),"Fixture background native model workers must be OFF");
                var config=server.getServerDirectory().resolve("config/mythictrpg");
                check(!MemoryIndexSettings.load(config.resolve("ai-memory-index.json")).enabled()
                        &&!DerivedSettings.load(config.resolve("ai-derived-memory.json")).semanticRetrieval(),"Fixture background legacy model workers must be OFF");
                clientField=MythAiRoomConversationEngine.class.getDeclaredField("llm");clientField.setAccessible(true);
                previousClient=clientField.get(MythAiRoomConversationEngine.INSTANCE);
                clientField.set(MythAiRoomConversationEngine.INSTANCE,trap);replaced=true;
                UUID absentPlayer=UUID.randomUUID(),room=UUID.randomUUID(),turn=UUID.randomUUID();
                var god=ResourceLocation.parse("mythictrpg:fortuna");
                check(server.getPlayerList().getPlayer(absentPlayer)==null,"No synthetic player should be installed");
                var request=new RoomConversationEngine.Request(room,0,turn,absentPlayer,"NoPlayerPolicyFixture",
                        List.of(god),god,"회상 요청은 처리되지 않아야 한다",List.of(),false,true,false,
                        List.of(new RoomConversationEngine.GodState(god,"R_NEUTRAL","E_NEUTRAL","",null)),false,Set.of(absentPlayer));
                var future=MythAiRoomConversationEngine.INSTANCE.respond(request);
                check(future.isDone(),"Blocked policy must return immediately without worker dispatch");
                var result=future.join();
                check(result.failure().equals("BLOCKED_CONTRACT_NOT_READY"),"Policy rejection must precede STALE_ROOM/player lookup");
                check(result.roomId().equals(room)&&result.turnId().equals(turn)&&result.speech().isEmpty()
                        &&result.proposalsJson().equals("[]")&&result.controls().isEmpty(),"Rejected request must contain no speech/actions");
                check(trap.generationCalls==0&&trap.intentCalls==0,"Neither classification nor generation may run under unsupported NEW");
                check(server.getPlayerList().getPlayer(absentPlayer)==null,"Policy rejection creates no player/room to make fallback work");
                close();helper.succeed();
            }catch(Throwable failure){close();helper.fail("Native NEW policy rejection: "+failure);}
        }
        @Override public void close(){
            if(closed)return;closed=true;
            try {if(replaced)clientField.set(MythAiRoomConversationEngine.INSTANCE,previousClient);}
            catch(IllegalAccessException failure){throw new IllegalStateException("Unable to restore policy fixture client",failure);}
            finally {trap.close();if(active==this)active=null;}
        }
    }
    /** Never creates an Ollama transport, even when an unexpected request reaches it. */
    private static final class TrapClient implements LocalLlmClient {
        int generationCalls,intentCalls;
        public LocalLlmRequestScheduler.ScheduledRequest<AiDialogueModels.StructuredAiResult> submit(UUID id,
                List<AiDialogueModels.OllamaMessage> messages,AiDialogueConfig.Settings settings){
            generationCalls++;throw new AssertionError("Unexpected generation under blocked NEW");
        }
        public LocalLlmRequestScheduler.ScheduledRequest<ConversationIntent> submitIntent(UUID id,
                List<AiDialogueModels.OllamaMessage> messages,AiDialogueConfig.Settings settings){
            intentCalls++;throw new AssertionError("Unexpected classifier under blocked NEW");
        }
        public void close(){ }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
