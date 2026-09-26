package com.sande.mythai.response.memory;
import com.google.gson.JsonParser;
import java.nio.file.*;
public record DerivedSettings(boolean enabled,boolean semanticRetrieval,long maxStorageBytes,int maxEntries) {
    public static final DerivedSettings OFF=new DerivedSettings(false,false,0,12000);
    public DerivedSettings {if(maxEntries<1||maxEntries>50000||maxStorageBytes<0||enabled&&maxStorageBytes<4096)throw new IllegalArgumentException("explicit derived quota required");}
    public static DerivedSettings load(Path path) {
        try {
            if(!Files.isRegularFile(path)||Files.size(path)>4096)return OFF;
            var j=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if(j.get("schemaVersion").getAsInt()!=1||!j.get("enabled").getAsJsonPrimitive().isBoolean()||!j.get("semanticRetrieval").getAsJsonPrimitive().isBoolean())return OFF;
            return new DerivedSettings(j.get("enabled").getAsBoolean(),j.get("semanticRetrieval").getAsBoolean(),j.get("maxStorageBytes").getAsLong(),j.get("maxEntries").getAsInt());
        }catch(Exception invalid){return OFF;}
    }
}
