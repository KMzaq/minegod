package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record AiActionDecisionPayload(UUID proposalId, boolean accepted) implements CustomPacketPayload {
    public static final Type<AiActionDecisionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "ai_action_decision"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AiActionDecisionPayload> STREAM_CODEC =
            StreamCodec.ofMember(AiActionDecisionPayload::encode, AiActionDecisionPayload::decode);

    public AiActionDecisionPayload {
        Objects.requireNonNull(proposalId, "proposalId");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(proposalId);
        buffer.writeBoolean(accepted);
    }

    private static AiActionDecisionPayload decode(RegistryFriendlyByteBuf buffer) {
        return new AiActionDecisionPayload(buffer.readUUID(), buffer.readBoolean());
    }
}
