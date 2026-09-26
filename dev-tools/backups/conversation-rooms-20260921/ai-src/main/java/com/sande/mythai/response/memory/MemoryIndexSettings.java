package com.sande.mythai.response.memory;

import com.google.gson.*;
import java.net.URI;
import java.nio.file.*;

/** An explicit operational opt-in, not an installer/model selector. No valid config => no files or model work. */
public record MemoryIndexSettings(boolean enabled,Mode semanticMode,boolean consolidate,long maxStorageBytes,int maxEntries,
        URI endpoint,String embeddingModel,String modelRevision,int dimensions,String extractionModel,String extractionRevision,
        int queryTimeoutMs,int backgroundTimeoutMs,Execution execution) {
    public record Execution(boolean embeddingCpu,int embeddingThreads,int embeddingKeepAliveSeconds,
            boolean extractionCpu,int extractionThreads,int extractionContext,int extractionMaxTokens,
            int extractionKeepAliveSeconds,double minimumSimilarity,boolean skipSemanticWhenFound) {
        public static final Execution DEFAULT=new Execution(false,0,0,false,0,0,768,0,0.75,false);
        public Execution {
            if(embeddingThreads<0||embeddingThreads>32||extractionThreads<0||extractionThreads>32
                    ||embeddingKeepAliveSeconds<0||embeddingKeepAliveSeconds>3600||extractionKeepAliveSeconds<0||extractionKeepAliveSeconds>3600
                    ||extractionContext!=0&&(extractionContext<1024||extractionContext>16384)
                    ||extractionMaxTokens<128||extractionMaxTokens>1024||!Double.isFinite(minimumSimilarity)
                    ||minimumSimilarity<0.5||minimumSimilarity>0.95)throw new IllegalArgumentException("memory execution budget");
        }
    }
    /** Preserve existing source callers and absent execution settings. Zero keep-alive means no override. */
    public MemoryIndexSettings(boolean enabled,Mode semanticMode,boolean consolidate,long maxStorageBytes,int maxEntries,
            URI endpoint,String embeddingModel,String modelRevision,int dimensions,String extractionModel,String extractionRevision,
            int queryTimeoutMs,int backgroundTimeoutMs) {
        this(enabled,semanticMode,consolidate,maxStorageBytes,maxEntries,endpoint,embeddingModel,modelRevision,dimensions,
                extractionModel,extractionRevision,queryTimeoutMs,backgroundTimeoutMs,Execution.DEFAULT);
    }
    public enum Mode { OFF,SHADOW,ON }
    public static final MemoryIndexSettings OFF=new MemoryIndexSettings(false,Mode.OFF,false,0,12000,
            URI.create("http://127.0.0.1:11434"),"","",0,"","",250,5000);
    public MemoryIndexSettings {
        if(semanticMode==null||endpoint==null||embeddingModel==null||modelRevision==null||extractionModel==null||extractionRevision==null||execution==null)throw new IllegalArgumentException("index config");
        if(!"http".equals(endpoint.getScheme())||!java.util.Set.of("127.0.0.1","[::1]","::1").contains(endpoint.getHost())
                ||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null||!java.util.Set.of("","/").contains(endpoint.getPath()))
            throw new IllegalArgumentException("explicit numeric loopback Ollama origin required");
        if(maxEntries<1||maxEntries>50000||maxStorageBytes<0||enabled&&maxStorageBytes<4096||queryTimeoutMs<1||queryTimeoutMs>2000
                ||backgroundTimeoutMs<1||backgroundTimeoutMs>30000)throw new IllegalArgumentException("index budgets");
        if(embeddingModel.length()>160||modelRevision.length()>128||extractionModel.length()>160||extractionRevision.length()>128)throw new IllegalArgumentException("model identity budget");
        if(enabled&&semanticMode!=Mode.OFF&&(embeddingModel.isBlank()||!modelRevision.matches("[a-f0-9]{64}")||dimensions<1||dimensions>4096))throw new IllegalArgumentException("embedding model/digest/dimension required");
        if(enabled&&consolidate&&(extractionModel.isBlank()||!extractionRevision.matches("[a-f0-9]{64}")))throw new IllegalArgumentException("extraction model/digest required");
    }
    public String fingerprint(){return DerivedMemory.hash(embeddingModel+"\n"+modelRevision+"\n"+dimensions+"\nraw-text-v1");}
    public String extractionVersion(){return "extractive-v3/"+DerivedMemory.hash(extractionModel+"\n"+extractionRevision);}
    public static MemoryIndexSettings load(Path file) {
        try {
            if(!Files.isRegularFile(file)||Files.size(file)>8192)return OFF;
            JsonObject j=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(j.get("schemaVersion").getAsInt()!=1||!j.get("enabled").getAsJsonPrimitive().isBoolean()||!j.get("consolidate").getAsJsonPrimitive().isBoolean())return OFF;
            return new MemoryIndexSettings(j.get("enabled").getAsBoolean(),Mode.valueOf(j.get("semanticMode").getAsString()),j.get("consolidate").getAsBoolean(),
                    j.get("maxStorageBytes").getAsLong(),j.get("maxEntries").getAsInt(),URI.create(j.get("endpoint").getAsString()),
                    j.get("embeddingModel").getAsString(),j.get("modelRevision").getAsString(),j.get("dimensions").getAsInt(),j.get("extractionModel").getAsString(),j.get("extractionRevision").getAsString(),
                    j.get("queryTimeoutMs").getAsInt(),j.get("backgroundTimeoutMs").getAsInt(),
                    j.has("execution")?new Gson().fromJson(j.get("execution"),Execution.class):Execution.DEFAULT);
        }catch(Exception invalid){return OFF;}
    }
}
