package com.sande.mythictrpg.ai.action;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Real game dispatch before speech, exactly one execution phase, and original-room result isolation. */
@GameTestHolder("mythictrpg_gm_actions")
@PrefixGameTestTemplate(false)
public final class GmActionFlowGameTests {
    private static final ResourceLocation GOD=ResourceLocation.parse("mythictrpg:fortuna");
    @GameTest(templateNamespace="mythictrpg_gm_actions",template="empty",batch="gm_action_items")
    public static void importantItemsCannotBeConsumedThroughAnOrdinaryItemRequest(GameTestHelper helper) {
        var player=helper.makeMockServerPlayerInLevel();
        player.getInventory().clearContent();
        var item=ResourceLocation.parse("minecraft:diamond");
        var protectedStack=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,3);
        var data=new net.minecraft.nbt.CompoundTag();var flags=new net.minecraft.nbt.CompoundTag();
        flags.putBoolean("important",true);data.put("mythictrpg:ai_protection",flags);
        protectedStack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(data));
        player.getInventory().setItem(0,protectedStack);
        player.getInventory().setItem(1,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,2));
        helper.assertValueEqual(InventoryItemTransferService.availability(player,item).inventoryCount(),2,"protected count offered to AI");
        helper.assertTrue(!InventoryItemTransferService.consume(player,item,3),"insufficient ordinary items partially consumed");
        helper.assertValueEqual(player.getInventory().getItem(1).getCount(),2,"failed transaction consumed items");
        helper.assertTrue(InventoryItemTransferService.consume(player,item,2),"ordinary items cannot be transferred");
        helper.assertValueEqual(player.getInventory().getItem(0).getCount(),3,"important items consumed");
        helper.assertTrue(player.getInventory().getItem(1).isEmpty(),"ordinary items remained after successful transfer");
        helper.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_gm_actions",template="empty",timeoutTicks=240,batch="gm_action_flow")
    public static void actionsResolveBeforeSpeechAndLateFinalizationCannotLeak(GameTestHelper helper) {
        var engine=new Engine();var restore=install(engine);var rooms=ConversationRooms.INSTANCE;
        var actor=connect(helper);var p=actor.player();rooms.memberships(p);
        var a=rooms.create(p,RoomType.PRIVATE,List.of(GOD),RecordingScope.LIVE_EPHEMERAL);
        var b=rooms.create(p,RoomType.PRIVATE,List.of(GOD),RecordingScope.TEST_EPHEMERAL);
        helper.startSequence().thenExecute(()->{
            rooms.selectPrivate(p,a.roomId());rooms.privateText(p,"도와줘");
            helper.assertTrue(!engine.requests.getFirst().readOnly()&&!engine.requests.getFirst().recording(),"live/off lost execution or retained recording");
            engine.decide(0);
        }).thenIdle(2).thenExecute(()->{
            helper.assertValueEqual(engine.results.size(),1,"no result narration requested");
            var result=engine.results.getFirst();
            helper.assertTrue(result.actionOutcomes().size()==1&&result.actionOutcomes().getFirst().status()==RoomConversationEngine.ActionStatus.EXECUTED,
                    "narration did not receive actual committed action");
            helper.assertTrue(engine.observed.stream().noneMatch(e->e.text().equals("UNPUBLISHED_DECISION")),"decision speech leaked before execution result");
            engine.finish(0,"실제 결과를 보고 한 말");
        }).thenIdle(2).thenExecute(()->{
            helper.assertTrue(engine.observed.stream().anyMatch(e->e.text().equals("실제 결과를 보고 한 말")),"final reply missing");
            helper.assertValueEqual(rooms.actionOutcomesFor(p,a.roomId(),a.revision(),GOD).size(),1,"action replayed during narration");
            helper.assertTrue(rooms.actionOutcomesFor(p,b.roomId(),b.revision(),GOD).isEmpty(),"outcome leaked to B");
            rooms.selectPrivate(p,b.roomId());rooms.privateText(p,"정보만 알려줘");engine.decide(1);
        }).thenIdle(2).thenExecute(()->{
            helper.assertTrue(engine.requests.get(1).readOnly(),"legacy test gained write authority");
            helper.assertValueEqual(engine.results.size(),1,"read-only requested action execution narration");
            helper.assertTrue(rooms.actionOutcomesFor(p,b.roomId(),b.revision(),GOD).isEmpty(),"read-only mutated game");
            rooms.selectPrivate(p,a.roomId());rooms.privateText(p,"다시 부탁");engine.decide(2);
        }).thenIdle(2).thenExecute(()->{
            helper.assertValueEqual(engine.results.size(),2,"rejected duplicate did not reach narration");
            helper.assertTrue(engine.results.get(1).actionOutcomes().stream().anyMatch(o->o.status()==RoomConversationEngine.ActionStatus.REJECTED),"rejection missing");
            rooms.leave(p,a,"END_BEFORE_FINAL_REPLY");engine.finish(1,"LATE_FINAL_REPLY");
        }).thenIdle(2).thenExecute(()->{
            helper.assertTrue(engine.observed.stream().noneMatch(e->e.text().equals("LATE_FINAL_REPLY")),"late result narration escaped ended room");
            rooms.clear();AiActionGateway.discardPlayer(p.getUUID());actor.close();restore.run();
        }).thenSucceed();
    }
    private static final class Engine implements RoomConversationEngine {
        final List<Request> requests=new ArrayList<>(),results=new ArrayList<>();
        final List<CompletableFuture<Result>> decisions=new ArrayList<>(),narrations=new ArrayList<>();
        final List<RoomDialogueEvent> observed=new ArrayList<>();
        public CompletableFuture<Result> respond(Request request){requests.add(request);var future=new CompletableFuture<Result>();decisions.add(future);return future;}
        public boolean supportsActionResultDialogue(){return true;}
        public CompletableFuture<Result> respondToActionResults(Request request,Result decision){results.add(request);var future=new CompletableFuture<Result>();narrations.add(future);return future;}
        public CompletableFuture<SplitResult> chooseSplit(SplitRequest request){return CompletableFuture.completedFuture(new SplitResult(request.roomId(),request.revision(),request.godId(),"","","unused"));}
        public void dialogueObserved(RoomDialogueEvent event){observed.add(event);}
        void decide(int index){var r=requests.get(index);decisions.get(index).complete(new Result(r.roomId(),r.revision(),r.turnId(),List.of(new Speech(GOD,"UNPUBLISHED_DECISION")),
                "[{\"type\":\"relationship_change\",\"parameters\":{\"affinity_delta\":\"1\"}}]",List.of(),""));}
        void finish(int index,String text){var r=results.get(index);narrations.get(index).complete(new Result(r.roomId(),r.revision(),r.turnId(),List.of(new Speech(GOD,text)),"[]",List.of(),""));}
    }
    private static Runnable install(RoomConversationEngine engine){
        try {var router=RoomConversationEngineRouter.INSTANCE;var field=RoomConversationEngineRouter.class.getDeclaredField("engine");field.setAccessible(true);
            var installed=RoomConversationEngineRouter.class.getDeclaredField("installed");installed.setAccessible(true);var old=field.get(router);var previous=installed.getBoolean(router);
            field.set(router,engine);installed.setBoolean(router,true);
            return ()->{try{field.set(router,old);installed.setBoolean(router,previous);}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}};
        }catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
    }
    private static Connected connect(GameTestHelper helper){
        var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"GmFlow"),false);
        var p=new ServerPlayer(helper.getLevel().getServer(),helper.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel=new io.netty.channel.embedded.EmbeddedChannel(connection);net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        p.server.getPlayerList().placeNewPlayer(connection,p,cookie);return new Connected(p,channel);
    }
    private record Connected(ServerPlayer player,io.netty.channel.embedded.EmbeddedChannel channel){void close(){player.server.getPlayerList().remove(player);channel.finishAndReleaseAll();}}
}
