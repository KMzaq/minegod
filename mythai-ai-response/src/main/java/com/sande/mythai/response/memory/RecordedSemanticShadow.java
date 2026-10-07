package com.sande.mythai.response.memory;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.EmbeddingRecords.QueryVector;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Native-only comparison. Similarity never becomes a fact, a prompt instruction or an action. */
final class RecordedSemanticShadow {
    // A 1600-character Korean prefix can exceed 4KiB. Never trim it just to fit an English-sized budget.
    private static final int MAX_BYTES = 16384;
    private static final AtomicLong completed = new AtomicLong(), unavailable = new AtomicLong(), matches = new AtomicLong();
    private RecordedSemanticShadow() { }
    static void compare(MemoryReadSession session, MemoryReadSession.Query query, QueryVector vector,
            double minimumSimilarity, Consumer<Runnable> dispatch) {
        new Comparison(session, query, vector, minimumSimilarity, dispatch).request(Optional.empty());
    }
    private static final class Comparison {
        final MemoryReadSession session;
        final MemoryReadSession.Query query;
        final QueryVector vector;
        final double minimum;
        final Consumer<Runnable> dispatch;
        final List<SemanticReadRecords.Page> pages = new ArrayList<>();
        final Set<UUID> selected = new HashSet<>();
        final AtomicBoolean finished = new AtomicBoolean();
        int bytes;
        Comparison(MemoryReadSession session, MemoryReadSession.Query query, QueryVector vector, double minimum, Consumer<Runnable> dispatch) {
            this.session = session; this.query = query; this.vector = vector; this.minimum = minimum; this.dispatch = dispatch;
        }
        boolean current() { return pages.stream().allMatch(session::current); }
        void request(Optional<SemanticReadRecords.Cursor> cursor) {
            guard(() -> {
                if (!current()) { fail(); return; }
                int left = MAX_BYTES - bytes;
                if (pages.size() >= 3 || left < 256 || selected.size() >= 4) { finish(); return; }
                session.semantic(query, vector, cursor, new MemoryReadSession.Budget(4 - selected.size(), left))
                        .whenComplete((page, failure) -> guard(() -> dispatch.accept(() -> guard(() -> accept(page, failure, left)))));
            });
        }
        void accept(SemanticReadRecords.Page page, Throwable failure, int left) {
            if (failure != null || page == null || !Set.of(MemoryReadSession.Status.PARTIAL, MemoryReadSession.Status.FOUND,
                    MemoryReadSession.Status.EMPTY).contains(page.status()) || !session.current(page)
                    || page.entries().size() > 4 - selected.size()) { fail(); return; }
            int received = 0;
            for (var entry : page.entries()) {
                received = Math.addExact(received, entry.text().getBytes(StandardCharsets.UTF_8).length);
                if (!Double.isFinite(entry.similarity()) || received > left) { fail(); return; }
            }
            pages.add(page); bytes += received;
            for (var entry : page.entries()) if (entry.similarity() >= minimum) selected.add(entry.messageId());
            if (page.next().isEmpty()) finish(); else request(page.next());
        }
        void finish() {
            if (!current()) { fail(); return; }
            long count = selected.size();
            if (!current()) { fail(); return; }
            if (finished.compareAndSet(false, true)) {
                completed.incrementAndGet(); matches.addAndGet(count);
                // Follow independently validated correction/cancellation links, not just a similar old promise.
                // Still diagnostics only; a prefix match does not certify the whole utterance or a game fact.
                RecordedInterpretationShadow.compareSemantic(session, pages, dispatch);
            }
        }
        void guard(Runnable action) { if (!finished.get()) try { action.run(); } catch (RuntimeException failed) { fail(); } }
        void fail() { if (finished.compareAndSet(false, true)) unavailable.incrementAndGet(); }
    }
    static void failed() { unavailable.incrementAndGet(); }
    static Map<String,Long> diagnostics() { return Map.of("completed", completed.get(), "unavailable", unavailable.get(), "matches", matches.get()); }
}
