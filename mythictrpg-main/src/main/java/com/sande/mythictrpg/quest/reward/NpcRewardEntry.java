package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** One server-executable item reward. */
public record NpcRewardEntry(ResourceLocation itemId, int count, CompoundTag components) implements RewardEntry {
    public NpcRewardEntry(ResourceLocation itemId, int count) {
        this(itemId, count, new CompoundTag());
    }

    public NpcRewardEntry {
        Objects.requireNonNull(itemId, "itemId");
        components = Objects.requireNonNull(components, "components").copy();
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("Reward item count must be between 1 and 64");
        }
        if (components.toString().length() > 16_384)
            throw new IllegalArgumentException("Reward item components exceed 16384 characters");
    }

    @Override public CompoundTag components() { return components.copy(); }

    /** Authored component data is frozen in a claim, not resolved from a later changed item template. */
    public ItemStack createStack(HolderLookup.Provider registries) {
        if (!BuiltInRegistries.ITEM.containsKey(itemId))
            throw new IllegalArgumentException("Reward item is not registered: " + itemId);
        // Retain the existing bulk plain-item reward behavior.
        if (components.isEmpty()) return new ItemStack(BuiltInRegistries.ITEM.get(itemId), count);
        CompoundTag data = new CompoundTag();
        data.putString("id", itemId.toString());
        data.putInt("count", count);
        data.put("components", components.copy());
        ItemStack stack = ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), data)
                .getOrThrow(message -> new IllegalArgumentException("Invalid authored reward item: " + message));
        if (stack.isEmpty() || stack.getCount() != count || !stack.is(BuiltInRegistries.ITEM.get(itemId)))
            throw new IllegalArgumentException("Reward item decoding changed its identity or count");
        return stack;
    }

    @Override
    public String description() {
        return itemId + " x" + count;
    }
}
