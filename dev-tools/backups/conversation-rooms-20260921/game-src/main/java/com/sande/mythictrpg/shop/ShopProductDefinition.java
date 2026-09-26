package com.sande.mythictrpg.shop;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

public record ShopProductDefinition(ResourceLocation id, String displayName,
        CompoundTag itemData, long price, boolean unlockedByDefault) {
    public ShopProductDefinition {
        Objects.requireNonNull(id, "id");
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        itemData = Objects.requireNonNull(itemData, "itemData").copy();
        if (displayName.isBlank() || displayName.codePointCount(0, displayName.length()) > 80) {
            throw new IllegalArgumentException("Invalid shop product display name");
        }
        if (price < 1L) throw new IllegalArgumentException("Shop product price must be positive");
    }

    @Override
    public CompoundTag itemData() {
        return itemData.copy();
    }

    public ItemStack createStack(HolderLookup.Provider registries) {
        return ItemStack.parse(registries, itemData).orElseThrow(() ->
                new IllegalStateException("Could not decode item stack for shop product " + id));
    }
}
