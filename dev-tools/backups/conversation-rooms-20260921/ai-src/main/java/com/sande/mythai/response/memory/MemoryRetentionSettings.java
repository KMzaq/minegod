package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import java.nio.file.*;

/** Operator-selected capacities, no automatic expiration/deletion. Missing file preserves existing limits. */
public record MemoryRetentionSettings(int schemaVersion,int maxEntries,int maxPerScope,long maxStorageBytes,
        int protectedReserveEntries,int protectedReservePerScope) {
    public static final MemoryRetentionSettings DEFAULT = new MemoryRetentionSettings(1,12000,2000,64L*1024*1024,0,0);
    public MemoryRetentionSettings {
        if(schemaVersion!=1 || maxEntries<1 || maxEntries>100000 || maxPerScope<1 || maxPerScope>maxEntries
                ||maxStorageBytes<65536||maxStorageBytes>1L<<30||protectedReserveEntries<0||protectedReserveEntries>=maxEntries
                ||protectedReservePerScope<0||protectedReservePerScope>=maxPerScope)
            throw new IllegalArgumentException("Invalid retention capacity/reserve");
    }
    public boolean accepts(int count,int scopeCount,MemoryJournal.Entry entry) {
        boolean protect=MemorySalience.protectedCandidate(entry);
        return count < maxEntries-(protect?0:protectedReserveEntries)
                && scopeCount < maxPerScope-(protect?0:protectedReservePerScope);
    }
    public static MemoryRetentionSettings load(Path path) {
        if(!Files.exists(path))return DEFAULT;
        try {
            if(Files.size(path)>4096)throw new IllegalArgumentException("retention config budget");
            return java.util.Objects.requireNonNull(new Gson().fromJson(Files.readString(path),MemoryRetentionSettings.class));
        }catch(Exception invalid){throw new IllegalArgumentException("Invalid ai-memory-retention.json; raw data unchanged",invalid);}
    }
}
