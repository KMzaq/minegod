package com.sande.mythictrpg.shop;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record ShopProductKey(ResourceLocation shopId, ResourceLocation productId)
        implements Comparable<ShopProductKey> {
    public ShopProductKey {
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(productId, "productId");
    }

    @Override
    public int compareTo(ShopProductKey other) {
        int shop = shopId.compareTo(other.shopId);
        return shop != 0 ? shop : productId.compareTo(other.productId);
    }
}
