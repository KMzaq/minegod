package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import net.minecraft.nbt.CompoundTag;
import org.objectweb.asm.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Pure fixtures + NBT/bytecode checks. Does not bootstrap Minecraft, a registry, a server or an LLM. */
public final class CourierStageTest {
    static int checks;
    static final UUID A=new UUID(0,1),B=new UUID(0,2),CA=new UUID(1,1),CB=new UUID(1,2);
    static final String G="mythictrpg:fortuna",H="mythictrpg:demeter",I="mythictrpg:hermes";
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void rejects(Runnable action,String message){try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    static class Probe implements CourierEngine.Probe {
        Set<UUID> missing=new HashSet<>();Set<String> absent=new HashSet<>();boolean visible=true;
        public boolean available(UUID entity,UUID subject){return !missing.contains(entity);}
        public boolean witnessed(UUID entity,CourierEngine.Event event,CourierSettings.Rule rule){return visible;}
        public boolean receiverAvailable(String god){return !absent.contains(god);}
    }
    static CourierSettings.Rule rule(CourierSettings.Publication publication,CourierSettings.Source source,Map<String,CourierSettings.Reception> gods){
        return new CourierSettings.Rule("test:rule","test:important",source,"minecraft:overworld",24,10,200,gods,publication,publication==CourierSettings.Publication.AUTHORED?"용을 구했다는 이야기":"", "");
    }
    static CourierSettings settings(CourierSettings.Rule rule){return new CourierSettings(1,true,false,false,1,Set.of("test:future_courier"),List.of(rule));}
    static final CourierSettings RULES=settings(rule(CourierSettings.Publication.CANDIDATE,CourierSettings.Source.DISCLOSED_DIALOGUE,Map.of(G,CourierSettings.Reception.CAUTIOUS,H,CourierSettings.Reception.INTERESTED,I,CourierSettings.Reception.IGNORE)));
    static class Fixture {
        RumorLedger ledger=new RumorLedger();Probe probe=new Probe();AtomicLong tick=new AtomicLong(100);
        CourierEngine engine=new CourierEngine(ledger,RULES,probe,tick::get);
        Fixture(){check(engine.bind(A,CA),"bind A");check(engine.bind(B,CB),"bind B");}
        CourierEngine.Event event(UUID who,UUID source){return new CourierEngine.Event(source,1,who,Set.of(who),Set.of(who),"test:important",CourierSettings.Source.DISCLOSED_DIALOGUE,"minecraft:overworld",1,64,1,"비늘의 용도를 설명하지 않고 요청했다",1000,tick.get());}
        CourierEngine.CandidateInput capture(UUID who){return engine.observe(event(who,UUID.randomUUID())).getFirst();}
        void restart(){ledger=RumorLedger.restore(new Gson().fromJson(new Gson().toJson(ledger.snapshot()),RumorLedger.Snapshot.class));engine=new CourierEngine(ledger,RULES,probe,tick::get);}
    }
    static CourierEngine.Candidate candidate(CourierEngine.CandidateInput in){return new CourierEngine.Candidate(in,in.excerpt(),"이상한 부탁을 했다는 소문","오해받은 여행자");}
    public static void main(String[] args)throws Exception {
        configuration();lifecycle();candidateGuards();delivery();storage();bounded();wiring();
        System.out.println("CourierStageTest: "+checks+" assertions passed");
    }
    static void configuration(){
        var off=new CourierEngine(new RumorLedger(),CourierSettings.OFF,new Probe(),()->100L);
        check(!off.bind(A,CA)&&!off.confirmedDeath(CA)&&off.deliver()==0&&off.pendingCandidates(1).isEmpty(),"OFF has no mutations");
        check(CourierSettings.load(java.nio.file.Path.of("absent-courier-settings.json")).equals(CourierSettings.OFF),"missing file OFF");
        for(boolean spawn:new boolean[]{false,true})for(boolean respawn:new boolean[]{false,true})if(spawn||respawn)
            rejects(()->new CourierSettings(1,true,spawn,respawn,1,RULES.courierTypes(),RULES.rules()),"no automatic lifecycle even when requested in config");
        rejects(()->new CourierSettings(1,true,false,false,1,Set.of(),RULES.rules()),"no placeholder type");
        rejects(()->new CourierSettings(1,true,false,false,33,RULES.courierTypes(),RULES.rules()),"bounded delivery");
        rejects(()->new CourierSettings(1,true,false,false,1,RULES.courierTypes(),List.of(RULES.rules().getFirst(),RULES.rules().getFirst())),"unique rules");
        rejects(()->rule(CourierSettings.Publication.AUTHORED,CourierSettings.Source.DISCLOSED_DIALOGUE,Map.of(G,CourierSettings.Reception.CAUTIOUS)),"no fixed dialogue allegation");
        check(new Gson().fromJson(new Gson().toJson(RULES),CourierSettings.class).equals(RULES),"settings roundtrip");
        check(RULES.rules().getFirst().fingerprint().equals(new Gson().fromJson(new Gson().toJson(RULES),CourierSettings.class).rules().getFirst().fingerprint()),"stable policy hash");
    }
    static void lifecycle(){
        var f=new Fixture();check(!f.engine.bind(A,CB)&&!f.engine.bind(B,CA),"one subject per entity and one active entity per subject");
        var heard=f.capture(A);check(f.engine.publish(candidate(heard)),"candidate accepted");
        for(int i=0;i<3;i++)f.engine.deliver();
        check(f.engine.heard(A,G,Set.of(A)).size()==1,"first receiver heard");
        f.tick.addAndGet(10);var late=f.capture(A);var b=f.capture(B);check(f.engine.publish(candidate(b)),"B publishes");
        check(f.engine.confirmedDeath(CA),"A observer killed (killer deliberately not an argument)");
        check(!f.engine.confirmedDeath(CA)&&!f.engine.confirmedDeath(UUID.randomUUID()),"duplicate and unknown death ignored");
        check(f.ledger.courier(A).blocked()&&!f.ledger.courier(B).blocked(),"only observed A suppressed");
        check(!f.engine.publish(candidate(late))&&f.engine.observe(f.event(A,UUID.randomUUID())).isEmpty(),"late candidate and new A observation blocked");
        check(f.engine.heard(A,G,Set.of(A)).size()==1,"already heard remains after death");
        check(f.ledger.pending().stream().allMatch(d->d.rootId().equals(b.rootId())),"B pending unaffected");
        f.restart();check(f.ledger.courier(A).blocked()&&f.engine.pendingCandidates(32).isEmpty(),"restart cannot revive blocked candidate");
        var newBird=UUID.randomUUID();check(f.engine.bind(A,newBird),"future explicit spawn may bind new UUID");
        check(!f.engine.publish(candidate(late))&&!f.engine.confirmedDeath(CA),"old epoch cannot publish or kill new courier");
        check(f.engine.heard(A,G,Set.of(A)).size()==1,"heard persists across rebinding");
        f.tick.addAndGet(10);var next=f.capture(A);check(!next.epoch().equals(late.epoch()),"new evidence has new epoch");
        check(f.engine.publish(candidate(next)),"new epoch accepts new evidence");
    }
    static void candidateGuards(){
        var f=new Fixture();var source=UUID.randomUUID();var event=f.event(A,source);
        f.probe.visible=false;check(f.engine.observe(event).isEmpty(),"unwitnessed fails");f.probe.visible=true;
        f.probe.missing.add(CA);check(f.engine.observe(event).isEmpty(),"unloaded not witnessed or marked dead");f.probe.missing.clear();
        check(!f.ledger.courier(A).blocked(),"unload is not death");
        var compound=new CourierEngine.Event(source,1,A,Set.of(A,B),Set.of(A,B),event.eventType(),event.source(),event.dimension(),1,64,1,event.excerpt(),1000,f.tick.get());
        check(f.engine.observe(compound).isEmpty(),"compound subject cannot bypass suppression");
        f.tick.incrementAndGet();check(f.engine.observe(event).isEmpty(),"old tick snapshot rejected");
        var input=f.engine.observe(f.event(A,source)).getFirst();
        check(f.engine.observe(f.event(A,source)).isEmpty(),"same source deduplicated");
        check(f.engine.observe(f.event(A,UUID.randomUUID())).isEmpty(),"cooldown blocks repeated rumors");
        f.restart();check(f.engine.pendingCandidates(1).equals(List.of(input)),"candidate recovery is persisted and bounded");
        check(f.engine.observe(f.event(A,UUID.randomUUID())).isEmpty(),"cooldown survives restart");
        rejects(()->f.engine.pendingCandidates(33),"candidate budget");
        var variants=List.of(new CourierEngine.CandidateInput(UUID.randomUUID(),input.rootId(),input.epoch(),input.ruleFingerprint(),input.excerpt()),
                new CourierEngine.CandidateInput(input.worldId(),UUID.randomUUID(),input.epoch(),input.ruleFingerprint(),input.excerpt()),
                new CourierEngine.CandidateInput(input.worldId(),input.rootId(),UUID.randomUUID(),input.ruleFingerprint(),input.excerpt()),
                new CourierEngine.CandidateInput(input.worldId(),input.rootId(),input.epoch(),"x",input.excerpt()),
                new CourierEngine.CandidateInput(input.worldId(),input.rootId(),input.epoch(),input.ruleFingerprint(),"invented"));
        for(var forged:variants)check(!f.engine.publish(candidate(forged)),"forged candidate context rejected");
        check(!f.engine.publish(new CourierEngine.Candidate(input,"not the evidence","claim","")),"exact quoted provenance required");
        check(f.ledger.revoke(input.rootId()),"invalidation before publication creates tombstone");f.restart();
        check(!f.engine.publish(candidate(input))&&f.engine.pendingCandidates(32).isEmpty(),"tombstone survives restart, no late revival");
        f.tick.addAndGet(10);var expired=f.capture(A);f.tick.addAndGet(201);
        check(!f.engine.publish(candidate(expired))&&f.engine.pendingCandidates(32).isEmpty(),"stale worker expires");
    }
    static void delivery(){
        var f=new Fixture();var input=f.capture(A);check(f.engine.publish(candidate(input)),"publish into queue");
        check(!f.engine.publish(candidate(input))&&f.engine.heard(A,G,Set.of(A)).isEmpty(),"publish once, not immediately knowledge");
        f.probe.absent.add(H);for(int i=0;i<4;i++)check(f.engine.deliver()<=1,"per-tick budget");
        check(f.engine.heard(A,G,Set.of(A)).getFirst().reception().equals("CAUTIOUS"),"cautious metadata");
        check(f.engine.heard(A,I,Set.of(A)).isEmpty(),"ignore does not enter prompt");
        check(f.engine.heard(A,G,Set.of(A,B)).isEmpty()&&f.engine.heard(B,G,Set.of(B)).isEmpty(),"audience/subject isolation");
        check(f.ledger.pending().size()==1,"missing god defers");f.probe.absent.clear();f.engine.deliver();
        check(f.engine.heard(A,H,Set.of(A)).getFirst().reception().equals("INTERESTED"),"interested receiver");
        f.probe.missing.add(CA);check(f.engine.heard(A,G,Set.of(A)).size()==1,"past receipt not contingent on loaded entity");
        var changed=settings(rule(CourierSettings.Publication.CANDIDATE,CourierSettings.Source.DISCLOSED_DIALOGUE,Map.of(G,CourierSettings.Reception.IGNORE)));
        check(new CourierEngine(f.ledger,changed,f.probe,f.tick::get).heard(A,G,Set.of(A)).isEmpty(),"changed disclosure policy fails closed");
        check(new CourierEngine(f.ledger,CourierSettings.OFF,f.probe,f.tick::get).heard(A,G,Set.of(A)).isEmpty(),"OFF hides new proofs");
        check(f.ledger.revoke(input.rootId())&&f.engine.heard(A,G,Set.of(A)).isEmpty(),"administrative invalidation removes receipt visibility");
        var authored=settings(rule(CourierSettings.Publication.AUTHORED,CourierSettings.Source.GAME_EVENT,Map.of(G,CourierSettings.Reception.CAUTIOUS)));
        var l=new RumorLedger();var p=new Probe();var t=new AtomicLong(10);var e=new CourierEngine(l,authored,p,t::get);e.bind(A,CA);
        var event=new CourierEngine.Event(UUID.randomUUID(),1,A,Set.of(A),Set.of(A),"test:important",CourierSettings.Source.GAME_EVENT,"minecraft:overworld",1,64,1,"제작 콘텐츠가 확인한 업적",100,t.get());
        check(e.observe(event).size()==1&&l.pending().size()==1&&e.pendingCandidates(1).isEmpty(),"authored event has no model call");
        p.missing.add(CA);check(e.deliver()==0&&!l.courier(A).blocked(),"unload defers delivery only");
        t.set(211);check(e.deliver()==0&&l.pending().isEmpty(),"expired undelivered work discarded");
        var positive=new Fixture();var pos=positive.capture(A);positive.engine.publish(new CourierEngine.Candidate(pos,pos.excerpt(),"용왕의 친구라고 한다","용왕의 친우"));
        positive.engine.confirmedDeath(CA);check(positive.ledger.pending().isEmpty(),"positive and negative share suppression, no valence bypass");
    }
    static void storage(){
        var f=new Fixture();var input=f.capture(A);f.engine.publish(candidate(input));f.engine.deliver();
        var tag=new CompoundTag();tag.putInt("dataVersion",2);tag.putString("state",new Gson().toJson(f.ledger.snapshot()));
        var loaded=RumorSavedData.load(tag,null);check(loaded.ready()&&loaded.snapshot().equals(f.ledger.snapshot()),"v2 proof/claim/receipt NBT roundtrip");
        check(loaded.save(new CompoundTag(),null).equals(tag),"v2 saved without change");
        var old=new RumorLedger();var s=old.snapshot();var v1=new RumorLedger.Snapshot(1,s.worldId(),s.couriers(),s.evidence(),s.claims(),s.pending(),s.receipts());
        var oldTag=new CompoundTag();oldTag.putInt("dataVersion",1);oldTag.putString("state",new Gson().toJson(v1));
        var oldLoaded=RumorSavedData.load(oldTag,null);check(oldLoaded.ready()&&oldLoaded.save(new CompoundTag(),null).equals(oldTag),"LP read/OFF does not upgrade v1");
        check(new RumorSavedData().save(new CompoundTag(),null).getInt("dataVersion")==1,"empty state remains v1");
        for(int version:new int[]{1,99}){var bad=tag.copy();bad.putInt("dataVersion",version);var rejected=RumorSavedData.load(bad,null);
            check(!rejected.ready()&&rejected.save(new CompoundTag(),null).equals(bad),"mismatch/unknown preserved read-only");}
        var corrupt=tag.copy();corrupt.putString("state",tag.getString("state").replace(input.excerpt(),"changed"));
        var rejected=RumorSavedData.load(corrupt,null);check(!rejected.ready()&&rejected.save(new CompoundTag(),null).equals(corrupt),"tampered proof not trusted or overwritten");
        var legacy=new RumorLedger();legacy.bindCourier(A,CA);var id=UUID.randomUUID();legacy.observe(id,A,CA,Set.of(A),"legacy",Set.of(G),Set.of(A));legacy.publish(id,"legacy"," ");legacy.deliver(legacy.pending().getFirst());
        check(new CourierEngine(legacy,CourierSettings.OFF,new Probe(),()->1L).heard(A,G,Set.of(A)).size()==1,"isolated legacy receipt contract preserved");
    }
    static void bounded(){
        var f=new Fixture();for(int i=0;i<RumorLedger.LIMIT;i++)check(f.ledger.observe(new UUID(9,i),A,CA,Set.of(A),"fixture",Set.of(G),Set.of(A)),"capacity insert");
        check(f.engine.observe(f.event(A,UUID.randomUUID())).isEmpty(),"full evidence rejects safely without deleting old data");
        check(f.ledger.snapshot().evidence().size()==RumorLedger.LIMIT,"bounded retained state");
    }
    static void wiring()throws Exception {
        var calls=new HashSet<String>();var ordered=new HashMap<String,List<String>>();
        try(var in=CourierStageTest.class.getResourceAsStream("CourierRumorService.class")){
            new ClassReader(Objects.requireNonNull(in)).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int a,String n,String d,String s,String[] ex){return new MethodVisitor(Opcodes.ASM9){
                    @Override public void visitMethodInsn(int op,String owner,String name,String desc,boolean it){calls.add(owner+"."+name);ordered.computeIfAbsent(n,k->new ArrayList<>()).add(name);}
                };}
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        check(calls.stream().anyMatch(s->s.endsWith("LivingDeathEvent.isCanceled")),"final cancellation checked in service");
        check(calls.stream().anyMatch(s->s.endsWith(".confirmedDead")),"actual lifecycle checked");
        check(calls.stream().noneMatch(s->s.contains("addFreshEntity")||s.contains(".spawn")||s.endsWith(".getChunk")||s.endsWith(".getChunkAt")||s.contains(".executeCommand")||s.contains("Reward")||s.contains("Affinity")||s.contains("HttpClient")),"no spawn/chunk-load/reward/affinity/model execution");
        check(ordered.get("unobstructed").indexOf("hasChunk")<ordered.get("unobstructed").indexOf("clip"),"intermediate chunks checked before ray trace");
        check(calls.stream().anyMatch(s->s.endsWith("MinecraftServer.isSameThread")),"game thread boundary");
        check(ordered.values().stream().anyMatch(s->s.contains("confirmedDeath")&&s.contains("deliver")&&s.indexOf("confirmedDeath")<s.indexOf("deliver")),"confirmed death processed before delivery");
    }
}
