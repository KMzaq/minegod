package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.dialogue.api.DialoguePriority;
import com.sande.mythictrpg.dialogue.presentation.DialogueComponentSanitizer;
import com.sande.mythictrpg.dialogue.presentation.DialogueTimingPolicy;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.Objects;
import java.util.UUID;

/** Display-only server-to-client DTO. It deliberately contains no God or target player ID. */
public record ClientDialoguePayload(UUID messageId, Component speakerDisplayName, Component dialogueText,
        ResourceLocation source, DialoguePriority priority, int fadeInTicks, int holdTicks, int fadeOutTicks)
        implements CustomPacketPayload {
    public static final int MAX_SPEAKER_CODE_POINTS = 128;
    public static final int MAX_DIALOGUE_CODE_POINTS = 1024;
    public static final int MAX_ENCODED_BYTES = 32 * 1024;

    public static final Type<ClientDialoguePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "dialogue_presentation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientDialoguePayload> STREAM_CODEC =
            StreamCodec.ofMember(ClientDialoguePayload::encode, ClientDialoguePayload::decode);

    public ClientDialoguePayload {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(priority, "priority");
        DialogueComponentSanitizer.requireAlreadySafe(
                speakerDisplayName, MAX_SPEAKER_CODE_POINTS, "speakerDisplayName");
        DialogueComponentSanitizer.requireAlreadySafe(
                dialogueText, MAX_DIALOGUE_CODE_POINTS, "dialogueText");
        speakerDisplayName = speakerDisplayName.copy();
        dialogueText = dialogueText.copy();
        validateTiming(fadeInTicks, holdTicks, fadeOutTicks);
    }

    @Override
    public Component speakerDisplayName() {
        return speakerDisplayName.copy();
    }

    @Override
    public Component dialogueText() {
        return dialogueText.copy();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public int encodedSize(RegistryAccess registryAccess) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), registryAccess, ConnectionType.NEOFORGE);
        try {
            STREAM_CODEC.encode(buffer, this);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        int start = buffer.writerIndex();
        UUIDUtil.STREAM_CODEC.encode(buffer, messageId);
        ComponentSerialization.STREAM_CODEC.encode(buffer, speakerDisplayName);
        ComponentSerialization.STREAM_CODEC.encode(buffer, dialogueText);
        ResourceLocation.STREAM_CODEC.encode(buffer, source);
        buffer.writeByte(priority.networkId());
        ByteBufCodecs.VAR_INT.encode(buffer, fadeInTicks);
        ByteBufCodecs.VAR_INT.encode(buffer, holdTicks);
        ByteBufCodecs.VAR_INT.encode(buffer, fadeOutTicks);
        int size = buffer.writerIndex() - start;
        if (size > MAX_ENCODED_BYTES) {
            throw new EncoderException("Dialogue payload size " + size + " exceeds " + MAX_ENCODED_BYTES);
        }
    }

    private static ClientDialoguePayload decode(RegistryFriendlyByteBuf buffer) {
        int start = buffer.readerIndex();
        try {
            ClientDialoguePayload payload = new ClientDialoguePayload(
                    UUIDUtil.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ComponentSerialization.STREAM_CODEC.decode(buffer),
                    ResourceLocation.STREAM_CODEC.decode(buffer),
                    DialoguePriority.fromNetworkId(buffer.readUnsignedByte()),
                    ByteBufCodecs.VAR_INT.decode(buffer),
                    ByteBufCodecs.VAR_INT.decode(buffer),
                    ByteBufCodecs.VAR_INT.decode(buffer));
            int size = buffer.readerIndex() - start;
            if (size > MAX_ENCODED_BYTES) {
                throw new DecoderException("Dialogue payload size " + size + " exceeds " + MAX_ENCODED_BYTES);
            }
            return payload;
        } catch (IllegalArgumentException exception) {
            throw new DecoderException("Invalid dialogue payload", exception);
        }
    }

    private static void validateTiming(int fadeInTicks, int holdTicks, int fadeOutTicks) {
        if (fadeInTicks < 0 || fadeInTicks > 40) {
            throw new IllegalArgumentException("fadeInTicks must be between 0 and 40");
        }
        if (holdTicks < DialogueTimingPolicy.MIN_HOLD_TICKS
                || holdTicks > DialogueTimingPolicy.MAX_HOLD_TICKS) {
            throw new IllegalArgumentException("holdTicks must be between 40 and 200");
        }
        if (fadeOutTicks < 0 || fadeOutTicks > 40) {
            throw new IllegalArgumentException("fadeOutTicks must be between 0 and 40");
        }
    }
}
