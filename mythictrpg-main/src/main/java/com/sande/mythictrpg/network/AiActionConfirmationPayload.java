package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

public record AiActionConfirmationPayload(UUID proposalId, ResourceLocation actionType,
        String title, String summary, int timeoutSeconds, List<String> verifiedTerms) implements CustomPacketPayload {
    public static final Type<AiActionConfirmationPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "ai_action_confirmation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AiActionConfirmationPayload> STREAM_CODEC =
            StreamCodec.ofMember(AiActionConfirmationPayload::encode, AiActionConfirmationPayload::decode);

    public AiActionConfirmationPayload {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(actionType, "actionType");
        title = bounded(title, 120);
        summary = bounded(summary, 600);
        verifiedTerms = List.copyOf(Objects.requireNonNull(verifiedTerms, "verifiedTerms"));
        if (verifiedTerms.isEmpty() || verifiedTerms.size() > 8) {
            throw new IllegalArgumentException("Invalid confirmation terms count");
        }
        verifiedTerms = verifiedTerms.stream().map(line -> bounded(line, 400)).toList();
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
        buffer.writeVarInt(verifiedTerms.size());
        verifiedTerms.forEach(line -> buffer.writeUtf(line, 400));
    }

    private static AiActionConfirmationPayload decode(RegistryFriendlyByteBuf buffer) {
        UUID proposal = buffer.readUUID();
        ResourceLocation action = buffer.readResourceLocation();
        String title = buffer.readUtf(120), summary = buffer.readUtf(600);
        int timeout = buffer.readVarInt(), count = buffer.readVarInt();
        if (count < 1 || count > 8) throw new IllegalArgumentException("Invalid confirmation terms count");
        List<String> terms = new ArrayList<>(count);
        for (int i = 0; i < count; i++) terms.add(buffer.readUtf(400));
        return new AiActionConfirmationPayload(proposal, action, title, summary, timeout, terms);
    }

    private static String bounded(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException("AI action confirmation text is too long");
        }
        return normalized;
    }
}
