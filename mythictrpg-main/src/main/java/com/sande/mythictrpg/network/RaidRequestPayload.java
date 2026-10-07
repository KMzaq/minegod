package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/** No player UUID, status, price, roster, arena or reward is accepted from clients. */
public record RaidRequestPayload(UUID token, Action action, int row) implements CustomPacketPayload {
    public enum Action {
        REFRESH(0), PREVIOUS(0), NEXT(0), CLOSE(0), CREATE(1), JOIN(2), START(4), CANCEL(8), LEAVE(16);
        private final int bit;
        Action(int bit) { this.bit = bit; }
        public int bit() { return bit; }
    }
    public static final Type<RaidRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "raid_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RaidRequestPayload> STREAM_CODEC = StreamCodec.ofMember(RaidRequestPayload::encode, RaidRequestPayload::decode);
    public RaidRequestPayload {
        java.util.Objects.requireNonNull(token); java.util.Objects.requireNonNull(action);
        if (row < -1 || row >= RaidPagePayload.PAGE_SIZE || (action.bit() != 0 && row < 0)) throw new IllegalArgumentException("Invalid raid selection");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private void encode(RegistryFriendlyByteBuf b) { b.writeUUID(token); b.writeEnum(action); b.writeVarInt(row); }
    private static RaidRequestPayload decode(RegistryFriendlyByteBuf b) { return new RaidRequestPayload(b.readUUID(), b.readEnum(Action.class), b.readVarInt()); }
}
