package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.economy.CurrencyState;
import com.sande.mythictrpg.shop.ShopType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ShopCatalogPayload(ShopType shopType, long balance, List<Product> products)
        implements CustomPacketPayload {
    public static final Type<ShopCatalogPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "shop_catalog"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShopCatalogPayload> STREAM_CODEC =
            StreamCodec.ofMember(ShopCatalogPayload::encode, ShopCatalogPayload::decode);

    public ShopCatalogPayload {
        Objects.requireNonNull(shopType, "shopType");
        if (balance < 0L || balance > CurrencyState.MAX_BALANCE) throw new IllegalArgumentException("Invalid balance");
        products = List.copyOf(products);
        if (products.size() > 512) throw new IllegalArgumentException("Too many shop products");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeEnum(shopType);
        buffer.writeLong(balance);
        buffer.writeVarInt(products.size());
        for (Product product : products) {
            buffer.writeResourceLocation(product.shopId());
            buffer.writeResourceLocation(product.productId());
            buffer.writeUtf(product.shopName(), 80);
            buffer.writeUtf(product.displayName(), 80);
            ItemStack.STREAM_CODEC.encode(buffer, product.item());
            buffer.writeLong(product.price());
            buffer.writeVarInt(product.availableLots() + 1);
        }
    }

    private static ShopCatalogPayload decode(RegistryFriendlyByteBuf buffer) {
        ShopType type = buffer.readEnum(ShopType.class);
        long balance = buffer.readLong();
        int size = buffer.readVarInt();
        if (size < 0 || size > 512) throw new IllegalArgumentException("Invalid shop product count");
        List<Product> products = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            products.add(new Product(buffer.readResourceLocation(), buffer.readResourceLocation(),
                    buffer.readUtf(80), buffer.readUtf(80), ItemStack.STREAM_CODEC.decode(buffer),
                    buffer.readLong(), buffer.readVarInt() - 1));
        }
        return new ShopCatalogPayload(type, balance, products);
    }

    public record Product(ResourceLocation shopId, ResourceLocation productId, String shopName,
            String displayName, ItemStack item, long price, int availableLots) {
        public Product {
            Objects.requireNonNull(shopId, "shopId");
            Objects.requireNonNull(productId, "productId");
            shopName = bounded(shopName, "shopName");
            displayName = bounded(displayName, "displayName");
            item = Objects.requireNonNull(item, "item").copy();
            if (item.isEmpty() || price < 1L || price > CurrencyState.MAX_BALANCE || availableLots < -1) {
                throw new IllegalArgumentException("Invalid shop product payload");
            }
        }

        @Override
        public ItemStack item() {
            return item.copy();
        }
    }

    private static String bounded(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > 80) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return normalized;
    }
}
