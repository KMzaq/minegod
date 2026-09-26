package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RewardChoicePayload(UUID claimId, String title, List<Option> options)
        implements CustomPacketPayload {
    public static final Type<RewardChoicePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "reward_choice"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RewardChoicePayload> STREAM_CODEC =
            StreamCodec.ofMember(RewardChoicePayload::encode, RewardChoicePayload::decode);

    public RewardChoicePayload {
        Objects.requireNonNull(claimId, "claimId");
        title = bounded(title, 120, "title");
        options = List.copyOf(options);
        if (options.size() < 2 || options.size() > 6) {
            throw new IllegalArgumentException("Reward choice payload must contain 2..6 options");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(claimId);
        buffer.writeUtf(title, 120);
        buffer.writeVarInt(options.size());
        for (Option option : options) {
            buffer.writeResourceLocation(option.optionId());
            buffer.writeUtf(option.displayName(), 80);
            buffer.writeUtf(option.summary(), 600);
        }
    }

    private static RewardChoicePayload decode(RegistryFriendlyByteBuf buffer) {
        UUID claimId = buffer.readUUID();
        String title = buffer.readUtf(120);
        int size = buffer.readVarInt();
        if (size < 2 || size > 6) {
            throw new IllegalArgumentException("Invalid reward choice option count");
        }
        List<Option> options = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            options.add(new Option(buffer.readResourceLocation(), buffer.readUtf(80), buffer.readUtf(600)));
        }
        return new RewardChoicePayload(claimId, title, options);
    }

    public record Option(ResourceLocation optionId, String displayName, String summary) {
        public Option {
            Objects.requireNonNull(optionId, "optionId");
            displayName = bounded(displayName, 80, "displayName");
            summary = bounded(summary, 600, "summary");
        }
    }

    private static String bounded(String value, int maximum, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > maximum) {
            throw new IllegalArgumentException("Invalid reward choice " + field);
        }
        return normalized;
    }
}
