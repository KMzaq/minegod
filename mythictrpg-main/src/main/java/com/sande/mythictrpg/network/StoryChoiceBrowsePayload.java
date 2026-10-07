package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A client may ask to view a page; the server computes all eligibility again. */
public record StoryChoiceBrowsePayload(int page) implements CustomPacketPayload {
    public static final Type<StoryChoiceBrowsePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "story_choice_browse"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StoryChoiceBrowsePayload> STREAM_CODEC =
            StreamCodec.ofMember(StoryChoiceBrowsePayload::encode, StoryChoiceBrowsePayload::decode);

    public StoryChoiceBrowsePayload {
        if (page < 0 || page > 1_000_000) throw new IllegalArgumentException("Invalid Story choice page");
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private void encode(RegistryFriendlyByteBuf buffer) { buffer.writeVarInt(page); }
    private static StoryChoiceBrowsePayload decode(RegistryFriendlyByteBuf buffer) {
        return new StoryChoiceBrowsePayload(buffer.readVarInt());
    }
}
