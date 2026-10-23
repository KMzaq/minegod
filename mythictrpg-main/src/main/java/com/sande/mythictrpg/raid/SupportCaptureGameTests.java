package com.sande.mythictrpg.raid;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import com.sande.mythictrpg.understanding.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Production-shaped events in a disposable build world. Never enrolls an operating player or writes a live world. */
@GameTestHolder("mythictrpg_support_statistics")
@PrefixGameTestTemplate(false)
public final class SupportCaptureGameTests {
    @GameTest(templateNamespace="mythictrpg_support_statistics",template="empty",timeoutTicks=30000)
    public static void actualRaidAndAdmittedDialogue(GameTestHelper h) {
        try {var f=new Fixture(h);h.onEachTick(()->{if(!f.closed)try{f.tick();}catch(Exception|AssertionError error){f.close();h.fail("Support capture: "+error);}});}
        catch(Exception error){h.fail("Support setup: "+error);}
    }
    private static final class Fixture {
        final GameTestHelper h;final MinecraftServer server;final UUID world=UUID.randomUUID();
        final AsyncActionLedger ledger;final ActionLedgerService.Runtime replacement=new ActionLedgerService.Runtime(),prior;
        final Map<MinecraftServer,ActionLedgerService.Runtime> runtimes;final List<EmbeddedChannel> channels=new ArrayList<>();
        final ServerPlayer owner,other;final RaidCatalog.Snapshot catalog=RaidCatalog.INSTANCE.snapshot();
        final long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        CompletableFuture<ActionRecord> write;CompletableFuture<PurposeSelfView.Snapshot> read;
        CompletableFuture<Optional<SupportSharing.Grant>> grant;RoomConversationEngine.Request sharedRequest;
        int phase;long at;UUID run;boolean closed;
        @SuppressWarnings("unchecked") Fixture(GameTestHelper h)throws Exception {
            this.h=h;server=h.getLevel().getServer();var base=server.getServerDirectory().toAbsolutePath().normalize();
            if(!base.toString().replace('\\','/').contains("/build/"))throw new IllegalStateException("build fixture required");
            ledger=new AsyncActionLedger(Files.createTempDirectory(base,"support-producer-"),world,new ActionLedgerStore.Limits(16*1024*1024,65536,10000),64);
            set(replacement,"worldId",world);set(replacement,"ledger",ledger);
            var field=ActionLedgerService.class.getDeclaredField("RUNTIMES");field.setAccessible(true);runtimes=(Map<MinecraftServer,ActionLedgerService.Runtime>)field.get(null);prior=runtimes.put(server,replacement);
            owner=connect("SupportOwner");other=connect("SupportOther");
        }
        ServerPlayer connect(String name) {
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),name),false);
            var p=new ServerPlayer(server,h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);channels.add(new EmbeddedChannel(connection));
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);server.getPlayerList().placeNewPlayer(connection,p,cookie);
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);return p;
        }
        ActionRecord.Draft control(String operation) {
            return new ActionRecord.Draft(UUID.randomUUID(),replacement.captureSession(),replacement.nextCaptureOrder(),"mythictrpg:detail/player_understanding_control",1,
                    owner.getUUID(),new ActionRecord.Subject("ACTIVITY","mythictrpg:player_understanding",null),System.currentTimeMillis(),server.overworld().getGameTime(),0,
                    h.getLevel().dimension().location().toString(),new ActionRecord.Position(0,64,0),ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,"COMPLETED",Map.of("operation",operation),"mythictrpg:admin_only_unprojected");
        }
        void tick()throws Exception {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            h.assertTrue(System.nanoTime()<deadline,"bounded wall clock");
            // EmbeddedConnection has no real packet-listener tick; run the vanilla player item-use tick explicitly.
            if(phase>=2)owner.doTick();
            switch(phase) {
                case 0 -> {if(!ledger.ready().isDone())return;write=ledger.submit(control("START")).durable();phase++;}
                case 1 -> {
                    if(!write.isDone())return;MinecraftActivityCapture.controlChanged(owner,write.join());
                    var center=h.absolutePos(new net.minecraft.core.BlockPos(2,2,2));Vec3 anchor=Vec3.atBottomCenterOf(center);
                    for(int x=-3;x<7;x++)for(int z=-3;z<7;z++)h.getLevel().setBlockAndUpdate(center.offset(x,-1,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                    var raid=ResourceLocation.parse("mythictrpg:support_fixture");var arenaId=ResourceLocation.parse("mythictrpg:support_arena");
                    var definition=RaidDefinition.parse(raid,JsonParser.parseString("{\"schemaVersion\":1,\"displayName\":\"Support fixture\",\"mode\":\"PUBLIC_WORLD\",\"arenas\":[\"mythictrpg:support_arena\"],\"minimumPlayers\":1,\"maximumPlayers\":1,\"queueTimeoutTicks\":24000,\"combatTimeoutTicks\":24000,\"livesPerPlayer\":10,\"disconnectGraceTicks\":20,\"boss\":{\"entityType\":\"minecraft:zombie\"},\"phases\":[],\"entryCondition\":{\"type\":\"mythictrpg:always\"},\"rewardEligibility\":\"ALL_FROZEN_ROSTER\",\"rewardGodId\":\"mythictrpg:demeter\",\"rewards\":[]}").getAsJsonObject());
                    var arena=new RaidDefinition.Arena(arenaId,h.getLevel().dimension().location(),new AABB(anchor.x-2,anchor.y-1,anchor.z-2,anchor.x+5,anchor.y+4,anchor.z+5),anchor,anchor.add(2,0,2),anchor.add(12,0,0),"{}");
                    set(RaidCatalog.INSTANCE,"snapshot",new RaidCatalog.Snapshot(Map.of(raid,definition),Map.of(arenaId,arena)));
                    var created=RaidRuntime.INSTANCE.create(owner,raid);h.assertTrue(created.succeeded(),created.message());run=created.attemptId().orElseThrow();
                    h.assertTrue(RaidRuntime.INSTANCE.start(owner,run).succeeded(),"raid starts");phase++;
                }
                case 2 -> {
                    var a=RaidState.get(server).find(run).orElseThrow();if(a.status()!=RaidState.Status.ACTIVE)return;
                    var boss=(net.minecraft.world.entity.Mob)h.getLevel().getEntity(a.bossId);boss.setNoAi(true);
                    h.assertTrue(boss.hurt(owner.damageSources().playerAttack(owner),1),"real attack committed");
                    owner.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.SHIELD));at=server.getTickCount();phase++;
                }
                case 3 -> {if(server.getTickCount()-at<20)return;owner.startUsingItem(InteractionHand.OFF_HAND);at=server.getTickCount();phase++;}
                case 4 -> {
                    if(server.getTickCount()-at<20)return;h.assertTrue(owner.isBlocking(),"actual shield state");owner.stopUsingItem();
                    dialogue(false);dialogue(true);MinecraftActivityCapture.flush(owner);read=ledger.understandingSnapshot(owner.getUUID());phase++;
                }
                case 5 -> {
                    if(!read.isDone())return;var snapshot=read.join();
                    h.assertTrue(snapshot.statistics().counters().stream().anyMatch(c->c.key()==SupportStatistics.Key.RAID_ATTACK&&c.actions()==1),"real raid attack producer");
                    h.assertTrue(snapshot.statistics().counters().stream().anyMatch(c->c.key()==SupportStatistics.Key.RAID_GUARD&&c.actions()==1),"guard transition counted once, holding not spam");
                    h.assertTrue(snapshot.statistics().counters().stream().anyMatch(c->c.key()==SupportStatistics.Key.DIALOGUE_INFORMATION&&c.actions()==1),"one-use classified player source; test scope excluded");
                    h.assertTrue(snapshot.statistics().counters().stream().noneMatch(c->c.key()==SupportStatistics.Key.DIALOGUE_HELP),"definition reload invalidates pending source before classification");
                    var goals=snapshot.purposes().project(world,owner.getUUID(),e->true).goals();
                    h.assertTrue(goals.size()==1&&goals.getFirst().statedIntention()==PurposeState.Intention.DESIRED,"actual target resolution and durable wish");
                    var view=PurposeSelfView.append(SupportStatistics.append(PlayerUnderstanding.project(world,owner.getUUID(),snapshot.activity(),false),snapshot.statistics(),snapshot.activity().feedback(),true),world,owner.getUUID(),snapshot.purposes(),true);
                    var page=new UnderstandingPayload(UUID.randomUUID(),true,true,false,view.notice(),view.cards());
                    var buf=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),server.registryAccess());
                    try{UnderstandingPayload.CODEC.encode(buf,page);h.assertValueEqual(UnderstandingPayload.CODEC.decode(buf),page,"expanded profile wire");}finally{buf.release();}
                    read=ledger.understandingSnapshot(other.getUUID());phase++;
                }
                case 6 -> {
                    if(!read.isDone())return;h.assertTrue(read.join().statistics().counters().isEmpty()&&read.join().purposes().evidence().isEmpty(),"other player not enrolled or annotated");
                    // Synthetic independent source episodes exercise consent/lease fencing, not raid skill inference.
                    for(int i=0;i<3;i++)ledger.submit(new ActionRecord.Draft(UUID.randomUUID(),UUID.randomUUID(),1,"mythictrpg:detail/player_activity_fact",1,
                            owner.getUUID(),new ActionRecord.Subject("ACTIVITY",ActivityEvidence.SUBJECT,null),System.currentTimeMillis(),0,0,
                            "minecraft:overworld",new ActionRecord.Position(0,64,0),ActionRecord.Type.PLAYER_ACTIVITY_FACT,"COMPLETED",
                            new ActivityEvidence(ActivityEvidence.Signal.CRAFT_ITEM,1,0,0).payload(),"mythictrpg:admin_only_unprojected",
                            new ActionRecord.Details(null,"AT_TRANSITION",null,Set.of(owner.getUUID()))));
                    write=ledger.submit(new ActionRecord.Draft(UUID.randomUUID(),replacement.captureSession(),replacement.nextCaptureOrder(),"mythictrpg:detail/player_understanding_feedback",1,
                            owner.getUUID(),new ActionRecord.Subject("ACTIVITY","mythictrpg:player_understanding",null),System.currentTimeMillis(),0,0,
                            "minecraft:overworld",new ActionRecord.Position(0,64,0),ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,"COMPLETED",
                            Map.of("key",SupportSharing.KEY,"operation","ALLOW"),"mythictrpg:admin_only_unprojected")).durable();phase++;
                }
                case 7 -> {
                    if(!write.isDone())return;write.join();var god=ResourceLocation.parse("mythictrpg:demeter");
                    sharedRequest=new RoomConversationEngine.Request(UUID.randomUUID(),1,UUID.randomUUID(),owner.getUUID(),"SupportOwner",List.of(god),god,"내 진행을 도와줘",List.of(),false,true,false,
                            List.of(new RoomConversationEngine.GodState(god,"STRANGER","NEUTRAL","",null)),false,Set.of(owner.getUUID()));
                    grant=SupportSharing.prepare(owner,sharedRequest);phase++;
                }
                case 8 -> {
                    if(!grant.isDone())return;var issued=grant.join().orElseThrow();h.assertTrue(issued.current(sharedRequest),"explicit consent yields usable grant");
                    h.assertTrue(SupportSharing.prepare(other,sharedRequest).join().isEmpty(),"other player cannot consume consent");
                    SupportSharing.revoke(owner);h.assertTrue(!issued.current(sharedRequest),"revocation fences an already prepared response");
                    h.assertTrue(SupportSharing.prepare(owner,sharedRequest).join().isEmpty(),"failed or pending DENY cannot reuse durable old ALLOW");
                    SupportSharing.invalidate(owner);h.assertTrue(SupportSharing.blocked(owner),"unrelated mutation cannot clear revocation latch");
                    close();h.succeed();
                }
            }
        }
        void dialogue(boolean test)throws Exception {
            var god=ResourceLocation.parse("mythictrpg:demeter");String name=SupportCharacterNames.preferred(GodDefinitionManager.INSTANCE.find(god).orElseThrow());
            h.assertTrue(name.equals("데메테르")&&SupportCharacterNames.resolve(name).equals(List.of(god))&&SupportCharacterNames.resolve("Demeter").equals(List.of(god)),"official Korean and server English resolve to the same game ID");
            h.assertTrue(SupportCharacterNames.resolve("디오니소스").isEmpty(),"unregistered fixture target is not invented");
            UUID room=UUID.randomUUID(),turn=UUID.randomUUID();String text=name+"를 만나보고 싶어.";
            var event=new RoomDialogueEvent(UUID.randomUUID(),room,1,Optional.of(turn),RoomType.PRIVATE,test?RecordingScope.TEST_RECORDING:RecordingScope.STANDARD,
                    "PLAYER",owner.getUUID().toString(),text,Set.of(god.toString()),Map.of(owner.getUUID(),"SupportOwner"),Map.of(),System.currentTimeMillis(),Set.of(god.toString()),world,Set.of(),List.of(),Set.of());
            var request=new RoomConversationEngine.Request(room,1,turn,owner.getUUID(),"SupportOwner",List.of(god),god,text,List.of(),false,true,false,
                    List.of(new RoomConversationEngine.GodState(god,"STRANGER","NEUTRAL","",null)),false,Set.of(owner.getUUID()));
            DialogueSupportCapture.published(server,event);
            var definitions=GodDefinitionManager.INSTANCE.progressionSnapshot();
            try {
                set(GodDefinitionManager.INSTANCE,"snapshot",new GodDefinitionManager.ProgressionSnapshot(definitions.definitions(),definitions.unlockIndex(),definitions.appearanceIndex(),definitions.categoryIndex(),definitions.generation()+1));
                DialogueSupportCapture.classified(owner,request,SupportSignal.Kind.DIALOGUE_HELP,name,PurposeEvidence.Kind.MEET,PurposeEvidence.Signal.DESIRE,text);
            }finally{set(GodDefinitionManager.INSTANCE,"snapshot",definitions);}
            DialogueSupportCapture.classified(other,request,SupportSignal.Kind.DIALOGUE_INFORMATION,name,PurposeEvidence.Kind.MEET,PurposeEvidence.Signal.DESIRE,text);
            DialogueSupportCapture.classified(owner,request,SupportSignal.Kind.DIALOGUE_INFORMATION,name,PurposeEvidence.Kind.MEET,PurposeEvidence.Signal.DESIRE,text);
            DialogueSupportCapture.classified(owner,request,SupportSignal.Kind.DIALOGUE_INFORMATION,name,PurposeEvidence.Kind.MEET,PurposeEvidence.Signal.DESIRE,text);
        }
        void close() {
            if(closed)return;closed=true;
            if(run!=null)RaidRuntime.INSTANCE.cancel(owner,run);
            try{set(RaidCatalog.INSTANCE,"snapshot",catalog);}catch(Exception ignored){}
            if(prior==null)runtimes.remove(server);else runtimes.put(server,prior);
            server.getPlayerList().remove(owner);server.getPlayerList().remove(other);channels.forEach(EmbeddedChannel::finishAndReleaseAll);ledger.close();
        }
        static void set(Object object,String name,Object value)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    }
}
