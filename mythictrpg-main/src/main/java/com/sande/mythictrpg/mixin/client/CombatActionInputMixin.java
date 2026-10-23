package com.sande.mythictrpg.mixin.client;

import com.sande.mythictrpg.client.weaponskill.BetterCombatClientBridge;
import com.sande.mythictrpg.client.weaponskill.WeaponSkillClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Run before Better Combat's input and tick hooks; authoritative server guards remain required. */
@Mixin(value = Minecraft.class, priority = 1500)
abstract class CombatActionInputMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void mythictrpg$blockAttack(CallbackInfoReturnable<Boolean> callback) {
        if (WeaponSkillClient.attacksLocked()) callback.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void mythictrpg$blockHeldAttack(boolean held, CallbackInfo callback) {
        if (WeaponSkillClient.attacksLocked()) callback.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void mythictrpg$cancelPendingUpswing(CallbackInfo callback) {
        BetterCombatClientBridge.cancelLockedUpswing();
    }
}
