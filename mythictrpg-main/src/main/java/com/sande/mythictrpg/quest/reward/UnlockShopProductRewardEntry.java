package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.shop.ShopProductKey;
import net.minecraft.resources.ResourceLocation;

public record UnlockShopProductRewardEntry(ResourceLocation shopId,
        ResourceLocation productId) implements RewardEntry {
    public UnlockShopProductRewardEntry {
        if (shopId == null || productId == null) throw new IllegalArgumentException("Shop reward IDs are required");
    }

    public ShopProductKey key() {
        return new ShopProductKey(shopId, productId);
    }

    @Override
    public String description() {
        return "상점 상품 해금: " + shopId + " / " + productId;
    }
}
