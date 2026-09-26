package com.sande.mythictrpg.mixin;
import com.sande.mythictrpg.gameplay.ledger.detail.DetailCapture;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Player.class)
abstract class PlayerDetailMixin {
    @Inject(method="attack",at=@At("HEAD"),require=1)
    private void mythictrpg$startAttack(Entity target,CallbackInfo ci) {if((Object)this instanceof ServerPlayer p)DetailCapture.beginAttack(p,target);}
    @Inject(method="attack",at=@At("RETURN"),require=1)
    private void mythictrpg$endAttack(Entity target,CallbackInfo ci) {if((Object)this instanceof ServerPlayer p)DetailCapture.endAttack(p,target);}
}
