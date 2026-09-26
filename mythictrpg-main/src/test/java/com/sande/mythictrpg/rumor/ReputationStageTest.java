package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.objectweb.asm.*;
import java.util.*;
import static com.sande.mythictrpg.rumor.ReputationLedger.*;

/** No game bootstrap, server, model calls or gameplay effects. Numeric policies are synthetic fixtures only. */
public final class ReputationStageTest {
    static int checks;
    static final UUID WORLD=new UUID(7,1),A=new UUID(7,2),B=new UUID(7,3);
    static final String GOD="mythictrpg:fortuna",OTHER="mythictrpg:demeter";
    static final ReputationSettings.Rule NEG=new ReputationSettings.Rule("test:negative","test:courier",GOD,-30,-1000,500);
    static final ReputationSettings.Rule POS=new ReputationSettings.Rule("test:positive","test:courier",GOD,40,-1000,1000);
    static final ReputationSettings SETTINGS=new ReputationSettings(1,true,50,60,List.of(NEG,POS));
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void rejects(Runnable action,String label){try{action.run();throw new AssertionError(label);}catch(IllegalArgumentException expected){checks++;}}
    static class Fixture {
        ReputationLedger ledger=new ReputationLedger(WORLD);Map<UUID,ReputationEngine.Evidence> evidence=new HashMap<>();
        ReputationEngine engine(){return new ReputationEngine(ledger,SETTINGS,(s,g,r)->Optional.ofNullable(evidence.get(r)));}
        Decision create(ReputationSettings.Rule rule){
            UUID source=UUID.randomUUID(),root=UUID.randomUUID();
            evidence.put(root,new ReputationEngine.Evidence(WORLD,root,1,A,GOD,source,"test:courier"));
            return new Decision(UUID.randomUUID(),WORLD,A,GOD,source,root,1,rule.id(),rule.fingerprint(),0,Outcome.ACCEPTED,DirectImpact.NOT_APPLIED,approval());
        }
        void restart(){ledger=ReputationLedger.restore(new Gson().fromJson(new Gson().toJson(ledger.snapshot()),Snapshot.class));}
    }
    static Approval approval(){return new Approval(UUID.randomUUID(),ApprovalKind.ADMIN_REVIEW,"test:verified_review");}
    static Decision change(Decision d,long expected,Outcome outcome,DirectImpact direct){
        return new Decision(UUID.randomUUID(),d.worldId(),d.subject(),d.godId(),d.sourceId(),d.rootId(),d.rumorRevision(),d.policyId(),d.policyFingerprint(),expected,outcome,direct,approval());
    }
    public static void main(String[] args)throws Exception {settings();assessments();guards();recovery();dialogueRecovery();persistence();capacity();pointQueries();proofPointQueries();wiring();
        System.out.println("ReputationStageTest: "+checks+" assertions passed; OFFLINE judgment foundation, not live gameplay");}
    static void settings(){
        check(ReputationSettings.load(java.nio.file.Path.of("absent-reputation-settings.json")).equals(ReputationSettings.OFF),"absent config OFF");
        rejects(()->new ReputationSettings(1,true,0,0,List.of()),"cannot silently enable missing content");
        rejects(()->new ReputationSettings(1,true,50,60,List.of(NEG,NEG)),"unique policies");
        rejects(()->new ReputationSettings.Rule("test:x","test:courier",GOD,1001,-1000,1000),"technical clamp");
        check(new Gson().fromJson(new Gson().toJson(SETTINGS),ReputationSettings.class).equals(SETTINGS),"authored settings roundtrip");
        var f=new Fixture();var d=f.create(NEG);var off=new ReputationEngine(f.ledger,ReputationSettings.OFF,(s,g,r)->Optional.ofNullable(f.evidence.get(r)));
        check(off.applyApproved(d)==Result.REJECTED&&f.ledger.size()==0,"OFF refuses writes");
        check(off.preview(A,GOD,150).equals(ReputationEngine.View.inactive(ReputationEngine.Status.OFF,150)),"OFF original relationship unchanged");
    }
    static void assessments(){
        var f=new Fixture();var d=f.create(NEG);
        check(f.engine().preview(A,GOD,100).modifier()==0,"receipt alone has no effect without assessment");
        check(f.engine().applyApproved(d)==Result.APPLIED,"explicit game-approved assessment");
        var view=f.engine().preview(A,GOD,100);check(view.baseAffinity()==100&&view.modifier()==-30&&view.judgementAffinity()==70,"base separate from computed judgment");
        long revision=f.ledger.revision();for(int i=0;i<30;i++)check(f.engine().preview(A,GOD,100).equals(view),"repeated recall cannot accumulate deduction");
        check(f.ledger.revision()==revision,"preview never writes");
        check(f.engine().applyApproved(d)==Result.DUPLICATE&&f.ledger.revision()==revision,"retry idempotent");
        check(f.engine().applyApproved(change(d,0,Outcome.ACCEPTED,DirectImpact.NOT_APPLIED))==Result.STALE,"late parallel decision rejected");
        var alternate=new Decision(UUID.randomUUID(),WORLD,A,GOD,d.sourceId(),UUID.randomUUID(),1,NEG.id(),NEG.fingerprint(),1,Outcome.ACCEPTED,DirectImpact.NOT_APPLIED,approval());
        f.evidence.put(alternate.rootId(),new ReputationEngine.Evidence(WORLD,alternate.rootId(),1,A,GOD,d.sourceId(),"test:courier"));
        check(f.engine().applyApproved(alternate)==Result.REJECTED,"same source in another rumor cannot stack");
        check(f.engine().preview(B,GOD,100).modifier()==0&&f.engine().preview(A,OTHER,100).modifier()==0,"player and god isolation");
        check(f.engine().preview(A,GOD,501).modifier()==0,"authored direct-relationship boundary respected");
        var d2=f.create(NEG);f.engine().applyApproved(d2);check(f.engine().preview(A,GOD,100).modifier()==-50,"negative cap");
        f.engine().applyApproved(f.create(POS));f.engine().applyApproved(f.create(POS));check(f.engine().preview(A,GOD,100).modifier()==10,"independent positive/negative caps");
        check(f.engine().preview(A,GOD,999).judgementAffinity()==1000,"overall judgment bounded to affinity domain");
        var scope=new ReputationService.Judgement(A,ResourceLocation.parse(GOD),Set.of(A),WORLD,view);
        check(!scope.equals(new ReputationService.Judgement(B,ResourceLocation.parse(GOD),Set.of(B),WORLD,view)),"snapshot cannot cross player");
        check(!scope.equals(new ReputationService.Judgement(A,ResourceLocation.parse(OTHER),Set.of(A),WORLD,view)),"snapshot cannot cross god");
        check(!scope.equals(new ReputationService.Judgement(A,ResourceLocation.parse(GOD),Set.of(A,B),WORLD,view)),"snapshot cannot cross audience");
    }
    static void guards(){
        var f=new Fixture();var d=f.create(NEG);var original=f.evidence.remove(d.rootId());
        check(f.engine().applyApproved(d)==Result.REJECTED,"undelivered rumor cannot create assessment");
        for(var bad:List.of(new ReputationEngine.Evidence(UUID.randomUUID(),d.rootId(),1,A,GOD,d.sourceId(),"test:courier"),
                new ReputationEngine.Evidence(WORLD,d.rootId(),2,A,GOD,d.sourceId(),"test:courier"),
                new ReputationEngine.Evidence(WORLD,d.rootId(),1,B,GOD,d.sourceId(),"test:courier"),
                new ReputationEngine.Evidence(WORLD,d.rootId(),1,A,OTHER,d.sourceId(),"test:courier"),
                new ReputationEngine.Evidence(WORLD,d.rootId(),1,A,GOD,UUID.randomUUID(),"test:courier"),
                new ReputationEngine.Evidence(WORLD,d.rootId(),1,A,GOD,d.sourceId(),"test:other"))) {
            f.evidence.put(d.rootId(),bad);check(f.engine().applyApproved(d)==Result.REJECTED,"forged scope/provenance rejected");
        }
        f.evidence.put(d.rootId(),original);f.engine().applyApproved(d);
        f.evidence.clear();check(f.engine().preview(A,GOD,100).modifier()==0,"revoked evidence removes effect without refund subtraction");
        f.evidence.put(d.rootId(),original);
        var changed=new ReputationSettings(1,true,50,60,List.of(new ReputationSettings.Rule(NEG.id(),NEG.courierRuleId(),GOD,-31,-1000,500)));
        check(new ReputationEngine(f.ledger,changed,(s,g,r)->Optional.ofNullable(f.evidence.get(r))).preview(A,GOD,100).modifier()==0,"changed policy requires new assessment");
        for(var direct:List.of(DirectImpact.ALREADY_APPLIED,DirectImpact.UNKNOWN)) {
            var old=f.ledger.find(A,GOD,d.sourceId());check(f.engine().applyApproved(change(d,old.version(),Outcome.ACCEPTED,direct))==Result.APPLIED,"game direct-impact decision recorded");
            check(f.engine().preview(A,GOD,100).modifier()==0,"no double counting or unknown direct impact");
        }
    }
    static void recovery(){
        for(var outcome:List.of(Outcome.DOUBTFUL,Outcome.IGNORED,Outcome.DISPUTED,Outcome.RECOVERED,Outcome.RETRACTED)) {
            var f=new Fixture();var d=f.create(NEG);f.engine().applyApproved(d);
            var remove=change(d,1,outcome,DirectImpact.NOT_APPLIED);check(f.engine().applyApproved(remove)==Result.APPLIED,"record reviewed outcome "+outcome);
            check(f.engine().preview(A,GOD,100).modifier()==0,"review removes modifier "+outcome);
            f.restart();check(f.engine().preview(A,GOD,100).modifier()==0,"review survives restart "+outcome);
            check(f.engine().applyApproved(remove)==Result.DUPLICATE,"review retry once "+outcome);
            var replay=change(d,2,Outcome.ACCEPTED,DirectImpact.NOT_APPLIED);
            if(outcome==Outcome.RECOVERED||outcome==Outcome.RETRACTED)check(f.engine().applyApproved(replay)==Result.TERMINAL,"recovery cannot be undone by late reassessment");
            check(f.evidence.containsKey(d.rootId()),"recovery never deletes source history");
        }
        var f=new Fixture();var d=f.create(NEG);f.engine().applyApproved(d);f.evidence.clear();
        check(f.engine().applyApproved(change(d,1,Outcome.RETRACTED,DirectImpact.UNKNOWN))==Result.APPLIED,"administrative removal works with revoked evidence");
    }
    static void persistence(){
        var f=new Fixture();var d=f.create(NEG);f.engine().applyApproved(d);f.restart();
        check(f.engine().preview(A,GOD,100).modifier()==-30&&f.engine().applyApproved(d)==Result.DUPLICATE,"restart keeps exactly-once assessment");
        var tag=new CompoundTag();tag.putInt("dataVersion",1);tag.putString("state",new Gson().toJson(f.ledger.snapshot()));
        var data=ReputationSavedData.load(tag,null);check(data.ready(WORLD)&&data.save(new CompoundTag(),null).equals(tag),"NBT roundtrip");
        check(!data.ready(UUID.randomUUID())&&data.save(new CompoundTag(),null).equals(tag),"wrong world unavailable and preserved");
        for(var bad:List.of(future(tag),corrupt(tag))) {
            var rejected=ReputationSavedData.load(bad,null);check(!rejected.ready(WORLD)&&rejected.save(new CompoundTag(),null).equals(bad),"corrupt or future NBT quarantined intact");
        }
        var state=f.ledger.snapshot();rejects(()->ReputationLedger.restore(new Snapshot(1,WORLD,state.revision(),List.of(state.entries().getFirst(),state.entries().getFirst()))),"duplicate saved assessment rejected");
        rejects(()->ReputationLedger.restore(new Snapshot(1,WORLD,state.revision()+1,state.entries())),"invalid saved revision rejected");
    }
    static void dialogueRecovery(){
        var f=new Fixture();var d=f.create(NEG);f.engine().applyApproved(d);var entry=f.ledger.find(A,GOD,d.sourceId());
        var context=new com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext(WORLD,UUID.randomUUID(),UUID.randomUUID(),A,GOD,Set.of(A),true);
        var proposal=new DialogueRecovery.Proposal(UUID.randomUUID(),context,3,d.rootId(),1,"그 부탁은 제작 재료가 필요해서 한 말이었어.");
        check(DialogueRecovery.eligible(proposal,context,3,entry),"dialogue explanation can enter game review");
        check(!DialogueRecovery.eligible(proposal,context,4,entry)&&!DialogueRecovery.eligible(proposal,null,3,entry),"late turn/closed conversation rejected");
        var otherSession=new com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext(WORLD,UUID.randomUUID(),context.generation(),A,GOD,Set.of(A),true);
        var otherAudience=new com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext(WORLD,context.interactionId(),context.generation(),A,GOD,Set.of(A,B),true);
        check(!DialogueRecovery.eligible(proposal,otherSession,3,entry)&&!DialogueRecovery.eligible(proposal,otherAudience,3,entry),"session and disclosure rechecked");
        check(!DialogueRecovery.eligible(new DialogueRecovery.Proposal(UUID.randomUUID(),context,3,UUID.randomUUID(),1,"설명"),context,3,entry),"wrong rumor cannot be cleared");
        check(f.engine().preview(A,GOD,100).modifier()==-30,"proposal alone is not recovery");
        var approved=new Decision(proposal.id(),WORLD,A,GOD,d.sourceId(),d.rootId(),1,NEG.id(),NEG.fingerprint(),1,Outcome.RECOVERED,DirectImpact.NOT_APPLIED,
                new Approval(proposal.id(),ApprovalKind.DIALOGUE_REVIEW,"test:contextual_persuasion"));
        check(f.engine().applyApproved(approved)==Result.APPLIED&&f.engine().preview(A,GOD,100).modifier()==0,"approved persuasive dialogue can recover");
        check(!DialogueRecovery.eligible(proposal,context,3,f.ledger.find(A,GOD,d.sourceId())),"same dialogue cannot be applied twice");
        f.restart();check(f.ledger.find(A,GOD,d.sourceId()).decision().approval().kind()==ApprovalKind.DIALOGUE_REVIEW,"dialogue review provenance persists without storing explanation");
    }
    static CompoundTag future(CompoundTag tag){var out=tag.copy();out.putInt("dataVersion",99);return out;}
    static CompoundTag corrupt(CompoundTag tag){var out=tag.copy();out.putString("state","{not-json");return out;}
    static void capacity(){
        var f=new Fixture();Decision first=null;
        for(int i=0;i<LIMIT;i++){var d=f.create(NEG);if(first==null)first=d;check(f.ledger.apply(d)==Result.APPLIED,"bounded assessment insert");}
        check(f.ledger.apply(f.create(NEG))==Result.CAPACITY&&f.ledger.size()==LIMIT,"capacity never deletes old receipt");
        check(f.engine().applyApproved(change(first,1,Outcome.RECOVERED,DirectImpact.NOT_APPLIED))==Result.APPLIED,"full capacity cannot block recovery");
        f.restart();check(f.ledger.find(A,GOD,first.sourceId()).terminal(),"terminal recovery retained at capacity after restart");
    }
    static void pointQueries(){
        var rumors=new RumorLedger();var courier=UUID.randomUUID();rumors.bindCourier(A,courier);UUID first=null;
        for(int i=0;i<70;i++){var root=UUID.randomUUID();if(first==null)first=root;rumors.observe(root,A,courier,Set.of(A),"fixture",Set.of(GOD),Set.of(A));rumors.publish(root,"fixture","");rumors.deliver(rumors.pending().getFirst());}
        check(rumors.heard(A,GOD,Set.of(A)).size()==64&&rumors.heardOne(A,GOD,first,Set.of(A)).isPresent(),"judgment independent of prompt top64");
        var engine=new CourierEngine(rumors,CourierSettings.OFF,new CourierEngine.Probe(){
            public boolean available(UUID e,UUID s){return true;}public boolean witnessed(UUID c,CourierEngine.Event e,CourierSettings.Rule r){return false;}public boolean receiverAvailable(String g){return true;}
        },()->1L);
        check(engine.heardOne(A,GOD,first,Set.of(A)).isPresent(),"point route preserves legacy read contract");
        check(engine.heardOne(A,GOD,first,Set.of(A,B)).isEmpty()&&engine.heardOne(B,GOD,first,Set.of(B)).isEmpty(),"point route audience and player isolation");
        rumors.courierDied(courier);check(engine.heardOne(A,GOD,first,Set.of(A)).isPresent(),"courier kill not retroactive reputation erasure");
        rumors.revoke(first);check(engine.heardOne(A,GOD,first,Set.of(A)).isEmpty(),"point route revocation");
    }
    static void wiring()throws Exception {
        var calls=new HashSet<String>();
        try(var in=ReputationStageTest.class.getResourceAsStream("ReputationService.class")){
            new ClassReader(Objects.requireNonNull(in)).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int a,String n,String d,String sig,String[] ex){return new MethodVisitor(Opcodes.ASM9){
                    @Override public void visitMethodInsn(int op,String owner,String name,String desc,boolean it){calls.add(owner+"."+name);}
                };}
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        check(calls.stream().noneMatch(s->s.contains(".adjustAffinity")||s.contains(".setAffinity")||s.contains(".grantQuestAffinity")||s.contains("QuestRuntime")||s.contains("Reward")||s.contains("HttpClient")||s.contains(".addFreshEntity")),"no permanent affinity/quest/reward/model/spawn effects");
        check(calls.stream().anyMatch(s->s.endsWith(".requireThread")),"server thread authority");
        check(calls.stream().anyMatch(s->s.endsWith(".heardOne")),"real received source adapter not AI text");
        check(calls.stream().anyMatch(s->s.endsWith(".review"))&&calls.stream().anyMatch(s->s.endsWith(".currentTurn"))&&calls.stream().anyMatch(s->s.endsWith(".memoryContextCurrent")),"dialogue review requires game reviewer and fresh context/turn");
    }
    static void proofPointQueries(){
        var ledger=new RumorLedger();var courier=UUID.randomUUID();
        var rule=new CourierSettings.Rule("test:courier","test:event",CourierSettings.Source.GAME_EVENT,"minecraft:overworld",16,1,200,
                Map.of(GOD,CourierSettings.Reception.CAUTIOUS,OTHER,CourierSettings.Reception.IGNORE),CourierSettings.Publication.AUTHORED,"fixture","");
        var settings=new CourierSettings(1,true,false,false,2,Set.of("test:courier"),List.of(rule));
        var probe=new CourierEngine.Probe(){public boolean available(UUID e,UUID p){return true;}public boolean witnessed(UUID c,CourierEngine.Event e,CourierSettings.Rule r){return true;}public boolean receiverAvailable(String g){return true;}};
        var engine=new CourierEngine(ledger,settings,probe,()->1L);engine.bind(A,courier);
        var root=engine.observe(new CourierEngine.Event(UUID.randomUUID(),1,A,Set.of(A),Set.of(A),"test:event",CourierSettings.Source.GAME_EVENT,"minecraft:overworld",0,64,0,"fixture",1,1)).getFirst().rootId();engine.deliver();
        check(engine.heardOne(A,GOD,root,Set.of(A)).equals(Optional.of(engine.heard(A,GOD,Set.of(A)).getFirst())),"point query preserves witnessed reception policy");
        check(engine.heardOne(A,OTHER,root,Set.of(A)).isEmpty(),"IGNORE cannot become relation judgment");
        check(new CourierEngine(ledger,CourierSettings.OFF,probe,()->1L).heardOne(A,GOD,root,Set.of(A)).isEmpty(),"disabled courier policy cannot feed reputation");
        engine.confirmedDeath(courier);check(engine.heardOne(A,GOD,root,Set.of(A)).isPresent(),"delivered proof survives subject courier death");
        ledger.revoke(root);check(engine.heardOne(A,GOD,root,Set.of(A)).isEmpty(),"revoked witnessed proof disappears");
    }
}
