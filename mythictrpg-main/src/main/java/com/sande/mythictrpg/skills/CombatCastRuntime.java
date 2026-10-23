package com.sande.mythictrpg.skills;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.control.CrowdControlService;
import com.sande.mythictrpg.prototype.PrototypeCombatAccess;
import com.sande.mythictrpg.weaponskill.WeaponSkillDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;

/** Paid, bounded prototype casts. No ownership is stored here and no potion effect is fabricated. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class CombatCastRuntime {
    public static final ResourceKey<DamageType> BLADE_DAMAGE=ResourceKey.create(Registries.DAMAGE_TYPE,ResourceLocation.parse("proto_cbt:blade_wave"));
    private static final Map<MinecraftServer,Map<UUID,Cast>> LOCKS=new IdentityHashMap<>();
    private static final Map<MinecraftServer,List<Cast>> WAVES=new IdentityHashMap<>();
    private static final int MAXIMUM_CASTS=256;
    private CombatCastRuntime() { }

    public static boolean isPrototypeAction(WeaponSkillDefinition.Action action) {
        return action instanceof WeaponSkillDefinition.BladeWave||action instanceof WeaponSkillDefinition.TimedParry||action instanceof WeaponSkillDefinition.ShortDash;
    }
    public static boolean locked(ServerPlayer player) {
        var cast=LOCKS.getOrDefault(player.server,Map.of()).get(player.getUUID());
        return cast!=null&&cast.context.player()==player&&SkillMana.now(player)<cast.endTick;
    }
    /** Null means a rejected pre-cast input. Nothing in this method charges resources. */
    public static PrototypeCombatAccess.Scope prepare(ServerPlayer player,SkillCastEvents.Source source,WeaponSkillDefinition.Action action) {
        if(!isPrototypeAction(action)||locked(player))return null;
        if(action instanceof WeaponSkillDefinition.ShortDash?source!=SkillCastEvents.Source.LEARNED:source!=SkillCastEvents.Source.WEAPON)return null;
        var scope=PrototypeCombatAccess.scope(player).orElse(null);
        if(scope==null||!PrototypeCombatAccess.valid(player,scope))return null;
        if(LOCKS.getOrDefault(player.server,Map.of()).size()+WAVES.getOrDefault(player.server,List.of()).size()>=MAXIMUM_CASTS)return null;
        if(action instanceof WeaponSkillDefinition.BladeWave) {
            if(player.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(BLADE_DAMAGE).isEmpty())return null;
            if(!loaded(player,new AABB(player.getEyePosition(),player.getEyePosition()).inflate(1)))return null;
        } else if(!DefensiveSkillRuntime.preflight(player,action,scope))return null;
        return scope;
    }
    /** Called once, only after the shared mana/cooldown transaction and Start snapshot. */
    public static void start(SkillCastEvents.Context context,WeaponSkillDefinition.Action action,PrototypeCombatAccess.Scope scope) {
        var player=context.player();var cast=new Cast(context,action,scope,SkillMana.now(player));
        var previous=LOCKS.computeIfAbsent(player.server,s->new HashMap<>()).put(player.getUUID(),cast);
        if(previous!=null&&!(previous.action instanceof WeaponSkillDefinition.BladeWave))finish(previous,previous.cancelled);
        if(action instanceof WeaponSkillDefinition.BladeWave) {
            WAVES.computeIfAbsent(player.server,s->new ArrayList<>()).add(cast);
            player.serverLevel().playSound(null,player.blockPosition(),SoundEvents.PLAYER_ATTACK_SWEEP,SoundSource.PLAYERS,0.4F,1.4F);
        } else DefensiveSkillRuntime.start(context,action,scope,cast.startTick);
    }
    /** Defense listeners revalidate at the actual damage event, including swaps between ticks. */
    public static boolean active(ServerPlayer player,SkillCastEvents.Context context) {
        var cast=LOCKS.getOrDefault(player.server,Map.of()).get(player.getUUID());
        if(cast==null||cast.context!=context||cast.cancelled||SkillMana.now(player)>=cast.endTick)return false;
        if(!valid(cast)||(context.source()==SkillCastEvents.Source.WEAPON&&!sameWeapon(cast))){abort(cast);return false;}
        return true;
    }
    /** Inventory/hotbar packet hooks can observe transient swaps even if restored within one tick. */
    public static void heldChanged(ServerPlayer player) {
        var cast=LOCKS.getOrDefault(player.server,Map.of()).get(player.getUUID());
        if(cast==null||cast.context.player()!=player)return;
        var current=player.getMainHandItem().copy();current.remove(DataComponents.DAMAGE);
        if(!ItemStack.isSameItemSameComponents(cast.observedWeapon,current)) {
            player.resetAttackStrengthTicker();cast.observedWeapon=current;
        }
        if(cast!=null&&!cast.cancelled&&cast.context.source()==SkillCastEvents.Source.WEAPON&&!sameWeapon(cast)
                &&(!(cast.action instanceof WeaponSkillDefinition.BladeWave)||!cast.launched))abort(cast);
    }
    private static boolean valid(Cast cast) {
        var p=cast.context.player();
        if(p.server.getPlayerList().getPlayer(p.getUUID())!=p||!p.isAlive()||p.isSpectator()||!PrototypeCombatAccess.valid(p,cast.scope))return false;
        // Once emitted, the projectile is independent of a later item/menu/stun state.
        return cast.action instanceof WeaponSkillDefinition.BladeWave&&cast.launched
                ||!p.isSleeping()&&p.containerMenu==p.inventoryMenu&&!CrowdControlService.isStunned(p);
    }
    private static boolean sameWeapon(Cast cast) {
        var held=cast.context.player().getMainHandItem();if(held.isEmpty())return false;
        var normalized=held.copy();normalized.remove(DataComponents.DAMAGE);
        return ItemStack.isSameItemSameComponents(cast.weapon,normalized);
    }
    private static void finish(Cast cast,boolean cancelled) {
        if(cast.finished)return;cast.finished=true;
        NeoForge.EVENT_BUS.post(new SkillCastEvents.Finished(cast.context,cast.hits,cancelled));
    }
    private static void abort(Cast cast) {
        if(cast.cancelled)return;cast.cancelled=true;
        if(!(cast.action instanceof WeaponSkillDefinition.BladeWave))DefensiveSkillRuntime.cancel(cast.context.player());
        finish(cast,true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void tick(ServerTickEvent.Pre event) {
        // Pre precedes world time increment; this is the time observed by subsequent entity/damage events.
        long now=event.getServer().overworld().getGameTime()+1;
        var locks=LOCKS.get(event.getServer());
        if(locks!=null)for(var cast:List.copyOf(locks.values())) {
            var player=cast.context.player();
            heldChanged(player);
            if(!valid(cast))abort(cast);
            else if(!cast.cancelled&&cast.context.source()==SkillCastEvents.Source.WEAPON&&!sameWeapon(cast)
                    &&(!(cast.action instanceof WeaponSkillDefinition.BladeWave)||!cast.launched))abort(cast);
            if(now>=cast.endTick) {
                locks.remove(player.getUUID(),cast);
                if(!(cast.action instanceof WeaponSkillDefinition.BladeWave)) {
                    DefensiveSkillRuntime.cancel(player);finish(cast,cast.cancelled);
                }
            } else if(!cast.cancelled&&!(cast.action instanceof WeaponSkillDefinition.BladeWave))DefensiveSkillRuntime.tick(player,now);
        }
        if(locks!=null&&locks.isEmpty())LOCKS.remove(event.getServer());
        var waves=WAVES.get(event.getServer());
        if(waves!=null)for(var cast:List.copyOf(waves)) {
            if(cast.cancelled||!valid(cast)) {abort(cast);waves.remove(cast);continue;}
            var action=(WeaponSkillDefinition.BladeWave)cast.action;
            if(!cast.launched&&now>=cast.startTick+action.windupTicks()) {
                if(!sameWeapon(cast)) {abort(cast);waves.remove(cast);continue;}
                cast.launched=true;cast.launchedTick=now;
            }
            if(!cast.launched||cast.lastWaveTick==now)continue;
            if(now>=cast.launchedTick+action.lifetimeTicks()){finish(cast,false);waves.remove(cast);continue;}
            cast.lastWaveTick=now;
            boolean complete=advance(cast,action);
            if(complete) {finish(cast,false);waves.remove(cast);}
        }
        if(waves!=null&&waves.isEmpty())WAVES.remove(event.getServer());
    }
    private static boolean advance(Cast cast,WeaponSkillDefinition.BladeWave action) {
        var p=cast.context.player();Vec3 start=cast.position;
        double step=Math.min(action.speed(),action.range()-cast.distance);
        if(step<=1.0E-8)return true;
        Vec3 end=start.add(cast.direction.scale(step));AABB swept=new AABB(start,end).inflate(action.radius()+1.0E-7);
        if(!loaded(p,swept)||!p.serverLevel().getWorldBorder().isWithinBounds(swept)
                ||!cast.scope.bounds().contains(start)||!cast.scope.bounds().contains(end))return true;
        double wall=firstBlock(p,start,end,action.radius());
        var targets=new ArrayList<Collision>();
        for(var entity:p.serverLevel().getEntities(p,swept,entity->entity.isAlive()&&!entity.isSpectator())) {
            LivingEntity target=entity instanceof LivingEntity living?living:entity instanceof PartEntity<?> part&&part.getParent() instanceof LivingEntity living?living:null;
            if(target==null||cast.collisions.contains(target.getUUID())||!PrototypeCombatAccess.enemy(p,cast.scope,target))continue;
            double t=intersection(entity.getBoundingBox().inflate(action.radius()),start,end);
            if(t<Double.POSITIVE_INFINITY&&t<wall-1.0E-8)targets.add(new Collision(target,entity,t));
        }
        targets.sort(Comparator.comparingDouble(Collision::fraction).thenComparingInt(c->c.collider().getId()));
        for(var collision:targets) {
            if(!valid(cast)){abort(cast);return true;}
            var target=collision.target();
            // Earlier hit listeners may change party membership or ownership in this very sweep.
            if(!PrototypeCombatAccess.enemy(p,cast.scope,target)||!cast.collisions.add(target.getUUID()))continue;
            double amount=cast.collisions.size()==1?action.firstDamage():action.subsequentDamage();
            float health=target.getHealth();
            var holder=p.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(BLADE_DAMAGE);
            // No direct living weapon entity: vanilla sharpness/strength/critical/charge are not copied.
            boolean accepted=collision.collider().hurt(new DamageSource(holder,null,p,start),(float)amount);
            if(accepted&&target.getHealth()<health) {
                cast.hits++;NeoForge.EVENT_BUS.post(new SkillCastEvents.Hit(cast.context,target));
                p.serverLevel().sendParticles(ParticleTypes.CRIT,target.getX(),target.getY()+target.getBbHeight()/2,target.getZ(),4,0.1,0.1,0.1,0);
            }
            if(cast.collisions.size()>=action.maximumTargets())return true;
        }
        if(wall<Double.POSITIVE_INFINITY) {
            Vec3 point=start.lerp(end,wall);p.serverLevel().sendParticles(ParticleTypes.CLOUD,point.x,point.y,point.z,3,0.04,0.04,0.04,0);
            return true;
        }
        cast.position=end;cast.distance+=step;
        p.serverLevel().sendParticles(ParticleTypes.END_ROD,end.x,end.y,end.z,2,0.03,0.03,0.03,0);
        return cast.distance>=action.range()-1.0E-8;
    }
    /** Continuous swept collision against actual voxel boxes, including thin/partial blocks. */
    static double firstBlock(ServerPlayer p,Vec3 start,Vec3 end,double radius) {
        double first=Double.POSITIVE_INFINITY;
        for(var shape:p.serverLevel().getBlockCollisions(p,new AABB(start,end).inflate(radius+1.0E-7)))
            for(var box:shape.toAabbs())first=Math.min(first,intersection(box.inflate(radius),start,end));
        return first;
    }
    static double intersection(AABB box,Vec3 start,Vec3 end) {
        if(box.contains(start))return 0;
        return box.clip(start,end).map(point->start.distanceTo(point)/start.distanceTo(end)).orElse(Double.POSITIVE_INFINITY);
    }
    private static boolean loaded(ServerPlayer player,AABB box) {
        for(int x=BlockPos.containing(box.minX,box.minY,box.minZ).getX()>>4;x<=(BlockPos.containing(box.maxX,box.maxY,box.maxZ).getX()>>4);x++)
            for(int z=BlockPos.containing(box.minX,box.minY,box.minZ).getZ()>>4;z<=(BlockPos.containing(box.maxX,box.maxY,box.maxZ).getZ()>>4);z++)
                if(!player.serverLevel().getChunkSource().hasChunk(x,z))return false;
        return true;
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void attack(AttackEntityEvent event) {
        if(event.getEntity() instanceof ServerPlayer player&&locked(player))event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void outgoing(LivingIncomingDamageEvent event) {
        if(event.getSource().getEntity() instanceof ServerPlayer player&&event.getSource().is(DamageTypes.PLAYER_ATTACK)&&locked(player))event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void item(PlayerInteractEvent.RightClickItem event) {
        if(event.getEntity() instanceof ServerPlayer player&&locked(player))event.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void use(LivingEntityUseItemEvent.Start event) {
        if(event.getEntity() instanceof ServerPlayer player&&locked(player))event.setCanceled(true);
    }
    @SubscribeEvent public static void left(PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof ServerPlayer player)cancel(player);
    }
    public static void cancel(ServerPlayer player) {
        var locks=LOCKS.get(player.server);var cast=locks==null?null:locks.get(player.getUUID());
        if(cast!=null&&cast.context.player()==player){locks.remove(player.getUUID(),cast);abort(cast);}
        var waves=WAVES.get(player.server);if(waves!=null)for(var wave:List.copyOf(waves))if(wave.context.player()==player){abort(wave);waves.remove(wave);}
        DefensiveSkillRuntime.cancel(player);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        var locks=LOCKS.remove(event.getServer());if(locks!=null)for(var cast:locks.values())abort(cast);
        var waves=WAVES.remove(event.getServer());if(waves!=null)for(var cast:waves)abort(cast);
    }
    private record Collision(LivingEntity target,Entity collider,double fraction) { }
    private static final class Cast {
        final SkillCastEvents.Context context;final WeaponSkillDefinition.Action action;final PrototypeCombatAccess.Scope scope;
        final long startTick,endTick;final ItemStack weapon;final Vec3 direction;final Set<UUID> collisions=new HashSet<>();
        ItemStack observedWeapon;Vec3 position;double distance;int hits;boolean cancelled,launched,finished;long lastWaveTick=Long.MIN_VALUE,launchedTick=Long.MIN_VALUE;
        Cast(SkillCastEvents.Context context,WeaponSkillDefinition.Action action,PrototypeCombatAccess.Scope scope,long startTick) {
            this.context=context;this.action=action;this.scope=scope;this.startTick=startTick;
            int duration=action instanceof WeaponSkillDefinition.BladeWave wave?wave.windupTicks()+wave.recoveryTicks()
                    :action instanceof WeaponSkillDefinition.TimedParry parry?parry.windupTicks()+parry.activeTicks()+parry.recoveryTicks()
                    :((WeaponSkillDefinition.ShortDash)action).windupTicks()+((WeaponSkillDefinition.ShortDash)action).movementTicks()+((WeaponSkillDefinition.ShortDash)action).recoveryTicks();
            endTick=startTick+duration;weapon=context.player().getMainHandItem().copy();weapon.remove(DataComponents.DAMAGE);
            observedWeapon=weapon.copy();
            position=context.player().getEyePosition();direction=context.player().getLookAngle().normalize();
        }
    }
}
