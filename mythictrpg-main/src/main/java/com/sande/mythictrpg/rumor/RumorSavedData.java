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
        return ready() ? access(server, ledger -> ledger.heard(subject, god, audience)) : java.util.List.of();
    }
    private void requireReady() { if (!ready()) throw new IllegalStateException("Rumor state quarantined"); }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejected != null) return rejected.copy();
        tag.putInt("dataVersion", 1); tag.putString("state", JSON.toJson(ledger.snapshot())); return tag;
    }
    public static RumorSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RumorSavedData data = new RumorSavedData();
        try {
            if (tag.getInt("dataVersion") != 1 || tag.getString("state").length() > 16_000_000) throw new IllegalArgumentException("Invalid schema/size");
            data.ledger = RumorLedger.restore(JSON.fromJson(tag.getString("state"), RumorLedger.Snapshot.class));
        } catch (RuntimeException failure) {
            data.rejected = tag.copy(); MythicTrpg.LOGGER.error("Rumor state preserved read-only after load failure", failure);
        }
        return data;
    }
}
