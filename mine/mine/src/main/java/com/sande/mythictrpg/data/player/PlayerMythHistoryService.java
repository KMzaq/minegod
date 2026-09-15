package com.sande.mythictrpg.data.player;

import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
import com.sande.mythictrpg.condition.engine.ConditionChangeDispatcher;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationAdapters;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Objects;
import java.util.UUID;

/**
 * Records durable item history through one entry point.
 * Automatic adapters currently cover ground pickup, crafting, and smelting only. Container transfer,
 * commands, creative inventory, trades, and direct inventory changes remain an Inventory Acquisition
 * Coverage decision for a later phase.
 */
public final class PlayerMythHistoryService {
    private PlayerMythHistoryService() {
    }

    /** Records an item obtained by an online player, including explicit Mythic TRPG reward systems. */
    public static ItemHistoryRecordResult recordItemObtained(ServerPlayer player, ItemStack stack) {
        Objects.requireNonNull(player, "player");
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Cannot record an empty ItemStack");
        }
        ResourceLocation itemId = itemId(stack.getItem());
        ItemHistoryRecordResult result = recordItemObtained(player.server, player.getUUID(), stack.getItem());
        GameplayObservationAdapters.onItemHistoryRecorded(player, itemId, result);
        return result;
    }

    /** Records an item for a canonical profile without requiring that player to be online. */
    public static ItemHistoryRecordResult recordItemObtained(
            MinecraftServer server, UUID playerId, Item item) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(item, "item");
        ResourceLocation itemId = itemId(item);
        ItemHistoryRecordResult result = PlayerMythDataService.get(server).recordObtainedItem(playerId, itemId);
        if (result == ItemHistoryRecordResult.NEW_RECORD) {
            ConditionChangeDispatcher.publishPlayerDependencyChanged(
                    server, BuiltinConditionTypes.PLAYER_ITEM_HISTORY_DEPENDENCY, playerId);
        }
        return result;
    }

    public static void onItemEntityPickup(ItemEntityPickupEvent.Post event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            recordItemObtained(player, event.getOriginalStack());
        }
    }

    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !event.getCrafting().isEmpty()) {
            recordItemObtained(player, event.getCrafting());
        }
    }

    public static void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !event.getSmelting().isEmpty()) {
            recordItemObtained(player, event.getSmelting());
        }
    }

    private static ResourceLocation itemId(Item item) {
        return BuiltInRegistries.ITEM.getResourceKey(item)
                .map(net.minecraft.resources.ResourceKey::location)
                .orElseThrow(() -> new IllegalArgumentException("Item is not registered: " + item));
    }
}
