package com.sande.mythictrpg.raid;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.content.ContentDefinitions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/** Prepared tools affect only authored damage from the actual boss/adds of the victim's active run. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class RaidCountermeasures {
    private RaidCountermeasures() { }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void damage(LivingDamageEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.server.isSameThread()
                || !Float.isFinite(event.getNewDamage()) || event.getNewDamage() <= 0) return;
        var state = RaidState.get(player.server);
        var attempt = state.membership(player.getUUID()).orElse(null);
        var source = event.getSource().getEntity();
        if (!state.ready() || attempt == null || attempt.status != RaidState.Status.ACTIVE || source == null
                || source.level() != player.level() || !attempt.id.equals(RaidRuntime.raidTag(source))
                || !attempt.spawnedEntities.contains(source.getUUID())) return;
        var member = attempt.members.get(player.getUUID());
        if (member == null || member.eliminated || member.awaitingRespawn || attempt.arena == null
                || !attempt.arena.dimension().equals(player.level().dimension().location())
                || !attempt.arena.bounds().contains(player.position()) || !attempt.arena.bounds().contains(source.position())) return;
        var damageType = event.getSource().typeHolder().unwrapKey().map(key -> key.location()).orElse(null);
        var definition = attempt.countermeasures.stream()
                .filter(rule -> rule.raid().equals(attempt.definition.id()) && rule.damageType().equals(damageType))
                .findFirst().orElse(null);
        if (definition == null || !BuiltInRegistries.ITEM.containsKey(definition.item())) return;
        ItemStack expected;
        try { expected = new com.sande.mythictrpg.quest.reward.NpcRewardEntry(definition.item(), 1, definition.components()).createStack(player.registryAccess()); }
        catch (RuntimeException invalid) { return; }
        ItemStack tool = ItemStack.isSameItemSameComponents(player.getOffhandItem(), expected) ? player.getOffhandItem() : player.getMainHandItem();
        if (!ItemStack.isSameItemSameComponents(tool, expected) || tool.isEmpty()) return;
        float adjusted = (float) (event.getNewDamage() * definition.multiplier());
        if (!Float.isFinite(adjusted) || adjusted < 0 || adjusted >= event.getNewDamage()) return;
        if (definition.consume()) { tool.shrink(1); player.getInventory().setChanged(); }
        event.setNewDamage(adjusted);
    }
}
