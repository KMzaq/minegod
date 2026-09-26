package com.sande.mythictrpg.mixin;
import com.sande.mythictrpg.gameplay.metric.ProvenanceTracking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Level.class)
abstract class ProvenanceLevelMixin {
    @Inject(method="markAndNotifyBlock",at=@At("HEAD"),require=1)
    private void mythictrpg$committed(BlockPos pos,LevelChunk chunk,BlockState old,BlockState next,int flags,int recursion,CallbackInfo ci) {
        if((Object)this instanceof ServerLevel level) ProvenanceTracking.changed(level,pos,old,next);
    }
}
