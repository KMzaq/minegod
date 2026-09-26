package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.function.Function;

/** Independent versioned state; does not add entities, alter player profiles or migrate LP files. */
public final class RumorSavedData extends SavedData {
    private static final Gson JSON = new Gson();
    private static final Factory<RumorSavedData> FACTORY = new Factory<>(RumorSavedData::new, RumorSavedData::load);
    private RumorLedger ledger = new RumorLedger();
    private int storageVersion = 1;
    private CompoundTag rejected;
    public static RumorSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Rumor state requires server thread");
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "mythictrpg_memory_rumor_v1");
    }
    public RumorSavedData() { setDirty(); }
    public boolean ready() { return rejected == null; }
    public java.util.UUID worldId() { requireReady(); return ledger.worldId(); }
    RumorLedger.Snapshot snapshot() { requireReady(); return ledger.snapshot(); }
    <T> T access(MinecraftServer server, Function<RumorLedger,T> operation) {
        if (!server.isSameThread()) throw new IllegalStateException("Rumor state requires server thread");
        requireReady(); long before = ledger.revision();
        try { return operation.apply(ledger); }
        finally { if (before != ledger.revision()) setDirty(); }
    }
    /** AI receives only authorized immutable claims, never the mutable ledger or other recipients' records. */
    public java.util.List<RumorLedger.HeardRumor> heard(MinecraftServer server, java.util.UUID subject,
            String god, java.util.Set<java.util.UUID> audience) {
        return ready() ? access(server, ledger -> CourierRumorService.heard(server,ledger,subject,god,audience).stream()
                .map(h -> ReputationService.decorate(server,subject,god,audience,h)).toList()) : java.util.List.of();
    }
    private void requireReady() { if (!ready()) throw new IllegalStateException("Rumor state quarantined"); }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejected != null) return rejected.copy();
        var snapshot=ledger.snapshot();
        if(snapshot.evidence().stream().anyMatch(e->e.proof()!=null))storageVersion=RumorLedger.VERSION;
        // Reading an LP/PERSONAL world while this feature is OFF must not upgrade its storage format.
        if(storageVersion==1)snapshot=new RumorLedger.Snapshot(1,snapshot.worldId(),snapshot.couriers(),snapshot.evidence(),snapshot.claims(),snapshot.pending(),snapshot.receipts());
        tag.putInt("dataVersion", storageVersion); tag.putString("state", JSON.toJson(snapshot)); return tag;
    }
    public static RumorSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RumorSavedData data = new RumorSavedData();
        try {
            if (!java.util.Set.of(1,RumorLedger.VERSION).contains(tag.getInt("dataVersion")) || tag.getString("state").length() > 16_000_000) throw new IllegalArgumentException("Invalid schema/size");
            var snapshot=JSON.fromJson(tag.getString("state"), RumorLedger.Snapshot.class);
            if(snapshot.version()!=tag.getInt("dataVersion"))throw new IllegalArgumentException("mismatched rumor schema");
            data.ledger = RumorLedger.restore(snapshot);
            data.storageVersion = snapshot.version();
        } catch (RuntimeException failure) {
            data.rejected = tag.copy(); MythicTrpg.LOGGER.error("Rumor state preserved read-only after load failure", failure);
        }
        return data;
    }
}
