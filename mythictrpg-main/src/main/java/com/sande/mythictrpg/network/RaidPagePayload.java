package com.sande.mythictrpg.network;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded server-issued view. Action bits are hints, not client authority. */
public record RaidPagePayload(UUID token, boolean open, int page, int pages, String notice, List<Row> rows)
        implements CustomPacketPayload {
    public static final int PAGE_SIZE = 6;
    public static final Type<RaidPagePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "raid_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RaidPagePayload> STREAM_CODEC = StreamCodec.ofMember(RaidPagePayload::encode, RaidPagePayload::decode);
    public RaidPagePayload {
        java.util.Objects.requireNonNull(token);
        rows = List.copyOf(rows);
        if (page < 0 || pages < 1 || pages > 128 || page >= pages || rows.size() > PAGE_SIZE) throw new IllegalArgumentException("Invalid raid page");
        bounded(notice, 512);
    }
    public record Row(String key, String title, String status, String details, int actions) {
        public Row {
            bounded(key, 256); bounded(title, 128); bounded(status, 32); bounded(details, 1024);
            if (actions < 0 || (actions & ~31) != 0) throw new IllegalArgumentException("Invalid raid actions");
        }
        public boolean allows(RaidRequestPayload.Action action) { return (actions & action.bit()) != 0; }
    }
    private static void bounded(String text, int max) {
        if (text == null || text.length() > max) throw new IllegalArgumentException("Invalid raid text");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private void encode(RegistryFriendlyByteBuf b) {
        b.writeUUID(token); b.writeBoolean(open); b.writeVarInt(page); b.writeVarInt(pages); b.writeUtf(notice, 512); b.writeVarInt(rows.size());
        for (Row row : rows) { b.writeUtf(row.key, 256); b.writeUtf(row.title, 128); b.writeUtf(row.status, 32); b.writeUtf(row.details, 1024); b.writeVarInt(row.actions); }
    }
    private static RaidPagePayload decode(RegistryFriendlyByteBuf b) {
        UUID token = b.readUUID(); boolean open = b.readBoolean(); int page = b.readVarInt(), pages = b.readVarInt(); String notice = b.readUtf(512);
        int size = b.readVarInt(); if (size < 0 || size > PAGE_SIZE) throw new IllegalArgumentException("Invalid raid row count");
        List<Row> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) rows.add(new Row(b.readUtf(256), b.readUtf(128), b.readUtf(32), b.readUtf(1024), b.readVarInt()));
        return new RaidPagePayload(token, open, page, pages, notice, rows);
    }
}
