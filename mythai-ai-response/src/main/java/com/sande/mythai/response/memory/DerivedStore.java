package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Append-only, quota-bound optional sidecar. Corruption is sealed in place; raw v1 remains the source of truth. */
public final class DerivedStore implements AutoCloseable {
    public enum Result { STORED,DUPLICATE,STALE,FULL,UNAVAILABLE }
    private record Row(int version,long sequence,DerivedMemory.Note note,String digest) {}
    public record Status(String state,long usedBytes,long maxBytes,int entries,long rejected,String reason) {}
    private static final Gson JSON=new Gson();
    private final MemoryJournal source; private final Path path;private final DerivedSettings settings;
    private final ThreadPoolExecutor writer;
    private final Map<String,DerivedMemory.Note> notes=new LinkedHashMap<>();
    private volatile List<DerivedMemory.Note> published=List.of();
    private volatile String state="STARTING",reason="";private volatile long used;private long sequence;
    private final java.util.concurrent.atomic.AtomicLong rejected=new java.util.concurrent.atomic.AtomicLong();
    private FileChannel lockChannel,channel;private FileLock lock;
    public DerivedStore(Path directory,MemoryJournal source,DerivedSettings settings) {
        this.source=source;this.path=directory;this.settings=settings;
        writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),work->{var t=new Thread(work,"mythai-derived");t.setDaemon(true);return t;}) {
            @Override protected void terminated(){
                try {if(channel!=null)channel.close();}catch(Exception failure){fail(failure);}
                try {if(lock!=null)lock.release();}catch(Exception failure){fail(failure);}
                try {if(lockChannel!=null)lockChannel.close();}catch(Exception failure){fail(failure);}
                if(!state.equals("FAILED"))state="CLOSED";
            }
        };
        writer.execute(()->{try{load();}catch(Exception failure){fail(failure);}});
    }
    private void load() throws Exception {
        if(!settings.enabled()){state="OFF";return;}
        Files.createDirectories(path);lockChannel=FileChannel.open(path.resolve("writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        lock=lockChannel.tryLock();if(lock==null)throw new java.io.IOException("sidecar already owned");
        Path file=path.resolve("derived-v1.jsonl");
        if(Files.exists(file)) {
            used=Files.size(file);if(used>settings.maxStorageBytes())throw new java.io.IOException("derived capacity exceeded");
            // Bound each frame before allocating/decoding; an explicit large disk quota is not a heap allocation budget.
            try(var input=new java.io.BufferedInputStream(Files.newInputStream(file))) {
                var frame=new java.io.ByteArrayOutputStream();int value;
                while((value=input.read())!=-1) {
                    if(value!='\n') {if(frame.size()>=32768)throw new java.io.IOException("oversize derived row");frame.write(value);continue;}
                    if(frame.size()==0)throw new java.io.IOException("empty derived row");
                    String line=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(frame.toByteArray())).toString();frame.reset();
                    Row row=JSON.fromJson(line,Row.class);
                    if(row.version()!=1||row.sequence()!=sequence+1||!digest(row.note()).equals(row.digest())||notes.putIfAbsent(row.note().identity(),row.note())!=null
                            ||notes.size()>settings.maxEntries())throw new java.io.IOException("invalid derived journal");
                    sequence=row.sequence();
                }
                if(frame.size()!=0)throw new java.io.IOException("incomplete derived record");
            }
        }
        channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND);
        published=List.copyOf(notes.values());state="READY";
    }
    private static String digest(DerivedMemory.Note n) {
        String value=n.identity()+"|"+n.key()+"|"+n.audience().stream().map(UUID::toString).sorted().toList()+"|"+n.kind()+"|"+n.quote()+"|"+n.recordedAt()+"|"+n.eventDate()+"|"+n.important();
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    public CompletableFuture<Result> append(MemoryJournal.Entry expected,RecallSettings.TimeBasis basis) {
        var result=new CompletableFuture<Result>();
        try {writer.execute(()->{
            if(!state.equals("READY")){result.complete(Result.UNAVAILABLE);return;}
            try {
                if(!source.stillCurrent(List.of(expected))){result.complete(Result.STALE);return;}
                var note=DerivedMemory.project(expected,basis);
                if(notes.containsKey(note.identity())){result.complete(Result.DUPLICATE);return;}
                byte[] bytes=(JSON.toJson(new Row(1,sequence+1,note,digest(note)))+"\n").getBytes(StandardCharsets.UTF_8);
                if(notes.size()>=settings.maxEntries()||used+bytes.length>settings.maxStorageBytes()){rejected.incrementAndGet();reason="CAPACITY_FULL";result.complete(Result.FULL);return;}
                ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
                // A concurrent raw deletion can leave inert physical bytes, never a valid search result.
                notes.put(note.identity(),note);sequence++;used+=bytes.length;published=List.copyOf(notes.values());result.complete(Result.STORED);
            }catch(Exception failure){fail(failure);result.complete(Result.UNAVAILABLE);}
        });}catch(RejectedExecutionException busy){rejected.incrementAndGet();result.complete(writer.isShutdown()?Result.UNAVAILABLE:Result.FULL);}
        return result;
    }
    public List<DerivedMemory.Note> view(MemoryJournal.ReadView raw) {
        if(!state.equals("READY")||!raw.ready()||raw.failed()||!raw.audience().contains(raw.key().player()))return List.of();
        Map<UUID,MemoryJournal.Entry> sources=new HashMap<>();raw.entries().stream().filter(e->!raw.pending().contains(e.id())).forEach(e->sources.put(e.id(),e));
        return published.stream().filter(n->n.key().equals(raw.key())&&DerivedMemory.valid(n,sources.get(n.sourceId()),raw.audience()))
                .filter(n->source.stillCurrent(List.of(sources.get(n.sourceId())))).toList();
    }
    /** Incremental bounded batch; backlog remains searchable through raw evidence. No model job. */
    public void refresh(MemoryJournal.ReadView raw,RecallSettings.TimeBasis basis) {
        if(!state.equals("READY"))return;
        var present=new HashSet<String>();published.forEach(n->present.add(n.identity()));
        raw.entries().stream().filter(e->e.source()==MemoryJournal.Source.PLAYER_STATEMENT&&!raw.pending().contains(e.id()))
                .filter(e->!present.contains(e.id()+"/"+DerivedMemory.fingerprint(e)+"/"+DerivedMemory.EXTRACTOR)).limit(8).forEach(e->append(e,basis));
    }
    public Status status(){return new Status(state,used,settings.maxStorageBytes(),published.size(),rejected.get(),reason);}
    private void fail(Exception failure){state="FAILED";reason=failure.getClass().getSimpleName()+":"+failure.getMessage();}
    public boolean awaitIdle() throws Exception {var f=new CompletableFuture<Boolean>();writer.execute(()->f.complete(true));return f.get(10,TimeUnit.SECONDS);}
    public boolean close(Duration timeout){writer.shutdown();try{boolean done=writer.awaitTermination(timeout.toMillis(),TimeUnit.MILLISECONDS);if(done&&!state.equals("FAILED"))state="CLOSED";return done;}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}
    public void close(){close(Duration.ofSeconds(5));}
}
