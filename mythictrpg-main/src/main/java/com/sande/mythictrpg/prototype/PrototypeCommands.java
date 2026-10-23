package com.sande.mythictrpg.prototype;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.skills.SkillMana;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Explicit OP practice controls. No blocks, existing inventories, buffs, cooldowns, or player histories are reset. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class PrototypeCommands {
    private record Arena(ServerPlayer owner,List<UUID> targets) { }
    private record Scheduled(ServerPlayer player,UUID target,String kind,long due) { }
    private static final Map<MinecraftServer,Map<UUID,Arena>> ARENAS=new IdentityHashMap<>();
    private static final Map<MinecraftServer,List<Scheduled>> SCHEDULED=new IdentityHashMap<>();
    private PrototypeCommands() { }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mythprototype").requires(s->s.hasPermission(2))
            .then(PrototypeGrantCommands.grantBuilder())
            .then(Commands.literal("arena").executes(c->create(c.getSource().getPlayerOrException())))
            .then(Commands.literal("join").then(Commands.argument("owner",EntityArgument.player()).executes(c->{
                boolean joined=PrototypeCombatAccess.joinTrial(c.getSource().getPlayerOrException(),EntityArgument.getPlayer(c,"owner"));
                c.getSource().sendSuccess(()->Component.literal(joined?"시험장에 참가했습니다. 최대 5명입니다.":"동일 영역의 유효한 시험장에 참가할 수 없습니다."),false);return joined?1:0;})))
            .then(Commands.literal("end").executes(c->{end(c.getSource().getPlayerOrException());return 1;}))
            .then(Commands.literal("status").executes(c->{status(c.getSource().getPlayerOrException());return 1;}))
            .then(Commands.literal("targets").executes(c->{var p=c.getSource().getPlayerOrException();for(UUID id:PrototypeCombatAccess.targets(p)) {
                var entity=p.serverLevel().getEntity(id);if(entity instanceof LivingEntity target&&target.isAlive())target.setHealth(target.getMaxHealth());
            }p.sendSystemMessage(Component.literal("살아 있는 시험 표적의 체력을 회복했습니다. 플레이어 자원은 유지됩니다."));return 1;}))
            .then(Commands.literal("attack").then(Commands.argument("kind",StringArgumentType.word())
                .suggests((c,b)->{for(String s:List.of("melee","projectile","area","mechanic","unclassified"))b.suggest(s);return b.buildFuture();})
                .executes(c->schedule(c.getSource().getPlayerOrException(),StringArgumentType.getString(c,"kind"),40))
                .then(Commands.argument("delayTicks",IntegerArgumentType.integer(1,200)).executes(c->schedule(c.getSource().getPlayerOrException(),StringArgumentType.getString(c,"kind"),IntegerArgumentType.getInteger(c,"delayTicks")))))));
    }
    private static int create(ServerPlayer p) {
        var current=PrototypeCombatAccess.scope(p).orElse(null);
        if(current!=null&&!PrototypeCombatAccess.wilderness(p,current)) {p.sendSystemMessage(Component.literal("이미 시험장 또는 레이드에 있습니다. /mythprototype end 후 다시 등록하세요."));return 0;}
        Vec3 direction=new Vec3(-Math.sin(Math.toRadians(p.getYRot())),0,Math.cos(Math.toRadians(p.getYRot())));
        List<UUID> targets=new ArrayList<>();
        for(int i=0;i<4;i++) {
            Vec3 pos=p.position().add(direction.scale(3+2*i));
            var target=EntityType.HUSK.create(p.serverLevel());
            if(target==null||!p.serverLevel().hasChunkAt(net.minecraft.core.BlockPos.containing(pos))) {discard(p,targets);return 0;}
            target.moveTo(pos);target.setNoAi(true);target.setSilent(true);target.setPersistenceRequired();
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);target.getAttribute(Attributes.ARMOR).setBaseValue(0);
            target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);target.setHealth(200);
            target.setCustomName(Component.literal("전투 시험 표적 "+(i+1)));target.setCustomNameVisible(true);
            if(!p.serverLevel().noCollision(target,target.getBoundingBox())||!p.serverLevel().addFreshEntity(target)) {
                target.discard();discard(p,targets);p.sendSystemMessage(Component.literal("전방 3·5·7·9칸에 빈 공간이 필요합니다. 평지에서 등록하세요."));return 0;
            }
            targets.add(target.getUUID());
        }
        AABB bounds=new AABB(p.position().add(-16,-4,-16),p.position().add(16,12,16));
        if(!PrototypeCombatAccess.registerTrial(p,bounds,targets)) {discard(p,targets);return 0;}
        ARENAS.computeIfAbsent(p.server,s->new HashMap<>()).put(p.getUUID(),new Arena(p,List.copyOf(targets)));
        p.sendSystemMessage(Component.literal("시험장 등록: 현재 위치 중심 32×32칸, 표적 4개. /mythprototype attack melee 는 2초 뒤 피해 4를 줍니다. /mythprototype end 로 종료."));
        return 1;
    }
    private static int schedule(ServerPlayer p,String kind,int delay) {
        if(!Set.of("melee","projectile","area","mechanic","unclassified").contains(kind))return 0;
        var target=PrototypeCombatAccess.targets(p).stream().map(p.serverLevel()::getEntity).filter(e->e instanceof LivingEntity&&e.isAlive())
            .min(Comparator.comparingDouble(e->e.distanceToSqr(p))).orElse(null);
        var queue=SCHEDULED.computeIfAbsent(p.server,s->new ArrayList<>());
        if(target==null||queue.stream().filter(s->s.player==p).count()>=8)return 0;
        queue.add(new Scheduled(p,target.getUUID(),kind,SkillMana.now(p)+delay));
        p.sendSystemMessage(Component.literal(kind+" 공격 예약: "+delay+"틱 뒤, 피해 4. 패링은 근접 공격에만 적용됩니다."));return 1;
    }
    private static void status(ServerPlayer p) {
        var scope=PrototypeCombatAccess.scope(p);
        p.sendSystemMessage(Component.literal("마나 "+String.format(Locale.ROOT,"%.2f / %.2f",SkillMana.mana(p),SkillMana.maximumMana(p))
                +" · 전투 범위 "+scope.map(s->s.id().toString()).orElse("없음")));
        for(UUID id:PrototypeCombatAccess.targets(p))if(p.serverLevel().getEntity(id) instanceof LivingEntity t)
            p.sendSystemMessage(Component.literal(t.getName().getString()+" HP "+t.getHealth()+" / "+t.getMaxHealth()));
    }
    private static void end(ServerPlayer p) {
        var arenas=ARENAS.get(p.server);Arena arena=arenas==null?null:arenas.remove(p.getUUID());
        if(arena!=null)discard(arena.owner,arena.targets);
        PrototypeCombatAccess.clearTrial(p);
        var queue=SCHEDULED.get(p.server);if(queue!=null)queue.removeIf(s->s.player==p);
        p.sendSystemMessage(Component.literal("시험장을 종료했습니다. 지급받은 장비·습득 권리·쿨타임은 유지됩니다."));
    }
    private static void discard(ServerPlayer owner,Collection<UUID> ids) {
        for(var level:owner.server.getAllLevels())for(UUID id:ids){var e=level.getEntity(id);if(e!=null)e.discard();}
    }
    private static boolean target(MinecraftServer server,UUID id){return ARENAS.getOrDefault(server,Map.of()).values().stream().anyMatch(a->a.targets.contains(id));}
    @SubscribeEvent public static void drops(LivingDropsEvent event){if(event.getEntity().level().getServer()!=null&&target(event.getEntity().level().getServer(),event.getEntity().getUUID()))event.setCanceled(true);}
    @SubscribeEvent public static void xp(LivingExperienceDropEvent event){if(event.getEntity().level().getServer()!=null&&target(event.getEntity().level().getServer(),event.getEntity().getUUID()))event.setDroppedExperience(0);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var queue=SCHEDULED.get(event.getServer());if(queue==null)return;
        long now=event.getServer().overworld().getGameTime();
        for(var scheduled:List.copyOf(queue))if(now>=scheduled.due) {
            queue.remove(scheduled);var p=scheduled.player;
            if(p.server.getPlayerList().getPlayer(p.getUUID())!=p)continue;
            var entity=p.serverLevel().getEntity(scheduled.target);
            if(entity instanceof LivingEntity enemy&&enemy.isAlive()) {
                float before=p.getHealth();
                PrototypeCombatAccess.hurt(p,enemy,PrototypeCatalog.id("test_"+scheduled.kind),enemy.getBoundingBox().getCenter(),4);
                p.sendSystemMessage(Component.literal("시험 공격 "+scheduled.kind+": 체력 피해 "+Math.max(0,before-p.getHealth())));
            }
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer p)end(p);}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){ARENAS.remove(event.getServer());SCHEDULED.remove(event.getServer());}
    @SubscribeEvent public static void stopping(ServerStoppingEvent event){for(var arena:ARENAS.getOrDefault(event.getServer(),Map.of()).values())discard(arena.owner,arena.targets);}
}
