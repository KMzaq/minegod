package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.recording.api.MemoryReadSession;
import com.sande.mythictrpg.recording.api.NativeMemoryEvidence;
import com.sande.mythictrpg.recording.api.NativeMemorySeal;
import com.sande.mythictrpg.recording.api.NativeInterpretationEvidence;
import com.sande.mythictrpg.recording.api.NativeInterpretationSeal;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Game-issued native speech references. A portable descriptor is not a permission or a world fact. */
public final class NativeRoomEvidence {
    private static final Map<MinecraftServer, PreparedCache<Scope>> CACHES = new WeakHashMap<>();
    private record Scope(UUID room, long revision, UUID turn, UUID player, String speaker,
                         boolean publicRoom, Set<String> gods, Set<UUID> audience, String mode) { }
    private NativeRoomEvidence() { }

    /** Fresh publication accepts an actual game-issued session/seal, never AI-provided IDs or JSON. */
    public static List<RoomEvidenceReference> references(MinecraftServer server, Request request,
            MemoryReadSession session, NativeMemorySeal seal) {
        requireThread(server);
        if (!(session instanceof RecordedMemoryAccess.Session issued) || !issued.issuedFor(request)
                || !issued.current(seal) || !ConversationRooms.INSTANCE.memoryReadCurrent(server, request))
            throw new IllegalArgumentException("UNISSUED_NATIVE_MEMORY_SEAL");
        var key = scope(request);
        var ref = seal.reference();
        NativeMemoryEvidence.decode(ref);
        if (!cache(server).register(key, Map.of(ref, () -> key.equals(scope(request))
                && ConversationRooms.INSTANCE.memoryReadCurrent(server, request)
                && issued.issuedFor(request) && issued.current(seal))))
            throw new IllegalArgumentException("STALE_NATIVE_MEMORY_SEAL");
        return List.of(ref);
    }
    /** An interpretation stays a candidate; only the issuing game's opaque identity authorizes this reference. */
    public static List<RoomEvidenceReference> references(MinecraftServer server, Request request,
            MemoryReadSession session, NativeInterpretationSeal seal) {
        requireThread(server);
        if(!(session instanceof RecordedMemoryAccess.Session issued)||!issued.issuedFor(request)
                ||!issued.current(seal)||!ConversationRooms.INSTANCE.memoryReadCurrent(server,request))
            throw new IllegalArgumentException("UNISSUED_NATIVE_INTERPRETATION_SEAL");
        var key=scope(request);var ref=seal.reference();NativeInterpretationEvidence.decode(ref);
        if(!cache(server).register(key,Map.of(ref,()->key.equals(scope(request))
                &&ConversationRooms.INSTANCE.memoryReadCurrent(server,request)
                &&issued.issuedFor(request)&&issued.current(seal))))
            throw new IllegalArgumentException("STALE_NATIVE_INTERPRETATION_SEAL");
        return List.of(ref);
    }

    /** Exact persisted-manifest validation uses the existing recording read worker, not disk work on tick. */
    public static CompletableFuture<Boolean> prepare(MinecraftServer server, Request request,
            Collection<RoomEvidenceReference> references) {
        requireThread(server);
        var nativeRefs = references.stream().filter(NativeRoomEvidence::supported).toList();
        if (nativeRefs.isEmpty()) return CompletableFuture.completedFuture(true);
        var key = scope(request);
        return cache(server).prepare(key, nativeRefs, () -> RecordedMemoryAccess.open(server, request)
                .filter(RecordedMemoryAccess.Session.class::isInstance).map(value -> {
                    var session = (RecordedMemoryAccess.Session) value;
                    return new Port() {
                        public CompletableFuture<Boolean> prepare(RoomEvidenceReference ref) {
                            return NativeInterpretationEvidence.KIND.equals(ref.kind())
                                    ?session.prepareNativeInterpretationReference(NativeInterpretationEvidence.decode(ref))
                                    :session.prepareNativeReference(NativeMemoryEvidence.decode(ref));
                        }
                        public boolean current(RoomEvidenceReference ref) {
                            return session.issuedFor(request)&&(NativeInterpretationEvidence.KIND.equals(ref.kind())
                                    ?session.currentNativeInterpretationReference(NativeInterpretationEvidence.decode(ref))
                                    :session.currentNativeReference(NativeMemoryEvidence.decode(ref)));
                        }
                    };
                }), () -> key.equals(scope(request)) && ConversationRooms.INSTANCE.memoryReadCurrent(server, request), server::execute);
    }

    /** Memory-only, same-request check immediately before context use or publication. */
    public static boolean current(MinecraftServer server, Request request, RoomEvidenceReference reference) {
        if (!server.isSameThread() || !supported(reference)) return false;
        try {
            if (!ConversationRooms.INSTANCE.memoryReadCurrent(server, request)) return false;
            var cache = CACHES.get(server);
            return cache != null && cache.current(scope(request), reference);
        } catch (RuntimeException unavailable) { return false; }
    }

