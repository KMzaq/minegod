package com.sande.mythictrpg.mixin;

import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerCapture;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class LivingEntityLedgerMixin {
    // After cancellation and !removed/!dead checks. Does not treat attack/health<=0 or loot as a kill.
    @Inject(method = "die", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;dead:Z",
            opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER), require = 1)
    private void mythictrpg$recordDeath(DamageSource source, CallbackInfo callback) {
        ActionLedgerCapture.deathCommitted((LivingEntity)(Object)this, source);
        com.sande.mythictrpg.gameplay.ledger.detail.DetailEvents.death((LivingEntity)(Object)this,source);
    }
}
