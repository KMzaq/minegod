package com.sande.mythictrpg.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Only the recipient's memberships are sent. This is not a public room directory. */
public record ConversationRoomsPayload(List<Entry> rooms, Optional<UUID> selectedPrivate) implements CustomPacketPayload {
    public static final Type<ConversationRoomsPayload> TYPE = new Type<>(ResourceLocation.parse("mythictrpg:conversation_rooms"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConversationRoomsPayload> STREAM_CODEC =
            StreamCodec.ofMember(ConversationRoomsPayload::encode, ConversationRoomsPayload::decode);
    public record Entry(UUID id, String code, String kind, String gods, int color, int players) {
        public Entry {
            Objects.requireNonNull(id);
            if (code == null || code.length() > 16 || kind == null || kind.length() > 24
                    || gods == null || gods.length() > 1024 || players < 0) throw new IllegalArgumentException("Invalid room display");
        }
        public boolean isPrivate() { return kind.equals("PRIVATE"); }
    }
    public ConversationRoomsPayload {
        rooms = List.copyOf(rooms); selectedPrivate = Objects.requireNonNull(selectedPrivate);
        if (rooms.size() > 64 || rooms.stream().map(Entry::id).distinct().count() != rooms.size()) throw new IllegalArgumentException("Room limit");
        UUID selectedId = selectedPrivate.orElse(null);
        if (selectedId != null && rooms.stream().noneMatch(e -> e.id().equals(selectedId) && e.isPrivate()))
            throw new IllegalArgumentException("Selected room is not a private membership");
    }
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(rooms.size());
        for (Entry e : rooms) { buf.writeUUID(e.id()); buf.writeUtf(e.code(),16); buf.writeUtf(e.kind(),24);
            buf.writeUtf(e.gods(),1024); buf.writeInt(e.color()); buf.writeVarInt(e.players()); }
        buf.writeBoolean(selectedPrivate.isPresent()); selectedPrivate.ifPresent(buf::writeUUID);
    }
    private static ConversationRoomsPayload decode(RegistryFriendlyByteBuf buf) {
        int n=buf.readVarInt(); if(n<0||n>64)throw new IllegalArgumentException("Room count");
        List<Entry> rooms=new ArrayList<>();
        for(int i=0;i<n;i++)rooms.add(new Entry(buf.readUUID(),buf.readUtf(16),buf.readUtf(24),buf.readUtf(1024),buf.readInt(),buf.readVarInt()));
        return new ConversationRoomsPayload(rooms,buf.readBoolean()?Optional.of(buf.readUUID()):Optional.empty());
    }
}
