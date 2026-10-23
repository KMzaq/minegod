package com.sande.mythictrpg.specialskill;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.engine.*;
import com.sande.mythictrpg.control.CrowdControlService;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.godavatar.GodAvatarDefinitionManager;
import com.sande.mythictrpg.quest.reward.SkillRewardBridge;
import com.sande.mythictrpg.raid.*;
import com.sande.mythictrpg.skills.SkillMana;
import com.sande.mythictrpg.skills.SkillCastEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;

/** All real effect authority remains with the game; the temporary avatar only presents the provider. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class SpecialSkillRuntime {
    public enum Result { SUCCESS, INVALID_PLAYER, NOT_OWNED, NOT_AUTHORED, NOT_IN_ACTIVE_RAID, CONDITION_FAILED,
        NO_MANA, MISSING_ITEM, COOLDOWN, RAID_LIMIT, DUPLICATE, UNAVAILABLE }
    private static final Set<ServerPlayer> CASTING=Collections.newSetFromMap(new IdentityHashMap<>());
    private record SlotCost(int slot,ItemStack expected,int count) { }
    private SpecialSkillRuntime() { }
    public static Result cast(ServerPlayer player,ResourceLocation id) {
        if(!player.server.isSameThread()||player.server.getPlayerList().getPlayer(player.getUUID())!=player||!player.isAlive()
                ||player.isSpectator()||player.isSleeping()||player.isUsingItem()||player.containerMenu!=player.inventoryMenu
                ||CrowdControlService.isStunned(player)||com.sande.mythictrpg.skills.CombatCastRuntime.locked(player))return Result.INVALID_PLAYER;
        var skill=SpecialSkillDefinitions.INSTANCE.find(id).orElse(null); if(skill==null)return Result.NOT_AUTHORED;
        if(!SkillRewardBridge.ownsSpecial(player,id))return Result.NOT_OWNED;
        var raid=activeRaid(player).orElse(null); if(raid==null)return Result.NOT_IN_ACTIVE_RAID;
        var god=GodDefinitionManager.INSTANCE.find(skill.providerGodId()).orElse(null);
        var appearance=GodAvatarDefinitionManager.INSTANCE.find(skill.providerGodId()).orElse(null);
        if(god==null||appearance==null||god.rank().isEmpty()
                ||god.rank().orElseThrow().ordinal()<com.sande.mythictrpg.quest.reward.blessing.GodRank.HIGHEST.ordinal())return Result.NOT_AUTHORED;
        if(skill.condition().isPresent()&&ConditionEngine.INSTANCE.evaluate(skill.condition().orElseThrow(),
                ConditionContexts.forServer(player.server,Optional.of(player.getUUID())))!=ConditionResult.MATCH)return Result.CONDITION_FAILED;
        var center=targetCenter(player,skill,raid).orElse(null); if(center==null)return Result.CONDITION_FAILED;
        if(!CASTING.add(player))return Result.DUPLICATE;
        try {
            var state=SpecialSkillState.get(player.server);
            state.pruneTerminalAttempts(RaidState.get(player.server).attempts().stream().filter(RaidState.Attempt::terminal)
                    .map(RaidState.Attempt::id).collect(java.util.stream.Collectors.toSet()));
            long now=System.currentTimeMillis(),tick=player.server.overworld().getGameTime();
            var check=state.canUse(player.getUUID(),id,raid.id(),skill.limits(),now,tick);
            if(check!=SpecialSkillState.Result.READY)return map(check);
            if(!SkillMana.canSpend(player,skill.manaCost()))return Result.NO_MANA;
            var costs=itemCosts(player,skill.limits().consumables()); if(costs==null)return Result.MISSING_ITEM;
            var avatar=SpecialSupportEntities.SUPPORT_AVATAR.get().create(player.serverLevel());
            if(avatar==null)return Result.UNAVAILABLE;
            avatar.initialize(skill.providerGodId(),god.displayName(),appearance.appearance());
            avatar.moveTo(player.getX(),player.getY()+.1,player.getZ(),player.getYRot(),player.getXRot());
            if(!player.serverLevel().addFreshEntity(avatar))return Result.UNAVAILABLE;
            for(var cost:costs)if(!ItemStack.isSameItemSameComponents(cost.expected(),player.getInventory().getItem(cost.slot()))
                    ||player.getInventory().getItem(cost.slot()).getCount()<cost.count()) { avatar.discard();return Result.MISSING_ITEM; }
            // Nothing external is scheduled between cost validation and committing this cast.
            if(!SkillMana.spend(player,skill.manaCost())) { avatar.discard();return Result.NO_MANA; }
            var committed=state.commit(player.getUUID(),id,raid.id(),skill.limits(),now,tick);
            if(committed!=SpecialSkillState.Result.SUCCESS) {
                SkillMana.refund(player,skill.manaCost());avatar.discard();return map(committed);
            }
            for(var cost:costs)player.getInventory().getItem(cost.slot()).shrink(cost.count());
            player.getInventory().setChanged();player.inventoryMenu.broadcastChanges();
            var cast=new SkillCastEvents.Context(UUID.randomUUID(),player,SkillCastEvents.Source.SPECIAL,id,skill.manaCost(),0,
                    skill.action().type()!=SpecialSkillDefinition.ActionType.HEAL_ROSTER);
            int hits=0;boolean interrupted=true;
            try {
                NeoForge.EVENT_BUS.post(new SkillCastEvents.Start(cast));
                for(UUID member:raid.playerIds()) {
                    var recipient=player.server.getPlayerList().getPlayer(member);
                    if(recipient!=null&&recipient.serverLevel()==player.serverLevel())recipient.sendSystemMessage(
                            Component.literal(god.displayName().getString()+"가 "+skill.name()+"을 사용합니다."));
                }
                avatar.swing(net.minecraft.world.InteractionHand.MAIN_HAND,true);
                hits=execute(player,skill,raid,center,cast);interrupted=false;
            }finally {
                NeoForge.EVENT_BUS.post(new SkillCastEvents.Finished(cast,hits,interrupted));
            }
            return Result.SUCCESS;
        }finally { CASTING.remove(player); }
    }
    public static Optional<RaidState.Attempt> activeRaid(ServerPlayer player) {
        var state=RaidState.get(player.server); if(!state.ready())return Optional.empty();
        return state.membership(player.getUUID()).filter(attempt->attempt.status()==RaidState.Status.ACTIVE
                &&attempt.arena().filter(arena->arena.dimension().equals(player.serverLevel().dimension().location())
                    &&arena.bounds().contains(player.position())).isPresent()
                &&attempt.member(player.getUUID()).filter(member->!member.eliminated()&&!member.awaitingRespawn()).isPresent());
    }
    private static Result map(SpecialSkillState.Result result) {
        return switch(result) {
            case SUCCESS -> Result.SUCCESS;
            case COOLDOWN -> Result.COOLDOWN;
            case RAID_LIMIT -> Result.RAID_LIMIT;
            case DUPLICATE -> Result.DUPLICATE;
            default -> Result.UNAVAILABLE;
        };
    }
    /** Exact components and one shared availability ledger prevent overlapping requirements double-counting a stack. */
    static List<SlotCost> itemCosts(ServerPlayer player,List<com.sande.mythictrpg.quest.reward.NpcRewardEntry> requirements) {
        int size=player.getInventory().getContainerSize();int[] remaining=new int[size];
        for(int slot=0;slot<size;slot++)remaining[slot]=(slot<36||slot==40)?player.getInventory().getItem(slot).getCount():0;
        int[] counts=new int[size];
        for(var requirement:requirements) {
            ItemStack expected;
            try { expected=requirement.createStack(player.registryAccess()); }catch(RuntimeException invalid) { return null; }
            int needed=requirement.count();
            for(int slot=0;slot<size&&needed>0;slot++) {
                if(remaining[slot]==0||!ItemStack.isSameItemSameComponents(player.getInventory().getItem(slot),expected))continue;
                int take=Math.min(needed,remaining[slot]);remaining[slot]-=take;counts[slot]+=take;needed-=take;
            }
            if(needed>0)return null;
        }
        var result=new ArrayList<SlotCost>();for(int slot=0;slot<size;slot++)if(counts[slot]>0)
            result.add(new SlotCost(slot,player.getInventory().getItem(slot).copy(),counts[slot]));return result;
    }
    private static Optional<Vec3> targetCenter(ServerPlayer player,SpecialSkillDefinition skill,RaidState.Attempt raid) {
        if(skill.action().type()==SpecialSkillDefinition.ActionType.HEAL_ROSTER)return Optional.of(player.position());
        var start=player.getEyePosition();var end=start.add(player.getLookAngle().scale(skill.action().range()));
        double pad=skill.action().radius()+1;
        int minX=net.minecraft.util.Mth.floor(Math.min(start.x,end.x)-pad)>>4,maxX=net.minecraft.util.Mth.floor(Math.max(start.x,end.x)+pad)>>4;
        int minZ=net.minecraft.util.Mth.floor(Math.min(start.z,end.z)-pad)>>4,maxZ=net.minecraft.util.Mth.floor(Math.max(start.z,end.z)+pad)>>4;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)if(!player.serverLevel().getChunkSource().hasChunk(x,z))return Optional.empty();
        var block=player.level().clip(new ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
        if(block.getType()!=HitResult.Type.MISS)end=block.getLocation();
        return raid.arena().orElseThrow().bounds().contains(end)?Optional.of(end):Optional.empty();
    }
    private static int execute(ServerPlayer player,SpecialSkillDefinition skill,RaidState.Attempt raid,Vec3 center,SkillCastEvents.Context cast) {
        int hits=0;double radius=skill.action().radius();
        if(skill.action().type()==SpecialSkillDefinition.ActionType.HEAL_ROSTER) {
            for(UUID member:raid.playerIds()) {
                var recipient=player.server.getPlayerList().getPlayer(member);
                if(recipient==null||!recipient.isAlive()||recipient.serverLevel()!=player.serverLevel()
                        ||recipient.position().distanceToSqr(center)>radius*radius||activeRaid(recipient).filter(run->run.id().equals(raid.id())).isEmpty())continue;
                float before=recipient.getHealth();recipient.heal((float)skill.action().amount());
                if(recipient.getHealth()>before)hits++;
            }
            player.serverLevel().sendParticles(ParticleTypes.HEART,center.x,center.y+1,center.z,12,radius/2,.5,radius/2,.1);return hits;
        }
        if(skill.action().type()==SpecialSkillDefinition.ActionType.LIGHTNING_STRIKE) {
            for(int y=0;y<=12;y++)player.serverLevel().sendParticles(ParticleTypes.ELECTRIC_SPARK,center.x,center.y+y,center.z,5,.2,.2,.2,.01);
        }else player.serverLevel().sendParticles(ParticleTypes.CRIT,center.x,center.y,center.z,40,radius/2,1,radius/2,.1);
        var targets=player.serverLevel().getEntitiesOfClass(LivingEntity.class,new AABB(center,center).inflate(radius),target->
                com.sande.mythictrpg.combat.CombatTargeting.canDamage(player,target)
                        &&raid.isLiveEntity(target.getUUID())
                        &&target.getPersistentData().hasUUID("mythictrpg_raid_attempt")
                        &&target.getPersistentData().getUUID("mythictrpg_raid_attempt").equals(raid.id())
                        &&raid.arena().orElseThrow().bounds().contains(target.position())&&target.position().distanceToSqr(center)<=radius*radius);
        for(var target:targets) {
            if(activeRaid(player).filter(attempt->attempt.id().equals(raid.id())).isEmpty())break;
            if(target.hurt(player.damageSources().indirectMagic(player,player),(float)skill.action().amount())) {
                hits++;NeoForge.EVENT_BUS.post(new SkillCastEvents.Hit(cast,target));
                if(skill.action().stunTicks()>0&&target.isAlive())CrowdControlService.applyStun(target,skill.action().stunTicks(),true,false);
            }
        }
        return hits;
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mythspecial")
                .then(Commands.literal("list").executes(context-> {
                    var player=context.getSource().getPlayerOrException();
                    var list=SpecialSkillDefinitions.INSTANCE.snapshot().values().stream().filter(skill->SkillRewardBridge.ownsSpecial(player,skill.id()))
                            .sorted(Comparator.comparing(SpecialSkillDefinition::id)).toList();
                    for(var skill:list)context.getSource().sendSuccess(()->Component.literal(skill.id()+" · "+skill.name()+" · 재사용 "
                            +Math.ceil(SpecialSkillState.get(player.server).remainingMillis(player.getUUID(),skill.id(),System.currentTimeMillis())/1000.0)+"초"),false);
                    if(list.isEmpty())context.getSource().sendSuccess(()->Component.literal("습득한 특수스킬이 없습니다."),false);return list.size();
                }))
                .then(Commands.literal("use").then(Commands.argument("skill",StringArgumentType.word()).executes(context-> {
                    ResourceLocation id=ResourceLocation.tryParse(StringArgumentType.getString(context,"skill"));
                    if(id==null)return 0;var result=cast(context.getSource().getPlayerOrException(),id);
                    context.getSource().sendSuccess(()->Component.literal(message(result)),false);return result==Result.SUCCESS?1:0;
                }))));
    }
    private static String message(Result result) {
        return switch(result) {
            case SUCCESS -> "특수스킬을 사용했습니다.";
            case INVALID_PLAYER -> "지금은 특수스킬을 사용할 수 없습니다.";
            case NOT_OWNED -> "아직 습득하지 않은 특수스킬입니다.";
            case NOT_AUTHORED -> "해당 특수스킬의 정의를 사용할 수 없습니다.";
            case NOT_IN_ACTIVE_RAID -> "진행 중인 본인 레이드의 전투 구역에서 사용하세요.";
            case CONDITION_FAILED -> "특수스킬의 사용 조건이나 대상 위치를 충족하지 못했습니다.";
            case NO_MANA -> "마나가 부족하거나 지금 다른 스킬을 사용 중입니다.";
            case MISSING_ITEM -> "필요한 소모 아이템이 부족합니다.";
            case COOLDOWN -> "아직 특수스킬의 재사용 시간이 지나지 않았습니다.";
            case RAID_LIMIT -> "이번 레이드 도전에서 사용할 수 있는 횟수를 모두 사용했습니다.";
            case DUPLICATE -> "이미 처리한 사용 요청입니다.";
            case UNAVAILABLE -> "특수스킬 상태를 확인할 수 없어 사용하지 않았습니다.";
        };
    }
}
