package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;
import java.util.function.Function;

/** Separate game receipt storage; never migrates LP/player affinity or writes when this feature is OFF. */
public final class ReputationSavedData extends SavedData {
    private static final Gson JSON=new Gson();
    private ReputationLedger ledger;private CompoundTag rejected;
    public ReputationSavedData(UUID world){ledger=new ReputationLedger(world);setDirty();}
    static ReputationSavedData get(MinecraftServer server,UUID world) {
        requireThread(server);
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(()->new ReputationSavedData(world),ReputationSavedData::load),"mythictrpg_reputation_judgement_v1");
    }
    public boolean ready(UUID world){return rejected==null&&ledger.worldId().equals(world);}
    ReputationLedger.Snapshot snapshot(){return ledger.snapshot();}
    <T> T access(MinecraftServer server,UUID world,Function<ReputationLedger,T> operation) {
        requireThread(server);if(!ready(world))throw new IllegalStateException("Reputation unavailable/world mismatch");
        long before=ledger.revision();try{return operation.apply(ledger);}finally{if(before!=ledger.revision())setDirty();}
    }
    static void requireThread(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Reputation requires server thread");}
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
