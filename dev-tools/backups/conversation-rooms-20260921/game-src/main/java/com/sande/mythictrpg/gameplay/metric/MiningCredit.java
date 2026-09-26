package com.sande.mythictrpg.gameplay.metric;

import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.quest.structure.*;
import com.sande.mythictrpg.gameplay.observation.MatureCropHarvestClassifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** One successful removal credit. Vanilla statistics and unrelated event consumers are untouched. */
public final class MiningCredit {
    private MiningCredit() { }
    public static boolean eligible(PlacementSource source, boolean normalMatureCrop) {
        if (source == PlacementSource.GENERATOR) return false;
        return normalMatureCrop || source != PlacementSource.PLAYER_PLACED;
    }
    private static final class Attempt {
        final ServerPlayer player; final BlockPos pos; final BlockState block; final boolean eligible;
        boolean counted;
        Attempt(ServerPlayer p,BlockPos pos,BlockState block,boolean eligible) {this.player=p;this.pos=pos.immutable();this.block=block;this.eligible=eligible;}
    }
    private static final ThreadLocal<Deque<Attempt>> ATTEMPTS=ThreadLocal.withInitial(ArrayDeque::new);
    public static void begin(ServerPlayer player,BlockPos pos,BlockState block) {
        var state=PlayerConstructionState.get(player.server);
        var source=state.placement(player.level().dimension(),pos).map(PlacementRecord::source).orElse(null);
        ATTEMPTS.get().push(new Attempt(player,pos,block,state.isWritable() && eligible(source,MatureCropHarvestClassifier.classify(block).isPresent())));
    }
    public static void removed(ServerPlayer player,BlockPos pos,BlockState block,boolean success) {
        var stack=ATTEMPTS.get(); if(stack.isEmpty())return; var a=stack.peek();
        if(!success || a.counted || a.player!=player || !a.pos.equals(pos) || !a.block.equals(block))return;
        a.counted=true; if(!a.eligible)return;
        var type=GameplayMetricTypes.ELIGIBLE_BLOCK_MINED;
        var subject=BuiltInRegistries.BLOCK.getKey(block.getBlock());
        var result=PlayerMythDataService.get(player.server).incrementGameplayCounters(player.getUUID(),
                Map.of(GameplayMetricKey.aggregate(type),1L,GameplayMetricKey.subject(type,subject),1L));
        if(result.updated()) com.sande.mythictrpg.gameplay.observation.GameplayObservationAdapters.eligibleBlockMined(player,subject,pos);
    }
    public static void end(ServerPlayer player,BlockPos pos) {
        var q=ATTEMPTS.get(); if(!q.isEmpty())q.pop(); if(q.isEmpty())ATTEMPTS.remove();
    }
    public static void clearIncomplete() { ATTEMPTS.remove(); }
}
