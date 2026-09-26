package com.sande.mythictrpg.gameplay.ledger.detail;

import com.google.gson.Gson;
import com.sande.mythictrpg.gameplay.ledger.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** No Minecraft bootstrap. Data, file, source-bytecode and synthetic rates only. */
public final class ActionDetailTest {
    private static int checks;private static Path root;
    private static final UUID WORLD=UUID.randomUUID(),PLAYER=UUID.randomUUID(),SESSION=UUID.randomUUID();
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void fails(Runnable work,String why){try{work.run();}catch(RuntimeException expected){check(true,why);return;}throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        root=Files.createTempDirectory(Files.createDirectories(Path.of(args[0])),"detail-");
        movement();settings();wire();hooks();for(int n:new int[]{1,4,6})fixture(n);
        System.out.println("ActionDetailTest: PASS ("+checks+" checks); artifacts="+root+"; NO in-game tick/compatibility claim");
    }
    private static MovementSamples.Point point(long tick,double x,String dimension){return new MovementSamples.Point(dimension,x,64,0,tick);}
    private static void movement() {
        var m=new MovementSamples();
        check(m.sample(PLAYER,point(0,0,"minecraft:overworld"),0).isEmpty(),"no invented interval");
        check(m.sample(PLAYER,point(1,0,"minecraft:overworld"),20).orElseThrow().coverage().equals("START_OR_RECONNECT"),"first sample coverage");
        check(m.sample(PLAYER,point(20,1000,"minecraft:overworld"),20).isEmpty(),"large displacement not invented teleport");
        check(m.sample(PLAYER,point(21,0,"minecraft:overworld"),20).orElseThrow().coverage().equals("UNCHANGED_ENDPOINTS_NOT_CONTINUOUS_STILLNESS"),"same endpoint does not prove stillness");
        check(m.sample(PLAYER,point(42,0,"minecraft:overworld"),20).orElseThrow().coverage().equals("SAMPLING_GAP"),"gap explicit");
        check(m.sample(PLAYER,point(43,0,"minecraft:the_nether"),20).orElseThrow().coverage().equals("DIMENSION_BOUNDARY"),"dimension separated");
        check(m.sample(PLAYER,point(2,0,"minecraft:the_nether"),20).orElseThrow().coverage().equals("CLOCK_RESET"),"clock rollback");
        check(m.sample(PLAYER,point(22,9,"minecraft:the_nether"),20).orElseThrow().coverage().equals("SAMPLED_ENDPOINTS_NOT_PATH"),"not path reconstruction");
        m.forget(PLAYER);check(m.sample(PLAYER,point(23,9,"minecraft:the_nether"),20).orElseThrow().elapsedTicks()==0,"logout/respawn reset");
        check(m.sample(UUID.randomUUID(),point(23,9,"minecraft:the_nether"),20).orElseThrow().elapsedTicks()==0,"player isolation");
        fails(()->point(1,Double.NaN,"minecraft:overworld"),"nonfinite coordinates denied");
    }
    private static void settings()throws Exception {
        Path f=root.resolve("settings.json");check(DetailSettings.load(f).equals(DetailSettings.OFF),"missing OFF");
        for(String value:List.of("{}","{\"schemaVersion\":2,\"enabled\":true,\"movementIntervalTicks\":20}","{\"schemaVersion\":1,\"enabled\":true,\"movementIntervalTicks\":-1}")) {
            Files.writeString(f,value);check(DetailSettings.load(f).equals(DetailSettings.OFF),"invalid OFF");
        }
        Files.writeString(f,"{\"schemaVersion\":1,\"enabled\":true,\"movementIntervalTicks\":0}");
        check(DetailSettings.load(f).enabled()&&DetailSettings.load(f).movementIntervalTicks()==0,"nonmovement can opt in without choosing interval");
    }
    private static String kind(ActionRecord.Type type){return switch(type){
        case MATURE_CROP_REMOVED,BLOCK_INTERACTION,BLOCK_BREAK_ATTEMPT,BLOCK_BREAK_RESULT,BLOCK_REMOVED->"BLOCK";
        case ENTITY_KILLED,ATTACK_ATTEMPT,ATTACK_RESULT,DAMAGE_APPLIED,PLAYER_DIED->"ENTITY";
        case ITEM_PICKED_UP->"ITEM";case QUEST_COMPLETED,QUEST_EVALUATED,QUEST_TRANSITION->"QUEST";
        case BATTLE_RESULT->"BATTLE";case ADVANCEMENT_EARNED->"ADVANCEMENT";case OBSERVED_ACTIVITY_SUMMARY->"ACTIVITY";default->"LOCATION";};}
    private static ActionRecord.Draft draft(UUID actor,int i,ActionRecord.Type type,String outcome) {
        String kind=kind(type);String id=switch(kind){case "ENTITY"->"minecraft:zombie";case "BLOCK"->"minecraft:stone";case "ITEM"->"minecraft:diamond";case "QUEST"->"mythictrpg:test";default->"minecraft:overworld";};
        return new ActionRecord.Draft(UUID.randomUUID(),SESSION,i,"mythictrpg:detail/"+type.name().toLowerCase(Locale.ROOT),1,actor,
                new ActionRecord.Subject(kind,id,kind.equals("ENTITY")?UUID.randomUUID():null),1800000000000L+i,i,i,"minecraft:overworld",
                new ActionRecord.Position(1,64,2),type,outcome,Map.of("test","synthetic_not_live"),"mythictrpg:admin_only_unprojected");
    }
    private static String outcome(ActionRecord.Type t){return switch(t){case BLOCK_INTERACTION->"CANCELLED";case ATTACK_ATTEMPT,BLOCK_BREAK_ATTEMPT->"ATTEMPTED";case ATTACK_RESULT->"RETURNED_NO_CONFIRMED_HEALTH_LOSS";case BLOCK_BREAK_RESULT->"NOT_REMOVED";case TELEPORT_RESULT->"NO_POSITION_CHANGE";default->"COMPLETED";};}
    private static void wire()throws Exception {
        var json=new Gson();int i=0;
        try(var store=new ActionLedgerStore(root.resolve("all-types"),WORLD,new ActionLedgerStore.Limits(4000000,128000,5000))) {
            for(var t:ActionRecord.Type.values())if(t.ordinal()>1){var d=draft(PLAYER,++i,t,outcome(t));check(json.fromJson(json.toJson(d),ActionRecord.Draft.class).equals(d),"wire "+t);check(store.append(d).sequence()==i,"durable "+t);}
        }
        try(var store=new ActionLedgerStore(root.resolve("all-types"),WORLD,new ActionLedgerStore.Limits(4000000,128000,5000))){check(store.size()==i,"all result variants survive restart");}
        fails(()->draft(PLAYER,1,ActionRecord.Type.ATTACK_ATTEMPT,"COMPLETED"),"attempt never success");
        fails(()->draft(PLAYER,1,ActionRecord.Type.TELEPORT_RESULT,"CANCELLED"),"no position change not cancellation proof");
        var d=draft(PLAYER,1,ActionRecord.Type.ATTACK_RESULT,"CANCELLED");
        fails(()->new ActionRecord.Draft(d.occurrenceId(),d.captureSession(),1,d.sourceRef(),1,PLAYER,new ActionRecord.Subject("ITEM","minecraft:stone",null),d.occurredAtUtc(),1,1,d.dimensionId(),d.position(),d.type(),d.outcome(),d.payload(),d.visibilityRef()),"wrong subject type rejected");
    }
    private static ClassNode node(String name)throws Exception {try(var in=ActionDetailTest.class.getResourceAsStream("/"+name+".class")){if(in==null)throw new AssertionError(name);var c=new ClassNode();new ClassReader(in).accept(c,0);return c;}}
    private static MethodNode method(String name,String member,String descriptor)throws Exception{return node(name).methods.stream().filter(m->m.name.equals(member)&&m.desc.equals(descriptor)).findFirst().orElseThrow();}
    private static void hooks()throws Exception {
        String sp="net/minecraft/server/level/ServerPlayer";
        for(String[] signature:new String[][]{{"teleportTo","(DDD)V"},{"teleportRelative","(DDD)V"},{"teleportTo","(Lnet/minecraft/server/level/ServerLevel;DDDFF)V"},{"teleportTo","(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z"}})
            check(method(sp,signature[0],signature[1])!=null,"actual installed teleport descriptor");
        var attack=method("net/neoforged/neoforge/common/CommonHooks","onPlayerAttackTarget","(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)Z");
        check(Arrays.stream(attack.instructions.toArray()).filter(i->i.getOpcode()==Opcodes.IRETURN).count()==2,"cancel/item RETURN ordinals in exact NeoForge");
        method("net/minecraft/world/entity/player/Player","attack","(Lnet/minecraft/world/entity/Entity;)V");check(true,"melee entrypoint");
        for(String name:List.of("PlayerDetailMixin","ServerPlayerDetailMixin","CommonHooksDetailMixin","ServerPlayerGameModeLedgerMixin","LivingEntityLedgerMixin")) {
            var c=node("com/sande/mythictrpg/mixin/"+name);
            for(var m:c.methods) {
                for(var ins:m.instructions)if(ins instanceof MethodInsnNode call)
                    check(!call.name.equals("setReturnValue")&&!call.name.equals("cancel"),"recording does not override game outcome");
            }
        }
        // Important details may now enter the fail-closed watch gateway, never gameplay replay.
        for(String name:List.of("DetailCapture","DetailEvents"))for(var m:node("com/sande/mythictrpg/gameplay/ledger/detail/"+name).methods)
            for(var ins:m.instructions)if(ins instanceof MethodInsnNode call)check(!call.owner.contains("GameplayObservationService"),"no game replay");
    }
    private static void fixture(int players)throws Exception {
        long heap=java.lang.Runtime.getRuntime().totalMemory()-java.lang.Runtime.getRuntime().freeMemory(),start=System.nanoTime();
        int each=120,total=players*each;long bytes;long[] submits=new long[total];int j=0;
        try(var raw=new AsyncActionLedger(root.resolve("fixture-"+players),WORLD,new ActionLedgerStore.Limits(16000000,256000,10000),2048)) {
            raw.ready().get();List<java.util.concurrent.CompletableFuture<?>> writes=new ArrayList<>();
            for(int p=0;p<players;p++){UUID actor=UUID.randomUUID();for(int k=0;k<each;k++){
                var t=ActionRecord.Type.values()[2+k%(ActionRecord.Type.values().length-2)];var d=draft(actor,j+1,t,outcome(t));
                long at=System.nanoTime();writes.add(raw.submit(d).durable());submits[j++]=System.nanoTime()-at;
            }}
            for(var f:writes)f.get();var s=raw.status();check(s.rejected()==0&&s.indexedEvents()==total,"synthetic "+players+" players no missing/rejected");bytes=s.usedBytes();
            check(s.pending()==0,"synthetic drained");
        }
        Arrays.sort(submits);long heapDelta=java.lang.Runtime.getRuntime().totalMemory()-java.lang.Runtime.getRuntime().freeMemory()-heap;
        String line="players="+players+",records="+total+",bytes="+bytes+",enqueueP95us="+submits[(int)(total*.95)]/1000+",elapsedMs="+(System.nanoTime()-start)/1000000+",heapDeltaBytes="+heapDelta+"; synthetic only, NOT tick or LLM latency";
        Files.writeString(root.resolve("fixture-"+players+".txt"),line);System.out.println(line);
    }
}
