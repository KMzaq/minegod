package com.sande.mythictrpg.understanding;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Isolated development-server authority/codec/async fixture, NOT a graphical client test. */
@GameTestHolder("mythictrpg_understanding")
@PrefixGameTestTemplate(false)
public final class UnderstandingGameTests {
    @GameTest(templateNamespace="mythictrpg_understanding",template="empty",timeoutTicks=600)
    public static void ownViewReplayCorrectionAndLateResponses(GameTestHelper h) {
        try {
            var f = new Fixture(h);
            h.onEachTick(() -> {
                if (f.finished) return;
                try { f.tick(); }
                catch (Exception | AssertionError failure) { f.close(); h.fail("Understanding fixture: " + failure); }
            });
        } catch (Exception failure) { h.fail("Understanding setup: " + failure); }
    }
    private static final class Fixture {
        final GameTestHelper h; final MinecraftServer server; final AsyncActionLedger ledger;
        final Map<MinecraftServer,ActionLedgerService.Runtime> runtimes; final ActionLedgerService.Runtime prior;
        final List<EmbeddedChannel> channels=new ArrayList<>(); final ServerPlayer owner,other;
        UUID token; long at; int phase; boolean finished;
        @SuppressWarnings("unchecked") Fixture(GameTestHelper h)throws Exception {
            this.h=h;server=h.getLevel().getServer();
            Path base=server.getServerDirectory().toAbsolutePath().normalize();
            if(!base.toString().replace('\\','/').contains("/build/"))throw new IllegalStateException("development build directory required");
            Path root=Files.createTempDirectory(base,"understanding-fixture-");
            UUID world=UUID.randomUUID();
            ledger=new AsyncActionLedger(root,world,new ActionLedgerStore.Limits(16*1024*1024,65536,10000),16);
            var replacement=new ActionLedgerService.Runtime();
            field(replacement,"worldId",world);field(replacement,"ledger",ledger);
            var mapField=ActionLedgerService.class.getDeclaredField("RUNTIMES");mapField.setAccessible(true);
            runtimes=(Map<MinecraftServer,ActionLedgerService.Runtime>)mapField.get(null);prior=runtimes.put(server,replacement);
            owner=connect("UnderstandingA");other=connect("UnderstandingB");
        }
        ServerPlayer connect(String name) {
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),name),false);
            var p=new ServerPlayer(server,h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channels.add(new EmbeddedChannel(connection));
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            server.getPlayerList().placeNewPlayer(connection,p,cookie);return p;
        }
        void tick()throws Exception {
            switch(phase) {
                case 0 -> {
                    if(ledger.status().state()!=AsyncActionLedger.State.READY)return;
                    codec();UnderstandingRuntime.open(owner);token=(UUID)get(session(owner),"token");at=server.getTickCount();phase++;
                }
                case 1 -> {
                    if(server.getTickCount()-at<12 || busy(owner))return;
                    UnderstandingRuntime.open(other);
                    UnderstandingRuntime.receive(other,new UnderstandingRequest(token,1,UnderstandingRequest.Action.START,"",""));
                    h.assertTrue(ledger.status().committedSequence()==0,"foreign token wrote control");
                    UnderstandingRuntime.receive(owner,new UnderstandingRequest(token,1,UnderstandingRequest.Action.START,"",""));phase++;
                }
                case 2 -> {
                    if(busy(owner))return;
                    h.assertTrue(ledger.status().committedSequence()==1,"start not committed exactly once");
                    h.assertTrue(((PlayerUnderstanding.View)get(session(owner),"view")).active(),"view not active after durable commit");
                    at=server.getTickCount();phase++;
                }
                case 3 -> {
                    if(server.getTickCount()-at<12)return;
                    UnderstandingRuntime.receive(owner,new UnderstandingRequest(token,1,UnderstandingRequest.Action.PAUSE,"",""));
                    h.assertTrue(ledger.status().committedSequence()==1,"replayed sequence accepted");
                    UnderstandingRuntime.receive(owner,new UnderstandingRequest(token,2,UnderstandingRequest.Action.DISPUTE,"FARMING",""));phase++;
                }
                case 4 -> {
                    if(busy(owner))return;
                    var view=(PlayerUnderstanding.View)get(session(owner),"view");
                    h.assertTrue(view.cards().stream().anyMatch(c->c.key().equals("FARMING")&&c.disputed()),"durable correction not projected");
                    at=server.getTickCount();phase++;
                }
                case 5 -> {
                    if(server.getTickCount()-at<22)return;
                    UnderstandingRuntime.open(owner);UUID newer=(UUID)get(session(owner),"token");
                    h.assertTrue(!newer.equals(token),"new screen reused old grant");
                    UnderstandingRuntime.receive(owner,new UnderstandingRequest(token,3,UnderstandingRequest.Action.PAUSE,"",""));
                    token=newer;at=server.getTickCount();phase++;
                }
                case 6 -> {
                    if(busy(owner)||server.getTickCount()-at<12)return;
                    h.assertTrue(ledger.status().committedSequence()==2,"stale screen mutated current state");
                    UnderstandingRuntime.receive(owner,new UnderstandingRequest(token,1,UnderstandingRequest.Action.DECLARE,"purpose","친구와 탐험하기"));phase++;
                }
                case 7 -> {
                    if(busy(owner))return;
                    h.assertTrue(((PlayerUnderstanding.View)get(session(owner),"view")).cards().stream().anyMatch(c->c.key().equals("purpose")),"purpose not shown");
                    UnderstandingRuntime.receive(owner,new UnderstandingRequest(token,2,UnderstandingRequest.Action.CLOSE,"",""));
                    UnderstandingRuntime.receive(owner,new UnderstandingRequest(token,3,UnderstandingRequest.Action.PAUSE,"",""));
                    h.assertTrue(session(owner)==null,"closed session retained grant");
                    h.assertTrue(ledger.status().committedSequence()==3,"closed grant executed");
                    close();h.succeed();
                }
            }
        }
        void codec() {
            var card=new PlayerUnderstanding.Card("FARMING","농사","잠정적","확정된 성격이 아님",List.of("자기 근거"),true);
            var page=new UnderstandingPayload(UUID.randomUUID(),true,true,false,"일부 기록",List.of(card));
            var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),server.registryAccess());
            try { UnderstandingPayload.CODEC.encode(b,page);h.assertValueEqual(UnderstandingPayload.CODEC.decode(b),page,"page codec"); }
            finally { b.release(); }
            var req=new UnderstandingRequest(page.token(),4,UnderstandingRequest.Action.DISPUTE,"FARMING","");
            b=new RegistryFriendlyByteBuf(Unpooled.buffer(),server.registryAccess());
            try { UnderstandingRequest.CODEC.encode(b,req);h.assertValueEqual(UnderstandingRequest.CODEC.decode(b),req,"request codec"); }
            finally { b.release(); }
        }
        boolean busy(ServerPlayer p)throws Exception { return (boolean)get(session(p),"busy"); }
        @SuppressWarnings("unchecked") Object session(ServerPlayer p)throws Exception {
            var f=UnderstandingRuntime.class.getDeclaredField("SESSIONS");f.setAccessible(true);
            var all=(Map<MinecraftServer,Map<UUID,?>>)f.get(null);return all.getOrDefault(server,Map.of()).get(p.getUUID());
        }
        void close() {
            if(finished)return;finished=true;
            if(prior==null)runtimes.remove(server);else runtimes.put(server,prior);
            server.getPlayerList().remove(owner);server.getPlayerList().remove(other);
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);ledger.awaitClose(5000);
        }
    }
    private static void field(Object target,String name,Object value)throws Exception {
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    private static Object get(Object target,String name)throws Exception {var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
}
