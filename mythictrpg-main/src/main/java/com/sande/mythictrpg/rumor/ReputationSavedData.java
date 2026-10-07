package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;
import java.util.function.Function;
import com.sande.mythictrpg.recording.server.LegacyRecordingQuota;
import com.sande.mythictrpg.recording.server.ManagedStoreRegistry;
import com.sande.mythictrpg.recording.server.ManagedSavedDataIo;
import net.minecraft.world.level.storage.LevelResource;

/** Separate game receipt storage; never migrates LP/player affinity or writes when this feature is OFF. */
public final class ReputationSavedData extends SavedData {
    private static final Gson JSON=new Gson();
    private ReputationLedger ledger;private CompoundTag rejected;
    private final LegacyRecordingQuota.SavedDataGate quota = new LegacyRecordingQuota.SavedDataGate(ManagedStoreRegistry.REPUTATION);
    public ReputationSavedData(UUID world){ledger=new ReputationLedger(world);setDirty();}
    static ReputationSavedData get(MinecraftServer server,UUID world) {
        requireThread(server);
        ReputationSavedData data = server.overworld().getDataStorage().computeIfAbsent(new Factory<>(()->new ReputationSavedData(world),ReputationSavedData::load),"mythictrpg_reputation_judgement_v1");
        data.bindQuota(server); return data;
    }
    public boolean ready(UUID world){return rejected==null&&ledger.worldId().equals(world);}
    ReputationLedger.Snapshot snapshot(){return ledger.snapshot();}
    <T> T access(MinecraftServer server,UUID world,Function<ReputationLedger,T> operation) {
        requireThread(server);if(!ready(world))throw new IllegalStateException("Reputation unavailable/world mismatch");bindQuota(server);
        long before=ledger.revision();try{return operation.apply(ledger);}finally{if(before!=ledger.revision())setDirty();}
    }
    static void requireThread(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Reputation requires server thread");}
    private void bindQuota(MinecraftServer server) {
        quota.bind(server.getWorldPath(LevelResource.ROOT).resolve("data/mythictrpg_reputation_judgement_v1.dat"));
        ledger.mutationGate(quota::enabled, (prospective, maintenance) -> quota.admit(JSON.toJson(prospective), maintenance));
    }
    @Override public boolean isDirty() { return super.isDirty() || quota.retryNeeded(); }
    @Override public void save(java.io.File file, HolderLookup.Provider registries) {
        if (isDirty() && ManagedSavedDataIo.queue(file, save(new CompoundTag(), registries), quota)) setDirty(false);
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        if(rejected!=null)return rejected.copy();
        tag.putInt("dataVersion",ReputationLedger.VERSION);tag.putString("state",JSON.toJson(ledger.snapshot()));return tag;
    }
    public static ReputationSavedData load(CompoundTag tag,HolderLookup.Provider registries) {
        var result=new ReputationSavedData(new UUID(0,0));
        try {
            if(tag.getInt("dataVersion")!=ReputationLedger.VERSION||tag.getString("state").length()>16_000_000)throw new IllegalArgumentException("Reputation schema/size");
            result.ledger=ReputationLedger.restore(JSON.fromJson(tag.getString("state"),ReputationLedger.Snapshot.class));
        }catch(RuntimeException invalid){result.rejected=tag.copy();MythicTrpg.LOGGER.error("Reputation state preserved read-only",invalid);}
        return result;
    }
}