    public static void clear(MinecraftServer server) {
        requireThread(server);
        var cache = CACHES.remove(server);
        if (cache != null) cache.clear();
    }
    private static PreparedCache<Scope> cache(MinecraftServer server) {
        return CACHES.computeIfAbsent(server, ignored -> new PreparedCache<>());
    }
    private static boolean supported(RoomEvidenceReference reference){
        return reference!=null&&(NativeMemoryEvidence.KIND.equals(reference.kind())||NativeInterpretationEvidence.KIND.equals(reference.kind()));
    }
    private static void decodeSupported(RoomEvidenceReference reference){
        if(reference!=null&&NativeInterpretationEvidence.KIND.equals(reference.kind()))NativeInterpretationEvidence.decode(reference);
        else NativeMemoryEvidence.decode(reference);
    }
    private static Scope scope(Request request) {
        return new Scope(request.roomId(), request.revision(), request.turnId(), request.playerId(),
                request.speakerGodId().toString(), request.publicRoom(),
                request.godIds().stream().map(Object::toString).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                Set.copyOf(request.audiencePlayerIds()), MemoryFoundationSettings.mode().name());
    }
    private static void requireThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("NATIVE_EVIDENCE_REQUIRES_GAME_THREAD");
    }

    interface Port {
        CompletableFuture<Boolean> prepare(RoomEvidenceReference reference);
        boolean current(RoomEvidenceReference reference);
    }
    /** Injectable orchestration only. Production scope keys and ports are exclusively game-created above. */
    static final class PreparedCache<K> {
        private final LinkedHashMap<K, Map<RoomEvidenceReference, BooleanSupplier>> guards = new LinkedHashMap<>();
        private final Map<K, CompletableFuture<Boolean>> pending = new HashMap<>();
        private long epoch;

        boolean register(K scope, Map<RoomEvidenceReference, BooleanSupplier> batch) {
            if (batch.isEmpty() || batch.size() > 64 || !batch.values().stream().allMatch(PreparedCache::valid)) return false;
            return install(scope, batch);
        }
        private boolean install(K scope, Map<RoomEvidenceReference, BooleanSupplier> batch) {
            var entries = new LinkedHashMap<>(guards.getOrDefault(scope, Map.of()));
            entries.putAll(batch);
            if (entries.size() > 64) return false;
            guards.put(scope, Map.copyOf(entries));
            while (guards.size() > 64) guards.remove(guards.keySet().iterator().next());
            return true;
        }
        boolean current(K scope, RoomEvidenceReference ref) {
            var entries = guards.get(scope);
            return entries != null && valid(entries.get(ref));
        }
        CompletableFuture<Boolean> prepare(K scope, Collection<RoomEvidenceReference> refs,
                Supplier<Optional<Port>> open, BooleanSupplier authority, Consumer<Runnable> dispatch) {
            final List<RoomEvidenceReference> requested;
            try {
                if (refs.size() > 64) return CompletableFuture.completedFuture(false);
                requested = refs.stream().distinct().toList();
                requested.forEach(NativeRoomEvidence::decodeSupported);
                if (!valid(authority)) return CompletableFuture.completedFuture(false);
                if (requested.isEmpty()) return CompletableFuture.completedFuture(true);
                if (pending.containsKey(scope) || pending.size() >= 16) return CompletableFuture.completedFuture(false);
            } catch (RuntimeException invalid) { return CompletableFuture.completedFuture(false); }
            final Port port;
            try { port = open.get().orElse(null); }
            catch (RuntimeException unavailable) { return CompletableFuture.completedFuture(false); }
            if (port == null) return CompletableFuture.completedFuture(false);
            var result = new CompletableFuture<Boolean>();
            long expected = epoch;
            pending.put(scope, result);
            result.whenComplete((value, failure) -> {
                try { dispatch.accept(() -> pending.remove(scope, result)); }
                catch (RuntimeException stopped) { /* No off-thread cache mutation. clear() releases a stopped runtime. */ }
            });
            prepareNext(scope, requested, 0, port, authority, dispatch, expected, result);
            return result.completeOnTimeout(false, 10, TimeUnit.SECONDS);
        }
        private void prepareNext(K scope, List<RoomEvidenceReference> refs, int index, Port port,
                BooleanSupplier authority, Consumer<Runnable> dispatch, long expected, CompletableFuture<Boolean> result) {
            if (result.isDone()) return;
            if (epoch != expected || !valid(authority)) { result.complete(false); return; }
            if (index == refs.size()) {
                var batch = new LinkedHashMap<RoomEvidenceReference, BooleanSupplier>();
                for (var ref : refs) batch.put(ref, () -> epoch == expected && valid(authority) && port.current(ref));
                if (!batch.values().stream().allMatch(PreparedCache::valid) || result.isDone()) { result.complete(false); return; }
                var committed = new LinkedHashMap<RoomEvidenceReference, BooleanSupplier>();
                batch.forEach((ref, guard) -> committed.put(ref, () -> Boolean.TRUE.equals(result.getNow(false)) && valid(guard)));
                // A timeout/cancel racing this insertion leaves only dormant guards, never publication authority.
                result.complete(install(scope, committed));
                return;
            }
            try {
                port.prepare(refs.get(index)).whenComplete((ready, failure) -> {
                    try { dispatch.accept(() -> {
                        if (result.isDone()) return;
                        if (failure != null || !Boolean.TRUE.equals(ready)) { result.complete(false); return; }
                        prepareNext(scope, refs, index + 1, port, authority, dispatch, expected, result);
                    }); } catch (RuntimeException unavailable) { result.complete(false); }
                });
            } catch (RuntimeException unavailable) { result.complete(false); }
        }
        void clear() {
            epoch++;
            var incomplete = List.copyOf(pending.values());
            pending.clear(); guards.clear();
            incomplete.forEach(result -> result.complete(false));
        }
        private static boolean valid(BooleanSupplier guard) {
            try { return guard != null && guard.getAsBoolean(); }
            catch (RuntimeException unavailable) { return false; }
        }
    }
}
