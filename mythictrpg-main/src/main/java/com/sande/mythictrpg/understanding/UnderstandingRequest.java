package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

public record UnderstandingRequest(UUID token, long sequence, Action action, String key, String text) implements CustomPacketPayload {
    public enum Action { REFRESH, START, PAUSE, DISPUTE, RESTORE, DECLARE, RETRACT, CLOSE, ADVICE, SHARING }
    public static final Type<UnderstandingRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "understanding_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UnderstandingRequest> CODEC = StreamCodec.ofMember(UnderstandingRequest::write, UnderstandingRequest::read);
    public UnderstandingRequest {
        Objects.requireNonNull(token); Objects.requireNonNull(action);
        if (sequence < 1 || sequence > 10000) throw new IllegalArgumentException("request sequence");
        UnderstandingPayload.bound(key, 64); UnderstandingPayload.bound(text, 160);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private void write(RegistryFriendlyByteBuf b) { b.writeUUID(token); b.writeVarLong(sequence); b.writeEnum(action); b.writeUtf(key, 64); b.writeUtf(text, 160); }
    private static UnderstandingRequest read(RegistryFriendlyByteBuf b) { return new UnderstandingRequest(b.readUUID(), b.readVarLong(), b.readEnum(Action.class), b.readUtf(64), b.readUtf(160)); }
}
