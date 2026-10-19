package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Only redacted self-view cards cross the wire. Never raw ledger payloads or NPC-private beliefs. */
public record UnderstandingPayload(UUID token, boolean open, boolean active, boolean pending,
                                   String notice, List<PlayerUnderstanding.Card> cards) implements CustomPacketPayload {
    public static final Type<UnderstandingPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "understanding_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UnderstandingPayload> CODEC = StreamCodec.ofMember(UnderstandingPayload::write, UnderstandingPayload::read);
    public UnderstandingPayload {
        Objects.requireNonNull(token); cards = List.copyOf(cards); bound(notice, 1024);
        if (cards.size() > 16) throw new IllegalArgumentException("card budget");
        for (var c : cards) {
            bound(c.key(), 64); bound(c.title(), 128); bound(c.assessment(), 128); bound(c.explanation(), 1024);
            if (c.evidence().size() > 3) throw new IllegalArgumentException("evidence budget");
            c.evidence().forEach(e -> bound(e, 256));
        }
    }
    public static void bound(String s, int max) { if (s == null || s.length() > max) throw new IllegalArgumentException("text budget"); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private void write(RegistryFriendlyByteBuf b) {
        b.writeUUID(token); b.writeBoolean(open); b.writeBoolean(active); b.writeBoolean(pending); b.writeUtf(notice, 1024); b.writeVarInt(cards.size());
        for (var c : cards) {
            b.writeUtf(c.key(), 64); b.writeUtf(c.title(), 128); b.writeUtf(c.assessment(), 128); b.writeUtf(c.explanation(), 1024);
            b.writeBoolean(c.disputed()); b.writeVarInt(c.evidence().size()); for (String e : c.evidence()) b.writeUtf(e, 256);
        }
    }
    private static UnderstandingPayload read(RegistryFriendlyByteBuf b) {
        UUID token = b.readUUID(); boolean open = b.readBoolean(), active = b.readBoolean(), pending = b.readBoolean();
        String notice = b.readUtf(1024); int count = b.readVarInt();
        if (count < 0 || count > 16) throw new IllegalArgumentException("card budget");
        var cards = new ArrayList<PlayerUnderstanding.Card>();
        for (int i = 0; i < count; i++) {
            String key = b.readUtf(64), title = b.readUtf(128), assessment = b.readUtf(128), explanation = b.readUtf(1024);
            boolean disputed = b.readBoolean(); int n = b.readVarInt();
            if (n < 0 || n > 3) throw new IllegalArgumentException("evidence budget");
            var evidence = new ArrayList<String>(); for (int j = 0; j < n; j++) evidence.add(b.readUtf(256));
            cards.add(new PlayerUnderstanding.Card(key, title, assessment, explanation, evidence, disputed));
        }
        return new UnderstandingPayload(token, open, active, pending, notice, cards);
    }
}
