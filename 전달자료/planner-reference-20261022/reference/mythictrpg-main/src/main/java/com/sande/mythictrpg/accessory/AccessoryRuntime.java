package com.sande.mythictrpg.accessory;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.skills.*;
import com.sande.mythictrpg.control.CrowdControlService;
import com.sande.mythictrpg.control.CrowdControlAppliedEvent;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import java.util.*;

/** Owned entitlement equipment. Effects are active only in the two worn slots; power uses best two acquisitions. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class AccessoryRuntime {
    public record Worn(UUID entitlement,AccessoryDefinition definition){}
    private record CastEquipment(List<Worn> worn,ResourceLocation target){}
    private static final ResourceLocation SPEED=ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,"accessory_speed");
    private static final Set<UUID> KNOT_PROCESSED=new HashSet<>();
    private static final LinkedHashSet<UUID> FINISHED=new LinkedHashSet<>();
    private static final Map<UUID,CastEquipment> CAST_EQUIPMENT=new HashMap<>();
    private AccessoryRuntime(){}
    public static List<AccessoryRewardBridge.Entitlement> entitlements(ServerPlayer player){
        if(!player.server.isSameThread())return List.of();
        try{return List.copyOf(AccessoryRewardBridge.entitlements(player));}catch(RuntimeException unavailable){return List.of();}
    }
    public static List<Worn> worn(ServerPlayer player){
        var state=AccessoryState.get(player.server);if(!state.isReady())return List.of();
        var owned=entitlements(player);List<Worn> result=new ArrayList<>();
        for(int slot=0;slot<2;slot++){
            UUID id=state.equipped(player.getUUID(),slot);if(id==null)continue;
            var value=owned.stream().filter(e->e.id().equals(id)).findFirst().orElse(null);if(value==null)continue;
            AccessoryDefinitions.INSTANCE.find(value.definitionId()).ifPresent(definition->result.add(new Worn(id,definition)));
        }
        return List.copyOf(result);
    }
    public static double bonus(ServerPlayer player,AccessoryEffect.Kind kind){return worn(player).stream().mapToDouble(w->w.definition().effect(kind).map(AccessoryEffect::amount).orElse(0.0)).sum();}
    public static double maximumMana(ServerPlayer player){return Math.min(1_000_000,200+bonus(player,AccessoryEffect.Kind.MAX_MANA));}
    public static double effectiveLuckBonus(ServerPlayer player){return bonus(player,AccessoryEffect.Kind.LUCK);}
    public static double goldBonusFraction(ServerPlayer player){return bonus(player,AccessoryEffect.Kind.GOLD_BONUS_FRACTION);}
    public static double fixedPower(ServerPlayer player){
        if(!AccessoryRewardBridge.isReady(player))throw new IllegalStateException("ACCESSORY_OWNERSHIP_UNAVAILABLE");
        return AccessoryRewardBridge.entitlements(player).stream().map(e->AccessoryDefinitions.INSTANCE.find(e.definitionId())
                .orElseThrow(()->new IllegalStateException("UNKNOWN_OWNED_ACCESSORY:"+e.definitionId())))
                .mapToDouble(AccessoryDefinition::fixedPower).boxed().sorted(Comparator.reverseOrder()).limit(2).mapToDouble(Double::doubleValue).sum();
    }
    public static boolean equip(ServerPlayer player,int slot,UUID entitlement){
        if(!live(player)||!AccessoryRewardBridge.isReady(player)||slot<0||slot>1||entitlement!=null&&entitlements(player).stream().noneMatch(e->e.id().equals(entitlement)
                &&AccessoryDefinitions.INSTANCE.find(e.definitionId()).isPresent()))return false;
        if(Objects.equals(AccessoryState.get(player.server).equipped(player.getUUID(),slot),entitlement))return true;
        boolean changed=AccessoryState.get(player.server).equip(player.getUUID(),slot,entitlement);
        if(changed){updateSpeed(player);SkillMana.mana(player);if(entitlement!=null)tryCleanse(player);}return changed;
    }
    public static boolean linkTarget(ServerPlayer player,ResourceLocation id){
        if(!live(player)||id!=null&&!LearnedSkillRuntime.isOwned(player,id))return false;
        return AccessoryState.get(player.server).linkTarget(player.getUUID(),id);
    }
    public static Optional<ResourceLocation> linkTarget(ServerPlayer player){return AccessoryState.get(player.server).linkTarget(player.getUUID());}
    @SubscribeEvent public static void starting(ServerStartingEvent event){SkillMana.maximumProvider(AccessoryRuntime::maximumMana);}
    @SubscribeEvent public static void started(SkillCastEvents.Start event){
        var c=event.context();if(!live(c.player())||FINISHED.contains(c.castId())||CAST_EQUIPMENT.size()>=4096)return;
        CAST_EQUIPMENT.putIfAbsent(c.castId(),new CastEquipment(worn(c.player()),linkTarget(c.player()).orElse(null)));
    }
    private static double castBonus(CastEquipment equipment,AccessoryEffect.Kind kind){return equipment.worn().stream().mapToDouble(w->w.definition().effect(kind).map(AccessoryEffect::amount).orElse(0.0)).sum();}
    @SubscribeEvent public static void hit(SkillCastEvents.Hit event){
        var c=event.context();if(c.source()!=SkillCastEvents.Source.WEAPON||!live(c.player())||KNOT_PROCESSED.contains(c.castId())||FINISHED.contains(c.castId()))return;
        var equipment=CAST_EQUIPMENT.get(c.castId());if(equipment==null||equipment.target()==null)return;
        var target=equipment.target();double fraction=castBonus(equipment,AccessoryEffect.Kind.LINK_KNOT);if(fraction<=0)return;
        if(KNOT_PROCESSED.size()>=4096)return;
        var definition=LearnedSkillDefinitions.INSTANCE.find(target).orElse(null);if(definition==null)return;
        if(LearnedSkillRuntime.reduceCooldown(c.player(),target,(long)Math.floor(definition.cooldownTicks()*Math.min(1,fraction)))>0)
            KNOT_PROCESSED.add(c.castId());
    }
    @SubscribeEvent public static void finished(SkillCastEvents.Finished event){
        var c=event.context();KNOT_PROCESSED.remove(c.castId());
        var equipment=CAST_EQUIPMENT.remove(c.castId());
        if(!FINISHED.add(c.castId()))return;if(FINISHED.size()>4096)FINISHED.remove(FINISHED.iterator().next());
        if(equipment==null||event.cancelled()||!c.offensive()||event.hitCount()!=0||c.source()==SkillCastEvents.Source.SPECIAL||c.manaCost()<=0||!live(c.player()))return;
        double fraction=Math.min(1,castBonus(equipment,AccessoryEffect.Kind.MISS_REFUND));
        if(fraction>0)SkillMana.refund(c.player(),c.manaCost()*fraction);
    }
    @SubscribeEvent public static void cc(CrowdControlAppliedEvent event){
        if(event.entity()instanceof ServerPlayer player&&event.dispellable()&&!event.mechanic())tryCleanse(player);
    }
    public static boolean tryCleanse(ServerPlayer player){
        if(!live(player))return false;var state=AccessoryState.get(player.server);long now=System.currentTimeMillis();
        for(var item:worn(player)){
            var effect=item.definition().effect(AccessoryEffect.Kind.CLEANSE_CC).orElse(null);
            if(effect!=null&&state.canTrigger(player.getUUID(),item.entitlement(),effect.kind(),now,effect.cooldownMillis())
                    &&CrowdControlService.cleanseOne(player))return state.trigger(player.getUUID(),item.entitlement(),effect.kind(),now,effect.cooldownMillis());
        }return false;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void damage(LivingDamageEvent.Pre event){
        float value=event.getNewDamage();if(!Float.isFinite(value)||value<=0)return;
        if(event.getSource().is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY))return;
        if(event.getSource().getEntity()instanceof ServerPlayer attacker&&attacker!=event.getEntity()&&live(attacker))value+=(float)bonus(attacker,AccessoryEffect.Kind.DAMAGE_FLAT);
        if(event.getEntity()instanceof ServerPlayer victim&&live(victim))value=Math.max(0,value-(float)bonus(victim,AccessoryEffect.Kind.DAMAGE_REDUCTION_FLAT));
        if(Float.isFinite(value))event.setNewDamage(value);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void damaged(LivingDamageEvent.Post event){
        if(!(event.getEntity()instanceof ServerPlayer player)||!live(player)||!(event.getSource().getEntity()instanceof LivingEntity enemy)
                ||enemy==player||enemy.isAlliedTo(player)||!Float.isFinite(event.getNewDamage())||event.getNewDamage()<=0)return;
        float after=player.getHealth(),threshold=player.getMaxHealth()*.25f;
        if(after<=0||after>=threshold||after+event.getNewDamage()<threshold)return;
        var state=AccessoryState.get(player.server);long now=System.currentTimeMillis();
        for(var item:worn(player)){
            var e=item.definition().effect(AccessoryEffect.Kind.EMERGENCY_HEAL).orElse(null);
            if(e!=null&&state.canStartHealing(player.getUUID())&&state.trigger(player.getUUID(),item.entitlement(),e.kind(),now,e.cooldownMillis()))
                state.startHealing(player.getUUID(),item.entitlement(),player.getMaxHealth()*e.amount(),e.durationTicks());
        }
    }
    @SubscribeEvent public static void tick(PlayerTickEvent.Post event){
        if(!(event.getEntity()instanceof ServerPlayer player)||player.server.getPlayerList().getPlayer(player.getUUID())!=player)return;
        var state=AccessoryState.get(player.server);long tick=player.server.overworld().getGameTime();
        // Landing bookkeeping must see actual movement even if a second PlayerTick arrives after
        // this tick's healing already ran. Only a real collision may replenish airborne charges.
        if(player.isAlive()&&!player.isSpectator()&&player.onGround()
                &&!player.level().noCollision(player.getBoundingBox().move(0,-.05,0)))state.grounded(player.getUUID(),tick);
        if(tick<=state.lastTick(player.getUUID()))return;
        double healing=state.tickHealing(player.getUUID(),tick,player.isAlive());
        if(player.isAlive()&&!player.isSpectator()){
            healing+=bonus(player,AccessoryEffect.Kind.HEALTH_REGEN_PER_SECOND)/20;
            if(healing>0)player.heal((float)Math.min(healing,player.getMaxHealth()));
            updateSpeed(player);
        }else updateSpeed(player);
    }
    private static void updateSpeed(ServerPlayer player){
        var attribute=player.getAttribute(Attributes.MOVEMENT_SPEED);if(attribute==null)return;
        double amount=player.isAlive()&&!player.isSpectator()?bonus(player,AccessoryEffect.Kind.SPEED_FLAT):0;
        var current=attribute.getModifier(SPEED);if(current!=null&&current.amount()==amount)return;
        attribute.removeModifier(SPEED);if(amount>0)attribute.addTransientModifier(new AttributeModifier(SPEED,amount,AttributeModifier.Operation.ADD_VALUE));
    }
    public static boolean jump(ServerPlayer player){
        if(!live(player)||player.onGround()||player.getAbilities().flying||player.isFallFlying()||player.isInWaterOrBubble()||player.onClimbable()
                ||player.isPassenger()||CrowdControlService.isStunned(player))return false;
        int allowed=(int)bonus(player,AccessoryEffect.Kind.EXTRA_JUMPS);if(allowed<=0)return false;
        long tick=player.server.overworld().getGameTime();if(!AccessoryState.get(player.server).jump(player.getUUID(),tick,allowed))return false;
        var velocity=player.getDeltaMovement();
        player.setDeltaMovement(velocity.x,Math.max(.42,velocity.y),velocity.z);player.fallDistance=0;player.hasImpulse=true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player));return true;
    }
    private static boolean live(ServerPlayer player){return player.server.isSameThread()&&player.server.getPlayerList().getPlayer(player.getUUID())==player&&player.isAlive()&&!player.isSpectator();}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){KNOT_PROCESSED.clear();FINISHED.clear();CAST_EQUIPMENT.clear();}
    @SubscribeEvent public static void commands(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("mythaccessory").executes(c->{AccessoryNetwork.open(c.getSource().getPlayerOrException(),0,"");return 1;})
                .then(Commands.literal("link").then(Commands.argument("skill",ResourceLocationArgument.id()).executes(c->{var p=c.getSource().getPlayerOrException();boolean okay=linkTarget(p,ResourceLocationArgument.getId(c,"skill"));c.getSource().sendSuccess(()->Component.literal(okay?"연계 대상 스킬을 설정했습니다.":"보유한 습득 스킬만 지정할 수 있습니다."),false);return okay?1:0;})))
                .then(Commands.literal("unlink").executes(c->linkTarget(c.getSource().getPlayerOrException(),null)?1:0)));
    }
}
