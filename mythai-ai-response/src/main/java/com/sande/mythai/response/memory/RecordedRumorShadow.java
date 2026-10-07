package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.server.RecordedMemoryAccess;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Diagnostic-only comparison. A received claim or a God's assessment is never a verified world fact. */
public final class RecordedRumorShadow {
    private static final int MAX_PAGES=3, MAX_ROOTS=4, MAX_BYTES=8192;
    private static final MemoryReadSession.Query RECENT=new MemoryReadSession.Query("",Optional.empty(),Optional.empty());
    private static final AtomicLong completed=new AtomicLong(),unavailable=new AtomicLong(),legacySelected=new AtomicLong(),legacyMatches=new AtomicLong();
    private RecordedRumorShadow() { }
    public static void compare(MinecraftServer server,Request request,Set<UUID> selected) {
        if(!server.isSameThread()||MemoryFoundationSettings.mode()!=MemoryFoundationSettings.Mode.RUMOR_TEST
                ||request.publicRoom()||request.godIds().size()!=1)return;
        compare(()->RecordedMemoryAccess.open(server,request),selected,server::execute);
    }
    static void compare(Supplier<Optional<MemoryReadSession>> opening,Set<UUID> selected,Consumer<Runnable> dispatch) {
        try {
            var legacy=Set.copyOf(selected);var session=opening.get();
            if(session.isPresent())new Comparison(session.orElseThrow(),legacy,dispatch).request(Optional.empty());
        }catch(RuntimeException failure){unavailable.incrementAndGet();}
    }
    private static final class Comparison {
        final MemoryReadSession session;
        final Set<UUID> legacy,found=new HashSet<>();
        final Consumer<Runnable> dispatch;
        final List<RumorReadRecords.Page> pages=new ArrayList<>();
        final AtomicBoolean finished=new AtomicBoolean();int bytes;
        Comparison(MemoryReadSession session,Set<UUID> legacy,Consumer<Runnable> dispatch){this.session=session;this.legacy=legacy;this.dispatch=dispatch;}
        boolean current(){return pages.stream().allMatch(session::current);}
        void request(Optional<RumorReadRecords.Cursor> cursor) {
            guard(()->{
                if(!current()){fail();return;}
                int remaining=MAX_BYTES-bytes,rows=MAX_ROOTS-found.size();
                if(pages.size()>=MAX_PAGES||rows<=0||remaining<256){finish();return;}
                session.rumors(RECENT,cursor,new MemoryReadSession.Budget(rows,remaining)).whenComplete((page,failure)->
                        guard(()->dispatch.accept(()->guard(()->accept(page,failure,rows,remaining)))));
            });
        }
        void accept(RumorReadRecords.Page page,Throwable failure,int rows,int remaining) {
            if(failure!=null||page==null||!Set.of(MemoryReadSession.Status.FOUND,MemoryReadSession.Status.EMPTY,MemoryReadSession.Status.PARTIAL).contains(page.status())
                    ||page.entries().size()>rows||!session.current(page)||!current()){fail();return;}
            int received=0;
            for(var entry:page.entries()) {
                received=Math.addExact(received,RumorReadRecords.wireByteSize(entry));
                if(received>remaining){fail();return;}
            }
            pages.add(page);bytes+=received;
            page.entries().forEach(entry->found.add(UUID.fromString(entry.source().sourceId())));
            if(page.next().isEmpty())finish();else request(page.next());
        }
        void finish() {
            if(!current()){fail();return;}
            long matches=found.stream().filter(legacy::contains).count();
            if(!current()){fail();return;}
            if(!finished.compareAndSet(false,true))return;
            completed.incrementAndGet();legacySelected.addAndGet(legacy.size());legacyMatches.addAndGet(matches);
        }
        void guard(Runnable action){if(!finished.get())try{action.run();}catch(RuntimeException failure){fail();}}
        void fail(){if(finished.compareAndSet(false,true))unavailable.incrementAndGet();}
    }
    /** No claim/epithet/root/subject/God/assessment is included in diagnostics. */
    public static Map<String,Long> diagnostics(){return Map.of("completed",completed.get(),"unavailable",unavailable.get(),
            "legacySelected",legacySelected.get(),"legacyMatches",legacyMatches.get());}
}
