package com.sande.mythictrpg.ai.room;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Real game-thread/room/publisher test. The injected engine tests routing, not LLM narrative quality. */
@GameTestHolder("mythictrpg_room_integration")
@PrefixGameTestTemplate(false)
public final class RoomConversationGameTests {
    private static final List<ResourceLocation> GODS=List.of(ResourceLocation.parse("mythictrpg:demeter"),
            ResourceLocation.parse("mythictrpg:fortuna"),ResourceLocation.parse("mythictrpg:lubras"));
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty",timeoutTicks=300,batch="room_full_path")
    public static void multiplePlayersReactAndIndependentRoomsCancelOnlyTheirOwnPendingTurn(GameTestHelper helper) {
        var engine=new ControlledEngine();
        var previous=overrideEngine(engine);
        var rooms=ConversationRooms.INSTANCE;
        var first=connectedPlayer(helper,"RoomOwner");var peer=connectedPlayer(helper,"RoomPeer");
        first.setPos(helper.absolutePos(net.minecraft.core.BlockPos.ZERO).getCenter());peer.setPos(first.position());
        rooms.create(first,RoomType.PUBLIC_MOBILE,GODS,RecordingScope.TEST_EPHEMERAL);
        var privateIds=new ArrayList<UUID>();
        helper.startSequence()
                .thenExecute(()->{
                    helper.assertTrue(rooms.publicText(peer,"mythictrpg:demeter 첫 질문"),"public input was not routed");
                    helper.assertValueEqual(engine.requests.size(),1,"one primary after peer joins");
                    var request=engine.requests.getFirst();
                    helper.assertValueEqual(request.audiencePlayerIds(),Set.of(first.getUUID(),peer.getUUID()),"actual multi-player audience");
                    helper.assertTrue(request.godIds().size()==3&&!request.secondary(),"three-God primary scope");
                    engine.speak(0,"주 응답");
                }).thenIdle(2)
                .thenExecute(()->{
                    helper.assertValueEqual(engine.requests.size(),2,"first optional reaction requested");
                    helper.assertTrue(engine.requests.get(1).secondary(),"reaction lost role");
                    helper.assertTrue(engine.requests.get(1).history().stream().anyMatch(h->h.text().equals("주 응답")&&h.messageId()!=null),"reaction did not receive actual published primary");
                    engine.pending.get(1).complete(RoomConversationEngine.Result.failed(engine.requests.get(1),"INJECTED_CANDIDATE_FAILURE"));
                }).thenIdle(2)
                .thenExecute(()->{
                    helper.assertValueEqual(engine.requests.size(),3,"candidate failure skipped rather than canceling remaining gods");
                    helper.assertTrue(!engine.requests.get(2).speakerGodId().equals(engine.requests.get(1).speakerGodId()),"candidate repeated");
                    engine.speak(2,"다음 신의 반응");
                }).thenIdle(2)
                .thenExecute(()->{
                    helper.assertValueEqual(engine.requests.size(),3,"reaction chain did not terminate after one pass");
                    helper.assertTrue(engine.observed.stream().anyMatch(e->e.text().equals("다음 신의 반응")&&e.heardGodIds().size()==3
                            &&e.fullTextReceiverIds().size()==2&&e.worldId()!=null),"complete audience/hearing receipt missing");
                    helper.assertTrue(engine.recorded.isEmpty(),"recording-off wrote transcripts");
                    for(int i=0;i<2;i++)privateIds.add(rooms.create(first,RoomType.PRIVATE,GODS,RecordingScope.TEST_EPHEMERAL).roomId());
                    rooms.selectPrivate(first,privateIds.get(0));rooms.privateText(first,"mythictrpg:demeter 비밀방 A");
                    rooms.selectPrivate(first,privateIds.get(1));rooms.privateText(first,"mythictrpg:demeter 비밀방 B");
                    helper.assertValueEqual(engine.requests.size(),5,"same player/gods concurrent private requests");
                    rooms.leave(first,rooms.resolveMember(first,privateIds.get(0).toString()).orElseThrow(),"TEST_CLOSE");
                    engine.speak(3,"만료된 방 대사");engine.speak(4,"살아 있는 방 대사");
                }).thenIdle(2)
                .thenExecute(()->{
                    helper.assertTrue(engine.observed.stream().noneMatch(e->e.text().equals("만료된 방 대사")),"late closed-room response leaked");
                    helper.assertTrue(engine.observed.stream().anyMatch(e->e.text().equals("살아 있는 방 대사")
                            &&e.fullTextReceiverIds().equals(Set.of(first.getUUID()))),"independent private reply missing or leaked to peer");
                    helper.assertValueEqual(engine.requests.size(),6,"other private room reaction survived");
                    var stale=engine.requests.get(5);
                    rooms.privateText(first,"mythictrpg:demeter 새 입력");
                    engine.speak(5,"새 입력 이전의 보조 반응");
                    helper.assertTrue(!engine.requests.get(6).turnId().equals(stale.turnId()),"new turn did not replace lease");
                }).thenIdle(2)
                .thenExecute(()->{
                    helper.assertTrue(engine.observed.stream().noneMatch(e->e.text().equals("새 입력 이전의 보조 반응")),"superseded reaction leaked");
                    rooms.clear();
                    for(var player:List.of(first,peer))player.server.getPlayerList().remove(player);
                    previous.run();
                }).thenSucceed();
    }
    private static Runnable overrideEngine(RoomConversationEngine engine) {
        try {
            var router=RoomConversationEngineRouter.INSTANCE;
            var value=RoomConversationEngineRouter.class.getDeclaredField("engine");value.setAccessible(true);
            var installed=RoomConversationEngineRouter.class.getDeclaredField("installed");installed.setAccessible(true);
            Object old=value.get(router);boolean wasInstalled=installed.getBoolean(router);
            value.set(router,engine);installed.setBoolean(router,true);
            return ()->{try{value.set(router,old);installed.setBoolean(router,wasInstalled);}
                catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}};
        } catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
    }
    private static ServerPlayer connectedPlayer(GameTestHelper helper,String name) {
        var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),name),false);
        var player=new ServerPlayer(helper.getLevel().getServer(),helper.getLevel(),cookie.gameProfile(),cookie.clientInformation());
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection,player,cookie);return player;
    }
    private static final class ControlledEngine implements RoomConversationEngine {
        final List<Request> requests=new ArrayList<>();final List<CompletableFuture<Result>> pending=new ArrayList<>();
        final List<RoomDialogueEvent> observed=new ArrayList<>(),recorded=new ArrayList<>();
        public CompletableFuture<Result> respond(Request request) {
            requests.add(request);var result=new CompletableFuture<Result>();pending.add(result);return result;
        }
        public CompletableFuture<SplitResult> chooseSplit(SplitRequest request) {
            return CompletableFuture.completedFuture(new SplitResult(request.roomId(),request.revision(),request.godId(),"","","TEST_NOT_REQUESTED"));
        }
        public void dialogueObserved(RoomDialogueEvent event){observed.add(event);}
        public void dialoguePublished(RoomDialogueEvent event){recorded.add(event);}
        void speak(int index,String text){var request=requests.get(index);pending.get(index).complete(new Result(request.roomId(),request.revision(),
                request.turnId(),List.of(new Speech(request.speakerGodId(),text)),"[]",List.of(),""));}
    }
}
