package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Separate v2 append journal; no v1 migration, no eviction. A corrupt file is sealed, not repaired silently. */
public final class MemoryIndexStore implements AutoCloseable {
    private record Frame(int version,long sequence,String payload,String hash) {}
    public record Status(String state,long bytes,long limit,int records,long rejected,String reason) {}
    private static final Gson JSON=new Gson();private static final int FRAME_LIMIT=256*1024, VECTOR_COMPONENT_CAP=16_777_216;
    private final Path directory;private final MemoryJournal raw;private final MemoryIndexSettings settings;
    private final Map<String,MemoryIndexRow> rows=new LinkedHashMap<>();
    private volatile List<MemoryIndexRow> published=List.of();private volatile String state="STARTING",reason="";private volatile long bytes;
    private volatile Set<String> identities=Set.of();
    private final AtomicLong rejected=new AtomicLong();private long sequence;private int components;private FileChannel file,lockFile;private FileLock lock;
    private final ThreadPoolExecutor writer;
    public MemoryIndexStore(Path directory,MemoryJournal raw,MemoryIndexSettings settings) {
        this.directory=directory;this.raw=raw;this.settings=settings;
        writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"mythai-index-store");t.setDaemon(true);return t;}) {
            protected void terminated(){for(var resource:new AutoCloseable[]{file,lock,lockFile})try{if(resource!=null)resource.close();}catch(Exception e){fail(e);}if(!state.equals("FAILED"))state="CLOSED";}
        };
        writer.execute(()->{try{load();}catch(Exception e){fail(e);}});
    }
    private void load()throws Exception {
        if(!settings.enabled()){state="OFF";return;}
        Files.createDirectories(directory);lockFile=FileChannel.open(directory.resolve("writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        lock=lockFile.tryLock();if(lock==null)throw new java.io.IOException("index already owned");
        Path path=directory.resolve("index-v2.jsonl");
        if(Files.exists(path)) {
            bytes=Files.size(path);if(bytes>settings.maxStorageBytes())throw new java.io.IOException("index quota exceeded");
            try(var in=new java.io.BufferedInputStream(Files.newInputStream(path))){var buffer=new java.io.ByteArrayOutputStream();int b;
                while((b=in.read())!=-1){if(b!='\n'){if(buffer.size()>=FRAME_LIMIT)throw new java.io.IOException("index frame too large");buffer.write(b);continue;}
                    String line=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer.toByteArray())).toString();buffer.reset();
                    Frame f=JSON.fromJson(line,Frame.class);
                    if(f==null||f.version()!=2||f.sequence()!=sequence+1||!DerivedMemory.hash(f.sequence()+"/"+f.payload()).equals(f.hash()))throw new java.io.IOException("index checksum/order");
                    var row=JSON.fromJson(f.payload(),MemoryIndexRow.class);components+=row.vector().length;
                    if(rows.putIfAbsent(row.identity(),row)!=null||rows.size()>settings.maxEntries()||components>VECTOR_COMPONENT_CAP)throw new java.io.IOException("index duplicate/capacity");sequence=f.sequence();
                }if(buffer.size()!=0)throw new java.io.IOException("incomplete index frame");
            }
        }
        file=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND);publish();state="READY";
    }
    public CompletableFuture<String> put(MemoryIndexRow row,List<MemoryJournal.Entry> expected) {
        var result=new CompletableFuture<String>();
        try{writer.execute(()->{try{
            if(!state.equals("READY")){result.complete("UNAVAILABLE");return;}
            if(!expected.contains(row.source())||!raw.stillCurrent(expected)){result.complete("STALE");return;}
            var view=new MemoryJournal.ReadView(row.source().key(),row.source().audience(),expected,Set.of(),true,false);
            if(row.links().stream().anyMatch(l->!row.linkCurrent(l,view))){result.complete("STALE_LINK");return;}
            if(rows.containsKey(row.identity())){result.complete("DUPLICATE");return;}
            String payload=JSON.toJson(row);byte[] data=(JSON.toJson(new Frame(2,sequence+1,payload,DerivedMemory.hash((sequence+1)+"/"+payload)))+"\n").getBytes(StandardCharsets.UTF_8);
            if(data.length>FRAME_LIMIT||bytes+data.length>settings.maxStorageBytes()||rows.size()>=settings.maxEntries()||components+row.vector().length>VECTOR_COMPONENT_CAP){
                rejected.incrementAndGet();reason=data.length>FRAME_LIMIT?"FRAME_LIMIT":bytes+data.length>settings.maxStorageBytes()?"BYTE_LIMIT"
                        :rows.size()>=settings.maxEntries()?"ENTRY_LIMIT":"VECTOR_MEMORY_LIMIT";result.complete("FULL");return;}
            var buffer=ByteBuffer.wrap(data);while(buffer.hasRemaining())file.write(buffer);file.force(true);
            sequence++;bytes+=data.length;components+=row.vector().length;rows.put(row.identity(),row);publish();result.complete("STORED");
        }catch(Exception e){fail(e);result.complete("UNAVAILABLE");}});}catch(RejectedExecutionException busy){rejected.incrementAndGet();result.complete("UNAVAILABLE");}
        return result;
    }
    public List<MemoryIndexRow> view(MemoryJournal.ReadView view){
        if(!state.equals("READY")||!view.ready()||view.failed()||!view.audience().contains(view.key().player()))return List.of();
        var sources=new HashMap<UUID,MemoryJournal.Entry>();view.entries().forEach(e->sources.put(e.id(),e));
        return published.stream().filter(r->r.source().key().equals(view.key())&&r.source().audience().containsAll(view.audience())
                &&!view.pending().contains(r.source().id())&&r.source().equals(sources.get(r.source().id()))&&raw.stillCurrent(List.of(r.source()))).toList();
    }
    public Status status(){return new Status(state,bytes,settings.maxStorageBytes(),published.size(),rejected.get(),reason);}
    private void publish(){published=List.copyOf(rows.values());identities=Set.copyOf(rows.keySet());}
    public boolean contains(String identity){return state.equals("READY")&&identities.contains(identity);}
    public void awaitIdle()throws Exception{var f=new CompletableFuture<Void>();writer.execute(()->f.complete(null));f.get(10,TimeUnit.SECONDS);}
    private void fail(Exception e){state="FAILED";reason=e.getClass().getSimpleName();}
    public void close(){writer.shutdown();try{if(!writer.awaitTermination(5,TimeUnit.SECONDS))reason="DRAIN_TIMEOUT";}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
