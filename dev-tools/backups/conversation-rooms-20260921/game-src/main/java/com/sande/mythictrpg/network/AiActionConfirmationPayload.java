package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record AiActionConfirmationPayload(UUID proposalId, ResourceLocation actionType,
        String title, String summary, int timeoutSeconds) implements CustomPacketPayload {
    public static final Type<AiActionConfirmationPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "ai_action_confirmation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AiActionConfirmationPayload> STREAM_CODEC =
            StreamCodec.ofMember(AiActionConfirmationPayload::encode, AiActionConfirmationPayload::decode);

    public AiActionConfirmationPayload {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(actionType, "actionType");
        title = bounded(title, 120);
        summary = bounded(summary, 600);
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("Invalid AI action confirmation timeout");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(proposalId);
        buffer.writeResourceLocation(actionType);
        buffer.writeUtf(title, 120);
        buffer.writeUtf(summary, 600);
        buffer.writeVarInt(timeoutSeconds);
    }

    private static AiActionConfirmationPayload decode(RegistryFriendlyByteBuf buffer) {
        return new AiActionConfirmationPayload(buffer.readUUID(), buffer.readResourceLocation(),
                buffer.readUtf(120), buffer.readUtf(600), buffer.readVarInt());
    }

    private static String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException("AI action confirmation text is too long");
        }
        return normalized;
    }
}
