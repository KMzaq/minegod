package com.sande.mythictrpg.skills;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.control.CrowdControlService;
import com.sande.mythictrpg.mixin.LivingCombatDefenseAccessor;
import com.sande.mythictrpg.prototype.PrototypeCombatAccess;
import com.sande.mythictrpg.weaponskill.WeaponSkillDefinition;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.IdentityHashMap;
import java.util.Map;

/** Short dedicated defense states. The shared cast runtime owns payment, lock, cancellation, and settlement. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class DefensiveSkillRuntime {
    private static final Map<ServerPlayer,Defense> STATES=new IdentityHashMap<>();
    private DefensiveSkillRuntime() { }

    public static boolean preflight(ServerPlayer player,WeaponSkillDefinition.Action action,PrototypeCombatAccess.Scope scope) {
        if(!PrototypeCombatAccess.valid(player,scope)||STATES.containsKey(player))return false;
        Vec3 facing=horizontalFacing(player);
        if(!finite(facing)||facing.lengthSqr()<0.99)return false;
        if(action instanceof WeaponSkillDefinition.TimedParry)return true;
        if(action instanceof WeaponSkillDefinition.ShortDash dash) {
            if(!scope.dashAllowed()||!walking(player)||!DashCollision.supported(player,player.getBoundingBox()))return false;
            Vec3 first=DashCollision.safeDelta(player,scope,facing.scale(dash.distance()/dash.movementTicks()));
            return first.length()+1.0E-6>=dash.minimumDistance();
        }
        return false;
    }

    public static void start(SkillCastEvents.Context context,WeaponSkillDefinition.Action action,PrototypeCombatAccess.Scope scope,long startTick) {
        if(!(action instanceof WeaponSkillDefinition.TimedParry)&&!(action instanceof WeaponSkillDefinition.ShortDash))
            throw new IllegalArgumentException("Unsupported defense action");
        var state=new Defense(context,action,scope,startTick,horizontalFacing(context.player()),context.player().position());
        STATES.put(context.player(),state);
        particles(context.player(),action instanceof WeaponSkillDefinition.TimedParry?ParticleTypes.ENCHANT:ParticleTypes.CLOUD,3);
        if(action instanceof WeaponSkillDefinition.ShortDash dash&&dash.windupTicks()==0)tick(context.player(),startTick);
    }

    /** Called by the shared runtime before entity damage for the upcoming server tick. */
    public static void tick(ServerPlayer player,long now) {
        Defense state=STATES.get(player);if(state==null)return;
        if(!valid(player,state)) {STATES.remove(player);return;}
        if(!(state.action instanceof WeaponSkillDefinition.ShortDash dash)||state.stopped)return;
        long elapsed=now-state.start;
        if(elapsed<dash.windupTicks())return;
        if(elapsed>=dash.windupTicks()+dash.movementTicks())return;
        int due=(int)(elapsed-dash.windupTicks()+1);
        while(state.moves<due&&!state.stopped) {
            if(!walking(player)||!DashCollision.supported(player,player.getBoundingBox())) {stopDash(player,state);break;}
            double progress=Math.max(0,player.position().subtract(state.origin).dot(state.facing));
            double step=Math.min(dash.distance()/dash.movementTicks(),Math.max(0,dash.distance()-progress));
            if(step<=1.0E-6) {stopDash(player,state);break;}
            Vec3 requested=state.facing.scale(step);
            Vec3 safe=DashCollision.safeDelta(player,state.scope,requested);
            state.moves++;
            if(safe.lengthSqr()>1.0E-12) {
                Vec3 destination=player.position().add(safe);
                player.setDeltaMovement(Vec3.ZERO);
                player.connection.teleport(destination.x,destination.y,destination.z,player.getYRot(),player.getXRot());
                player.setOnGround(true);
                particles(player,ParticleTypes.CLOUD,2);
            }
            if(safe.length()+1.0E-5<requested.length())stopDash(player,state);
        }
        if(elapsed==dash.windupTicks()+dash.evasionTicks())particles(player,ParticleTypes.END_ROD,2);
    }

    public static void cancel(ServerPlayer player){STATES.remove(player);}

    @SubscribeEvent(priority=EventPriority.LOW)
    public static void incoming(LivingIncomingDamageEvent event) {
        if(event.isCanceled()||!(event.getEntity() instanceof ServerPlayer player)||!player.server.isSameThread()
                ||event.getAmount()<=0||!Float.isFinite(event.getAmount())
                ||event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)||player.isInvulnerableTo(event.getSource()))return;
        Defense state=STATES.get(player);if(state==null||!valid(player,state))return;
        // Incoming is before vanilla's hurt-cooldown branch. An already-ignored hit must not spend the parry window.
        if(player.invulnerableTime>10&&!event.getSource().is(DamageTypeTags.BYPASSES_COOLDOWN)
                &&event.getAmount()<=((LivingCombatDefenseAccessor)player).mythictrpg$lastHurt())return;
        var attack=PrototypeCombatAccess.attack(player,event.getSource()).orElse(null);
        if(attack==null)return;
        long elapsed=SkillMana.now(player)-state.start;
        if(state.action instanceof WeaponSkillDefinition.ShortDash dash&&!state.stopped&&attack.dodge()
                &&elapsed>=dash.windupTicks()&&elapsed<dash.windupTicks()+dash.evasionTicks()) {
            event.setCanceled(true);return;
        }
        if(!(state.action instanceof WeaponSkillDefinition.TimedParry parry)||state.consumed||!attack.parry()
                ||elapsed<parry.windupTicks()||elapsed>=parry.windupTicks()+parry.activeTicks()
                ||event.getSource().is(DamageTypeTags.IS_PROJECTILE)
                ||!(event.getSource().getEntity() instanceof LivingEntity attacker)
                ||event.getSource().getDirectEntity()!=attacker||!PrototypeCombatAccess.enemy(player,state.scope,attacker))return;
        Vec3 center=player.getBoundingBox().getCenter(),origin=attack.origin();
        if(origin==null||!finite(origin)||center.distanceToSqr(origin)>parry.range()*parry.range())return;
        Vec3 relative=new Vec3(origin.x-center.x,0,origin.z-center.z);
        if(relative.lengthSqr()<1.0E-12||state.facing.dot(relative.normalize())+1.0E-7<Math.cos(Math.toRadians(parry.halfAngleDegrees())))return;
        if(!loadedRay(player,center,origin)||player.level().clip(new ClipContext(center,origin,
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player)).getType()!=HitResult.Type.MISS)return;
        state.consumed=true;
        event.setCanceled(true);
        particles(player,ParticleTypes.CRIT,5);
        player.serverLevel().playSound(null,player.blockPosition(),SoundEvents.SHIELD_BLOCK,SoundSource.PLAYERS,0.7F,1.25F);
    }

    private static boolean valid(ServerPlayer player,Defense state) {
        return CombatCastRuntime.active(player,state.context)&&PrototypeCombatAccess.valid(player,state.scope)
                &&player.isAlive()&&!player.isSpectator()&&!CrowdControlService.isStunned(player)
                &&(!(state.action instanceof WeaponSkillDefinition.TimedParry)
                    ||sameWeapon(state.weapon,player.getMainHandItem()));
    }
    private static boolean sameWeapon(ItemStack expected,ItemStack held) {
        ItemStack normalized=held.copy();normalized.remove(DataComponents.DAMAGE);
        return !held.isEmpty()&&ItemStack.isSameItemSameComponents(expected,normalized);
    }
    private static boolean walking(ServerPlayer p) {
        return p.onGround()&&!p.isInWater()&&!p.isInLava()&&!p.isSwimming()&&!p.getAbilities().flying
                &&!p.isFallFlying()&&!p.isPassenger()&&!p.isSleeping()&&!p.onClimbable()&&!CrowdControlService.isStunned(p)
                &&!DashCollision.liquid(p,p.getBoundingBox());
    }
    private static Vec3 horizontalFacing(ServerPlayer player) {
        // Yaw remains defined while looking vertically, so vertical aim still dashes forward on the ground.
        double radians=Math.toRadians(player.getYRot());
        return new Vec3(-Math.sin(radians),0,Math.cos(radians)).normalize();
    }
    private static void stopDash(ServerPlayer p,Defense state){state.stopped=true;p.setDeltaMovement(Vec3.ZERO);particles(p,ParticleTypes.END_ROD,2);}
    private static void particles(ServerPlayer p,net.minecraft.core.particles.SimpleParticleType type,int count) {
        p.serverLevel().sendParticles(type,p.getX(),p.getY()+0.8,p.getZ(),count,0.15,0.25,0.15,0.015);
    }
    private static boolean finite(Vec3 value){return Double.isFinite(value.x)&&Double.isFinite(value.y)&&Double.isFinite(value.z);}
    private static boolean loadedRay(ServerPlayer p,Vec3 from,Vec3 to) {
        for(int x=(int)Math.floor(Math.min(from.x,to.x))>>4;x<=((int)Math.floor(Math.max(from.x,to.x)))>>4;x++)
            for(int z=(int)Math.floor(Math.min(from.z,to.z))>>4;z<=((int)Math.floor(Math.max(from.z,to.z)))>>4;z++)
                if(!p.serverLevel().getChunkSource().hasChunk(x,z))return false;
        return true;
    }
    private static final class Defense {
        final SkillCastEvents.Context context;final WeaponSkillDefinition.Action action;final PrototypeCombatAccess.Scope scope;
        final long start;final Vec3 facing,origin;final ItemStack weapon;int moves;boolean consumed,stopped;
        Defense(SkillCastEvents.Context context,WeaponSkillDefinition.Action action,PrototypeCombatAccess.Scope scope,long start,Vec3 facing,Vec3 origin) {
            this.context=context;this.action=action;this.scope=scope;this.start=start;this.facing=facing;this.origin=origin;
            weapon=context.player().getMainHandItem().copy();weapon.remove(DataComponents.DAMAGE);
        }
    }
}
