package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record RewardChoiceSelectionPayload(UUID claimId, ResourceLocation optionId)
        implements CustomPacketPayload {
    public static final Type<RewardChoiceSelectionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "reward_choice_selection"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RewardChoiceSelectionPayload> STREAM_CODEC =
            StreamCodec.ofMember(RewardChoiceSelectionPayload::encode, RewardChoiceSelectionPayload::decode);

    public RewardChoiceSelectionPayload {
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(optionId, "optionId");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(claimId);
        buffer.writeResourceLocation(optionId);
    }

    private static RewardChoiceSelectionPayload decode(RegistryFriendlyByteBuf buffer) {
        return new RewardChoiceSelectionPayload(buffer.readUUID(), buffer.readResourceLocation());
    }
}
