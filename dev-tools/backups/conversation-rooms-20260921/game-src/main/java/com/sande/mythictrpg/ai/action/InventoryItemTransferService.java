package com.sande.mythictrpg.ai.action;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Optional;

/** Server-side item lookup and atomic consume plan for inventory plus the looked-at block container. */
final class InventoryItemTransferService {
    private static final double LOOK_DISTANCE = 5.0D;

    private InventoryItemTransferService() {
    }

    static Availability availability(ServerPlayer player, ResourceLocation itemId) {
        int inventoryCount = count(player.getInventory(), itemId);
        Optional<Container> lookedAt = lookedAtContainer(player);
        int containerCount = lookedAt.map(container -> count(container, itemId)).orElse(0);
        return new Availability(inventoryCount, containerCount, lookedAt);
    }

    static boolean consume(ServerPlayer player, ResourceLocation itemId, int requested) {
        Availability available = availability(player, itemId);
        if (available.total() < requested) {
            return false;
        }
        int remaining = remove(player.getInventory(), itemId, requested);
        if (remaining > 0 && available.lookedAtContainer().isPresent()) {
            Container container = available.lookedAtContainer().orElseThrow();
            remaining = remove(container, itemId, remaining);
            container.setChanged();
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        return remaining == 0;
    }

    private static Optional<Container> lookedAtContainer(ServerPlayer player) {
        HitResult hit = player.pick(LOOK_DISTANCE, 0.0F, false);
        if (!(hit instanceof BlockHitResult blockHit)) {
            return Optional.empty();
        }
        BlockEntity blockEntity = player.level().getBlockEntity(blockHit.getBlockPos());
        return blockEntity instanceof Container container ? Optional.of(container) : Optional.empty();
    }

    private static int count(Container container, ResourceLocation itemId) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && itemId.equals(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()))) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Returns the amount that could not be removed. */
    private static int remove(Container container, ResourceLocation itemId, int requested) {
        int remaining = requested;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !itemId.equals(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()))) {
                continue;
            }
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
        }
        return remaining;
    }

    record Availability(int inventoryCount, int containerCount, Optional<Container> lookedAtContainer) {
        int total() {
            return inventoryCount + containerCount;
        }
    }
}
