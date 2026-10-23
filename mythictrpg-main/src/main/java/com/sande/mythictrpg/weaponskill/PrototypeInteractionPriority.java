package com.sande.mythictrpg.weaponskill;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Method;
import java.util.Arrays;

/** Side-effect-free interaction capability lookup. No use/interact callback is executed as a probe. */
public final class PrototypeInteractionPriority {
    private PrototypeInteractionPriority() { }
    private static final ClassValue<Boolean> BLOCKS=new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            for(var current=type;current!=null&&current!=BlockBehaviour.class;current=current.getSuperclass())
                for(var method:current.getDeclaredMethods()) {
                    if(signature(method,InteractionResult.class,BlockState.class,Level.class,BlockPos.class,Player.class,BlockHitResult.class)
                            ||signature(method,ItemInteractionResult.class,ItemStack.class,BlockState.class,Level.class,BlockPos.class,Player.class,InteractionHand.class,BlockHitResult.class))return true;
                }
            return false;
        }
    };
    private static final ClassValue<Boolean> ENTITIES=new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            // Mob's final dispatcher is universal; only a specialized mob interaction has priority.
            for(var current=type;current!=null&&current!=Entity.class&&current!=Mob.class;current=current.getSuperclass())
                for(var method:current.getDeclaredMethods())
                    if(signature(method,InteractionResult.class,Player.class,InteractionHand.class)
                            ||signature(method,InteractionResult.class,Player.class,Vec3.class,InteractionHand.class))return true;
            return false;
        }
    };
    private static boolean signature(Method method,Class<?> result,Class<?>... parameters) {
        return method.getReturnType()==result&&Arrays.equals(method.getParameterTypes(),parameters);
    }
    public static boolean prototypeSkill(ResourceLocation id) {
        return id!=null&&id.getNamespace().equals("proto_cbt")&&(id.getPath().equals("blade_wave")||id.getPath().equals("timed_parry"));
    }
    public static boolean block(Player player,BlockHitResult hit) {
        return hit.getType()==HitResult.Type.BLOCK&&BLOCKS.get(player.level().getBlockState(hit.getBlockPos()).getBlock().getClass());
    }
    public static boolean entity(Entity target) {return ENTITIES.get(target.getClass());}

    /** The server recomputes the ray; clients cannot claim an empty sky while targeting a chest/NPC. */
    public static boolean directlyTargeted(Player player) {
        Vec3 start=player.getEyePosition();double blockRange=player.blockInteractionRange(),entityRange=player.entityInteractionRange();
        double range=Math.max(blockRange,entityRange);Vec3 end=start.add(player.getLookAngle().scale(range));
        var hit=player.level().clip(new net.minecraft.world.level.ClipContext(start,end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,player));
        double limit=hit.getType()==HitResult.Type.MISS?range*range:start.distanceToSqr(hit.getLocation());
        var entityHit=ProjectileUtil.getEntityHitResult(player,start,end,player.getBoundingBox().expandTowards(end.subtract(start)).inflate(1),
                target->!target.isSpectator()&&target.isPickable(),Math.min(limit,entityRange*entityRange));
        // Combat weapons may cast at villagers; Shift input still reaches vanilla trading before this check.
        if(entityHit!=null&&start.distanceToSqr(entityHit.getLocation())<=limit)
            return !(entityHit.getEntity() instanceof AbstractVillager)&&entity(entityHit.getEntity());
        return hit.getType()==HitResult.Type.BLOCK&&start.distanceToSqr(hit.getLocation())<=blockRange*blockRange&&block(player,hit);
    }
}
