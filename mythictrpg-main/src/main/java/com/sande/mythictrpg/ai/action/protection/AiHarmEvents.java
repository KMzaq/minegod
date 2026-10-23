package com.sande.mythictrpg.ai.action.protection;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.godavatar.GodAvatarEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Production damage hooks. No room, text or model output can clear protection. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class AiHarmEvents {
    private record Click(java.util.UUID target,long tick) { }
    private static final java.util.Map<ServerPlayer,Click> CLICKS=new java.util.WeakHashMap<>();
    private AiHarmEvents() { }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void attackSelected(net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) {
        if(event.getEntity() instanceof ServerPlayer player&&event.getTarget() instanceof GodAvatarEntity target)
            CLICKS.put(player,new Click(target.getUUID(),player.server.overworld().getGameTime()));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void incoming(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        GodAvatarEntity source = god(event.getSource());
        if (source == null) return;
        if(com.sande.mythictrpg.raid.RaidRuntime.controlsEntity(source))return; // Authored raid lifecycle owns its combat, not autonomous AI punishment.
        if (source.godId().isEmpty() || !AiHarmProtection.damageAllowed(player, source.godId().orElseThrow())) {
            event.setCanceled(true);
            if (source.getTarget() == player) { source.setTarget(null); source.getNavigation().stop(); }
        } else AiDeathLossGuard.warn(player,source);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void damaged(LivingDamageEvent.Post event) {
        if (!Float.isFinite(event.getNewDamage()) || event.getNewDamage() <= 0) return;
        if (event.getEntity() instanceof GodAvatarEntity target && target.godId().isPresent()
                && event.getSource().getEntity() instanceof ServerPlayer player
                && event.getSource().getDirectEntity() == player && event.getSource().is(DamageTypes.PLAYER_ATTACK)) {
            // Direct successful attacks only. Reflected damage, pets, lingering projectiles and mere chat cannot reopen combat.
            var click=CLICKS.remove(player);
            if(click==null||!click.target().equals(target.getUUID())||click.tick()!=player.server.overworld().getGameTime())return;
            AiHarmProtection.voluntaryAttack(player, target.godId().orElseThrow());
            AiDeathLossGuard.voluntaryAttack(player,target.godId().orElseThrow());
        }
    }

    /** Vanilla totem consumption precedes LivingDeathEvent; protect explicitly important totems before that. */
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void protectedTotem(LivingDamageEvent.Pre event) {
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        var actor=god(event.getSource());
        if(actor!=null&&com.sande.mythictrpg.raid.RaidRuntime.controlsEntity(actor))return;
        if(actor==null&&AiDeathLossGuard.explicitGod(player)==null)return;
        if(event.getNewDamage()<player.getHealth()+player.getAbsorptionAmount())return;
        for(var hand:net.minecraft.world.InteractionHand.values()) {
            var stack=player.getItemInHand(hand);
            if(stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)&&AiProtectedItems.rejection(player,stack).isPresent()) {
                event.setNewDamage(Math.max(0,player.getHealth()+player.getAbsorptionAmount()-1));return;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void died(LivingDeathEvent event) {
        // Unlike LivingDamageEvent.Post this is after the totem-resurrection check.
        if (event.getEntity() instanceof ServerPlayer player) {
            GodAvatarEntity source = god(event.getSource());
            if (source != null && !com.sande.mythictrpg.raid.RaidRuntime.controlsEntity(source) && source.godId().isPresent() && !player.isAlive())
                AiHarmProtection.afterDamage(player, source.godId().orElseThrow(), true);
            if(!player.isAlive())AiDeathLossGuard.clear(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void preventUnwarnedLoss(LivingDeathEvent event) {
        if(!(event.getEntity() instanceof ServerPlayer player))return;
        var actor=god(event.getSource());
        if(actor!=null&&com.sande.mythictrpg.raid.RaidRuntime.controlsEntity(actor))return;
        var god=actor==null?AiDeathLossGuard.explicitGod(player):actor.godId().orElse(null);
        if(god!=null&&AiDeathLossGuard.preventDeath(player,god)) {
            event.setCanceled(true);player.setHealth(1.0F);
            if(actor!=null&&AiDeathLossGuard.protectedPossessions(player)){actor.setTarget(null);actor.getNavigation().stop();}
        }
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) { AiHarmProtection.endRenewedCombat(player);AiDeathLossGuard.clear(player);CLICKS.remove(player); }
    }

    @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        CLICKS.clear();AiDeathLossGuard.clearAll();
    }

    private static GodAvatarEntity god(DamageSource source) {
        if (source.getEntity() instanceof GodAvatarEntity god) return god;
        return source.getDirectEntity() instanceof GodAvatarEntity god ? god : null;
    }
}
