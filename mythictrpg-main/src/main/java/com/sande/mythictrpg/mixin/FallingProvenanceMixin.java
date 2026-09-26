package com.sande.mythictrpg.mixin;
import com.sande.mythictrpg.gameplay.metric.ProvenanceTracking;
import com.sande.mythictrpg.quest.structure.PlacementRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

@Mixin(FallingBlockEntity.class)
abstract class FallingProvenanceMixin {
    @Unique private static final ThreadLocal<Deque<Optional<PlacementRecord>>> mythictrpg$source=ThreadLocal.withInitial(ArrayDeque::new);
    @Inject(method="fall",at=@At("HEAD"),require=1)
    private static void mythictrpg$beforeFall(Level level,BlockPos pos,BlockState state,CallbackInfoReturnable<FallingBlockEntity> ci) {
        mythictrpg$source.get().push(Optional.ofNullable(ProvenanceTracking.source(level,pos)));
    }
    @Inject(method="fall",at=@At("RETURN"),require=1)
    private static void mythictrpg$afterFall(Level level,BlockPos pos,BlockState state,CallbackInfoReturnable<FallingBlockEntity> ci) {
        var q=mythictrpg$source.get();var prior=q.pop();if(q.isEmpty())mythictrpg$source.remove();
        if(level instanceof ServerLevel)prior.ifPresent(record->ci.getReturnValue().getPersistentData().put("mythictrpg_mining_origin",ProvenanceTracking.save(record)));
    }
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),require=1)
    private boolean mythictrpg$land(Level level,BlockPos pos,BlockState state,int flags) {
        boolean placed=level.setBlock(pos,state,flags);
        if(placed && level instanceof ServerLevel server) {
            var entity=(FallingBlockEntity)(Object)this;
            ProvenanceTracking.restore(server,pos,ProvenanceTracking.load(entity.getPersistentData().getCompound("mythictrpg_mining_origin")));
        }
        return placed;
    }
}
