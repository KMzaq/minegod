package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.recording.api.MemoryReadSession;
import com.sande.mythictrpg.recording.server.RecordedMemoryAccess;
import net.minecraft.server.MinecraftServer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** Explicit SHADOW only: new archive results never enter a prompt, memory writer, proposal or gameplay path. */
public final class RecordedMemoryShadow {
    private static final int MAX_PAGES = 3, MAX_MESSAGES = 4, MAX_BYTES = 4096;
    private static final AtomicLong completed = new AtomicLong(), unavailable = new AtomicLong(), matchedHistory = new AtomicLong();
    private static final AtomicLong legacySelected = new AtomicLong(), legacyMatches = new AtomicLong();
    private RecordedMemoryShadow() { }
    public static void compare(MinecraftServer server, Request request) {
        compare(() -> RecordedMemoryAccess.open(server, request), request, task -> server.execute(task));
        RecordedEmbeddingService.compare(server, request);
    }
    /** Observe the already-computed legacy selection; never run a second legacy query or delay generation. */
    public static void compare(MinecraftServer server, Request request, Set<UUID> selectedByExistingReader) {
        compare(() -> RecordedMemoryAccess.open(server, request), request, task -> server.execute(task), selectedByExistingReader);
        RecordedEmbeddingService.compare(server, request);
    }
    /** The already-computed room plan carries follow-up focus without running a second legacy search. */
    public static void compare(MinecraftServer server, Request request, RoomMemoryBridge.Recall recall) {
        compare(() -> RecordedMemoryAccess.open(server, request), request, task -> server.execute(task),
                recall.sourceMessageIds(), recall.query());
        RecordedEmbeddingService.compare(server, request, recall.query());
    }
    /** Injectable optional boundary for failure tests; this does not mint a read session or expose its content. */
    static void compare(Supplier<Optional<MemoryReadSession>> opening, Request request, Consumer<Runnable> dispatch) {
        compare(opening, request, dispatch, Set.of());
    }
    static void compare(Supplier<Optional<MemoryReadSession>> opening, Request request, Consumer<Runnable> dispatch, Set<UUID> selectedByExistingReader) {
        compare(opening, request, dispatch, selectedByExistingReader, Optional.empty());
    }
    static void compare(Supplier<Optional<MemoryReadSession>> opening, Request request, Consumer<Runnable> dispatch,
                        Set<UUID> selectedByExistingReader, Optional<RecallQuery> carried) {
        isolated(() -> {
            var prepared = RecordedRecallQuery.prepare(request, carried);
            if (prepared.isEmpty()) return;
            var legacy = Set.copyOf(selectedByExistingReader);
            var session = opening.get();
            if (session.isEmpty()) return;
            var query = prepared.orElseThrow().query();
            var history = request.history().stream().map(line -> line.messageId()).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            new Comparison(session.get(), query, dispatch, history, legacy).request(Optional.empty());
        });
    }
    /** Sequential, ephemeral diagnostic-only pages. No second legacy search and no model work. */
    private static final class Comparison {
        private final MemoryReadSession session;
        private final MemoryReadSession.Query query;
        private final Consumer<Runnable> dispatch;
        private final Set<UUID> history, legacy;
        private final List<MemoryReadSession.Page> pages = new ArrayList<>();
        private final Set<UUID> found = new HashSet<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private int usedBytes;
        Comparison(MemoryReadSession session, MemoryReadSession.Query query, Consumer<Runnable> dispatch, Set<UUID> history, Set<UUID> legacy) {
            this.session = session; this.query = query; this.dispatch = dispatch; this.history = history; this.legacy = legacy;
        }
        private void request(Optional<MemoryReadSession.Cursor> cursor) {
            guard(() -> {
                if (!current()) { fail(); return; }
                int rows = MAX_MESSAGES - found.size(), bytes = MAX_BYTES - usedBytes;
                if (pages.size() >= MAX_PAGES || rows <= 0 || bytes < 256) { finish(); return; }
                session.query(query, cursor, new MemoryReadSession.Budget(rows, bytes)).whenComplete((page, failure) ->
                        guard(() -> dispatch.accept(() -> guard(() -> accept(page, failure, rows, bytes)))));
            });
        }
        private void accept(MemoryReadSession.Page page, Throwable failure, int rows, int bytes) {
            if (failure != null || page == null || !Set.of(MemoryReadSession.Status.FOUND, MemoryReadSession.Status.EMPTY,
                    MemoryReadSession.Status.PARTIAL).contains(page.status()) || !session.current(page)
                    || page.entries().size() > rows) { fail(); return; }
            int received = 0;
            for (var entry : page.entries()) {
                Objects.requireNonNull(entry.messageId());
                received = Math.addExact(received, entry.text().getBytes(StandardCharsets.UTF_8).length);
                if (received > bytes) { fail(); return; }
            }
            pages.add(page); usedBytes += received; // Count duplicate text too: total returned content stays <=4KiB.
            page.entries().forEach(entry -> found.add(entry.messageId()));
            if (page.next().isEmpty()) finish(); else request(page.next());
        }
        private boolean current() {
            // A newer valid page never launders a first page whose original proof was withdrawn.
            return pages.stream().allMatch(session::current);
        }
        private void finish() {
            if (!current()) { fail(); return; }
            long historyCount = found.stream().filter(history::contains).count();
            long legacyCount = found.stream().filter(legacy::contains).count();
            // Revalidate the complete page set immediately before committing aggregate diagnostics.
            if (!current()) { fail(); return; }
            if (!finished.compareAndSet(false, true)) return;
            completed.incrementAndGet(); matchedHistory.addAndGet(historyCount);
            legacySelected.addAndGet(legacy.size()); legacyMatches.addAndGet(legacyCount);
            // The raw comparison has finished its sequential reads. Optional typed interpretations
            // use only these exact issued pages, never an arbitrary source ID or another room.
            RecordedInterpretationShadow.compare(session, pages, dispatch);
        }
        private void guard(Runnable action) {
            if (finished.get()) return;
            try { action.run(); } catch (RuntimeException ignored) { fail(); }
        }
        private void fail() { if (finished.compareAndSet(false, true)) unavailable.incrementAndGet(); }
    }
    private static void isolated(Runnable action) {
        try { action.run(); } catch (RuntimeException ignored) { unavailable.incrementAndGet(); }
    }
    /** Aggregate internal diagnostics only; no body, God, subject, room or secret-existence logging. */
    public static Map<String,Long> diagnostics() { return Map.of("completed", completed.get(), "unavailable", unavailable.get(),
            "recentHistoryMatches", matchedHistory.get(), "legacySelected", legacySelected.get(), "legacyMatches", legacyMatches.get()); }
}
