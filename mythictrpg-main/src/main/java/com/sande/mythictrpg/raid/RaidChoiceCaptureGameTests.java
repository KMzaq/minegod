package com.sande.mythictrpg.raid;

import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import com.sande.mythictrpg.understanding.RaidChoiceEvidence;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Isolated producer/close-hook/persistence test, not a simulated player encounter. */
@GameTestHolder("mythictrpg_raid_choice")
@PrefixGameTestTemplate(false)
public final class RaidChoiceCaptureGameTests {
    @GameTest(templateNamespace="mythictrpg_raid_choice",template="empty",timeoutTicks=600)
    public static void choicesRespectRosterAndSurviveReload(GameTestHelper h) {
        try {
            var f=new Fixture(h);
            h.onEachTick(()->{
                if(f.finished)return;
                try {f.tick();}
                catch(Exception|AssertionError failure){f.close();h.fail("Raid choice fixture: "+failure);}
            });
        }catch(Exception failure){h.fail("Raid choice setup: "+failure);}
    }
    private static final class Fixture {
        final GameTestHelper h; final MinecraftServer server; final Path root; final UUID world=UUID.randomUUID();
        final Map<MinecraftServer,ActionLedgerService.Runtime> runtimes; final ActionLedgerService.Runtime prior;
        final UUID chosen=UUID.randomUUID(), declined=UUID.randomUUID(), unavailable=UUID.randomUUID(), uninstructed=UUID.randomUUID();
        AsyncActionLedger ledger; CompletableFuture<ActionLedgerStore.Page> page; int phase; boolean finished;
        @SuppressWarnings("unchecked") Fixture(GameTestHelper h)throws Exception {
            this.h=h;server=h.getLevel().getServer();
            var base=server.getServerDirectory().toAbsolutePath().normalize();
            if(!base.toString().replace('\\','/').contains("/build/"))throw new IllegalStateException("isolated build directory required");
            root=Files.createTempDirectory(base,"raid-choice-fixture-"); ledger=open();
            var replacement=new ActionLedgerService.Runtime();set(replacement,"worldId",world);set(replacement,"ledger",ledger);
            var f=ActionLedgerService.class.getDeclaredField("RUNTIMES");f.setAccessible(true);
            runtimes=(Map<MinecraftServer,ActionLedgerService.Runtime>)f.get(null);prior=runtimes.put(server,replacement);
        }
        AsyncActionLedger open()throws Exception {
            return new AsyncActionLedger(root,world,new ActionLedgerStore.Limits(16*1024*1024,65536,10000),32);
        }
        void tick()throws Exception {
            if(phase==0) {
                if(ledger.status().state()!=AsyncActionLedger.State.READY)return;
                var definition=RaidCatalog.INSTANCE.find(SealedGuardianEncounter.RAID_ID).orElseThrow();
                var attempt=new RaidState.Attempt(UUID.randomUUID(),chosen,UUID.randomUUID(),definition,Set.of(),0);
                attempt.arena=RaidCatalog.INSTANCE.snapshot().arenas().get(definition.arenaIds().getFirst());
                for(var id:List.of(chosen,declined,unavailable,uninstructed))attempt.members.put(id,new RaidState.Member(id));
                var evidence=new RaidChoiceEvidence(UUID.randomUUID(),RaidChoiceEvidence.Kind.MECHANIC,
                        RaidChoiceEvidence.Result.CHOSEN,"seal-0","sealed_guardian-v1",true);
                RaidChoiceCapture.record(server,attempt,chosen,evidence); // FORMING: rejected.
                attempt.status=RaidState.Status.ACTIVE;
                RaidChoiceCapture.record(server,attempt,UUID.randomUUID(),evidence); // Not on roster: rejected.
                Object fight=construct("Fight");
                @SuppressWarnings("unchecked") var instructed=(Set<UUID>)get(fight,"sealInstructionRecipients");
                instructed.add(declined);
                @SuppressWarnings("unchecked") var opportunities=(List<Map<UUID,Object>>)get(fight,"sealOpportunities");
                for(var id:List.of(chosen,declined,unavailable,uninstructed)) {
                    Object opportunity=construct("SealOpportunity");
                    set(opportunity,"attempted",id.equals(chosen));
                    set(opportunity,"longest",id.equals(declined)||id.equals(uninstructed)?40:1);
                    opportunities.getFirst().put(id,opportunity);
                }
                var close=SealedGuardianEncounter.class.getDeclaredMethod("closeSealEvidence",MinecraftServer.class,RaidState.Attempt.class,fight.getClass(),int.class);
                close.setAccessible(true);close.invoke(null,server,attempt,fight,0);close.invoke(null,server,attempt,fight,0);
                UUID decision=UUID.nameUUIDFromBytes((attempt.id()+"/seal/0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                RaidChoiceCapture.record(server,attempt,chosen,new RaidChoiceEvidence(decision,RaidChoiceEvidence.Kind.MECHANIC,
                        RaidChoiceEvidence.Result.CHOSEN,"seal-0","sealed_guardian-v1",true)); // Same final outcome, new capture: one event.
                page=ledger.after(new ActionLedgerStore.Cursor(world,0),null,10);phase++;
            }else if(phase==1) {
                if(!page.isDone())return;
                verify(page.join());
                h.assertTrue(ledger.awaitClose(5000),"ledger close");ledger=open();phase++;
            }else if(phase==2) {
                if(ledger.status().state()!=AsyncActionLedger.State.READY)return;
                page=ledger.after(new ActionLedgerStore.Cursor(world,0),null,10);phase++;
            }else if(phase==3) {
                if(!page.isDone())return;
                verify(page.join());close();h.succeed();
            }
        }
        void verify(ActionLedgerStore.Page page) {
            h.assertTrue(ledger.status().rejected()==0,"idempotent producer retry must not create a false gap");
            h.assertTrue(page.records().size()==4,"only four roster opportunities; close is idempotent");
            var results=new HashMap<UUID,RaidChoiceEvidence.Result>();
            for(var r:page.records()) {
                h.assertTrue(r.event().type()==ActionRecord.Type.RAID_CHOICE,"choice type survives encoding");
                h.assertTrue(r.event().details().participants().equals(Set.of(r.event().actorId())),"individual evidence only");
                h.assertTrue("OFFLINE_UNKNOWN".equals(r.event().details().locationStatus()),"offline has no invented location");
                var choice=RaidChoiceEvidence.parse(r.event().payload());h.assertTrue(choice.test(),"trial stays test-only");
                results.put(r.event().actorId(),choice.result());
            }
            h.assertTrue(results.get(chosen)==RaidChoiceEvidence.Result.CHOSEN,"participation attempt");
            h.assertTrue(results.get(declined)==RaidChoiceEvidence.Result.NOT_CHOSEN,"full opportunity without attempt");
            h.assertTrue(results.get(unavailable)==RaidChoiceEvidence.Result.UNAVAILABLE,"brief proximity is not a negative choice");
            h.assertTrue(results.get(uninstructed)==RaidChoiceEvidence.Result.UNAVAILABLE,"proximity without delivered instructions is not a negative choice");
            h.assertTrue(RaidChoiceEvidence.cards(page.records(),Map.of()).stream().allMatch(c->c.assessment().contains("근거 부족")),"test choices excluded from profile");
        }
        void close() {
            if(finished)return;finished=true;
            if(prior==null)runtimes.remove(server);else runtimes.put(server,prior);
            ledger.awaitClose(5000);
        }
    }
    private static Object construct(String nested)throws Exception {
        var c=Class.forName(SealedGuardianEncounter.class.getName()+"$"+nested).getDeclaredConstructor();c.setAccessible(true);return c.newInstance();
    }
    private static Object get(Object o,String name)throws Exception {var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception {var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
}
