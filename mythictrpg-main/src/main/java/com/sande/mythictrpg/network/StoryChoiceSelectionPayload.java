package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** The client sends no authority, effects, audience or conditions. */
public record StoryChoiceSelectionPayload(String instanceId, long revision, ResourceLocation outcomeId)
        implements CustomPacketPayload {
    public static final Type<StoryChoiceSelectionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "story_choice_selection"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StoryChoiceSelectionPayload> STREAM_CODEC =
            StreamCodec.ofMember(StoryChoiceSelectionPayload::encode, StoryChoiceSelectionPayload::decode);

    public StoryChoiceSelectionPayload {
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
        if (instanceId.isBlank() || instanceId.length() > 256 || revision < 0)
            throw new IllegalArgumentException("Invalid Story choice identity");
        Objects.requireNonNull(outcomeId, "outcomeId");
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(instanceId, 256);
        buffer.writeVarLong(revision);
        buffer.writeResourceLocation(outcomeId);
    }
    private static StoryChoiceSelectionPayload decode(RegistryFriendlyByteBuf buffer) {
        return new StoryChoiceSelectionPayload(buffer.readUtf(256), buffer.readVarLong(), buffer.readResourceLocation());
    }
}
