package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.gameplay.ledger.*;
import java.nio.file.*;
import java.util.*;

public final class SupportStatisticsTest {
    private static final UUID WORLD=UUID.randomUUID(),PLAYER=UUID.randomUUID(),OTHER=UUID.randomUUID(),SESSION=UUID.randomUUID();
    private static int checks;private static long order;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static ActionRecord.Draft draft(UUID actor,ActionRecord.Type type,String subject,Map<String,String> data,long tick,UUID run,String dimension) {
        return new ActionRecord.Draft(UUID.randomUUID(),SESSION,++order,"mythictrpg:detail/"+type.name().toLowerCase(Locale.ROOT),1,actor,
                new ActionRecord.Subject("ACTIVITY",subject,null),1_800_000_000_000L+order,tick,tick,dimension,new ActionRecord.Position(0,64,0),
                type,"COMPLETED",data,"mythictrpg:admin_only_unprojected",new ActionRecord.Details(null,"AT_TRANSITION",run,Set.of(actor)));
    }
    private static ActionRecord.Draft control(UUID p,String operation){return draft(p,ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,"mythictrpg:player_understanding",Map.of("operation",operation),0,null,"minecraft:overworld");}
    private static ActionRecord.Draft activity(UUID p,ActivityEvidence.Signal signal,int count,long tick){return draft(p,ActionRecord.Type.PLAYER_ACTIVITY_FACT,ActivityEvidence.SUBJECT,new ActivityEvidence(signal,count,tick,tick).payload(),tick,null,"minecraft:overworld");}
    private static SupportStatistics.Counter counter(SupportStatistics.Snapshot s,SupportStatistics.Key key){return s.counters().stream().filter(c->c.key()==key).findFirst().orElseThrow();}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory(Path.of(args[0]),"cumulative-");
        var limits=new ActionLedgerStore.Limits(64*1024*1024,65536,20000);
        SupportStatistics.Snapshot saved;
        try(var store=new ActionLedgerStore(root,WORLD,limits)) {
            store.append(activity(PLAYER,ActivityEvidence.Signal.HARVEST_CROP,99,0));
            store.append(control(PLAYER,"START"));store.append(control(OTHER,"START"));
            var repeated=activity(PLAYER,ActivityEvidence.Signal.HARVEST_CROP,3,1);store.append(repeated);store.append(repeated);
            for(int i=0;i<410;i++)store.append(activity(PLAYER,ActivityEvidence.Signal.HARVEST_CROP,1,10+i));
            check(counter(store.supportStatistics(PLAYER),SupportStatistics.Key.FARMING).actions()==413,"all counts survive 400-record window; duplicate excluded");
            check(counter(store.supportStatistics(PLAYER),SupportStatistics.Key.FARMING).episodes()==1,"spam is one independent episode");
            check(!counter(store.supportStatistics(PLAYER),SupportStatistics.Key.FARMING).established(),"one episode not a tendency");
            store.append(activity(PLAYER,ActivityEvidence.Signal.HARVEST_CROP,1,12001));store.append(activity(PLAYER,ActivityEvidence.Signal.PLANT_CROP,1,24001));
            check(counter(store.supportStatistics(PLAYER),SupportStatistics.Key.FARMING).established(),"repeated independent episodes support tentative tendency");
            store.append(activity(OTHER,ActivityEvidence.Signal.BIOME_CHANGE,10,30000));
            check(store.supportStatistics(PLAYER).counters().stream().noneMatch(c->c.key()==SupportStatistics.Key.EXPLORATION),"player isolation");
            var trial=draft(PLAYER,ActionRecord.Type.PLAYER_SUPPORT_SIGNAL,SupportSignal.SUBJECT,new SupportSignal(SupportSignal.Kind.RAID_ATTACK,100,"trial").payload(),31000,UUID.randomUUID(),"mythictrpg:raid_trial");store.append(trial);
            check(store.supportStatistics(PLAYER).counters().stream().noneMatch(c->c.key()==SupportStatistics.Key.RAID_ATTACK),"trial excluded");
            for(int i=0;i<3;i++) {
                UUID run=UUID.randomUUID();
                for(var kind:List.of(SupportSignal.Kind.RAID_ATTACK,SupportSignal.Kind.RAID_GUARD))store.append(draft(PLAYER,ActionRecord.Type.PLAYER_SUPPORT_SIGNAL,SupportSignal.SUBJECT,new SupportSignal(kind,2,"raid").payload(),32000+i,run,"minecraft:overworld"));
            }
            check(counter(store.supportStatistics(PLAYER),SupportStatistics.Key.RAID_ATTACK).established()&&counter(store.supportStatistics(PLAYER),SupportStatistics.Key.RAID_GUARD).established(),"attack and cautious guard coexist across runs");
            var before=store.supportStatistics(PLAYER);
            check(before.equals(store.supportStatistics(PLAYER)),"reading and elapsed wall time do not decay activity");
            for(int i=0;i<33;i++)store.append(activity(PLAYER,ActivityEvidence.Signal.BIOME_CHANGE,1,50000L+12000L*i));
            check(counter(store.supportStatistics(PLAYER),SupportStatistics.Key.FARMING).recentEpisodes()==0,"new active play changes recent tendency without deleting cumulative counts");
            check(counter(store.supportStatistics(PLAYER),SupportStatistics.Key.FARMING).actions()==415,"lifetime statistics retained");
            check(counter(store.supportStatistics(PLAYER),SupportStatistics.Key.RAID_GUARD).recentEpisodes()==3,"ordinary play cannot evict raid-domain tendencies");
            var goal=new CharacterGoalSignal(new PurposeEvidence.Goal(PurposeEvidence.Kind.MEET,"test:dionysus"),PurposeEvidence.Signal.DESIRE,UUID.randomUUID(),"디오니소스를 만나고 싶어");
            for(int i=0;i<3;i++)store.append(draft(PLAYER,ActionRecord.Type.PLAYER_CHARACTER_SIGNAL,CharacterGoalSignal.SUBJECT,goal.payload(),600000L+12000*i,null,"minecraft:overworld"));
            var purpose=store.purposes(PLAYER).project(WORLD,PLAYER,e->true).goals().getFirst();
            check(purpose.statedIntention()==PurposeState.Intention.DESIRED&&purpose.independentEpisodes()==3,"repeated hope remains hope and accumulates");
            check(store.purposes(OTHER).evidence().isEmpty(),"character goal belongs only to speaking player");
            var deny=new CharacterGoalSignal(goal.goal(),PurposeEvidence.Signal.DISOWN,UUID.randomUUID(),"디오니소스를 만나는 건 내 목표가 아니야");
            store.append(draft(PLAYER,ActionRecord.Type.PLAYER_CHARACTER_SIGNAL,CharacterGoalSignal.SUBJECT,deny.payload(),650000,null,"minecraft:overworld"));
            check(store.purposes(PLAYER).project(WORLD,PLAYER,e->true).goals().getFirst().lifecycle()==PurposeState.Lifecycle.DISOWNED,"correction removes active goal");
            var stats=store.supportStatistics(PLAYER);var base=new PlayerUnderstanding.View(true,"통계",List.of());
            var quiet=SupportStatistics.append(base,stats,List.of(),false);
            check(quiet.cards().stream().noneMatch(c->c.explanation().contains("선택 가능한 도움")),"statistics do not unsolicitedly recommend");
            check(SupportStatistics.append(base,stats,List.of(),true).cards().stream().anyMatch(c->c.explanation().contains("선택 가능한 도움")),"requested support uses established recent tendencies");
            var feedback=store.append(draft(PLAYER,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,"mythictrpg:player_understanding",Map.of("key","SUPPORT_EXPLORATION","operation","DISPUTE"),650001,null,"minecraft:overworld"));
            var view=SupportStatistics.append(base,store.supportStatistics(PLAYER),List.of(feedback),true);
            check(view.cards().stream().filter(c->c.key().equals("SUPPORT_EXPLORATION")).allMatch(c->c.disputed()&&!c.explanation().contains("선택 가능한 도움")),"own correction overrides support");
            saved=store.supportStatistics(PLAYER);
            var snapshot=new PurposeSelfView.Snapshot(store.understanding(PLAYER),store.purposes(PLAYER),saved);
            check(!SupportSharing.allowed(WORLD,PLAYER,snapshot)&&SupportSharing.projection(WORLD,PLAYER,snapshot).isEmpty(),"sharing default off even with useful private statistics");
            store.append(draft(PLAYER,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,"mythictrpg:player_understanding",Map.of("key",SupportSharing.KEY,"operation","ALLOW"),650002,null,"minecraft:overworld"));
            snapshot=new PurposeSelfView.Snapshot(store.understanding(PLAYER),store.purposes(PLAYER),store.supportStatistics(PLAYER));
            check(SupportSharing.allowed(WORLD,PLAYER,snapshot),"explicit UI consent authorizes a bounded projection");
            check(!SupportSharing.allowed(WORLD,OTHER,snapshot)&&!SupportSharing.allowed(UUID.randomUUID(),PLAYER,snapshot),"sharing consent cannot cross actor/world");
            check(!SupportSharing.projection(WORLD,PLAYER,snapshot).toString().contains("탐험"),"disputed tendency is not shared");
            check(SupportSharing.projection(WORLD,PLAYER,snapshot).toString().contains("RAID"),"established raid activity produces optional support after consent");
            check(!SupportSharing.projection(WORLD,PLAYER,snapshot).containsKey("tendencies"),"NPC gets bounded support options, not personality narration input");
            check(!SupportSharing.projection(WORLD,PLAYER,snapshot).toString().contains("test:dionysus"),"disowned target never used for support");
            var restore=store.append(draft(PLAYER,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,"mythictrpg:player_understanding",Map.of("key","SUPPORT_EXPLORATION","operation","RESTORE"),650003,null,"minecraft:overworld"));
            snapshot=new PurposeSelfView.Snapshot(store.understanding(PLAYER),store.purposes(PLAYER),store.supportStatistics(PLAYER));
            check(SupportSharing.projection(WORLD,PLAYER,snapshot).toString().contains(SupportStatistics.Key.EXPLORATION.suggestion),"explicit restore supersedes an earlier dispute for NPC support");
            check(SupportStatistics.append(base,snapshot.statistics(),List.of(restore,feedback),true).cards().stream().filter(c->c.key().equals("SUPPORT_EXPLORATION")).allMatch(c->!c.disputed()),"UI and NPC share latest correction, independent of list order");
            store.append(draft(PLAYER,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,"mythictrpg:player_understanding",Map.of("key",SupportSharing.KEY,"operation","DENY"),650003,null,"minecraft:overworld"));
            snapshot=new PurposeSelfView.Snapshot(store.understanding(PLAYER),store.purposes(PLAYER),store.supportStatistics(PLAYER));
            check(SupportSharing.projection(WORLD,PLAYER,snapshot).isEmpty(),"revocation overrides prior sharing consent");
            saved=store.supportStatistics(PLAYER);
        }
        try(var store=new ActionLedgerStore(root,WORLD,limits)) {
            check(saved.equals(store.supportStatistics(PLAYER)),"restart rebuilds exactly from existing journal");
            check(store.purposes(PLAYER).project(WORLD,PLAYER,e->true).goals().getFirst().lifecycle()==PurposeState.Lifecycle.DISOWNED,"goal correction survives restart");
            store.append(control(PLAYER,"PAUSE"));store.append(activity(PLAYER,ActivityEvidence.Signal.BIOME_CHANGE,100,900000));
            check(!store.supportStatistics(PLAYER).active()&&store.supportStatistics(PLAYER).counters().isEmpty(),"paused analysis excludes later data");
            store.append(control(PLAYER,"START"));check(store.supportStatistics(PLAYER).counters().isEmpty(),"restart analysis does not import previous test/history");
        }
        System.out.println("SupportStatisticsTest: "+checks+" checks passed");
    }
}
