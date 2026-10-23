package com.sande.mythictrpg.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads vanilla's pending hurt-immunity threshold without changing that threshold or its timer. */
@Mixin(LivingEntity.class)
public interface LivingCombatDefenseAccessor {
    @Accessor("lastHurt")
    float mythictrpg$lastHurt();
}
