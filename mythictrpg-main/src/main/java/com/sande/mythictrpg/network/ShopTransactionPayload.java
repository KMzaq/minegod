package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.shop.ShopTransactionService;
import com.sande.mythictrpg.shop.ShopType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record ShopTransactionPayload(ShopType shopType, ResourceLocation shopId,
        ResourceLocation productId, int lots) implements CustomPacketPayload {
    public static final Type<ShopTransactionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "shop_transaction"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShopTransactionPayload> STREAM_CODEC =
            StreamCodec.ofMember(ShopTransactionPayload::encode, ShopTransactionPayload::decode);

    public ShopTransactionPayload {
        Objects.requireNonNull(shopType, "shopType");
        Objects.requireNonNull(shopId, "shopId");
        Objects.requireNonNull(productId, "productId");
        if (lots < 1 || lots > ShopTransactionService.MAX_LOTS_PER_REQUEST) {
            throw new IllegalArgumentException("Invalid shop transaction quantity");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeEnum(shopType);
        buffer.writeResourceLocation(shopId);
        buffer.writeResourceLocation(productId);
        buffer.writeVarInt(lots);
    }

    private static ShopTransactionPayload decode(RegistryFriendlyByteBuf buffer) {
        return new ShopTransactionPayload(buffer.readEnum(ShopType.class), buffer.readResourceLocation(),
                buffer.readResourceLocation(), buffer.readVarInt());
    }
}
