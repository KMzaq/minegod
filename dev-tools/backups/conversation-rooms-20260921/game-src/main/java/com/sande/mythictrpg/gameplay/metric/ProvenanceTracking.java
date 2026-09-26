package com.sande.mythictrpg.gameplay.metric;

import com.sande.mythictrpg.quest.structure.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;
import java.util.*;

/** Reuses the construction ledger; no second placement owner database or movement journal. */
public final class ProvenanceTracking {
    private ProvenanceTracking() { }
    private record Pending(BlockPos pos,BlockState expected,PlacementRecord source) { }
    private record Use(ServerPlayer player,boolean direct,Map<BlockPos,Pending> blocks) { }
    private static final ThreadLocal<Deque<Use>> USES=ThreadLocal.withInitial(ArrayDeque::new);
    private record Fluid(ServerLevel level,BlockPos pos,BlockState expected) { }
    private static final ThreadLocal<List<Fluid>> FLUIDS=ThreadLocal.withInitial(ArrayList::new);
    public static PlacementRecord source(Level level,BlockPos pos) {
        return level instanceof ServerLevel s ? PlayerConstructionState.get(s.getServer()).placement(s.dimension(),pos).orElse(null):null;
    }
    public static void restore(ServerLevel level,BlockPos pos,PlacementRecord record) {
        var state=PlayerConstructionState.get(level.getServer());
        state.removeBlock(level.dimension(),pos);
        StructureEvaluationState.get(level.getServer()).removeBlock(level.dimension(),pos);
        if(record==null)return;
        state.recordBlock(level.dimension(),pos,record.placerId(),record.source(),record.placedAtGameTick());
        if(record.source()!=PlacementSource.GENERATOR) StructureEvaluationState.get(level.getServer()).recordBlock(
                level.dimension(),pos,record.placerId(),record.source(),record.placedAtGameTick());
    }
    public static void beginUse(UseOnContext context) {
        USES.get().push(new Use(context.getPlayer() instanceof ServerPlayer p?p:null,
                context.getItemInHand().getItem() instanceof BlockItem,new LinkedHashMap<>()));
    }
    public static void placement(BlockEvent.EntityPlaceEvent event) {
        if(event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player))return;
        var positions=event instanceof BlockEvent.EntityMultiPlaceEvent multi
                ?multi.getReplacedBlockSnapshots().stream().map(s->s.getPos()).toList():List.of(event.getPos());
        Use current=USES.get().peek();
        for(BlockPos pos:positions) {
            // Bone meal/tool-driven tree growth is not direct log placement.
            if(current!=null && current.player()==player && !current.direct())continue;
            var record=new PlacementRecord(player.getUUID(),level.getGameTime(),PlacementSource.PLAYER_PLACED);
            if(current!=null && current.player()==player)
                current.blocks().put(pos.immutable(),new Pending(pos.immutable(),level.getBlockState(pos),record));
            else restore(level,pos,record); // External mod's explicit placement event (requires its commit contract).
        }
    }
    public static void endUse(UseOnContext context,InteractionResult result) {
        var q=USES.get(); if(q.isEmpty())return;Use use=q.pop();if(q.isEmpty())USES.remove();
        if(!result.consumesAction() || use.player()==null)return;
        var level=use.player().serverLevel();
        for(var pending:use.blocks().values())if(level.getBlockState(pending.pos()).equals(pending.expected()))restore(level,pending.pos(),pending.source());
    }
    public static void fluidResult(LevelAccessor level,BlockPos pos,BlockState next) {
        if(!(level instanceof ServerLevel server) || next.equals(server.getBlockState(pos)))return;
        if(next.is(Blocks.COBBLESTONE)||next.is(Blocks.STONE)||next.is(Blocks.OBSIDIAN)||next.is(Blocks.BASALT)
                ||next.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mythictrpg","generator_outputs")))) {
            var pending=FLUIDS.get();if(pending.size()<4096)pending.add(new Fluid(server,pos.immutable(),next));
        }
    }
    /** Called only on a committed/notification path, not during captured placement or its rollback. */
    public static void changed(ServerLevel level,BlockPos pos,BlockState old,BlockState next) {
        if(level.restoringBlockSnapshots || level.captureBlockSnapshots || !level.getBlockState(pos).equals(next))return;
        var fluids=FLUIDS.get();
        for(var iterator=fluids.iterator();iterator.hasNext();) {
            var f=iterator.next();if(f.level()==level&&f.pos().equals(pos)&&f.expected().equals(next)) {
                iterator.remove();restore(level,pos,new PlacementRecord(new UUID(0,0),level.getGameTime(),PlacementSource.GENERATOR));return;
            }
        }
        if(next.isAir() || next.liquid() || old.getBlock() instanceof SaplingBlock && next.is(BlockTags.LOGS)) {
            restore(level,pos,null);
        }
    }
    public static CompoundTag save(PlacementRecord record) {
        var tag=new CompoundTag();if(record!=null){tag.putUUID("placer",record.placerId());tag.putLong("tick",record.placedAtGameTick());tag.putString("source",record.source().name());}return tag;
    }
    public static PlacementRecord load(CompoundTag tag) {
        if(!tag.hasUUID("placer"))return null;
        return new PlacementRecord(tag.getUUID("placer"),tag.getLong("tick"),PlacementSource.valueOf(tag.getString("source")));
    }
    public static void clearIncomplete() { USES.remove();FLUIDS.remove(); }
}
