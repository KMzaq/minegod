package com.sande.mythictrpg.mixin;
import com.sande.mythictrpg.gameplay.metric.ProvenanceTracking;
import com.sande.mythictrpg.quest.structure.PlacementRecord;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.*;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

@Mixin(PistonBaseBlock.class)
abstract class PistonProvenanceMixin {
    @Unique private record Move(BlockPos from,BlockPos to,BlockState state,PlacementRecord provenance) { }
    @Unique private final ThreadLocal<Deque<List<Move>>> mythictrpg$moves=ThreadLocal.withInitial(ArrayDeque::new);
    @Inject(method="moveBlocks",at=@At("HEAD"),require=1)
    private void mythictrpg$begin(Level level,BlockPos pos,Direction face,boolean extending,CallbackInfoReturnable<Boolean> ci) {
        mythictrpg$moves.get().push(new ArrayList<>());
    }
    @Redirect(method="moveBlocks",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/piston/PistonStructureResolver;getToPush()Ljava/util/List;"),require=1)
    private List<BlockPos> mythictrpg$capture(PistonStructureResolver resolver,Level level,BlockPos pos,Direction face,boolean extending) {
        var list=resolver.getToPush();
        if(level instanceof ServerLevel)for(BlockPos from:list)mythictrpg$moves.get().peek().add(new Move(from.immutable(),
                from.relative(extending?face:face.getOpposite()),level.getBlockState(from),ProvenanceTracking.source(level,from)));
        return list; // Original resolver result, no changed piston decision.
    }
    @Inject(method="moveBlocks",at=@At("RETURN"),require=1)
    private void mythictrpg$finish(Level level,BlockPos pos,Direction face,boolean extending,CallbackInfoReturnable<Boolean> ci) {
        var q=mythictrpg$moves.get();var moves=q.pop();if(q.isEmpty())mythictrpg$moves.remove();
        if(!(level instanceof ServerLevel server)||!Boolean.TRUE.equals(ci.getReturnValue()))return;
        for(var m:moves)ProvenanceTracking.restore(server,m.from(),null);
        for(var m:moves) {
            BlockState current=server.getBlockState(m.to());
            boolean matches=current.equals(m.state()) || current.is(Blocks.MOVING_PISTON)
                    && server.getBlockEntity(m.to()) instanceof PistonMovingBlockEntity moving && moving.getMovedState().equals(m.state());
            if(matches)ProvenanceTracking.restore(server,m.to(),m.provenance());
        }
    }
}
