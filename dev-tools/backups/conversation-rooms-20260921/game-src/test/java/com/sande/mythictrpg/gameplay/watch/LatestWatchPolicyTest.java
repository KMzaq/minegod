package com.sande.mythictrpg.gameplay.watch;
import com.sande.mythictrpg.gameplay.ledger.*;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import java.nio.file.*;
import java.util.*;

public final class LatestWatchPolicyTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        GodWatchTest.root=Files.createTempDirectory(Files.createDirectories(Path.of(args[0])),"latest-watch-");
        String a=GodWatchTest.GOD_A,b=GodWatchTest.GOD_B;
        try(var f=new GodWatchTest.Fixture("occlusion")) {
            f.rules();var wa=f.start(a,GodWatchTest.A);f.start(b,GodWatchTest.A);
            var event=f.event(GodWatchTest.A);var original=GodWatchTest.scene(event);
            var scene=new Scene(original.eventId(),original.captureSession(),original.captureOrder(),original.context(),original.powers(),original.domains(),false,false,original.visibility(),Set.of(a));
            var observed=GodWatchTest.get(f.watch.capture(event,scene).observed());
            check(observed.size()==1&&observed.getFirst().observerGodId().equals(b),"god-specific barrier keeps allowed god only");
            var area=new Area("minecraft:overworld",0,0,0,10,100,10);
            var selective=new RewardWatchSettings.Barrier(new Ref("test:temple",1),area,false,Set.of(b));
            var absolute=new RewardWatchSettings.Barrier(new Ref("test:hidden",1),area,true,Set.of());
            check(selective.blocks(a,event)&&!selective.blocks(b,event),"explicit allowlist not invented hierarchy");
            check(absolute.blocks(a,event)&&absolute.blocks(b,event),"absolute means all external gods");
            check(RewardWatchSettings.OFF.rule(a).isEmpty(),"no implicit observation policy");
            var aggregation=new ObservedActivity();
            check(aggregation.accept(wa,event,original),"visible committed sample admitted");
            check(aggregation.accept(wa,event,original),"same sample retry does not multiply");
            check(aggregation.due(110,20).isEmpty(),"no early summary");
            check(aggregation.due(120,20).getFirst().count()==1,"counts observed sample not vanilla total");
            aggregation.accept(wa,event,original);
            check(!aggregation.accept(wa,event,scene),"blocked sample denied");
            check(aggregation.due(999,20).isEmpty(),"occlusion clears unfinished aggregation/no exit backfill");
            aggregation.accept(wa,event,original);aggregation.discard(wa.id());
            check(aggregation.due(999,20).isEmpty(),"logout/watch interval boundary clears samples");
            // Duplicate important notification after a different capture keeps the ORIGINAL time/location and no new proof.
            var details=new ActionRecord.Details("minecraft:plains","AT_TRANSITION",UUID.randomUUID(),Set.of(GodWatchTest.A,GodWatchTest.B));
            UUID id=UUID.randomUUID();
            var first=new ActionRecord.Draft(id,f.session,++f.order,"mythictrpg:detail/battle_result",1,GodWatchTest.A,
                    new ActionRecord.Subject("BATTLE","test:raid",null),100,100,6000,"minecraft:overworld",new ActionRecord.Position(2,70,4),
                    ActionRecord.Type.BATTLE_RESULT,"COMPLETED",Map.of("battle_result","VICTORY"),"mythictrpg:admin_only_unprojected",details);
            var receipt=f.raw.submitTransition(first);GodWatchTest.get(receipt.durable());
            var retry=new ActionRecord.Draft(id,f.session,++f.order,first.sourceRef(),1,first.actorId(),first.subject(),200,200,7000,first.dimensionId(),new ActionRecord.Position(8,80,8),first.type(),first.outcome(),first.payload(),first.visibilityRef(),details);
            var repeated=f.raw.submitTransition(retry);
            check(GodWatchTest.get(repeated.durable()).event().equals(first),"retry retains original immutable capture");
            check(GodWatchTest.get(f.watch.observeSubmitted(retry,GodWatchTest.scene(retry),repeated)).isEmpty(),"retry cannot acquire new observation");
            var page=GodWatchTest.get(f.raw.after(new ActionLedgerStore.Cursor(f.world,0),GodWatchTest.B,100));
            check(page.records().stream().anyMatch(r->r.event().occurrenceId().equals(id)),"joint battle is referenced for all actual participants");
        }
        System.out.println("LatestWatchPolicyTest: PASS ("+checks+" checks); no server/GameTest/model");
    }
}
