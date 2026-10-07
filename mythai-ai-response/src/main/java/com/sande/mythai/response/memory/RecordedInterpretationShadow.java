package com.sande.mythai.response.memory;

import com.sande.mythictrpg.recording.api.InterpretationReadRecords;
import com.sande.mythictrpg.recording.api.MemoryReadSession;
import com.sande.mythictrpg.recording.api.SemanticReadRecords;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Diagnostic-only native interpretations of authorized typed hits. Never a prompt or gameplay input. */
final class RecordedInterpretationShadow {
    private static final int MAX_CALLS = 3, MAX_CARDS = 4, MAX_BYTES = 4096;
    private static final AtomicLong completed = new AtomicLong(), unavailable = new AtomicLong();
    private static final AtomicLong cards = new AtomicLong(), linkedCards = new AtomicLong();
    private RecordedInterpretationShadow() { }
    @FunctionalInterface private interface Reader {
        CompletableFuture<InterpretationReadRecords.Page> read(Optional<InterpretationReadRecords.Cursor> cursor, MemoryReadSession.Budget budget);
    }
    private record Seed(boolean empty, BooleanSupplier current, Reader reader) { }

    static void compare(MemoryReadSession session, List<MemoryReadSession.Page> rawPages, Consumer<Runnable> dispatch) {
        try {
            compareSeeds(session, List.copyOf(rawPages).stream().map(p -> new Seed(p.entries().isEmpty(), () -> session.current(p),
                    (cursor, budget) -> session.interpretations(p, cursor, budget))).toList(), dispatch);
        } catch (RuntimeException ignored) { unavailable.incrementAndGet(); }
    }
    /** Semantic hits keep their own issued-page identity and gate; never manufacture raw seed pages. */
    static void compareSemantic(MemoryReadSession session, List<SemanticReadRecords.Page> semanticPages, Consumer<Runnable> dispatch) {
        try {
            compareSeeds(session, List.copyOf(semanticPages).stream().map(p -> new Seed(p.entries().isEmpty(), () -> session.current(p),
                    (cursor, budget) -> session.interpretations(p, cursor, budget))).toList(), dispatch);
        } catch (RuntimeException ignored) { unavailable.incrementAndGet(); }
    }
    private static void compareSeeds(MemoryReadSession session, List<Seed> issued, Consumer<Runnable> dispatch) {
        if (issued.size() > 3) throw new IllegalArgumentException("SHADOW_SEED_LIMIT");
        var seeds = issued.stream().filter(p -> !p.empty()).toList();
        if (!seeds.isEmpty()) new Comparison(session, issued, seeds, dispatch).request();
    }

    private static final class Comparison {
        final MemoryReadSession session;
        final List<Seed> issued, seeds;
        final Consumer<Runnable> dispatch;
        final List<InterpretationReadRecords.Page> pages = new ArrayList<>();
        final Map<UUID,Boolean> found = new HashMap<>();
        final AtomicBoolean finished = new AtomicBoolean();
        final Map<Integer,InterpretationReadRecords.Cursor> cursors = new HashMap<>();
        int calls, bytes, seed;
        Comparison(MemoryReadSession session, List<Seed> issued, List<Seed> seeds,
                   Consumer<Runnable> dispatch) {
            this.session = session; this.issued = issued; this.seeds = seeds; this.dispatch = dispatch;
        }
        void request() {
            guard(() -> {
                if (!current()) { fail(); return; }
                if (calls >= MAX_CALLS || found.size() >= MAX_CARDS || MAX_BYTES - bytes < 256) { finish(); return; }
                // Give each issued page one turn before following a derived cursor on a prior seed.
                int selected;
                if (seed < seeds.size()) selected = seed++;
                else if (!cursors.isEmpty()) selected = Collections.min(cursors.keySet());
                else { finish(); return; }
                var cursor = Optional.ofNullable(cursors.remove(selected));
                int rows = MAX_CARDS - found.size(), remaining = MAX_BYTES - bytes;
                calls++;
                seeds.get(selected).reader().read(cursor, new MemoryReadSession.Budget(rows, remaining))
                        .whenComplete((page, failure) -> guard(() -> dispatch.accept(() -> guard(() -> {
                            if (failure != null || page == null || !Set.of(MemoryReadSession.Status.FOUND,
                                    MemoryReadSession.Status.EMPTY, MemoryReadSession.Status.PARTIAL).contains(page.status())
                                    || !current() || !session.current(page) || page.entries().size() > rows) { fail(); return; }
                            int received = 0;
                            for (var card : page.entries()) {
                                Objects.requireNonNull(card.memoryId());
                                for (var quote : card.quotes()) received = Math.addExact(received,
                                        quote.text().getBytes(StandardCharsets.UTF_8).length);
                                if (received > remaining) { fail(); return; }
                            }
                            bytes += received; pages.add(page);
                            page.entries().forEach(card -> found.merge(card.memoryId(), !card.links().isEmpty(), Boolean::logicalOr));
                            page.next().ifPresent(next -> cursors.put(selected, next));
                            request();
                        }))));
            });
        }
        boolean current() { return issued.stream().allMatch(seed -> seed.current().getAsBoolean()) && pages.stream().allMatch(session::current); }
        void finish() {
            if (!current()) { fail(); return; }
            long linked = found.values().stream().filter(Boolean::booleanValue).count();
            if (!current()) { fail(); return; }
            if (!finished.compareAndSet(false, true)) return;
            completed.incrementAndGet(); cards.addAndGet(found.size()); linkedCards.addAndGet(linked);
        }
        void guard(Runnable action) { if (!finished.get()) try { action.run(); } catch (RuntimeException ignored) { fail(); } }
        void fail() { if (finished.compareAndSet(false, true)) unavailable.incrementAndGet(); }
    }

    /** Counts only: no source IDs, observer, player, quote text or private-existence details. */
    static Map<String,Long> diagnostics() { return Map.of("completed", completed.get(), "unavailable", unavailable.get(),
            "cards", cards.get(), "linkedCards", linkedCards.get()); }
}
