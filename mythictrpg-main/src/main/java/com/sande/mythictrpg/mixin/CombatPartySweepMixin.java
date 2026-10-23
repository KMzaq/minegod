package com.sande.mythictrpg.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sande.mythictrpg.combat.CombatTargeting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla sweep applies knockback before hurt; exclude protected targets before that side effect. */
@Mixin(Player.class)
abstract class CombatPartySweepMixin {
    @WrapOperation(method="attack", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/player/Player;isAlliedTo(Lnet/minecraft/world/entity/Entity;)Z"), require=1)
    private boolean mythictrpg$protectSweepTarget(Player attacker, Entity target, Operation<Boolean> original) {
        return original.call(attacker,target) || attacker instanceof ServerPlayer player
                && target instanceof LivingEntity living && !CombatTargeting.canDamage(player,living);
    }
}
