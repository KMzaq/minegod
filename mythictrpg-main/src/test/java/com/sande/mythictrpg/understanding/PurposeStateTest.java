package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.gameplay.ledger.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.sande.mythictrpg.understanding.PurposeEvidence.*;
import static com.sande.mythictrpg.understanding.PurposeState.*;

/** Source/scope/state/persistence tests, not a psychological validity or live dialogue claim. */
public final class PurposeStateTest {
    private static final UUID W = UUID.randomUUID(), P = UUID.randomUUID(), SESSION = UUID.randomUUID();
    private static final Goal GOAL = new Goal(Kind.MEET, "test:person");
    private static final ActionLedgerStore.Limits LIMITS = new ActionLedgerStore.Limits(16*1024*1024,65536,10000);
    private static int checks;
    private static UUID id(long n) { return new UUID(0,n); }
    private static PurposeEvidence e(long n,long episode,Signal signal) {
        boolean action = Set.of(Signal.CHOOSE,Signal.EXPLORE,Signal.PREPARE,Signal.PURSUE,Signal.COMPLETE).contains(signal);
        return new PurposeEvidence(W,P,id(n),n,id(episode+10000),GOAL,action?Origin.GAME_ACTION:Origin.SELF_SPEECH,
                Attribution.DIRECT_SELF,signal,Context.DIRECTED,false,false,"fixture "+n);
    }
    private static PurposeEvidence scope(PurposeEvidence e,UUID w,UUID p,Attribution a,Context c,boolean test) {
        return new PurposeEvidence(w,p,e.sourceId(),e.sourceOrder(),e.episodeId(),e.goal(),e.origin(),a,e.signal(),c,false,test,e.sourceDescription());
    }
    private static State state(PurposeEvidence... rows) { return project(W,P,0,List.of(rows),true,x->true).goals().getFirst(); }
    private static void check(boolean ok,String why) { checks++; if(!ok)throw new AssertionError(why); }
    private static void rejects(Runnable work,String why) { try { work.run();throw new AssertionError(why); } catch(IllegalArgumentException expected){checks++;} }
    public static void main(String[] args)throws Exception {
        var wish=e(1,1,Signal.DESIRE);
        check(state(wish).statedIntention()==Intention.DESIRED,"single wish acknowledged immediately");
        check(state(wish).observedProgress()==Progress.NONE,"speech does not perform action");
        var repeated=new ArrayList<PurposeEvidence>();for(int n=1;n<=30;n++)repeated.add(e(n,n,Signal.DESIRE));
        var many=project(W,P,0,repeated,true,x->true).goals().getFirst();
        check(many.statedIntention()==Intention.DESIRED && many.strength()==Strength.REPEATED,"thirty wishes never become decision");
        check(many.observedProgress()==Progress.NONE,"repeated speech never proves pursuit");
        check(state(wish,wish,wish).independentEpisodes()==1,"same occurrence counted once");
        check(state(wish,e(2,1,Signal.DESIRE),e(3,1,Signal.ASK)).strength()==Strength.LIMITED,"same episode repetition capped");
        check(state(e(1,1,Signal.DECIDE)).statedIntention()==Intention.DECIDED,"decision does not require repeat");
        check(state(e(1,1,Signal.DECIDE),e(2,2,Signal.DESIRE)).statedIntention()==Intention.DESIRED,"latest meaning, not maximum ever");
        check(state(wish,e(2,2,Signal.CHOOSE),e(3,3,Signal.EXPLORE)).inferredPursuit(),"independent cross-behavior convergence");
        check(state(wish,e(2,2,Signal.CHOOSE),e(3,3,Signal.EXPLORE)).statedIntention()==Intention.DESIRED,"actions not invented declaration");
        check(!state(e(1,1,Signal.CHOOSE),e(2,1,Signal.EXPLORE)).inferredPursuit(),"one event cannot manufacture convergence");
        check(!state(e(1,1,Signal.EXPLORE),e(2,2,Signal.EXPLORE)).inferredPursuit(),"repeated path alone remains ambiguous");
        for(Context context:List.of(Context.AMBIGUOUS,Context.FORCED,Context.SYSTEM_SUGGESTED,Context.MISSING)) {
            var row=scope(e(2,2,Signal.EXPLORE),W,P,Attribution.DIRECT_SELF,context,false);
            check(state(wish,row).strength()==Strength.LIMITED && state(wish,row).observedProgress()==Progress.NONE,"no confidence from "+context);
        }
        for(Attribution attribution:List.of(Attribution.OTHER_PERSON,Attribution.QUOTED,Attribution.HYPOTHETICAL,Attribution.UNRESOLVED))
            check(project(W,P,0,List.of(scope(wish,W,P,attribution,Context.DIRECTED,false)),true,x->true).goals().isEmpty(),"no own goal from "+attribution);
        check(project(W,P,0,List.of(scope(wish,UUID.randomUUID(),P,Attribution.DIRECT_SELF,Context.DIRECTED,false)),true,x->true).goals().isEmpty(),"world isolation");
        check(project(W,P,0,List.of(scope(wish,W,UUID.randomUUID(),Attribution.DIRECT_SELF,Context.DIRECTED,false)),true,x->true).goals().isEmpty(),"player isolation");
        check(project(W,P,0,List.of(scope(wish,W,P,Attribution.DIRECT_SELF,Context.DIRECTED,true)),true,x->true).goals().isEmpty(),"test exclusion");
        check(project(W,P,1,List.of(wish),true,x->true).goals().isEmpty(),"start boundary");
        check(project(W,P,0,List.of(wish),true,x->false).goals().isEmpty(),"NPC cannot read SELF");
        var revoked=project(W,P,0,List.of(wish,e(2,2,Signal.CHOOSE),e(3,3,Signal.EXPLORE)),false,x->x.sourceOrder()==1);
        check(!revoked.complete() && revoked.goals().getFirst().strength()==Strength.LIMITED,"filter before aggregate and retain incomplete marker");
        for(Signal control:List.of(Signal.WITHDRAW,Signal.DISOWN,Signal.PAUSE)) {
            var stopped=state(wish,e(2,2,control),e(3,3,Signal.EXPLORE),e(4,4,Signal.CHOOSE));
            check(stopped.lifecycle()!=Lifecycle.ACTIVE && stopped.observedProgress()==Progress.NONE,"old goal stays stopped "+control);
            check(stopped.conflictingEvidence() && stopped.counterevidence().contains(id(1)),"facts retained without resurrection");
            var resumed=state(wish,e(2,2,control),e(3,3,Signal.DESIRE));
            check(resumed.lifecycle()==Lifecycle.ACTIVE && resumed.independentEpisodes()==1,"explicit reopening uses new evidence");
        }
        var complete=state(e(1,1,Signal.COMPLETE));
        check(complete.lifecycle()==Lifecycle.ACHIEVED && complete.statedIntention()==Intention.UNKNOWN,"completion does not invent motivation");
        var other=new PurposeEvidence(W,P,id(2),2,id(2),new Goal(Kind.LEARN_ABOUT,"test:other"),Origin.SELF_SPEECH,
                Attribution.DIRECT_SELF,Signal.DECIDE,Context.DIRECTED,false,false,"");
        check(project(W,P,0,List.of(wish,other),true,x->true).goals().size()==2,"goals coexist");
        var shuffled=new ArrayList<>(repeated);Collections.reverse(shuffled);
        check(project(W,P,0,shuffled,true,x->true).equals(project(W,P,0,repeated,true,x->true)),"out of order completion deterministic");
        var conflict=new PurposeEvidence(W,P,wish.sourceId(),1,wish.episodeId(),GOAL,Origin.SELF_SPEECH,
                Attribution.DIRECT_SELF,Signal.DECIDE,Context.DIRECTED,false,false,wish.sourceDescription());
        rejects(()->state(wish,conflict),"conflicting duplicate not last-writer-wins");
        rejects(()->new PurposeEvidence(W,P,id(1),1,id(1),GOAL,Origin.SELF_SPEECH,Attribution.DIRECT_SELF,
                Signal.COMPLETE,Context.DIRECTED,false,false,""),"speech cannot complete");
        rejects(()->new PurposeEvidence(W,P,id(1),1,id(1),GOAL,Origin.SELF_SPEECH,Attribution.DIRECT_SELF,
                Signal.DESIRE,Context.DIRECTED,true,false,""),"model cannot mark dramatic speech a game choice");
        Path root=Files.createTempDirectory(Path.of(args[0]),"purpose-");persistence(root);
        System.out.println("PurposeStateTest: PASS ("+checks+" checks); synthetic authority fixtures, no live server; artifacts="+root);
    }
    private static ActionRecord.Draft draft(long n,ActionRecord.Type type,Map<String,String> payload) {
        String kind=type==ActionRecord.Type.POSITION_SAMPLE?"LOCATION":"ACTIVITY";
        return new ActionRecord.Draft(id(n),SESSION,n,"mythictrpg:detail/"+type.name().toLowerCase(Locale.ROOT),1,P,
                new ActionRecord.Subject(kind,kind.equals("ACTIVITY")?"mythictrpg:player_understanding":"minecraft:overworld",null),
                1800000000000L+n,n,0,"minecraft:overworld",new ActionRecord.Position(0,64,0),type,"COMPLETED",payload,"mythictrpg:admin_only_unprojected");
    }
    private static void persistence(Path root)throws Exception {
        SliceHolder holder=new SliceHolder();
        try(var store=new ActionLedgerStore(root,W,LIMITS)) {
            store.append(draft(100,ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,Map.of("operation","START")));
            var source=store.append(draft(101,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,Map.of("key","purpose","operation","DECLARE","text","만나고 싶어")));
            var ev=new PurposeEvidence(W,P,source.event().occurrenceId(),source.sequence(),PurposeLedger.episodeOf(source),GOAL,Origin.SELF_SPEECH,
                    Attribution.DIRECT_SELF,Signal.DESIRE,Context.DIRECTED,false,false,"만나고 싶어");
            var annotation=PurposeLedger.annotate(source,ev);var saved=store.append(annotation);
            check(store.append(annotation).equals(saved),"durable idempotency");
            check(store.purposes(P).evidence().size()==1,"one indexed interpretation");
            check(!store.purposes(UUID.randomUUID()).active(),"other player has no enrollment");
            holder.value=store.purposes(P).project(W,P,x->true);
            var bad=scope(ev,W,UUID.randomUUID(),Attribution.DIRECT_SELF,Context.DIRECTED,false);
            rejects(()->PurposeLedger.annotate(source,bad),"cannot reattribute raw source");
            var action=store.append(draft(102,ActionRecord.Type.POSITION_SAMPLE,Map.of("test","true")));
            var test=new PurposeEvidence(W,P,action.event().occurrenceId(),action.sequence(),PurposeLedger.episodeOf(action),GOAL,Origin.GAME_ACTION,
                    Attribution.DIRECT_SELF,Signal.EXPLORE,Context.DIRECTED,false,false,"");
            rejects(()->PurposeLedger.annotate(action,test),"cannot launder test source");
            check(store.find(source.event().occurrenceId()).orElseThrow().equals(source),"annotation does not replace raw fact");
        }
        try(var ledger=new AsyncActionLedger(root,W,LIMITS,8)) {
            ledger.ready().get(10,TimeUnit.SECONDS);
            check(ledger.purposes(P).get(5,TimeUnit.SECONDS).project(W,P,x->true).equals(holder.value),"restart reconstructs same source ordered state");
            var snapshot=ledger.understandingSnapshot(P).get(5,TimeUnit.SECONDS);
            var base=PlayerUnderstanding.project(W,P,snapshot.activity(),false);
            var view=PurposeSelfView.append(base,W,P,snapshot.purposes());
            var card=view.cards().stream().filter(c->c.key().startsWith("purpose_")).findFirst().orElseThrow();
            check(card.assessment().contains("희망")&&!card.assessment().contains("결심"),"self screen preserves wish stage");
            check(card.evidence().size()==1&&card.explanation().contains("자동 공유하지"),"self evidence and privacy displayed");
            new UnderstandingPayload(UUID.randomUUID(),true,true,false,view.notice(),view.cards());
            check(snapshot.activity().head()==snapshot.purposes().head(),"atomic same-worker self-view fence");
            var original=snapshot.purposes().evidence().getFirst();
            check(ledger.annotatePurpose(original).get(5,TimeUnit.SECONDS).event().type()==ActionRecord.Type.PLAYER_PURPOSE_EVIDENCE,"safe async candidate idempotency");
            var invalidEpisode=new PurposeEvidence(W,P,original.sourceId(),original.sourceOrder(),UUID.randomUUID(),original.goal(),original.origin(),
                    original.attribution(),original.signal(),original.context(),false,false,original.sourceDescription());
            try {ledger.annotatePurpose(invalidEpisode).get(5,TimeUnit.SECONDS);throw new AssertionError("invented episode accepted");}
            catch(java.util.concurrent.ExecutionException expected){checks++;}
            check(ledger.status().state()==AsyncActionLedger.State.READY,"invalid candidate does not kill raw recording worker");
            ledger.submit(draft(199,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,Map.of("key","purpose","operation","RETRACT","text",""))).durable().get(5,TimeUnit.SECONDS);
            check(ledger.purposes(P).get(5,TimeUnit.SECONDS).project(W,P,x->true).goals().getFirst().lifecycle()==Lifecycle.WITHDRAWN,"self UI retraction invalidates old typed hypotheses");
            ledger.submit(draft(200,ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,Map.of("operation","PAUSE"))).durable().get(5,TimeUnit.SECONDS);
            check(!ledger.purposes(P).get(5,TimeUnit.SECONDS).active(),"pause hides purpose projection");
            ledger.submit(draft(201,ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,Map.of("operation","START"))).durable().get(5,TimeUnit.SECONDS);
            check(ledger.purposes(P).get(5,TimeUnit.SECONDS).evidence().isEmpty(),"new start cannot import old notes");
        }
    }
    private static final class SliceHolder { Projection value; }
}
