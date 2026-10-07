package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.server.RecordedMemoryAccess;
import net.minecraft.server.MinecraftServer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Compare existing selected observations with bounded native recent events, never speech or game authority. */
public final class RecordedObservationShadow {
    private static final Gson JSON = new Gson();
    private static final int MAX_PAGES = 3, MAX_EVENTS = 4, MAX_BYTES = 8192;
    private static final MemoryReadSession.Query RECENT = new MemoryReadSession.Query("", Optional.empty(), Optional.empty());
    private static final AtomicLong completed = new AtomicLong(), unavailable = new AtomicLong();
    private static final AtomicLong legacySelected = new AtomicLong(), legacyMatches = new AtomicLong();
    private RecordedObservationShadow() { }

    public static void compare(MinecraftServer server, Request request, Set<UUID> selectedByExistingReader) {
        // Existing Watch proofs do not grant public broadcast or disclosure to a second God.
        if (!server.isSameThread() || request.publicRoom() || request.godIds().size() != 1) return;
        compare(() -> RecordedMemoryAccess.open(server, request), selectedByExistingReader, server::execute);
    }
    static void compare(Supplier<Optional<MemoryReadSession>> opening, Set<UUID> selected, Consumer<Runnable> dispatch) {
        try {
            var legacy = Set.copyOf(selected);
            var session = opening.get();
            if (session.isPresent()) new Comparison(session.get(), legacy, dispatch).request(Optional.empty());
        } catch (RuntimeException failure) { unavailable.incrementAndGet(); }
    }
    private static final class Comparison {
        final MemoryReadSession session;
        final Set<UUID> legacy, found = new HashSet<>();
        final Consumer<Runnable> dispatch;
        final List<ObservationReadRecords.Page> pages = new ArrayList<>();
        final AtomicBoolean finished = new AtomicBoolean();
        int bytes;
        Comparison(MemoryReadSession session, Set<UUID> legacy, Consumer<Runnable> dispatch) {
            this.session = session; this.legacy = legacy; this.dispatch = dispatch;
        }
        boolean current() { return pages.stream().allMatch(session::current); }
        void request(Optional<ObservationReadRecords.Cursor> cursor) {
            guard(() -> {
                if (!current()) { fail(); return; }
                int remaining = MAX_BYTES - bytes, rows = MAX_EVENTS - found.size();
                if (pages.size() >= MAX_PAGES || rows <= 0 || remaining < 256) { finish(); return; }
                // Recent means the reader's archive-source ordering, not an invented UTC action timestamp.
                session.observations(RECENT, cursor, new MemoryReadSession.Budget(rows, remaining))
                        .whenComplete((page, failure) -> guard(() -> dispatch.accept(() -> guard(() -> accept(page, failure, rows, remaining)))));
            });
        }
        void accept(ObservationReadRecords.Page page, Throwable failure, int rows, int remaining) {
            if (failure != null || page == null || !Set.of(MemoryReadSession.Status.FOUND,
                    MemoryReadSession.Status.PARTIAL, MemoryReadSession.Status.EMPTY).contains(page.status())
                    || page.entries().size() > rows || !session.current(page) || !current()) { fail(); return; }
            int received = 0;
            for (var entry : page.entries()) {
                received = Math.addExact(received, wireBytes(entry));
                if (received > remaining) { fail(); return; }
            }
            pages.add(page); bytes += received;
            page.entries().forEach(entry -> found.add(entry.experience().observationId()));
            if (page.next().isEmpty()) finish(); else request(page.next());
        }
        void finish() {
            if (!current()) { fail(); return; }
            long matches = found.stream().filter(legacy::contains).count();
            if (!current()) { fail(); return; }
            if (!finished.compareAndSet(false, true)) return;
            completed.incrementAndGet(); legacySelected.addAndGet(legacy.size()); legacyMatches.addAndGet(matches);
        }
        void guard(Runnable work) {
            if (finished.get()) return;
            try { work.run(); } catch (RuntimeException failure) { fail(); }
        }
        void fail() { if (finished.compareAndSet(false, true)) unavailable.incrementAndGet(); }
    }
    static int wireBytes(ObservationReadRecords.Entry entry) {
        // These typed records contain UUID/string/enums, not Instant reflection or raw administrative payload.
        return JSON.toJson(entry).getBytes(StandardCharsets.UTF_8).length;
    }
    /** Only aggregate counters; source/body/subject/God identifiers never leave the comparison. */
    public static Map<String,Long> diagnostics() {
        return Map.of("completed", completed.get(), "unavailable", unavailable.get(),
                "legacySelected", legacySelected.get(), "legacyMatches", legacyMatches.get());
    }
}
