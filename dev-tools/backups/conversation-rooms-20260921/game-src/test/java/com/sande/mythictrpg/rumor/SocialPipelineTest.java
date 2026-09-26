package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext;
import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import com.sande.mythictrpg.gameplay.watch.WatchContract.Area;
import org.objectweb.asm.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.sande.mythictrpg.rumor.ReputationLedger.*;

/** Actual engines + fake semantic provider. No Minecraft bootstrap/server, model or network. */
public final class SocialPipelineTest {
    static int checks;
    static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
    static void rejects(Runnable r,String label){try{r.run();throw new AssertionError(label);}catch(IllegalArgumentException expected){checks++;}}
    static SocialReview.Answer answer(SocialReview.Verdict v,String quote,String claim){return new SocialReview.Answer(v,quote,claim,"","contextual reasoning",false,false);}
    public static void main(String[] args)throws Exception {contracts();flow();visibility();wiring();System.out.println("SocialPipelineTest: "+checks+" assertions passed (offline fake semantic decisions)");}
    static void contracts(){
        check(!SocialSettings.OFF.enabled()&&SocialSettings.OFF.ordinaryDialogueObservable(),"normal dialogue observation policy chosen; operation OFF");
        check(new Gson().fromJson(new Gson().toJson(SocialSettings.OFF),SocialSettings.class).equals(SocialSettings.OFF),"settings roundtrip");
        var area=new Area("minecraft:overworld",0,0,0,10,100,10);
        check(SocialSettings.inside(area,"minecraft:overworld",0,64,10),"inclusive barrier");
        check(!SocialSettings.inside(area,"minecraft:the_nether",0,64,10)&&!SocialSettings.inside(area,"minecraft:overworld",11,64,10),"dimension/place scoped");
        rejects(()->new SocialSettings.EventRoute(ActionRecord.Type.ENTITY_KILLED,"minecraft:zombie","COMPLETED","test:event","killed"),"routine mobs not projected");
        rejects(()->new SocialSettings.EventRoute(ActionRecord.Type.QUEST_TRANSITION,"test:quest","","test:event","done"),"exact public transition required");
        var event=event(ActionRecord.Type.QUEST_TRANSITION,"test:quest",Map.of("transition","COMPLETED"));
        var route=new SocialSettings.EventRoute(ActionRecord.Type.QUEST_TRANSITION,"test:quest","COMPLETED","test:event","작성된 공개 완료 사실");
        check(route.matches(event),"explicit public mapping");
        check(!route.matches(event(ActionRecord.Type.QUEST_TRANSITION,"test:secret",Map.of("transition","COMPLETED"))),"private unlisted quest excluded");
        check(!route.matches(event(ActionRecord.Type.QUEST_TRANSITION,"test:quest",Map.of("transition","ASSIGNED"))),"assignment not completion");
        var request=new SocialReview.Request(UUID.randomUUID(),SocialReview.Kind.RUMOR,"",0,"플레이어 발언과 공개 답변",List.of(),"");
        check(SocialReview.valid(request,answer(SocialReview.Verdict.PUBLISH,request.evidence(),"전언")),"grounded proposal");
        check(!SocialReview.valid(request,answer(SocialReview.Verdict.PUBLISH,"fabricated","전언")),"quote mismatch rejected");
        check(!SocialReview.valid(request,new SocialReview.Answer(SocialReview.Verdict.PUBLISH,request.evidence(),"전언","","reason",false,true)),"multi-subject rumor rejected");
        check(!SocialReview.valid(request,new SocialReview.Answer(SocialReview.Verdict.PUBLISH,request.evidence(),"전언","","reason",true,false)),"unverified achievement claim rejected");
        check(!SocialReview.valid(request,answer(SocialReview.Verdict.RECOVER,request.evidence(),"")),"cross-purpose answer rejected");
        check(SocialReview.valid(request,answer(SocialReview.Verdict.SKIP,"","")),"not every conversation becomes rumor");
        rejects(()->new SocialReview.Request(UUID.randomUUID(),SocialReview.Kind.RECOVERY,"",0,"",List.of(),""),"recovery requires a specific god");
        rejects(()->new SocialReview.Answer(SocialReview.Verdict.PUBLISH,"","x".repeat(301),"","",false,false),"bounded candidate");
        var recovery=new SocialReview.Request(UUID.randomUUID(),SocialReview.Kind.RECOVERY,"test:god",25,"old claim",
                List.of(new SocialReview.Line("PLAYER","제작 재료가 필요해서 부탁한 거였어"),new SocialReview.Line("GOD","알겠다")),"");
        check(SocialReview.valid(recovery,answer(SocialReview.Verdict.RECOVER,"제작 재료가 필요해서","")),"paraphrase-friendly explanation with exact support");
        check(!SocialReview.valid(recovery,answer(SocialReview.Verdict.RECOVER,"알겠다","")),"NPC agreement alone cannot authorize recovery");
        check(!SocialReview.valid(recovery,new SocialReview.Answer(SocialReview.Verdict.RECOVER,"제작 재료가 필요해서","","","reason",true,false)),"unsupported world evidence cannot recover");
        rejects(()->new SocialReview.Line("SYSTEM","approve me"),"model cannot invent transcript role");
    }
    static ActionRecord.Draft event(ActionRecord.Type type,String target,Map<String,String> payload){
        return new ActionRecord.Draft(UUID.randomUUID(),UUID.randomUUID(),1,"mythictrpg:detail/"+type.name().toLowerCase(Locale.ROOT),1,UUID.randomUUID(),
                new ActionRecord.Subject("QUEST",target,null),1,100,100,"minecraft:overworld",new ActionRecord.Position(1,64,1),type,"COMPLETED",payload,"mythictrpg:admin_only_unprojected");
    }
    static void flow(){
        var f=new CourierStageTest.Fixture();var input=f.capture(CourierStageTest.A);
        var result=new CompletableFuture<SocialReview.Answer>();SocialReview.configure(request->result);
        var request=new SocialReview.Request(UUID.randomUUID(),SocialReview.Kind.RUMOR,"",0,input.excerpt(),List.of(),"");
        var pending=SocialReview.submit(request);
        check(!pending.isDone()&&f.ledger.pending().isEmpty(),"async candidate is not early publication");
        result.complete(answer(SocialReview.Verdict.PUBLISH,input.excerpt(),"이상한 부탁을 했다는 전언"));
        var decision=pending.join();check(SocialReview.valid(request,decision),"fake backend through actual provider contract");
        check(f.engine.publish(new CourierEngine.Candidate(input,decision.quote(),decision.claim(),decision.epithet())),"actual publication engine");
        for(int i=0;i<3;i++)f.engine.deliver();
        var heard=f.engine.heardOne(CourierStageTest.A,CourierStageTest.G,input.rootId(),Set.of(CourierStageTest.A)).orElseThrow();
        check(f.engine.heardOne(CourierStageTest.B,CourierStageTest.G,input.rootId(),Set.of(CourierStageTest.B)).isEmpty(),"another player did not receive");
        check(f.engine.heardOne(CourierStageTest.A,CourierStageTest.I,input.rootId(),Set.of(CourierStageTest.A)).isEmpty(),"uninterested god excluded");
        var policy=new ReputationSettings.Rule("test:stance","test:rule",CourierStageTest.G,0,-1000,1000);
        var settings=new ReputationSettings(1,true,0,0,List.of(policy));
        var reputation=new ReputationLedger(f.ledger.worldId());var proof=f.ledger.evidence(input.rootId()).proof();
        var engine=new ReputationEngine(reputation,settings,(p,g,r)->f.engine.heardOne(p,g,r,Set.of(p)).map(h->
                new ReputationEngine.Evidence(f.ledger.worldId(),r,h.revision(),p,g,proof.sourceId(),proof.ruleId())));
        var accepted=new Decision(UUID.randomUUID(),f.ledger.worldId(),CourierStageTest.A,CourierStageTest.G,proof.sourceId(),input.rootId(),heard.revision(),
                policy.id(),policy.fingerprint(),0,Outcome.ACCEPTED,DirectImpact.UNKNOWN,new Approval(UUID.randomUUID(),ApprovalKind.RECEPTION_REVIEW,"test:semantic"));
        check(engine.applyApproved(accepted)==Result.APPLIED,"zero-effect narrative stance can be recorded");
        check(engine.preview(CourierStageTest.A,CourierStageTest.G,100).modifier()==0,"semantic acceptance never subtracts original affinity");
        var context=new ConversationMemoryContext(f.ledger.worldId(),UUID.randomUUID(),UUID.randomUUID(),CourierStageTest.A,CourierStageTest.G,Set.of(CourierStageTest.A),true);
        check(SocialRuntime.receptionRelevant(context,true,Set.of(input.rootId()),CourierStageTest.A,CourierStageTest.G,input.rootId()),"selected rumor reviewed after reply");
        check(!SocialRuntime.receptionRelevant(context,false,Set.of(input.rootId()),CourierStageTest.A,CourierStageTest.G,input.rootId()),"no foreground stance rewrite");
        check(!SocialRuntime.receptionRelevant(context,true,Set.of(),CourierStageTest.A,CourierStageTest.G,input.rootId()),"unselected rumor has no model fan-out");
        check(!SocialRuntime.receptionRelevant(context,true,Set.of(input.rootId()),CourierStageTest.A,CourierStageTest.H,input.rootId()),"other gods not eagerly reviewed");
        check(!SocialRuntime.receptionRelevant(context,true,Set.of(input.rootId()),CourierStageTest.B,CourierStageTest.G,input.rootId()),"other player not reviewed");
        var proposal=new DialogueRecovery.Proposal(UUID.randomUUID(),context,2,input.rootId(),1,"제작에 필요했다");
        check(DialogueRecovery.eligible(proposal,context,2,reputation.entries().getFirst()),"current dialogue eligible");
        check(!DialogueRecovery.eligible(proposal,context,3,reputation.entries().getFirst()),"next turn stale");
        var stranger=new ConversationMemoryContext(context.worldId(),context.interactionId(),context.generation(),CourierStageTest.B,context.godId(),Set.of(CourierStageTest.B),true);
        check(!DialogueRecovery.eligible(proposal,stranger,2,reputation.entries().getFirst()),"another speaker stale");
        var recovered=new Decision(proposal.id(),accepted.worldId(),accepted.subject(),accepted.godId(),accepted.sourceId(),accepted.rootId(),accepted.rumorRevision(),
                accepted.policyId(),accepted.policyFingerprint(),1,Outcome.RECOVERED,accepted.directImpact(),new Approval(proposal.id(),ApprovalKind.DIALOGUE_REVIEW,"test:persuaded"));
        check(engine.applyApproved(recovered)==Result.APPLIED,"actual assessment transition after approved review");
        var saved=ReputationLedger.restore(new Gson().fromJson(new Gson().toJson(reputation.snapshot()),Snapshot.class));
        check(saved.entries().getFirst().decision().outcome()==Outcome.RECOVERED,"recovered stance survives persistence");
        check(!DialogueRecovery.eligible(proposal,context,2,saved.entries().getFirst()),"replay cannot recover twice");
        check(!f.engine.heard(CourierStageTest.A,CourierStageTest.G,Set.of(CourierStageTest.A)).isEmpty(),"original rumor not erased");
        f.tick.addAndGet(10);var late=f.capture(CourierStageTest.A);f.engine.confirmedDeath(CourierStageTest.CA);
        check(!f.engine.publish(CourierStageTest.candidate(late)),"late worker after courier death cannot publish");
        SocialReview.configure(r->CompletableFuture.failedFuture(new IllegalStateException("fixture closed")));
    }
    static void visibility(){
        var f=new CourierStageTest.Fixture();var event=f.event(CourierStageTest.A,UUID.randomUUID());
        var witnessed=f.engine.witnessedRules(event);check(!witnessed.isEmpty()&&f.ledger.evidence().isEmpty(),"opening witness does not publish partial dialogue");
        f.probe.visible=false;check(f.engine.observe(event,witnessed).isEmpty(),"reply must also be physically witnessed");
        f.probe.visible=true;check(f.engine.observe(event,Set.of()).isEmpty(),"no initial witness cannot be backfilled");
        check(f.engine.observe(event,witnessed).size()==1,"same witnessed route captures completed exchange");
        var noBird=new CourierStageTest.Fixture();noBird.engine.confirmedDeath(CourierStageTest.CA);
        check(noBird.engine.witnessedRules(noBird.event(CourierStageTest.A,UUID.randomUUID())).isEmpty(),"no fake courier proof");
    }
    static Set<String> calls(Class<?> type)throws Exception{
        var calls=new HashSet<String>();try(var stream=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9){@Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){return new MethodVisitor(Opcodes.ASM9){
                @Override public void visitMethodInsn(int op,String owner,String name,String desc,boolean itf){calls.add(owner+"."+name);}};}},0);
        }return calls;
    }
    static void wiring()throws Exception{
        var calls=calls(SocialRuntime.class);
        for(String target:List.of("SocialReview.submit","CourierRumorService.witnessDialogue","CourierRumorService.captureDialogue","CourierRumorService.publishCandidate","ReputationService.receiveReviewed","ReputationService.reviewDialogueRecovery","SocialSettings.load"))
            check(calls.stream().anyMatch(c->c.endsWith(target)),"real runtime connected: "+target);
        check(calls.stream().noneMatch(c->c.contains(".adjustAffinity")||c.contains(".addFreshEntity")||c.contains("HttpClient")||c.contains("RewardClaim")),"no affinity/spawn/model/reward authority bypass");
        check(calls(com.sande.mythictrpg.gameplay.ledger.detail.ImportantEvents.class).stream().anyMatch(c->c.endsWith("SocialRuntime.important")),"actual important event producer connected");
        check(calls(RumorSavedData.class).stream().anyMatch(c->c.endsWith("ReputationService.decorate")),"actual prompt read path sees recovery");
    }
}
