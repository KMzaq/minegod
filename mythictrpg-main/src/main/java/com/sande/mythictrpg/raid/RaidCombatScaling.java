package com.sande.mythictrpg.raid;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.power.CombatPowerEvaluator;
import com.sande.mythictrpg.power.CombatPowerRuntime;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.commons.lang3.mutable.MutableFloat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Actual combat projection of the shared provisional model. No ownership or displayed power is changed here. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class RaidCombatScaling {
    private static final ResourceLocation STRENGTH_MODIFIER = ResourceLocation.withDefaultNamespace("effect.strength");
    private static final Map<UUID, Attack> ATTACKS = new HashMap<>();
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private RaidCombatScaling() { }

    private static final class Attack {
        final ServerPlayer player;
        final Entity target;
        final ItemStack weapon;
        final int tick;
        CriticalHitEvent critical;
        Attack(ServerPlayer player, Entity target) {
            this.player = player; this.target = target; weapon = player.getWeaponItem().copy(); tick = player.server.getTickCount();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void attacking(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.server.isSameThread()) return;
        ATTACKS.remove(player.getUUID());
        if (eligibleRaidTarget(player, event.getTarget()) && !player.isAutoSpinAttack()
                && player.hasEffect(MobEffects.DAMAGE_BOOST)) ATTACKS.put(player.getUUID(), new Attack(player, event.getTarget()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void critical(CriticalHitEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.server.isSameThread()) return;
        Attack attack = ATTACKS.get(player.getUUID());
        if (current(attack, player, event.getTarget())) attack.critical = event;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void incoming(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer defender && defender.server.isSameThread()) protection(event, defender);
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || !player.server.isSameThread()
                || event.getSource().getDirectEntity() != player || !event.getSource().is(DamageTypes.PLAYER_ATTACK)
                || event.getSource().is(DamageTypeTags.IS_PROJECTILE)) return;
        Attack attack = ATTACKS.remove(player.getUUID());
        if (!current(attack, player, event.getEntity()) || attack.critical == null
                || !eligibleRaidTarget(player, event.getEntity()) || player.isAutoSpinAttack()) return;
        var strength = player.getEffect(MobEffects.DAMAGE_BOOST);
        if (strength == null || !Float.isFinite(event.getAmount()) || event.getAmount() <= 0
                || !Float.isFinite(event.getOriginalAmount()) || event.getOriginalAmount() <= 0) return;
        CombatPowerEvaluator.Model model = model();
        if (model == null) return;
        int level = Math.min(17, strength.getAmplifier() + 1);
        double base = weaponBase(player, attack.weapon, model);
        double charge = player.getAttackStrengthScale(0.5F);
        double critical = attack.critical.isCriticalHit() ? attack.critical.getDamageMultiplier() : 1;
        double extra = base * model.strengthWeaponFractionPerLevel() * level
                * (0.2 + charge * charge * 0.8) * critical;
        // The original amount already includes vanilla +3/level, sharpness, charge, item bonuses and crit.
        // Scale only by the additional contribution, preserving earlier incoming-event multipliers too.
        double adjusted = event.getAmount() * (1 + extra / event.getOriginalAmount());
        if (Double.isFinite(adjusted) && adjusted > event.getAmount() && adjusted <= Float.MAX_VALUE)
            event.setAmount((float) adjusted);
    }

    private static boolean current(Attack attack, ServerPlayer player, Entity target) {
        return attack != null && attack.player == player && attack.target == target
                && attack.tick == player.server.getTickCount()
                && ItemStack.isSameItemSameComponents(attack.weapon, player.getWeaponItem());
    }

    static boolean eligibleRaidTarget(ServerPlayer player, Entity target) {
        if (player.server.getPlayerList().getPlayer(player.getUUID()) != player
                || !player.isAlive() || player.isSpectator() || !(target instanceof LivingEntity living)
                || target instanceof ServerPlayer || !living.isAlive() || target.level() != player.level()) return false;
        var state = RaidState.get(player.server);
        if (!state.ready()) return false;
        var attempt = state.membership(player.getUUID()).orElse(null);
        if (attempt == null || attempt.status != RaidState.Status.ACTIVE
                || !attempt.id.equals(RaidRuntime.raidTag(target)) || !attempt.spawnedEntities.contains(target.getUUID())
                || attempt.deadEntities.contains(target.getUUID()) || attempt.cleanedEntities.contains(target.getUUID())) return false;
        var member = attempt.members.get(player.getUUID());
        return member != null && !member.eliminated && !member.awaitingRespawn && attempt.arena != null
                && attempt.arena.dimension().equals(player.level().dimension().location())
                && attempt.arena.bounds().contains(player.position()) && attempt.arena.bounds().contains(target.position());
    }

    private static double weaponBase(ServerPlayer player, ItemStack weapon, CombatPowerEvaluator.Model model) {
        AttributeInstance actual = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (actual == null) return 0;
        var withoutStrength = new AttributeInstance(Attributes.ATTACK_DAMAGE, ignored -> { });
        withoutStrength.setBaseValue(actual.getBaseValue());
        actual.getModifiers().stream().filter(modifier -> !modifier.id().equals(STRENGTH_MODIFIER))
                .forEach(withoutStrength::addTransientModifier);
        var sharpness = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
        int level = Math.min(255, EnchantmentHelper.getItemEnchantmentLevel(sharpness, weapon));
        double sharpnessBonus = level > 0 ? model.sharpnessBase() + model.sharpnessPerLevel() * level : 0;
        return Math.max(0, withoutStrength.getValue() + sharpnessBonus);
    }

    private static void protection(LivingIncomingDamageEvent event, ServerPlayer player) {
        var source = event.getSource();
        if (source.is(DamageTypeTags.BYPASSES_EFFECTS) || source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)
                || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        CombatPowerEvaluator.Model model = model();
        if (model == null) return;
        event.addReductionModifier(DamageContainer.Reduction.ENCHANTMENTS, (container, priorReduction) -> {
            float before = container.getNewDamage();
            if (!Float.isFinite(before) || before <= 0 || !Float.isFinite(priorReduction)) return priorReduction;
            // Armor durability is consumed between Incoming and this phase. Read the surviving
            // equipment now, exactly when vanilla computes enchantment mitigation.
            var inputs = protectionInputs(player, source);
            if (inputs == null) return priorReduction;
            double vanillaReduction = Math.min(20, inputs.totalPoints()) / 25.0;
            double otherRemaining = 1 - Math.min(20, Math.max(0, inputs.totalPoints() - inputs.replacedPoints())) / 25.0;
            double desiredReduction = 1 - otherRemaining / (1 + inputs.levels() / model.protectionHalfValue());
            // Replace only registered protection, preserving earlier reduction modifiers and
            // allowing the countermeasure/absorption phases to run once afterward.
            double adjusted = priorReduction + before * (desiredReduction - vanillaReduction);
            return (float) Math.max(0, Math.min(before, adjusted));
        });
    }
    private record ProtectionInputs(int levels, float replacedPoints, float totalPoints) { }
    private static ProtectionInputs protectionInputs(ServerPlayer player, net.minecraft.world.damagesource.DamageSource source) {
        var protection = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
        int levels = 0;
        float replacedPoints = 0;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack worn = player.getItemBySlot(slot);
            var registered = CombatPowerRuntime.registeredAcquiredGear(player, worn).orElse(null);
            if (registered == null || !protection.value().matchingSlot(slot)) continue;
            int level = EnchantmentHelper.getItemEnchantmentLevel(protection, worn);
            if (level < 1 || level > 255 || registered.protectionLevel() != level) continue;
            var contribution = new MutableFloat();
            protection.value().modifyDamageProtection(player.serverLevel(), level, worn, player, source, contribution);
            if (!Float.isFinite(contribution.floatValue()) || contribution.floatValue() <= 0) continue;
            levels += level;
            replacedPoints += contribution.floatValue();
        }
        if (levels == 0) return null;
        float totalPoints = EnchantmentHelper.getDamageProtection(player.serverLevel(), player, source);
        if (!Float.isFinite(totalPoints) || totalPoints < replacedPoints) return null;
        return new ProtectionInputs(levels,replacedPoints,totalPoints);
    }

    private static CombatPowerEvaluator.Model model() {
        try { return CombatPowerRuntime.publicModel(); }
        catch (RuntimeException unavailable) { return null; }
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        ATTACKS.values().removeIf(attack -> attack.player.server == event.getServer());
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { ATTACKS.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { ATTACKS.values().removeIf(attack -> attack.player.server == event.getServer()); }
}
