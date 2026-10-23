package com.sande.mythictrpg.skills;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.combat.CombatTargeting;
import com.sande.mythictrpg.control.CrowdControlService;
import com.sande.mythictrpg.quest.reward.BlessingEffectApplication;
import com.sande.mythictrpg.weaponskill.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Controllers authenticate ownership; this executor owns resource settlement and one cast lifecycle. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class SkillExecutor {
    private static final Set<ServerPlayer> CASTING=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<MinecraftServer,List<Pulse>> PENDING=new IdentityHashMap<>();
    private SkillExecutor() { }
    public static String cooldownKey(SkillCastEvents.Source source,ResourceLocation id) {
        return source==SkillCastEvents.Source.WEAPON?id.toString():source.name().toLowerCase(Locale.ROOT)+":"+id.getNamespace()+"/"+id.getPath();
    }
    public static boolean availablePlayer(ServerPlayer p) {
        return p.server.isSameThread()&&p.server.getPlayerList().getPlayer(p.getUUID())==p&&p.isAlive()&&!p.isSpectator()
                &&!p.isSleeping()&&!p.isUsingItem()&&p.containerMenu==p.inventoryMenu&&!CrowdControlService.isStunned(p)&&!CombatCastRuntime.locked(p);
    }
    public static WeaponSkillRuntime.Result cast(ServerPlayer p,SkillCastEvents.Source source,ResourceLocation id,String name,
            int cost,int cooldown,WeaponSkillDefinition.Action action) {
        if(!availablePlayer(p))return WeaponSkillRuntime.Result.INVALID_PLAYER;
        if(!CASTING.add(p))return WeaponSkillRuntime.Result.DUPLICATE;
        try {
            var prototype=CombatCastRuntime.isPrototypeAction(action);
            var prototypeScope=prototype?CombatCastRuntime.prepare(p,source,action):null;
            if(prototype&&prototypeScope==null)return WeaponSkillRuntime.Result.UNAVAILABLE;
            if(action instanceof WeaponSkillDefinition.SelfHeal&&p.getHealth()>=p.getMaxHealth())return WeaponSkillRuntime.Result.NO_EFFECT;
            if(action instanceof WeaponSkillDefinition.SelfEffect effect&&!effectNeeded(p,effect))return WeaponSkillRuntime.Result.NO_EFFECT;
            double range=action instanceof WeaponSkillDefinition.AimedDamage d?d.range():action instanceof WeaponSkillDefinition.PeriodicDamage d?d.range()
                    :action instanceof WeaponSkillDefinition.AreaDamage d?d.radius():0;
            if(range>0&&!loaded(p,range))return WeaponSkillRuntime.Result.UNAVAILABLE;
            if(action instanceof WeaponSkillDefinition.PeriodicDamage) {
                var list=PENDING.getOrDefault(p.server,List.of());
                if(list.size()>=256||list.stream().filter(v->v.context.player()==p).count()>=8)return WeaponSkillRuntime.Result.UNAVAILABLE;
            }
            SkillMana.mana(p);
            var paid=WeaponSkillState.get(p.server).trySpend(p.getUUID(),cooldownKey(source,id),cost,cooldown,SkillMana.now(p));
            if(paid!=WeaponSkillState.Result.SUCCESS)return switch(paid) {
                case NO_MANA->WeaponSkillRuntime.Result.NO_MANA;case COOLDOWN->WeaponSkillRuntime.Result.COOLDOWN;
                case DUPLICATE->WeaponSkillRuntime.Result.DUPLICATE;default->WeaponSkillRuntime.Result.UNAVAILABLE;
            };
            var context=new SkillCastEvents.Context(UUID.randomUUID(),p,source,id,cost,cooldown,
                    !(action instanceof WeaponSkillDefinition.SelfHeal)&&!(action instanceof WeaponSkillDefinition.SelfEffect)
                            &&!(action instanceof WeaponSkillDefinition.TimedParry)&&!(action instanceof WeaponSkillDefinition.ShortDash));
            int hits=0;boolean scheduled=false;boolean cancelled=true;
            try {
                NeoForge.EVENT_BUS.post(new SkillCastEvents.Start(context));
                if(prototype) {
                    CombatCastRuntime.start(context,action,prototypeScope);scheduled=true;
                } else if(action instanceof WeaponSkillDefinition.SelfHeal heal)p.heal((float)heal.amount());
                else if(action instanceof WeaponSkillDefinition.SelfEffect effect) {
                    var holder=BuiltInRegistries.MOB_EFFECT.getHolder(effect.effectId()).orElseThrow();
                    BlessingEffectApplication.apply(p,new MobEffectInstance(holder,effect.durationTicks(),effect.level()-1));
                } else if(action instanceof WeaponSkillDefinition.AimedDamage damage) {
                    var target=aim(p,damage.range());if(target!=null&&damage(context,target,damage.amount()))hits++;
                } else if(action instanceof WeaponSkillDefinition.AreaDamage area) {
                    var targets=p.serverLevel().getEntitiesOfClass(LivingEntity.class,p.getBoundingBox().inflate(area.radius()),
                            target->target!=p&&validTarget(p,target)&&p.distanceToSqr(target)<=area.radius()*area.radius());
                    targets.sort(Comparator.<LivingEntity>comparingDouble(p::distanceToSqr).thenComparing(e->e.getUUID().toString()));
                    for(var target:targets.stream().limit(area.maximumTargets()).toList())
                        if(p.hasLineOfSight(target)&&damage(context,target,area.amount()))hits++;
                } else if(action instanceof WeaponSkillDefinition.PeriodicDamage periodic) {
                    LivingEntity target=aim(p,periodic.range());
                    if(target!=null) {
                        if(damage(context,target,periodic.amount()))hits++;
                        if(periodic.pulses()>1&&target.isAlive()) {
                            PENDING.computeIfAbsent(p.server,s->new ArrayList<>()).add(new Pulse(context,target,periodic,hits,SkillMana.now(p)+periodic.intervalTicks()));
                            scheduled=true;
                        }
                    }
                }
                cancelled=false;p.swing(net.minecraft.world.InteractionHand.MAIN_HAND,true);
            } finally {
                if(!scheduled)NeoForge.EVENT_BUS.post(new SkillCastEvents.Finished(context,hits,cancelled));
            }
            // A valid offensive cast pays even when every target was missed or blocked.
            return WeaponSkillRuntime.Result.SUCCESS;
        } finally {CASTING.remove(p);}
    }
    private static boolean effectNeeded(ServerPlayer p,WeaponSkillDefinition.SelfEffect effect) {
        var holder=BuiltInRegistries.MOB_EFFECT.getHolder(effect.effectId()).orElse(null);
        if(holder==null||holder.value().isInstantenous()||!holder.value().isBeneficial())return false;
        var active=p.getEffect(holder);if(active==null)return true;
        Tag encoded=MobEffectInstance.CODEC.encodeStart(p.registryAccess().createSerializationContext(NbtOps.INSTANCE),active).result().orElse(null);
        while(encoded instanceof CompoundTag data) {
            if(data.getInt("amplifier")>=effect.level()-1&&(data.getInt("duration")==-1||data.getInt("duration")>=effect.durationTicks()))return false;
            encoded=data.get("hidden_effect");
        }
        return true;
    }
    private static boolean loaded(ServerPlayer p,double radius) {
        for(int x=(int)Math.floor(p.getX()-radius-2)>>4;x<=((int)Math.floor(p.getX()+radius+2)>>4);x++)
            for(int z=(int)Math.floor(p.getZ()-radius-2)>>4;z<=((int)Math.floor(p.getZ()+radius+2)>>4);z++)
                if(!p.serverLevel().getChunkSource().hasChunk(x,z))return false;
        return true;
    }
    private static LivingEntity aim(ServerPlayer p,double range) {
        Vec3 start=p.getEyePosition(),end=start.add(p.getLookAngle().scale(range));
        var block=p.level().clip(new ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,p));
        if(block.getType()!=HitResult.Type.MISS)end=block.getLocation();
        var hit=ProjectileUtil.getEntityHitResult(p,start,end,p.getBoundingBox().expandTowards(end.subtract(start)).inflate(1),
                entity->entity instanceof LivingEntity living&&validTarget(p,living)&&entity.isPickable(),start.distanceToSqr(end));
        return hit!=null&&hit.getEntity() instanceof LivingEntity living&&validTarget(p,living)?living:null;
    }
    private static boolean validTarget(ServerPlayer p,LivingEntity target) {
        return CombatTargeting.canDamage(p,target);
    }
    private static boolean damage(SkillCastEvents.Context context,LivingEntity target,double amount) {
        ServerPlayer p=context.player();if(!validTarget(p,target))return false;
        float before=target.getHealth()+target.getAbsorptionAmount();
        boolean accepted=target.hurt(p.damageSources().indirectMagic(p,p),(float)amount);
        boolean hit=accepted&&target.getHealth()+target.getAbsorptionAmount()<before;
        if(hit)NeoForge.EVENT_BUS.post(new SkillCastEvents.Hit(context,target));
        return hit;
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var list=PENDING.get(event.getServer());if(list==null)return;
        long now=event.getServer().overworld().getGameTime();
        for(Pulse pulse:List.copyOf(list)) {
            var p=pulse.context.player();boolean valid=p.server.getPlayerList().getPlayer(p.getUUID())==p&&p.isAlive()&&!p.isSpectator()
                    &&validTarget(p,pulse.target)&&p.distanceToSqr(pulse.target)<=pulse.action.range()*pulse.action.range();
            if(!valid) {list.remove(pulse);NeoForge.EVENT_BUS.post(new SkillCastEvents.Finished(pulse.context,pulse.hits,true));continue;}
            if(now<pulse.nextTick)continue;
            boolean complete=false;
            try {
                if(p.hasLineOfSight(pulse.target)&&damage(pulse.context,pulse.target,pulse.action.amount()))pulse.hits++;
                pulse.remaining--;pulse.nextTick=now+pulse.action.intervalTicks();
                complete=true;
            } finally {
                if(!complete||pulse.remaining==0||!pulse.target.isAlive()) {
                    list.remove(pulse);NeoForge.EVENT_BUS.post(new SkillCastEvents.Finished(pulse.context,pulse.hits,!complete));
                }
            }
        }
        if(list.isEmpty())PENDING.remove(event.getServer());
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        var pending=PENDING.remove(event.getServer());
        if(pending!=null)for(var pulse:pending)NeoForge.EVENT_BUS.post(new SkillCastEvents.Finished(pulse.context,pulse.hits,true));
        CASTING.removeIf(p->p.server==event.getServer());
    }
    private static final class Pulse {
        final SkillCastEvents.Context context;final LivingEntity target;final WeaponSkillDefinition.PeriodicDamage action;
        int hits,remaining;long nextTick;
        Pulse(SkillCastEvents.Context context,LivingEntity target,WeaponSkillDefinition.PeriodicDamage action,int hits,long nextTick) {
            this.context=context;this.target=target;this.action=action;this.hits=hits;remaining=action.pulses()-1;this.nextTick=nextTick;
        }
    }
}
