package com.sande.mythictrpg.prototype;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.combat.CombatTargeting;
import com.sande.mythictrpg.raid.RaidState;
import com.sande.mythictrpg.specialskill.SpecialSkillRuntime;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Game-owned encounter membership and per-hit permissions, never inferred from client tags. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class PrototypeCombatAccess {
    public record Scope(UUID id,ResourceLocation dimension,AABB bounds,boolean dashAllowed) { }
    public record Attack(UUID token,Vec3 origin,boolean parry,boolean dodge,boolean knockback) { }
    private record Trial(Scope scope,UUID owner,Set<UUID> members,Set<UUID> targets) { }
    private record Pending(ServerPlayer victim,Attack attack) { }
    private static final Map<MinecraftServer,Map<UUID,Trial>> TRIALS=new IdentityHashMap<>();
    private static final Map<DamageSource,Pending> ATTACKS=new IdentityHashMap<>();
    private static final ResourceLocation SEALED=ResourceLocation.parse("mythictrpg:trial/sealed_guardian");
    private PrototypeCombatAccess() { }
    public static Optional<Scope> scope(ServerPlayer p) {
        var state=RaidState.get(p.server);
        if(!state.ready())return Optional.empty();
        var raid=SpecialSkillRuntime.activeRaid(p);
        if(raid.isPresent())return raid.get().arena().map(a->new Scope(raid.get().id(),a.dimension(),a.bounds(),raid.get().definition().id().equals(SEALED)));
        // An invalid/ended active arena cannot silently become an unrestricted wilderness cast.
        if(frozenRaidMembership(p))return Optional.empty();
        var trial=trials(p.server).values().stream().filter(t->t.members.contains(p.getUUID())).findFirst();
        if(trial.isPresent())return inside(p,trial.get().scope)?Optional.of(trial.get().scope):Optional.empty();
        if(reservedPosition(p,p.position()))return Optional.empty();
        var level=p.serverLevel();var border=level.getWorldBorder();
        return Optional.of(new Scope(wildernessId(p),level.dimension().location(),
                new AABB(border.getMinX(),level.getMinBuildHeight(),border.getMinZ(),
                        border.getMaxX(),level.getMaxBuildHeight(),border.getMaxZ()),true));
    }
    public static boolean wilderness(ServerPlayer p,Scope scope){return scope.id.equals(wildernessId(p));}
    private static UUID wildernessId(ServerPlayer p){return UUID.nameUUIDFromBytes(("mythictrpg:wilderness/"+p.getUUID()+"/"+p.serverLevel().dimension().location()).getBytes(StandardCharsets.UTF_8));}
    private static boolean frozenRaidMembership(ServerPlayer p) {
        return RaidState.get(p.server).attempts().stream().anyMatch(a->a.member(p.getUUID()).isPresent()&&switch(a.status()) {
            // VICTORY_PENDING is already cleaned up and returned; only reward settlement remains.
            case STARTING,ACTIVE,CLEANUP_PENDING->true;default->false;
        });
    }
    public static boolean valid(ServerPlayer p,Scope expected) {
        return p.server.isSameThread()&&p.isAlive()&&!p.isSpectator()&&scope(p).filter(expected::equals).isPresent();
    }
    public static boolean enemy(ServerPlayer p,Scope scope,LivingEntity target) {
        if(!valid(p,scope)||!CombatTargeting.canDamage(p,target)||target.level()!=p.level()||!scope.bounds.contains(target.position()))return false;
        Trial trial=trials(p.server).get(scope.id);
        if(trial!=null)return trial.targets.contains(target.getUUID());
        if(wilderness(p,scope))return !reservedPosition(p,target.position())
                &&trials(p.server).values().stream().noneMatch(t->t.targets.contains(target.getUUID()))
                &&RaidState.get(p.server).attempts().stream().noneMatch(a->a.ownsArena()&&a.isLiveEntity(target.getUUID()));
        return RaidState.get(p.server).find(scope.id).filter(a->a.status()==RaidState.Status.ACTIVE&&a.isLiveEntity(target.getUUID())).isPresent();
    }
    private static boolean reservedPosition(ServerPlayer p,Vec3 position) {
        ResourceLocation dimension=p.serverLevel().dimension().location();
        return trials(p.server).values().stream().anyMatch(t->t.scope.dimension.equals(dimension)&&t.scope.bounds.contains(position))
                ||RaidState.get(p.server).attempts().stream().filter(RaidState.Attempt::ownsArena)
                .anyMatch(a->a.arena().filter(arena->arena.dimension().equals(dimension)&&arena.bounds().contains(position)).isPresent());
    }
    /** Dash separately sweeps collision and floor support; this check never loads missing chunks. */
    public static boolean safeMove(ServerPlayer p,Scope scope,Vec3 destination) {
        if(!valid(p,scope)||!finite(destination))return false;
        AABB box=p.getBoundingBox().move(destination.subtract(p.position()));
        if(box.minX<scope.bounds.minX||box.maxX>scope.bounds.maxX||box.minY<scope.bounds.minY||box.maxY>scope.bounds.maxY
                ||box.minZ<scope.bounds.minZ||box.maxZ>scope.bounds.maxZ||!p.serverLevel().getWorldBorder().isWithinBounds(box))return false;
        for(int x=((int)Math.floor(box.minX))>>4;x<=((int)Math.floor(box.maxX))>>4;x++)
            for(int z=((int)Math.floor(box.minZ))>>4;z<=((int)Math.floor(box.maxZ))>>4;z++)
                if(!p.serverLevel().getChunkSource().hasChunk(x,z))return false;
        return true;
    }
    public static Optional<Attack> attack(ServerPlayer victim,DamageSource source) {
        Pending pending=ATTACKS.get(source);
        if(pending!=null)return pending.victim==victim?Optional.of(pending.attack):Optional.empty();
        var scope=scope(victim).orElse(null);
        // Authored encounters retain their per-hit allowlist, even for vanilla mob_attack damage.
        if(scope==null||!wilderness(victim,scope))return Optional.empty();
        if(source.getEntity() instanceof ServerPlayer attacker&&!CombatTargeting.canDamage(attacker,victim))return Optional.empty();
        if((source.is(DamageTypes.PLAYER_ATTACK)||source.is(DamageTypes.MOB_ATTACK))
                &&source.getEntity() instanceof LivingEntity attacker&&source.getDirectEntity()==attacker
                &&enemy(victim,scope,attacker))
            return Optional.of(new Attack(UUID.randomUUID(),attacker.getBoundingBox().getCenter(),true,true,true));
        boolean blade=source.is(ResourceKey.create(Registries.DAMAGE_TYPE,PrototypeCatalog.id("blade_wave")))
                &&source.getEntity() instanceof ServerPlayer;
        boolean projectile=source.getDirectEntity() instanceof Projectile&&(source.is(DamageTypes.ARROW)
                ||source.is(DamageTypes.TRIDENT)||source.is(DamageTypes.MOB_PROJECTILE)||source.is(DamageTypes.THROWN)
                ||source.is(DamageTypes.FIREBALL)||source.is(DamageTypes.UNATTRIBUTED_FIREBALL)
                ||source.is(DamageTypes.WITHER_SKULL)||source.is(DamageTypes.FIREWORKS));
        if(blade||projectile) {
            Vec3 origin=source.getDirectEntity()!=null?source.getDirectEntity().getBoundingBox().getCenter():source.getSourcePosition();
            if(finite(origin))return Optional.of(new Attack(UUID.randomUUID(),origin,false,true,true));
        }
        return Optional.empty();
    }
    /** Trusted encounter wraps exactly one hurt call. Permission cannot survive or replay its source. */
    public static boolean hurt(ServerPlayer victim,LivingEntity attacker,ResourceLocation attackId,Vec3 origin,float amount) {
        var scope=scope(victim).orElse(null);
        if(scope==null||!enemy(victim,scope,attacker)||!finite(origin)||!Float.isFinite(amount)||amount<=0)return false;
        boolean trial=trials(victim.server).containsKey(scope.id);
        String key=attackId.toString();
        boolean melee=trial?key.equals("proto_cbt:test_melee"):key.equals("mythictrpg:trial/guardian_sweep");
        boolean area=trial?key.equals("proto_cbt:test_area"):key.equals("mythictrpg:trial/guardian_ring");
        boolean projectile=trial&&key.equals("proto_cbt:test_projectile");
        DamageSource source=projectile?new DamageSource(victim.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE,PrototypeCatalog.id("practice_projectile"))),attacker,attacker,origin)
                :victim.damageSources().mobAttack(attacker);
        if(melee||area||projectile)ATTACKS.put(source,new Pending(victim,new Attack(UUID.randomUUID(),origin,melee,true,false)));
        try{return victim.hurt(source,amount);}finally{ATTACKS.remove(source);}
    }
    /** Server-side practice fixture; player-facing registration commands require OP. */
    public static boolean registerTrial(ServerPlayer p,AABB bounds,Collection<UUID> targets) {
        if(!p.server.isSameThread()||!inside(p,new Scope(UUID.randomUUID(),p.serverLevel().dimension().location(),bounds,true))
                ||bounds.getXsize()>96||bounds.getYsize()>48||bounds.getZsize()>96||targets.size()>32
                ||scope(p).filter(s->wilderness(p,s)).isEmpty()
                ||trials(p.server).values().stream().anyMatch(t->t.members.contains(p.getUUID())))return false;
        Scope scope=new Scope(UUID.randomUUID(),p.serverLevel().dimension().location(),bounds,true);
        trials(p.server).put(scope.id,new Trial(scope,p.getUUID(),new LinkedHashSet<>(Set.of(p.getUUID())),new LinkedHashSet<>(targets)));
        return true;
    }
    public static boolean joinTrial(ServerPlayer p,ServerPlayer owner) {
        var trial=trials(p.server).values().stream().filter(t->t.members.contains(owner.getUUID())).findFirst().orElse(null);
        if(trial==null||trial.members.size()>=5||!inside(p,trial.scope)
                ||trials(p.server).values().stream().anyMatch(t->t.members.contains(p.getUUID()))
                ||RaidState.get(p.server).membership(p.getUUID()).isPresent()||frozenRaidMembership(p))return false;
        return trial.members.add(p.getUUID());
    }
    public static void clearTrial(ServerPlayer p) {
        trials(p.server).entrySet().removeIf(e->{var t=e.getValue();t.members.remove(p.getUUID());return t.owner.equals(p.getUUID())||t.members.isEmpty();});
    }
    public static Set<UUID> targets(ServerPlayer p){return scope(p).map(s->trials(p.server).get(s.id)).map(t->Set.copyOf(t.targets)).orElse(Set.of());}
    private static Map<UUID,Trial> trials(MinecraftServer server){return TRIALS.computeIfAbsent(server,s->new HashMap<>());}
    private static boolean inside(ServerPlayer p,Scope s){return p.serverLevel().dimension().location().equals(s.dimension)&&s.bounds.contains(p.position());}
    private static boolean finite(Vec3 v){return v!=null&&Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z);}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer p)clearTrial(p);}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){TRIALS.remove(event.getServer());ATTACKS.clear();}
}
