package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.recording.api.NativeMemoryEvidence;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** No server, disk or model: async scope/cache/late-completion boundaries, independently of SQL provenance tests. */
public final class NativeRoomEvidenceCacheTest {
    private static int checks;
    private static final UUID WORLD = UUID.randomUUID(), DATASET = UUID.randomUUID();
    private static final String HASH = "a".repeat(64);
    public static void main(String[] args) {
        isolation(); asynchronous(); failures(); bounded(); completionRace();
        System.out.println("NativeRoomEvidenceCacheTest: " + checks + " checks passed");
    }
    private static RoomEvidenceReference ref() {
        return NativeMemoryEvidence.encode(new NativeMemoryEvidence.Reference(1, WORLD, DATASET, UUID.randomUUID(), HASH));
    }
    private static void isolation() {
        var cache = new NativeRoomEvidence.PreparedCache<String>(); var a = ref(); var b = ref();
        var authority = new AtomicBoolean(true); var source = new AtomicBoolean(true);
        var reads = new AtomicInteger();
        var port = port(reference -> { reads.incrementAndGet(); return CompletableFuture.completedFuture(true); }, reference -> source.get());
        check(cache.prepare("A/turn1", List.of(a), () -> Optional.of(port), authority::get, Runnable::run).join(), "prepared A");
        check(reads.get() == 1 && cache.current("A/turn1", a), "one exact read");
        check(!cache.current("B/turn1", a) && !cache.current("A/turn2", a) && !cache.current("A/turn1", b), "scope, turn, ref isolated");
        source.set(false); check(!cache.current("A/turn1", a), "source revocation");
        source.set(true); authority.set(false); check(!cache.current("A/turn1", a), "room revocation");
        authority.set(true); cache.clear(); check(!cache.current("A/turn1", a), "lifecycle clear");
        check(cache.register("fresh", Map.of(a, () -> true)), "fresh issued guard registration");
        check(cache.current("fresh", a), "fresh current");
        check(!cache.register("foreign", Map.of(a, () -> { throw new IllegalStateException(); })), "broken guard rejected");
        check(!cache.current("foreign", a), "broken guard absent");
    }
    private static void asynchronous() {
        var cache = new NativeRoomEvidence.PreparedCache<String>(); var a = ref(); var b = ref();
        var first = new CompletableFuture<Boolean>(); var second = new CompletableFuture<Boolean>();
        var dispatch = new ArrayDeque<Runnable>(); var prepared = new ArrayList<RoomEvidenceReference>();
        var current = new AtomicBoolean(true); var opens = new AtomicInteger();
        var port = port(ref -> { prepared.add(ref); return ref.equals(a) ? first : second; }, ref -> current.get());
        Supplier<Optional<NativeRoomEvidence.Port>> open = () -> { opens.incrementAndGet(); return Optional.of(port); };
        var result = cache.prepare("A", List.of(a,b), open, () -> true, dispatch::add);
        check(!result.isDone() && prepared.equals(List.of(a)), "sequential first");
        check(!cache.prepare("A", List.of(a), open, () -> true, dispatch::add).join() && opens.get() == 1, "same scope busy no second open");
        first.complete(true); check(prepared.size() == 1, "wait game dispatch"); drain(dispatch);
        check(prepared.equals(List.of(a,b)) && !result.isDone(), "second on game dispatch");
        current.set(false); second.complete(true); drain(dispatch);
        check(!result.join() && !cache.current("A", a) && !cache.current("A", b), "all sources rechecked before atomic batch");
        var late = new CompletableFuture<Boolean>();
        var cancelled = cache.prepare("cancel", List.of(a), () -> Optional.of(port(r -> late, r -> true)), () -> true, dispatch::add);
        cancelled.cancel(false); late.complete(true); drain(dispatch);
        check(cancelled.isCancelled() && !cache.current("cancel", a), "cancel late callback cannot register");
        var afterClear = new CompletableFuture<Boolean>();
        var cleared = cache.prepare("clear", List.of(a), () -> Optional.of(port(r -> afterClear,r -> true)), () -> true, dispatch::add);
        cache.clear(); afterClear.complete(true); drain(dispatch);
        check(!cleared.join() && !cache.current("clear", a), "clear invalidates pending epoch");
        var denied = cache.prepare("deadline", List.of(a), () -> Optional.of(port(r -> new CompletableFuture<>(), r -> true)), () -> true, dispatch::add);
        denied.complete(false); drain(dispatch); check(!cache.current("deadline", a), "timeout-shaped completion no guard");
    }
    private static void failures() {
        var cache = new NativeRoomEvidence.PreparedCache<String>(); var a = ref(); var opens = new AtomicInteger();
        Supplier<Optional<NativeRoomEvidence.Port>> absent = () -> { opens.incrementAndGet(); return Optional.empty(); };
        check(!cache.prepare("x", List.of(a), absent, () -> true, Runnable::run).join(), "no store");
        check(!cache.prepare("x", List.of(a), absent, () -> false, Runnable::run).join() && opens.get() == 1, "no authority no open");
        var malformed = new RoomEvidenceReference(NativeMemoryEvidence.KIND, "{}");
        check(!cache.prepare("x", List.of(malformed), absent, () -> true, Runnable::run).join() && opens.get() == 1, "malformed before store");
        check(!cache.prepare("x", List.of(a), () -> { throw new IllegalStateException(); }, () -> true, Runnable::run).join(), "open throws");
        check(!cache.prepare("x", List.of(a), () -> Optional.of(port(r -> CompletableFuture.failedFuture(new IllegalStateException()),r -> true)), () -> true, Runnable::run).join(), "read fails");
        check(!cache.prepare("x", List.of(a), () -> Optional.of(port(r -> CompletableFuture.completedFuture(false),r -> true)), () -> true, Runnable::run).join(), "invalid manifest");
        check(!cache.prepare("dispatch", List.of(a), () -> Optional.of(port(r -> CompletableFuture.completedFuture(true),r -> true)), () -> true,
                task -> { throw new RejectedExecutionException(); }).join(), "dispatch rejected");
        check(!cache.current("dispatch", a), "rejected dispatch no guard");
        check(cache.prepare("empty", List.of(), absent, () -> true, Runnable::run).join(), "empty no work");
    }
    private static void bounded() {
        var cache = new NativeRoomEvidence.PreparedCache<String>(); var a = ref(); var opens = new AtomicInteger();
        Supplier<Optional<NativeRoomEvidence.Port>> pending = () -> { opens.incrementAndGet(); return Optional.of(port(r -> new CompletableFuture<>(),r -> true)); };
        for (int i=0;i<16;i++) check(!cache.prepare("pending"+i,List.of(a),pending,()->true,Runnable::run).isDone(),"bounded pending accepted");
        check(!cache.prepare("overflow",List.of(a),pending,()->true,Runnable::run).join() && opens.get()==16,"pending overflow no worker");
        cache.clear();
        check(!cache.prepare("tooMany",Collections.nCopies(65,a),pending,()->true,Runnable::run).join(),"input size bounded before distinct");
        for(int i=0;i<65;i++) check(cache.register("scope"+i,Map.of(a,()->true)),"bounded scope registration");
        check(!cache.current("scope0",a)&&cache.current("scope64",a),"old scope evicted");
        var many = new HashMap<RoomEvidenceReference,BooleanSupplier>();
        for(int i=0;i<64;i++)many.put(ref(),()->true);
        check(cache.register("full",many),"64 refs accepted");
        check(!cache.register("full",Map.of(a,()->true))&&!cache.current("full",a),"reference cache cap no partial insertion");
    }
    private static void completionRace() {
        var cache = new NativeRoomEvidence.PreparedCache<String>(); var a = ref();
        var input = new CompletableFuture<Boolean>(); var resultHolder = new AtomicReference<CompletableFuture<Boolean>>();
        var port = port(r -> input, r -> { resultHolder.get().complete(false); return true; });
        var result = cache.prepare("race",List.of(a),()->Optional.of(port),()->true,Runnable::run);
        resultHolder.set(result); input.complete(true);
        check(!result.join()&&!cache.current("race",a),"completion race cannot install live guard");
    }
    private static NativeRoomEvidence.Port port(Function<RoomEvidenceReference,CompletableFuture<Boolean>> prepare, Predicate<RoomEvidenceReference> current) {
        return new NativeRoomEvidence.Port() {
            public CompletableFuture<Boolean> prepare(RoomEvidenceReference ref){return prepare.apply(ref);}
            public boolean current(RoomEvidenceReference ref){return current.test(ref);}
        };
    }
    private static void drain(Queue<Runnable> tasks) { while(!tasks.isEmpty()) tasks.remove().run(); }
    private static void check(boolean okay,String reason) { checks++; if(!okay)throw new AssertionError(reason); }
}
