package com.sande.mythictrpg.mixin;
import com.sande.mythictrpg.gameplay.ledger.detail.DetailCapture;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(ServerPlayer.class)
abstract class ServerPlayerDetailMixin {
    @Inject(method={"teleportTo(DDD)V","teleportRelative(DDD)V","teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V"},at=@At("HEAD"),require=3)
    private void mythictrpg$startTeleport(CallbackInfo ci) {DetailCapture.beginTeleport((ServerPlayer)(Object)this);}
    @Inject(method={"teleportTo(DDD)V","teleportRelative(DDD)V","teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V"},at=@At("RETURN"),require=3)
    private void mythictrpg$endTeleport(CallbackInfo ci) {DetailCapture.endTeleport((ServerPlayer)(Object)this);}
    @Inject(method="teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",at=@At("HEAD"),require=1)
    private void mythictrpg$startTeleportResult(CallbackInfoReturnable<Boolean> ci) {DetailCapture.beginTeleport((ServerPlayer)(Object)this);}
    @Inject(method="teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",at=@At("RETURN"),require=1)
    private void mythictrpg$endTeleportResult(CallbackInfoReturnable<Boolean> ci) {DetailCapture.endTeleport((ServerPlayer)(Object)this);}
}
