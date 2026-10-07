package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.objectweb.asm.*;
import static com.sande.mythictrpg.rumor.NativeRumorReadAccess.*;

/** Real game ledgers/CourierEngine, no server, registry bootstrap, model, archive DB or affinity mutation. */
public final class NativeRumorReadAccessTest {
    private static int checks;
    private static final UUID A=new UUID(0,1), B=new UUID(0,2), OUT=new UUID(0,3), BIRD=new UUID(1,1);
    private static final String G="mythictrpg:fortuna", H="mythictrpg:demeter";
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    private static void rejects(Runnable action,String message) {
        try {action.run();throw new AssertionError(message);}catch(IllegalArgumentException|NullPointerException expected){checks++;}
    }
    private static CourierSettings.Rule courierRule(Map<String,CourierSettings.Reception> gods) {
        return new CourierSettings.Rule("test:receipt_rule","test:item_seen",CourierSettings.Source.GAME_EVENT,
                "minecraft:overworld",16,1,10000,gods,CourierSettings.Publication.AUTHORED,"물건을 가져갔다는 소문","소문 속 여행자");
    }
    private static final CourierSettings.Rule RULE=courierRule(Map.of(G,CourierSettings.Reception.CAUTIOUS,H,CourierSettings.Reception.INTERESTED));
    private static CourierSettings settings(CourierSettings.Rule rule) {
        return new CourierSettings(1,true,false,false,32,Set.of("test:explicit_courier"),List.of(rule));
    }
    private static ReputationSettings.Rule policy(String god) {
        return new ReputationSettings.Rule("test:assessment_"+(god.equals(G)?"g":"h"),RULE.id(),god,-1,-1000,1000);
    }
    private static final class Fixture implements Probe {
        RumorLedger rumors=new RumorLedger(); ReputationLedger reputation=new ReputationLedger(rumors.worldId());
        UUID lineage=UUID.randomUUID(); AtomicLong tick=new AtomicLong(100); boolean issued=true,available=true;
        boolean reputationReady=true; Set<String> missingGods=new HashSet<>();
        Map<String,ReputationSettings.Rule> policies=new HashMap<>(Map.of(G,policy(G),H,policy(H)));
        CourierSettings rules=settings(RULE); CourierEngine engine;
        Fixture(){rebuild();check(engine.bind(A,BIRD),"explicit trusted courier bound");}
        void rebuild(){engine=new CourierEngine(rumors,rules,new CourierEngine.Probe(){
            public boolean available(UUID courier,UUID subject){return available;}
            public boolean witnessed(UUID courier,CourierEngine.Event event,CourierSettings.Rule rule){return true;}
            public boolean receiverAvailable(String god){return !missingGods.contains(god);}
        },tick::get);}
        UUID root(Set<UUID> audience,boolean deliver) {
            tick.incrementAndGet();
            var event=new CourierEngine.Event(UUID.randomUUID(),1,A,Set.of(A),audience,RULE.eventType(),RULE.source(),RULE.dimension(),
                    123,64,456,"PRIVATE_WITNESS_PAYLOAD_NOT_FOR_READER",1000,tick.get());
            var inputs=engine.observe(event);check(inputs.size()==1,"trusted source was actually observed");
            if(deliver)check(engine.deliver()==2,"both authored recipients actually received");
            return inputs.getFirst().rootId();
        }
        Scope scope(){return scope(Set.of(A),G);}
        Scope scope(Set<UUID> players,String god){return new Scope(rumors.worldId(),god,players,Set.of(god),false);}
        Snapshot read(UUID root){return NativeRumorReadAccess.read(scope(),root,this).orElseThrow();}
        public boolean current(){return issued;}
        public Optional<UUID> lineage(){return Optional.ofNullable(lineage);}
        public RumorLedger.Evidence evidence(UUID root){return rumors.evidence(root);}
        public Optional<RumorLedger.HeardRumor> heard(UUID subject,String god,UUID root,Set<UUID> audience){return engine.heardOne(subject,god,root,audience);}
        public Assessment assessment(RumorLedger.Evidence e,RumorLedger.HeardRumor heard,String god,Set<UUID> audience){
            return reputationReady?NativeRumorReadAccess.assessment(rumors.worldId(),e,heard,god,policies.get(god),
                    reputation.find(e.subject(),god,e.proof().sourceId())):Assessment.unknown();
        }
        ReputationLedger.Decision decision(UUID root,String god,long version,ReputationLedger.Outcome outcome){
            var e=rumors.evidence(root);var p=policies.get(god);
            return new ReputationLedger.Decision(UUID.randomUUID(),rumors.worldId(),e.subject(),god,e.proof().sourceId(),root,1,
                    p.id(),p.fingerprint(),version,outcome,ReputationLedger.DirectImpact.UNKNOWN,
                    new ReputationLedger.Approval(UUID.randomUUID(),ReputationLedger.ApprovalKind.GAME_RESULT,"test:game_approved"));
        }
        void judge(UUID root,String god,long version,ReputationLedger.Outcome outcome){
            check(reputation.apply(decision(root,god,version,outcome))==ReputationLedger.Result.APPLIED,"game approved exact assessment applied");
        }
        void restart(){var json=new Gson();rumors=RumorLedger.restore(json.fromJson(json.toJson(rumors.snapshot()),RumorLedger.Snapshot.class));
            reputation=ReputationLedger.restore(json.fromJson(json.toJson(reputation.snapshot()),ReputationLedger.Snapshot.class));rebuild();}
    }
    public static void main(String[] args)throws Exception {
        scopeAndLifecycle();assessmentAvailability();assessmentBinding();oldPointReadAndRestart();boundsAndCanonicalHash();productionWiring();
        System.out.println("NativeRumorReadAccessTest: "+checks+" assertions passed");
    }
    private static void scopeAndLifecycle(){
        var f=new Fixture();var root=f.root(Set.of(A,B),false);
        check(NativeRumorReadAccess.read(f.scope(),root,f).isEmpty(),"publication and pending delivery are not knowledge");
        check(f.engine.deliver()==2,"pending actual deliveries complete");
        var s=f.read(root);check(s.subjectPlayerId().equals(A)&&s.recipientGodId().equals(G),"source determines actual subject and receiver");
        check(NativeRumorReadAccess.read(f.scope(Set.of(A,B),G),root,f).isPresent(),"requester B may hear subject A only while A is in audience");
        check(NativeRumorReadAccess.read(f.scope(Set.of(B),G),root,f).isEmpty(),"subject must remain present");
        check(NativeRumorReadAccess.read(f.scope(Set.of(A,OUT),G),root,f).isEmpty(),"ungranted listener denied");
        check(NativeRumorReadAccess.read(new Scope(f.rumors.worldId(),G,Set.of(A),Set.of(G,H),false),root,f).isEmpty(),"multi God unsupported");
        check(NativeRumorReadAccess.read(new Scope(f.rumors.worldId(),G,Set.of(A),Set.of(G),true),root,f).isEmpty(),"public disclosure denied");
        check(NativeRumorReadAccess.read(f.scope(Set.of(A),"test:unknown_god"),root,f).isEmpty(),"nonreceiver denied");
        check(NativeRumorReadAccess.read(f.scope(),UUID.randomUUID(),f).isEmpty(),"unknown root denied");
        f.issued=false;check(!NativeRumorReadAccess.current(f.scope(),s,f),"stale room lease denied");f.issued=true;
        var lineage=f.lineage;f.lineage=null;check(NativeRumorReadAccess.read(f.scope(),root,f).isEmpty(),"missing durable lineage unavailable");
        f.lineage=UUID.randomUUID();check(!NativeRumorReadAccess.current(f.scope(),s,f),"reset lineage invalidates prior snapshot");f.lineage=lineage;
        f.missingGods.add(G);check(!NativeRumorReadAccess.current(f.scope(),s,f),"current game receiver requirement enforced");f.missingGods.clear();
        check(f.engine.confirmedDeath(BIRD),"confirmed courier death");
        check(NativeRumorReadAccess.current(f.scope(),s,f),"courier death does not erase already received rumor");
        check(f.rumors.revoke(root),"explicit game root revocation");
        check(!NativeRumorReadAccess.current(f.scope(),s,f),"immediate revocation without archive reconciliation");
        var legacy=new Fixture();var old=UUID.randomUUID();
        check(legacy.rumors.observe(old,A,BIRD,Set.of(A),"legacy",Set.of(G),Set.of(A)),"legacy fixture observed");
        check(legacy.rumors.publish(old,"legacy claim",""),"legacy published");
        check(legacy.rumors.deliver(legacy.rumors.pending().getFirst()),"legacy received");
        check(NativeRumorReadAccess.read(legacy.scope(),old,legacy).isEmpty(),"legacy unproven roots not upgraded");
    }
    private static void assessmentAvailability(){
        var f=new Fixture();var root=f.root(Set.of(A),true);
        check(f.read(root).assessment().availability()==AssessmentAvailability.UNASSESSED,"ready exact policy plus absent decision really unassessed");
        f.reputationReady=false;var unknown=f.read(root);
        check(unknown.assessment().equals(Assessment.unknown()),"unavailable reputation remains UNKNOWN");f.reputationReady=true;
        check(!NativeRumorReadAccess.current(f.scope(),unknown,f),"availability transition invalidates prior exact snapshot");
        var p=f.policies.remove(G);check(f.read(root).assessment().availability()==AssessmentAvailability.UNKNOWN,"missing policy is not unassessed");f.policies.put(G,p);
        var unassessed=f.read(root);f.judge(root,G,0,ReputationLedger.Outcome.DOUBTFUL);var assessed=f.read(root);
        check(!NativeRumorReadAccess.current(f.scope(),unassessed,f),"new game assessment invalidates issued unassessed snapshot");
        check(assessed.assessment().outcome().orElseThrow()==ReputationLedger.Outcome.DOUBTFUL&&assessed.assessment().version()==1,"own current assessment version");
        f.judge(root,H,0,ReputationLedger.Outcome.ACCEPTED);
        check(NativeRumorReadAccess.current(f.scope(),assessed,f),"another God's assessment does not leak or stale this snapshot");
        var otherGod=NativeRumorReadAccess.read(f.scope(Set.of(A),H),root,f).orElseThrow();
        check(otherGod.assessment().outcome().orElseThrow()==ReputationLedger.Outcome.ACCEPTED&&otherGod.reception().equals("INTERESTED")
                &&assessed.assessment().outcome().orElseThrow()==ReputationLedger.Outcome.DOUBTFUL&&assessed.reception().equals("CAUTIOUS"),
                "same root retains independent recipient assessment and authored reception");
        var next=f.root(Set.of(A),true);f.judge(next,G,0,ReputationLedger.Outcome.IGNORED);
        check(NativeRumorReadAccess.current(f.scope(),assessed,f),"unrelated root assessment remains independent");
        f.judge(root,G,1,ReputationLedger.Outcome.RECOVERED);
        check(!NativeRumorReadAccess.current(f.scope(),assessed,f),"terminal recovery immediately changes exact snapshot");
        check(f.read(root).assessment().outcome().orElseThrow()==ReputationLedger.Outcome.RECOVERED,"recovery is preserved as judgment not claim deletion");
        f.policies.put(G,new ReputationSettings.Rule(p.id(),p.courierRuleId(),G,-2,-1000,1000));
        check(f.read(root).assessment().availability()==AssessmentAvailability.UNKNOWN,"outdated policy judgment not projected as current or unassessed");
        f.rules=settings(courierRule(Map.of(G,CourierSettings.Reception.INTERESTED,H,CourierSettings.Reception.INTERESTED)));f.rebuild();
        check(NativeRumorReadAccess.read(f.scope(),root,f).isEmpty(),"changed reception policy invalidates old witnessed rule");
    }
    private static void assessmentBinding(){
        var f=new Fixture();var root=f.root(Set.of(A),true);var e=f.evidence(root);var heard=f.heard(A,G,root,Set.of(A)).orElseThrow();var p=f.policies.get(G);
        var d=f.decision(root,G,0,ReputationLedger.Outcome.ACCEPTED);
        var entry=new ReputationLedger.Entry(d,1,d.approval());
        check(NativeRumorReadAccess.assessment(f.rumors.worldId(),e,heard,G,p,entry).availability()==AssessmentAvailability.ASSESSED,"matching stored decision read");
        var bad=List.of(
            new ReputationLedger.Decision(d.id(),UUID.randomUUID(),A,G,d.sourceId(),root,1,p.id(),p.fingerprint(),0,d.outcome(),d.directImpact(),d.approval()),
            new ReputationLedger.Decision(d.id(),d.worldId(),B,G,d.sourceId(),root,1,p.id(),p.fingerprint(),0,d.outcome(),d.directImpact(),d.approval()),
            new ReputationLedger.Decision(d.id(),d.worldId(),A,H,d.sourceId(),root,1,p.id(),p.fingerprint(),0,d.outcome(),d.directImpact(),d.approval()),
            new ReputationLedger.Decision(d.id(),d.worldId(),A,G,UUID.randomUUID(),root,1,p.id(),p.fingerprint(),0,d.outcome(),d.directImpact(),d.approval()),
            new ReputationLedger.Decision(d.id(),d.worldId(),A,G,d.sourceId(),UUID.randomUUID(),1,p.id(),p.fingerprint(),0,d.outcome(),d.directImpact(),d.approval()),
            new ReputationLedger.Decision(d.id(),d.worldId(),A,G,d.sourceId(),root,2,p.id(),p.fingerprint(),0,d.outcome(),d.directImpact(),d.approval()),
            new ReputationLedger.Decision(d.id(),d.worldId(),A,G,d.sourceId(),root,1,"test:other",p.fingerprint(),0,d.outcome(),d.directImpact(),d.approval()),
            new ReputationLedger.Decision(d.id(),d.worldId(),A,G,d.sourceId(),root,1,p.id(),"0".repeat(64),0,d.outcome(),d.directImpact(),d.approval()));
        for(var other:bad)check(NativeRumorReadAccess.assessment(f.rumors.worldId(),e,heard,G,p,new ReputationLedger.Entry(other,1,other.approval()))
                .equals(Assessment.unknown()),"foreign/stale exact assessment cannot confer a current judgment");
    }
    private static void oldPointReadAndRestart(){
        var f=new Fixture();var old=f.root(Set.of(A),true);var snapshot=f.read(old);
        for(int i=0;i<70;i++)f.root(Set.of(A),true);
        check(f.engine.heard(A,G,Set.of(A)).stream().noneMatch(h->h.rootId().equals(old)),"old root deliberately outside legacy top64");
        check(NativeRumorReadAccess.current(f.scope(),snapshot,f),"point lookup remains available beyond top64");
        var game=f.rumors.snapshot();var reputation=f.reputation.snapshot();
        for(int i=0;i<10;i++)check(NativeRumorReadAccess.current(f.scope(),snapshot,f),"repeated exact read stable");
        check(f.rumors.snapshot().equals(game)&&f.reputation.snapshot().equals(reputation),"reads never publish/deliver/review or modify ledgers");
        f.restart();check(NativeRumorReadAccess.current(f.scope(),snapshot,f),"same durable lineage and game receipt survive restart");
    }
    private static void boundsAndCanonicalHash(){
        var f=new Fixture();var root=f.root(Set.of(B,A),true);var s=f.read(root);var e=f.evidence(root);var proof=e.proof();
        var canonical=CourierSettings.hash(new Gson().toJson(List.of(f.lineage,root,A,proof.sourceId(),proof.sourceRevision(),
                proof.excerptHash(),s.claimRevision(),s.claim(),s.epithet(),e.disclosureAudience().stream().map(UUID::toString).sorted().toList())));
        check(s.sourceHash().equals(canonical),"exact existing capture canonical source binding");
        var serialized=new Gson().toJson(List.of(s.worldId(),s.lineageId(),s.rootId(),s.claimRevision(),s.subjectPlayerId(),s.recipientGodId(),
                s.evidenceSourceId(),s.evidenceSourceRevision(),s.evidenceExcerptHash(),s.claim(),s.epithet(),s.disclosureAudience(),s.reception(),
                s.assessment().availability().name(),s.assessment().outcome().map(Enum::name).orElse(""),s.assessment().version()));
        check(!serialized.contains(e.excerpt())&&!serialized.contains(BIRD.toString())&&!serialized.contains("affinity")
                &&!serialized.contains("dimension")&&!serialized.contains("observer"),"no hidden payload/courier/location/affinity in snapshot");
        try{s.disclosureAudience().add(OUT);throw new AssertionError("immutable audience");}catch(UnsupportedOperationException expected){checks++;}
        for(var availability:AssessmentAvailability.values()) {
            if(availability!=AssessmentAvailability.ASSESSED)rejects(()->new Assessment(availability,Optional.of(ReputationLedger.Outcome.ACCEPTED),1),"unknown/unassessed has no fabricated decision");
        }
        rejects(()->new Assessment(AssessmentAvailability.ASSESSED,Optional.empty(),1),"assessed requires outcome");
        rejects(()->new Assessment(AssessmentAvailability.ASSESSED,Optional.of(ReputationLedger.Outcome.ACCEPTED),0),"assessed requires actual version");
    }
    private static void productionWiring()throws Exception {
        var calls=new HashSet<String>();
        for(Class<?> type:List.of(NativeRumorReadAccess.class,Class.forName(NativeRumorReadAccess.class.getName()+"$1"),ReputationService.class)) {
            try(var stream=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                new ClassReader(Objects.requireNonNull(stream)).accept(new ClassVisitor(Opcodes.ASM9){
                    @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                        if(type==ReputationService.class&&!name.equals("nativeAssessment")&&!name.contains("nativeAssessment"))return null;
                        return new MethodVisitor(Opcodes.ASM9){@Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf){calls.add(owner+"#"+method);}};
                    }
                },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            }
        }
        check(calls.contains("com/sande/mythictrpg/ai/server/ConversationRooms#memoryReadCurrent"),"production game-issued room gate");
        check(calls.contains("com/sande/mythictrpg/rumor/CourierRumorService#heardOne"),"production exact receipt lookup");
        check(calls.contains("com/sande/mythictrpg/rumor/ReputationLedger#find"),"production point assessment lookup");
        check(!calls.contains("com/sande/mythictrpg/rumor/RumorLedger#heard")&&!calls.contains("com/sande/mythictrpg/rumor/ReputationLedger#entries"),"no top64 or whole-assessment scan");
    }
}
