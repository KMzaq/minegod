package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record AiConversationStatePayload(boolean enabled, Component godDisplayName)
        implements CustomPacketPayload {
    public static final Type<AiConversationStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "ai_conversation_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AiConversationStatePayload> STREAM_CODEC =
            StreamCodec.ofMember(AiConversationStatePayload::encode, AiConversationStatePayload::decode);

    public AiConversationStatePayload {
        godDisplayName = Objects.requireNonNull(godDisplayName, "godDisplayName").copy();
    }

    @Override
    public Component godDisplayName() {
        return godDisplayName.copy();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(enabled);
        ComponentSerialization.STREAM_CODEC.encode(buffer, godDisplayName);
    }

    private static AiConversationStatePayload decode(RegistryFriendlyByteBuf buffer) {
        return new AiConversationStatePayload(buffer.readBoolean(),
                ComponentSerialization.STREAM_CODEC.decode(buffer));
    }
}
