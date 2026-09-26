package com.sande.mythictrpg.mixin;
import com.sande.mythictrpg.gameplay.metric.ProvenanceTracking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.EventHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=EventHooks.class,remap=false)
abstract class FluidProvenanceMixin {
    @Inject(method="fireFluidPlaceBlockEvent",at=@At("RETURN"),require=1)
    private static void mythictrpg$fluid(LevelAccessor level,BlockPos pos,BlockPos source,BlockState state,CallbackInfoReturnable<BlockState> ci) {
        ProvenanceTracking.fluidResult(level,pos,ci.getReturnValue());
    }
}
