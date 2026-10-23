package com.sande.mythictrpg.understanding;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Real Minecraft hooks on an isolated development server. No model requests or production-world access. */
@GameTestHolder("mythictrpg_activity_support")
@PrefixGameTestTemplate(false)
public final class ActivityCaptureGameTests {
    @GameTest(templateNamespace="mythictrpg_activity_support",template="empty",timeoutTicks=600)
    public static void actualHooksEnrollmentPauseAndPrivateProjection(GameTestHelper h) {
        try {
            var fixture=new Fixture(h);
            h.onEachTick(()->{if(!fixture.finished)try{fixture.tick();}catch(Exception|AssertionError error){fixture.close();h.fail("Activity support: "+error);}});
        }catch(Exception error){h.fail("Activity support setup: "+error);}
    }
    private static final class Fixture {
        final GameTestHelper h;final MinecraftServer server;final AsyncActionLedger ledger;
        final Map<MinecraftServer,ActionLedgerService.Runtime> runtimes;final ActionLedgerService.Runtime prior,replacement;
        final List<EmbeddedChannel> channels=new ArrayList<>();final ServerPlayer owner,other;final UUID world=UUID.randomUUID();
        CompletableFuture<ActionRecord> write;CompletableFuture<PlayerUnderstanding.Slice> read;
        ActionRecord start;int phase;long at,pausedHead;boolean finished;
        @SuppressWarnings("unchecked") Fixture(GameTestHelper helper)throws Exception {
            h=helper;server=h.getLevel().getServer();
            Path base=server.getServerDirectory().toAbsolutePath().normalize();
            if(!base.toString().replace('\\','/').contains("/build/"))throw new IllegalStateException("development directory required");
            ledger=new AsyncActionLedger(Files.createTempDirectory(base,"activity-fixture-"),world,
                    new ActionLedgerStore.Limits(16*1024*1024,65536,10000),32);
            replacement=new ActionLedgerService.Runtime();set(replacement,"worldId",world);set(replacement,"ledger",ledger);
            var field=ActionLedgerService.class.getDeclaredField("RUNTIMES");field.setAccessible(true);
            runtimes=(Map<MinecraftServer,ActionLedgerService.Runtime>)field.get(null);prior=runtimes.put(server,replacement);
            owner=connect("ActivityOwner");other=connect("ActivityOther");
        }
        ServerPlayer connect(String name) {
            var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),name),false);
            var player=new ServerPlayer(server,h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new Connection(PacketFlow.SERVERBOUND);channels.add(new EmbeddedChannel(connection));
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            server.getPlayerList().placeNewPlayer(connection,player,cookie);
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            var spot=h.absolutePos(new net.minecraft.core.BlockPos(1,2,1));player.teleportTo(spot.getX(),spot.getY(),spot.getZ());
            return player;
        }
        ActionRecord.Draft control(String operation) {
            var pos=owner.blockPosition();
            return new ActionRecord.Draft(UUID.randomUUID(),replacement.captureSession(),replacement.nextCaptureOrder(),
                    "mythictrpg:detail/player_understanding_control",1,owner.getUUID(),new ActionRecord.Subject("ACTIVITY","mythictrpg:player_understanding",null),
                    System.currentTimeMillis(),server.overworld().getGameTime(),owner.level().getDayTime(),owner.level().dimension().location().toString(),
                    new ActionRecord.Position(pos.getX(),pos.getY(),pos.getZ()),ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,"COMPLETED",
                    Map.of("operation",operation),"mythictrpg:admin_only_unprojected");
        }
        void tick()throws Exception {
            switch(phase) {
                case 0 -> {
                    if(ledger.status().state()!=AsyncActionLedger.State.READY)return;
                    owner.awardStat(Stats.FISH_CAUGHT,50); // Historical data must be baselined, never imported.
                    MinecraftActivityCapture.record(owner,ActivityEvidence.Signal.PLACE_BLOCK,100);
                    MinecraftActivityCapture.flush(owner);
                    h.assertTrue(ledger.status().committedSequence()==0,"capture before analysis consent");
                    write=ledger.submit(control("START")).durable();phase++;
                }
                case 1 -> {
                    if(!write.isDone())return;start=write.join();MinecraftActivityCapture.controlChanged(owner,start);
                    h.assertTrue(MinecraftActivityCapture.visitTransition("a","a:plains","a","a:forest",true,false),"active biome transition");
                    h.assertTrue(!MinecraftActivityCapture.visitTransition("a","a:plains","a","a:forest",false,false),"passive movement excluded");
                    h.assertTrue(!MinecraftActivityCapture.visitTransition("a","a:plains","b","a:forest",true,false),"dimension transfer excluded");
                    h.assertTrue(!MinecraftActivityCapture.visitTransition("a","a:plains","a","a:forest",true,true),"passenger excluded");
                    h.assertTrue(!MinecraftActivityCapture.visitTransition("a","","a","a:forest",true,false),"missing baseline excluded");
                    // Exercise vanilla stat changes, a real successful placement and actual removal hooks.
                    owner.awardStat(Stats.FISH_CAUGHT,2);owner.awardStat(Stats.SLEEP_IN_BED,1);
                    var base=h.absolutePos(new net.minecraft.core.BlockPos(2,1,2));
                    h.getLevel().setBlockAndUpdate(base,Blocks.STONE.defaultBlockState());
                    owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Blocks.OAK_PLANKS,8));
                    owner.gameMode.useItemOn(owner,h.getLevel(),owner.getMainHandItem(),InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(base),Direction.UP,base,false));
                    h.assertTrue(h.getLevel().getBlockState(base.above()).is(Blocks.OAK_PLANKS),"real placement not committed");
                    var crop=h.absolutePos(new net.minecraft.core.BlockPos(3,2,2));
                    h.getLevel().setBlockAndUpdate(crop.below(),Blocks.FARMLAND.defaultBlockState());
                    h.getLevel().setBlockAndUpdate(crop,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
                    h.assertTrue(owner.gameMode.destroyBlock(crop),"real crop removal failed");
                    h.assertTrue(owner.gameMode.destroyBlock(base.above()),"real block removal failed");
                    // Nether wart/cocoa belong to farming too; immature crop removal is not mining.
                    MinecraftActivityCapture.blockRemoved(owner,Blocks.NETHER_WART.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.NetherWartBlock.AGE,3),true);
                    MinecraftActivityCapture.blockRemoved(owner,Blocks.COCOA.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.CocoaBlock.AGE,2),true);
                    MinecraftActivityCapture.blockRemoved(owner,Blocks.NETHER_WART.defaultBlockState(),true);
                    MinecraftActivityCapture.blockRemoved(owner,Blocks.STONE.defaultBlockState(),false);
                    owner.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                    MinecraftActivityCapture.record(owner,ActivityEvidence.Signal.PLACE_BLOCK,50);
                    owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                    MinecraftActivityCapture.record(other,ActivityEvidence.Signal.PLACE_BLOCK,40);
                    MinecraftActivityCapture.flush(other);
                    at=server.getTickCount();phase++;
                }
                case 2 -> {
                    if(server.getTickCount()-at<25)return;
                    owner.awardStat(Stats.FISH_CAUGHT,1); // Flush must include the unsampled last second.
                    MinecraftActivityCapture.flush(owner);read=ledger.understanding(owner.getUUID());phase++;
                }
                case 3 -> {
                    if(!read.isDone())return;var slice=read.join();
                    var facts=new EnumMap<ActivityEvidence.Signal,Integer>(ActivityEvidence.Signal.class);
                    for(var row:slice.events())if(row.event().type()==ActionRecord.Type.PLAYER_ACTIVITY_FACT) {
                        h.assertValueEqual(row.event().actorId(),owner.getUUID(),"owner bound");
                        h.assertValueEqual(row.event().visibilityRef(),"mythictrpg:admin_only_unprojected","no implicit NPC access");
                        var value=ActivityEvidence.parse(row.event().payload());facts.merge(value.signal(),value.count(),Integer::sum);
                    }
                    h.assertValueEqual(facts.get(ActivityEvidence.Signal.FISH_CAUGHT),3,"no historical fish backfill; flush includes pending stat delta");
                    h.assertValueEqual(facts.get(ActivityEvidence.Signal.SLEEP_IN_BED),1,"rest capture");
                    h.assertValueEqual(facts.get(ActivityEvidence.Signal.PLACE_BLOCK),1,"successful placement exactly once");
                    h.assertValueEqual(facts.get(ActivityEvidence.Signal.HARVEST_CROP),3,"wheat/nether wart/cocoa harvest");
                    h.assertValueEqual(facts.get(ActivityEvidence.Signal.REMOVE_BLOCK),1,"crop not counted as mining");
                    var view=PlayerUnderstanding.project(world,owner.getUUID(),slice,false);
                    h.assertTrue(view.cards().stream().anyMatch(c->c.key().equals("FISHING")&&!c.evidence().isEmpty()),"self projection");
                    var page=new UnderstandingPayload(UUID.randomUUID(),true,true,false,view.notice(),view.cards());
                    var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),server.registryAccess());
                    try{UnderstandingPayload.CODEC.encode(buffer,page);h.assertValueEqual(UnderstandingPayload.CODEC.decode(buffer),page,"expanded cards codec");}finally{buffer.release();}
                    read=ledger.understanding(other.getUUID());phase++;
                }
                case 4 -> {
                    if(!read.isDone())return;h.assertTrue(read.join().events().isEmpty(),"other player received facts");
                    write=ledger.submit(control("PAUSE")).durable();phase++;
                }
                case 5 -> {
                    if(!write.isDone())return;
                    MinecraftActivityCapture.controlChanged(owner,write.join());pausedHead=ledger.status().committedSequence();
                    MinecraftActivityCapture.controlChanged(owner,start); // A late old START must not re-enable capture.
                    owner.awardStat(Stats.FISH_CAUGHT,9);MinecraftActivityCapture.record(owner,ActivityEvidence.Signal.PLACE_BLOCK,9);
                    MinecraftActivityCapture.flush(owner);at=server.getTickCount();phase++;
                }
                case 6 -> {
                    if(server.getTickCount()-at<25)return;
                    MinecraftActivityCapture.flush(owner);read=ledger.understanding(owner.getUUID());phase++;
                }
                case 7 -> {
                    if(!read.isDone())return;
                    h.assertTrue(ledger.status().committedSequence()==pausedHead,"pause/stale control wrote more facts");
                    h.assertTrue(!PlayerUnderstanding.project(world,owner.getUUID(),read.join(),false).active(),"pause projection");
                    close();h.succeed();
                }
            }
        }
        void close() {
            if(finished)return;finished=true;
            server.getPlayerList().remove(owner);server.getPlayerList().remove(other);
            if(prior==null)runtimes.remove(server);else runtimes.put(server,prior);
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);ledger.awaitClose(5000);
        }
    }
    private static void set(Object target,String name,Object value)throws Exception {
        var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
}
