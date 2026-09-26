package com.sande.mythai.response.memory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Worker-side opt-in attachment. Missing/invalid configs never install a model or schedule model work. */
public final class DerivedService {
    private record Runtime(DerivedSettings settings,DerivedStore store,MemoryIndexRuntime index) {}
    private static final Map<MemoryJournal,Runtime> STORES=new ConcurrentHashMap<>();
    private static final Set<MemoryJournal> CLOSED=Collections.newSetFromMap(new WeakHashMap<>());
    private DerivedService() {}
    public static RecallSearch.Result search(MemoryJournal journal,Path config,Path directory,MemoryJournal.ReadView view,
            RecallQuery query,RecallSettings settings,Set<String> recent,List<String> recentPlayers,long now) {
        var base=RecallSearch.search(view,query,settings,recent,recentPlayers,now,15_000_000L);
        try {
            var runtime=runtime(journal,config,directory);
            if(runtime==null)return base;
            if(runtime.store()!=null)runtime.store().refresh(view,settings.timeBasis());
            return runtime.index()==null?base:runtime.index().search(view,base,settings,now);
        } catch(RuntimeException optionalFailure) { return base; }
    }
    public static DerivedStore.Status status(MemoryJournal journal) {
        var r=STORES.get(journal);return r==null||r.store()==null?new DerivedStore.Status("OFF",0,0,0,0,""):r.store().status();
    }
    public static MemoryIndexStore.Status indexStatus(MemoryJournal journal) {
        var r=STORES.get(journal);return r==null||r.index()==null?new MemoryIndexStore.Status("OFF",0,0,0,0,""):r.index().status();
    }
    public static String diagnostic(MemoryJournal journal) {
        var r=STORES.get(journal);return (r==null?"index=NOT_INITIALIZED":r.index()==null?"index=OFF (missing/disabled/invalid config)":r.index().diagnostic())
                +"; model_admission="+ModelAdmission.status();
    }
    public static boolean initialized(MemoryJournal journal){return STORES.containsKey(journal);}
    /** Must run on the memory reader, never the server tick. */
    public static void initialize(MemoryJournal journal,Path config,Path directory){runtime(journal,config,directory);}
    static synchronized void initialize(MemoryJournal journal,Path config,Path directory,
            java.util.function.Function<MemoryIndexSettings,OllamaMemoryBackend> backend){
        if(!CLOSED.contains(journal)&&journal.ready())STORES.computeIfAbsent(journal,j->create(j,config,directory,backend));
    }
    /** Nonblocking tick signal; the runtime performs I/O/scanning on its own worker. */
    public static void pump(MemoryJournal journal){var r=STORES.get(journal);if(r!=null&&r.index()!=null)r.index().pump();}
    private static synchronized Runtime runtime(MemoryJournal journal,Path config,Path directory) {
        if(CLOSED.contains(journal)||!journal.ready())return null;
        return STORES.computeIfAbsent(journal,j->create(j,config,directory,OllamaMemoryBackend::new));
    }
    private static Runtime create(MemoryJournal journal,Path config,Path directory,
            java.util.function.Function<MemoryIndexSettings,OllamaMemoryBackend> backend){
        var loaded=DerivedSettings.load(config);var indexed=MemoryIndexSettings.load(config.resolveSibling("ai-memory-index.json"));
        return new Runtime(loaded,loaded.enabled()?new DerivedStore(directory,journal,loaded):null,
                indexed.enabled()?new MemoryIndexRuntime(directory.resolveSibling("index-v2"),journal,indexed,backend.apply(indexed)):null);
    }
    static void awaitIdle(MemoryJournal journal)throws Exception{var r=STORES.get(journal);if(r!=null&&r.index()!=null)r.index().awaitIdle();}
    /** Mark even journals with queued (not yet started) readers. Late workers cannot reopen a sidecar. */
    public static boolean close(Collection<MemoryJournal> journals) {
        List<Runtime> closing;
        synchronized(DerivedService.class){CLOSED.addAll(journals);CLOSED.addAll(STORES.keySet());closing=List.copyOf(STORES.values());STORES.clear();}
        boolean drained=true;
        for(var r:closing){if(r.index()!=null){r.index().close();drained&=r.index().drained();}
            if(r.store()!=null)drained&=r.store().close(java.time.Duration.ofSeconds(5));}
        return drained;
    }
}
