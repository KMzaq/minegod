package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.gameplay.ledger.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Support-only contract, persisted-window invariance and per-player isolation; not an LLM or UI simulation. */
public final class ActivitySupportTest {
    private static final UUID WORLD=UUID.randomUUID(),PLAYER=UUID.randomUUID(),OTHER=UUID.randomUUID(),SESSION=UUID.randomUUID();
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable work,String message){try{work.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    private static ActionRecord.Draft draft(long n,UUID actor,ActionRecord.Type type,Map<String,String> payload) {
        String kind=type==ActionRecord.Type.POSITION_SAMPLE?"LOCATION":type==ActionRecord.Type.RAID_CHOICE?"BATTLE":"ACTIVITY";
        String subject=type==ActionRecord.Type.PLAYER_ACTIVITY_FACT?ActivityEvidence.SUBJECT:kind.equals("BATTLE")?"test:raid":"mythictrpg:player_understanding";
        long tick=type==ActionRecord.Type.PLAYER_ACTIVITY_FACT?ActivityEvidence.parse(payload).lastTick():n;
        return new ActionRecord.Draft(UUID.randomUUID(),SESSION,n,"mythictrpg:detail/"+type.name().toLowerCase(Locale.ROOT),1,
                actor,new ActionRecord.Subject(kind,subject,null),1_800_000_000_000L+n,tick,tick,"minecraft:overworld",
                new ActionRecord.Position(0,64,0),type,"COMPLETED",payload,"mythictrpg:admin_only_unprojected",
                new ActionRecord.Details(null,"AT_TRANSITION",type==ActionRecord.Type.RAID_CHOICE?SESSION:null,Set.of(actor)));
    }
    private static ActionRecord row(long n,UUID actor,ActivityEvidence.Signal signal,long tick) {
        return new ActionRecord(1,WORLD,n,draft(n,actor,ActionRecord.Type.PLAYER_ACTIVITY_FACT,new ActivityEvidence(signal,1,tick,tick).payload()));
    }
    private static ActionRecord start() {return new ActionRecord(1,WORLD,1,draft(1,PLAYER,ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,Map.of("operation","START")));}
    private static PlayerUnderstanding.View view(List<ActionRecord> events) {
        return PlayerUnderstanding.project(WORLD,PLAYER,new PlayerUnderstanding.Slice(start(),events,List.of(),100_000,false),false);
    }
    private static PlayerUnderstanding.Card card(PlayerUnderstanding.View view,String key) {
        return view.cards().stream().filter(c->c.key().equals(key)).findFirst().orElseThrow();
    }
    private static ActionRecord removed(long n,String dimension,UUID session) {
        return new ActionRecord(1,WORLD,n,new ActionRecord.Draft(UUID.randomUUID(),session,n,"mythictrpg:detail/block_removed",1,
                PLAYER,new ActionRecord.Subject("BLOCK","minecraft:stone",null),1_800_000_000_000L,10,10,dimension,
                new ActionRecord.Position(0,64,0),ActionRecord.Type.BLOCK_REMOVED,"COMPLETED",Map.of(),"mythictrpg:admin_only_unprojected"));
    }
    public static void main(String[] args)throws Exception {
        for(var signal:ActivityEvidence.Signal.values()) {
            var evidence=new ActivityEvidence(signal,4,1,5);
            check(ActivityEvidence.parse(evidence.payload()).equals(evidence),"bounded schema roundtrip "+signal);
            var observation=row(2,PLAYER,signal,10);
            check(!card(view(List.of(observation)),signal.axis.name()).evidence().isEmpty(),"positive category "+signal);
            check(card(view(List.of(observation)),signal.axis.name()).explanation().contains("AFK"),"support notice "+signal);
            check(card(view(List.of(row(2,OTHER,signal,10))),signal.axis.name()).evidence().isEmpty(),"foreign player "+signal);
            check(card(view(List.of(new ActionRecord(1,UUID.randomUUID(),2,observation.event()))),signal.axis.name()).evidence().isEmpty(),"foreign world "+signal);
            check(card(view(List.of(observation,observation)),signal.axis.name()).explanation().contains("기록 1건"),"dedup "+signal);
        }
        rejects(()->new ActivityEvidence(ActivityEvidence.Signal.FISH_CAUGHT,0,0,1),"zero count");
        rejects(()->new ActivityEvidence(ActivityEvidence.Signal.FISH_CAUGHT,1,2,1),"backwards interval");
        rejects(()->ActivityEvidence.parse(Map.of("signal","AFK","count","1","first_tick","0","last_tick","1")),"AFK cannot be persistent evidence");
        rejects(()->ActivityEvidence.parse(Map.of("signal","FISH_CAUGHT","count","1","first_tick","0","last_tick","1","idle_minutes","5")),"extra penalty field");
        check(!PlayerUnderstanding.validFeedbackKey("AFK"),"AFK cannot become an assessed trait");
        var buffer=new ActivityEvidence.Buffer();
        for(int i=0;i<500;i++)buffer.add(ActivityEvidence.Signal.PLACE_BLOCK,1,50+i);
        var buffered=buffer.drain();
        check(buffered.size()==1&&buffered.getFirst().count()==500,"bounded aggregate per signal");
        check(buffered.getFirst().firstTick()==50&&buffered.getFirst().lastTick()==549,"interval retained");
        check(buffer.drain().isEmpty(),"drain does not duplicate");
        check(SupportPresence.resolve(true,true,false,false,false)==SupportPresence.AFK,"idle display");
        check(SupportPresence.resolve(true,true,true,false,false)==SupportPresence.RESTING,"sleep not AFK");
        check(SupportPresence.resolve(true,true,false,true,false)==SupportPresence.WAITING,"fishing wait not AFK");
        check(SupportPresence.resolve(true,true,false,false,true)==SupportPresence.WAITING,"menu wait not AFK");
        check(SupportPresence.resolve(false,false,false,false,false)==SupportPresence.UNKNOWN,"missing sample not inactivity");
        for(var presence:SupportPresence.values())check(presence.notice().contains("감점"),"all states explicitly neutral");
        var all=new ArrayList<ActionRecord>();long sequence=2;
        for(var signal:ActivityEvidence.Signal.values())all.add(row(sequence++,PLAYER,signal,10));
        check(view(all).cards().size()<=15,"activity+raid cards leave at least one purpose slot");
        check(card(view(all),"EXPLORATION").evidence().stream().anyMatch(s->s.contains("탐사 목적 미확인")),"visit evidence never proves exploration preference");
        var compact=row(3,PLAYER,ActivityEvidence.Signal.REMOVE_BLOCK,10);
        var diagnostic=removed(2,"minecraft:overworld",SESSION);
        check(card(view(List.of(diagnostic,compact)),"GATHERING").explanation().contains("기록 1건"),"legacy diagnostic and compact fact not double counted");
        check(!ActivityEvidence.covers(compact,removed(2,"minecraft:the_nether",SESSION),PlayerUnderstanding.Axis.GATHERING),"dedup dimension isolation");
        check(!ActivityEvidence.covers(compact,removed(2,"minecraft:overworld",UUID.randomUUID()),PlayerUnderstanding.Axis.GATHERING),"dedup server session isolation");
        var trial=removed(2,"mythictrpg:raid_trial",SESSION);
        check(!PlayerUnderstanding.supportRecord(trial)&&card(view(List.of(trial)),"GATHERING").evidence().isEmpty(),"development trial dimension excluded from activity projection and index");
        var positive=new RaidChoiceEvidence(UUID.randomUUID(),RaidChoiceEvidence.Kind.SUPPORT,RaidChoiceEvidence.Result.CHOSEN,"phase","v1",false);
        var choice=new ActionRecord(1,WORLD,30,draft(30,PLAYER,ActionRecord.Type.RAID_CHOICE,positive.payload()));
        var base=card(view(List.of(choice)),"RAID_SUPPORT");
        var mixed=new ArrayList<ActionRecord>();mixed.add(choice);
        for(var result:List.of(RaidChoiceEvidence.Result.NOT_CHOSEN,RaidChoiceEvidence.Result.UNAVAILABLE,RaidChoiceEvidence.Result.CANCELLED)) {
            var negative=new RaidChoiceEvidence(UUID.randomUUID(),RaidChoiceEvidence.Kind.SUPPORT,result,"phase","v1",false);
            mixed.add(new ActionRecord(1,WORLD,31+mixed.size(),draft(31+mixed.size(),PLAYER,ActionRecord.Type.RAID_CHOICE,negative.payload())));
        }
        check(base.equals(card(view(mixed),"RAID_SUPPORT")),"absence/AFK/missed opportunities cannot lower or strengthen support evidence");
        var activity=row(2,PLAYER,ActivityEvidence.Signal.PLACE_BLOCK,10);
        var invalidGoal=new PurposeEvidence(WORLD,PLAYER,activity.event().occurrenceId(),2,PurposeLedger.episodeOf(activity),
                new PurposeEvidence.Goal(PurposeEvidence.Kind.MEET,"test:god"),PurposeEvidence.Origin.GAME_ACTION,
                PurposeEvidence.Attribution.DIRECT_SELF,PurposeEvidence.Signal.EXPLORE,PurposeEvidence.Context.DIRECTED,false,false,"building");
        rejects(()->PurposeLedger.validateSource(activity,invalidGoal),"compact activity cannot invent an unrelated meeting goal");
        Path parent=Path.of(args[0]);Files.createDirectories(parent);
        persistence(Files.createTempDirectory(parent,"support-"));
        System.out.println("ActivitySupportTest: PASS ("+checks+" checks); no production server, LLM or graphical UI");
    }
    private static void persistence(Path path)throws Exception {
        var limits=new ActionLedgerStore.Limits(16*1024*1024,65536,5000);
        List<PlayerUnderstanding.Card> baseline;
        try(var store=new ActionLedgerStore(path,WORLD,limits)) {
            store.append(start().event());
            store.append(row(2,PLAYER,ActivityEvidence.Signal.HARVEST_CROP,10).event());
            baseline=PlayerUnderstanding.project(WORLD,PLAYER,store.understanding(PLAYER),false).cards();
            for(int i=3;i<503;i++) {
                store.append(draft(i,PLAYER,ActionRecord.Type.POSITION_SAMPLE,Map.of("coverage","UNCHANGED_ENDPOINTS_NOT_CONTINUOUS_STILLNESS")));
                var result=List.of(RaidChoiceEvidence.Result.NOT_CHOSEN,RaidChoiceEvidence.Result.CANCELLED,RaidChoiceEvidence.Result.UNAVAILABLE).get(i%3);
                var missed=new RaidChoiceEvidence(UUID.randomUUID(),RaidChoiceEvidence.Kind.MECHANIC,result,"phase","v1",false);
                store.append(draft(i+1000,PLAYER,ActionRecord.Type.RAID_CHOICE,missed.payload()));
            }
            check(store.size()==1002,"raw diagnostics preserved, not deleted");
            check(store.understanding(PLAYER).events().size()==1&&!store.understanding(PLAYER).truncated(),"idle diagnostics cannot evict positive bounded-window evidence");
            check(baseline.equals(PlayerUnderstanding.project(WORLD,PLAYER,store.understanding(PLAYER),false).cards()),"profile invariant after long idle/missed-opportunity history");
            var decision=UUID.randomUUID();
            var selected=new RaidChoiceEvidence(decision,RaidChoiceEvidence.Kind.SUPPORT,RaidChoiceEvidence.Result.CHOSEN,"phase","v1",false);
            store.append(draft(2000,PLAYER,ActionRecord.Type.RAID_CHOICE,selected.payload()));
            check(store.understanding(PLAYER).events().size()==2,"positive choice indexed");
            var testCancellation=new RaidChoiceEvidence(decision,RaidChoiceEvidence.Kind.SUPPORT,RaidChoiceEvidence.Result.CANCELLED,"phase","v1",true);
            store.append(draft(2001,PLAYER,ActionRecord.Type.RAID_CHOICE,testCancellation.payload()));
            check(store.understanding(PLAYER).events().size()==2,"test correction cannot invalidate a real choice");
            var cancelled=new RaidChoiceEvidence(decision,RaidChoiceEvidence.Kind.SUPPORT,RaidChoiceEvidence.Result.CANCELLED,"phase","v1",false);
            store.append(draft(2002,PLAYER,ActionRecord.Type.RAID_CHOICE,cancelled.payload()));
            check(baseline.equals(PlayerUnderstanding.project(WORLD,PLAYER,store.understanding(PLAYER),false).cards()),"cancelled source does not remain positive");
        }
        try(var ledger=new AsyncActionLedger(path,WORLD,limits,8)) {
            ledger.ready().get(5,TimeUnit.SECONDS);
            var slice=ledger.understanding(PLAYER).get(5,TimeUnit.SECONDS);
            check(baseline.equals(PlayerUnderstanding.project(WORLD,PLAYER,slice,false).cards()),"support index and facts survive restart");
            check(ledger.understandingControl(PLAYER).get(5,TimeUnit.SECONDS).sequence()==1,"small durable enrollment query");
            check(ledger.understandingControl(OTHER).get(5,TimeUnit.SECONDS)==null,"other player not enrolled");
        }
    }
}
