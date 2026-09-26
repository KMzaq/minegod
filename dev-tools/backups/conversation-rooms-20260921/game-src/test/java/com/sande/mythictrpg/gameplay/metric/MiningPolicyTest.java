package com.sande.mythictrpg.gameplay.metric;
import com.sande.mythictrpg.quest.structure.PlacementSource;
import com.sande.mythictrpg.gameplay.ledger.detail.RecordingPolicy;
import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

public final class MiningPolicyTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static ClassNode node(String name)throws Exception {
        try(var in=MiningPolicyTest.class.getResourceAsStream("/"+name+".class")){
            if(in==null)throw new AssertionError(name);var n=new ClassNode();new ClassReader(in).accept(n,0);return n;
        }
    }
    static MethodNode method(String name,String member)throws Exception {
        return node(name).methods.stream().filter(m->m.name.equals(member)).findFirst().orElseThrow();
    }
    public static void main(String[] args)throws Exception {
        check(!MiningCredit.eligible(PlacementSource.PLAYER_PLACED,false),"own and others' direct blocks excluded without UUID loophole");
        check(MiningCredit.eligible(PlacementSource.PLAYER_PLACED,true),"normal mature crop exception");
        check(MiningCredit.eligible(null,false),"missing provenance credited as natural policy not observation proof");
        check(MiningCredit.eligible(PlacementSource.DERIVED,false),"natural tool transformation eligible");
        check(!MiningCredit.eligible(PlacementSource.GENERATOR,false)&&!MiningCredit.eligible(PlacementSource.GENERATOR,true),"known generator wins even without placer");
        var construction=new com.sande.mythictrpg.quest.structure.PlayerConstructionState();
        var dimension=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse("minecraft:overworld"));
        var pos=new net.minecraft.core.BlockPos(1,64,2);UUID owner=UUID.randomUUID();
        construction.recordBlock(dimension,pos,owner,PlacementSource.PLAYER_PLACED,1);
        construction.recordBlock(dimension,pos,UUID.randomUUID(),PlacementSource.DERIVED,2);
        check(construction.placement(dimension,pos).orElseThrow().source()==PlacementSource.PLAYER_PLACED,"tool cannot launder direct provenance");
        var generator=new net.minecraft.core.BlockPos(2,64,2);
        construction.recordBlock(dimension,generator,new UUID(0,0),PlacementSource.GENERATOR,3);
        var nbt=construction.save(new net.minecraft.nbt.CompoundTag(),null);
        var loader=construction.getClass().getDeclaredMethod("load",net.minecraft.nbt.CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class);loader.setAccessible(true);
        var restored=(com.sande.mythictrpg.quest.structure.PlayerConstructionState)loader.invoke(null,nbt,null);
        check(restored.isWritable()&&restored.placement(dimension,pos).equals(construction.placement(dimension,pos))
                &&restored.placement(dimension,generator).orElseThrow().source()==PlacementSource.GENERATOR,"provenance survives restart");
        var carried=construction.placement(dimension,pos).orElseThrow();
        check(ProvenanceTracking.load(ProvenanceTracking.save(carried)).equals(carried),"falling entity persisted carry roundtrip");
        var broken=nbt.copy();broken.putString("ledger","not a list");
        var rejected=(com.sande.mythictrpg.quest.structure.PlayerConstructionState)loader.invoke(null,broken,null);
        check(!rejected.isWritable()&&!rejected.isReady()&&rejected.save(new net.minecraft.nbt.CompoundTag(),null).equals(broken),"malformed provenance not treated as natural; raw preserved");
        for(var t:List.of(ActionRecord.Type.POSITION_SAMPLE,ActionRecord.Type.ENTITY_KILLED,ActionRecord.Type.ATTACK_ATTEMPT,ActionRecord.Type.BLOCK_REMOVED))
            check(!RecordingPolicy.important(t),"ordinary not durable by default: "+t);
        for(var t:List.of(ActionRecord.Type.BATTLE_RESULT,ActionRecord.Type.QUEST_TRANSITION,ActionRecord.Type.ADVANCEMENT_EARNED))
            check(RecordingPolicy.important(t),"important: "+t);
        method("net/minecraft/world/level/Level","markAndNotifyBlock");checks++;
        method("net/neoforged/neoforge/common/CommonHooks","onPlaceItemIntoWorld");checks++;
        method("net/neoforged/neoforge/event/EventHooks","fireFluidPlaceBlockEvent");checks++;
        var piston=method("net/minecraft/world/level/block/piston/PistonBaseBlock","moveBlocks");
        check(Arrays.stream(piston.instructions.toArray()).filter(i->i instanceof MethodInsnNode m&&m.owner.equals("net/minecraft/world/level/block/piston/PistonStructureResolver")&&m.name.equals("getToPush")).count()==1,"exact installed piston source snapshot target");
        var falling=method("net/minecraft/world/entity/item/FallingBlockEntity","tick");
        check(Arrays.stream(falling.instructions.toArray()).filter(i->i instanceof MethodInsnNode m&&m.owner.equals("net/minecraft/world/level/Level")&&m.name.equals("setBlock")&&m.desc.equals("(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z")).count()==1,"exact installed falling commit target");
        for(String type:List.of("ProvenanceLevelMixin","FluidProvenanceMixin","PistonProvenanceMixin","FallingProvenanceMixin"))
            for(var m:node("com/sande/mythictrpg/mixin/"+type).methods)for(var instruction:m.instructions)
                if(instruction instanceof MethodInsnNode call)check(!call.name.equals("setReturnValue")&&!call.name.equals("cancel"),"never override gameplay result");
        System.out.println("MiningPolicyTest: PASS ("+checks+" checks); bytecode targets checked, live mod composition unverified");
    }
}
