package com.sande.mythai.response.memory;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.InterpretationReadRecords.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** No sockets/model: only issued-page diagnostics, cancellation and bounded transport consumption. */
final class RecordedInterpretationShadowTest {
    private static int checks;
    private static final ActorRef PLAYER = new ActorRef(ActorKind.PLAYER, UUID.randomUUID().toString());
    private static final Instant AT = Instant.parse("2026-09-30T12:00:00Z");
    static int run() {
        bounded(); invalidation(); lastBoundaryAndSessions(); failures(); defaultUnavailable(); semanticSeeds();
        return checks;
    }
    private static class Session implements MemoryReadSession {
        final List<CompletableFuture<InterpretationReadRecords.Page>> answers;
        final List<MemoryReadSession.Page> calledSeeds = new ArrayList<>();
        final List<SemanticReadRecords.Page> calledSemanticSeeds = new ArrayList<>();
        final List<Optional<InterpretationReadRecords.Cursor>> calledCursors = new ArrayList<>();
        final List<Budget> budgets = new ArrayList<>();
        final Set<Object> revoked = Collections.newSetFromMap(new IdentityHashMap<>());
        Session(List<CompletableFuture<InterpretationReadRecords.Page>> answers) { this.answers = answers; }
        @Override public CompletableFuture<MemoryReadSession.Page> query(Query q, Optional<MemoryReadSession.Cursor> c, Budget b) {
            throw new AssertionError("Derived diagnostics must not repeat raw or legacy search");
        }
        @Override public CompletableFuture<InterpretationReadRecords.Page> interpretations(MemoryReadSession.Page seed,
                Optional<InterpretationReadRecords.Cursor> cursor, Budget budget) {
            int call = budgets.size(); check(call < 3 && call < answers.size(), "bounded derived queries");
            calledSeeds.add(seed); calledCursors.add(cursor); budgets.add(budget); return answers.get(call);
        }
        @Override public boolean current(MemoryReadSession.Page page) { return !revoked.contains(page); }
        @Override public boolean current(InterpretationReadRecords.Page page) { return !revoked.contains(page); }
        @Override public boolean current(SemanticReadRecords.Page page) { return !revoked.contains(page); }
        @Override public CompletableFuture<InterpretationReadRecords.Page> interpretations(SemanticReadRecords.Page seed,
                Optional<InterpretationReadRecords.Cursor> cursor, Budget budget) {
            int call = budgets.size(); check(call < 3 && call < answers.size(), "bounded semantic-seeded interpretation queries");
            calledSemanticSeeds.add(seed); calledCursors.add(cursor); budgets.add(budget); return answers.get(call);
        }
    }
    private static MemoryReadSession.Page raw() {
        return new MemoryReadSession.Page(MemoryReadSession.Status.PARTIAL, List.of(new MemoryReadSession.Entry(
                UUID.randomUUID(), PLAYER, AT, "actual raw seed", false)), Optional.empty());
    }
    private static InterpretationReadRecords.Entry entry(UUID id, String text, boolean link) {
        UUID old = UUID.randomUUID(), recent = UUID.randomUUID();
        var quotes = new ArrayList<Quote>(); quotes.add(new Quote("e0", old, PLAYER, AT, text));
        var inputs = new ArrayList<Coverage>(); inputs.add(new Coverage("e0", old, text.length(), text.length()));
        var links = new ArrayList<Link>();
        if (link) {
            quotes.add(new Quote("e1", recent, PLAYER, AT.plusSeconds(1), "취소할게"));
            inputs.add(new Coverage("e1", recent, 4, 4));
            links.add(new Link("e1", "e0", ProjectionRecords.Relation.CANCELS));
        }
        return new InterpretationReadRecords.Entry(id, ProjectionRecords.Layer.EVENT,
                link ? ProjectionRecords.ClaimKind.CORRECTION_OR_EXPLANATION : ProjectionRecords.ClaimKind.SPEAKER_CLAIM,
                "fixture-v1", quotes, links, inputs);
    }
    private static InterpretationReadRecords.Page page(List<InterpretationReadRecords.Entry> entries, boolean more) {
        return new InterpretationReadRecords.Page(MemoryReadSession.Status.PARTIAL, entries,
                more ? Optional.of(InterpretationReadRecords.Cursor.unregistered()) : Optional.empty());
    }
    private static Session session(InterpretationReadRecords.Page... pages) {
        return new Session(Arrays.stream(pages).map(CompletableFuture::completedFuture).toList());
    }
    private static void bounded() {
        var a = raw(); var b = raw(); var first = entry(UUID.randomUUID(), "약속했어", true);
        var second = entry(UUID.randomUUID(), "다른 대화", false);
        var p1 = page(List.of(first), true); var p2 = page(List.of(second), true); var p3 = page(List.of(first), true);
        var read = session(p1, p2, p3);
        var before = RecordedInterpretationShadow.diagnostics();
        RecordedInterpretationShadow.compare(read, List.of(a, b), Runnable::run);
        var after = RecordedInterpretationShadow.diagnostics();
        check(read.calledSeeds.equals(List.of(a, b, a)), "each issued raw page gets first lookup before a continuation");
        check(read.calledCursors.get(0).isEmpty() && read.calledCursors.get(1).isEmpty()
                && read.calledCursors.get(2).equals(p1.next()), "continuation belongs to its exact seed");
        check(after.get("completed") == before.get("completed") + 1 && after.get("cards") == before.get("cards") + 2
                && after.get("linkedCards") == before.get("linkedCards") + 1, "unique cards/links counted, not duplicated sources or facts");
        check(read.budgets.get(1).rows() == 3 && read.budgets.get(2).rows() == 2
                && read.budgets.get(2).utf8Bytes() < read.budgets.get(1).utf8Bytes(), "all queries share card and UTF-8 text budgets");
        check(after.keySet().equals(Set.of("completed", "unavailable", "cards", "linkedCards")), "aggregate counters expose no quotes/IDs/private subject");
        var four = session(page(List.of(entry(UUID.randomUUID(), "a", false), entry(UUID.randomUUID(), "b", false),
                entry(UUID.randomUUID(), "c", false), entry(UUID.randomUUID(), "d", false)), true));
        RecordedInterpretationShadow.compare(four, List.of(raw()), Runnable::run);
        check(four.budgets.size() == 1, "four cards stop pagination");
        var bytes = session(page(List.of(entry(UUID.randomUUID(), "가".repeat(1300), false)), true));
        RecordedInterpretationShadow.compare(bytes, List.of(raw()), Runnable::run);
        check(bytes.budgets.size() == 1, "remaining fewer than 256 bytes prevents next query");
    }
    private static void invalidation() {
        for (boolean revokeRaw : List.of(false, true)) {
            var seed = raw(); var p1 = page(List.of(entry(UUID.randomUUID(), "원래 약속", true)), true);
            var later = new CompletableFuture<InterpretationReadRecords.Page>();
            var read = new Session(List.of(CompletableFuture.completedFuture(p1), later));
            var before = RecordedInterpretationShadow.diagnostics();
            RecordedInterpretationShadow.compare(read, List.of(seed), Runnable::run);
            check(read.budgets.size() == 2 && RecordedInterpretationShadow.diagnostics().get("completed").equals(before.get("completed")),
                    "no premature diagnostic success while next page pending");
            read.revoked.add(revokeRaw ? seed : p1);
            later.complete(page(List.of(entry(UUID.randomUUID(), "new valid result", false)), false));
            var after = RecordedInterpretationShadow.diagnostics();
            check(after.get("unavailable") == before.get("unavailable") + 1 && after.get("cards").equals(before.get("cards"))
                    && after.get("completed").equals(before.get("completed")), "revoked earlier seed or interpretation discards complete diagnostic");
        }
    }
    private static void failures() {
        var seed = raw(); var before = RecordedInterpretationShadow.diagnostics();
        var oversize = session(page(List.of(entry(UUID.randomUUID(), "가".repeat(1500), false)), false));
        RecordedInterpretationShadow.compare(oversize, List.of(seed), Runnable::run);
        var failed = new Session(List.of(CompletableFuture.failedFuture(new IllegalStateException("private text"))));
        RecordedInterpretationShadow.compare(failed, List.of(seed), Runnable::run);
        RecordedInterpretationShadow.compare(session(page(List.of(), false)), List.of(seed), r -> { throw new IllegalStateException("stopped"); });
        var denied = new InterpretationReadRecords.Page(MemoryReadSession.Status.UNAVAILABLE, List.of(), Optional.empty());
        RecordedInterpretationShadow.compare(session(denied), List.of(seed), Runnable::run);
        var after = RecordedInterpretationShadow.diagnostics();
        check(after.get("unavailable") == before.get("unavailable") + 4 && after.get("cards").equals(before.get("cards")),
                "oversized body, failed future, stopped dispatcher, and unavailable status isolated without data output");
        var empty = session();
        RecordedInterpretationShadow.compare(empty, List.of(new MemoryReadSession.Page(MemoryReadSession.Status.PARTIAL, List.of(), Optional.empty())), Runnable::run);
        check(empty.budgets.isEmpty(), "no derived lookup without authorized raw seed hits");
    }
    private static void lastBoundaryAndSessions() {
        var page = page(List.of(entry(UUID.randomUUID(), "마지막 근거", false)), false);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var last = new Session(List.of(CompletableFuture.completedFuture(page))) {
            @Override public boolean current(InterpretationReadRecords.Page p) { return calls.incrementAndGet() < 4; }
        };
        var before = RecordedInterpretationShadow.diagnostics();
        RecordedInterpretationShadow.compare(last, List.of(raw()), Runnable::run);
        var after = RecordedInterpretationShadow.diagnostics();
        check(calls.get() == 4 && after.get("unavailable") == before.get("unavailable") + 1
                && after.get("completed").equals(before.get("completed")), "final pre-counter recheck can still discard all results");

        var rawA = raw(); var rawB = raw();
        var pendingA = new CompletableFuture<InterpretationReadRecords.Page>();
        var pendingB = new CompletableFuture<InterpretationReadRecords.Page>();
        var a = new Session(List.of(pendingA)); var b = new Session(List.of(pendingB));
        before = RecordedInterpretationShadow.diagnostics();
        RecordedInterpretationShadow.compare(a, List.of(rawA), Runnable::run);
        RecordedInterpretationShadow.compare(b, List.of(rawB), Runnable::run);
        a.revoked.add(rawA); pendingB.complete(page); pendingA.complete(page);
        after = RecordedInterpretationShadow.diagnostics();
        check(after.get("completed") == before.get("completed") + 1 && after.get("unavailable") == before.get("unavailable") + 1
                && after.get("cards") == before.get("cards") + 1, "concurrent comparisons isolate pages, cursors, revocation and result state");
        check(a.calledSeeds.equals(List.of(rawA)) && b.calledSeeds.equals(List.of(rawB)), "session A never supplies its seed page to session B");
    }
    private static void defaultUnavailable() {
        var legacy = new MemoryReadSession() {
            public CompletableFuture<MemoryReadSession.Page> query(Query q, Optional<MemoryReadSession.Cursor> c, Budget b) { throw new AssertionError(); }
            public boolean current(MemoryReadSession.Page p) { return true; }
        };
        var before = RecordedInterpretationShadow.diagnostics();
        RecordedInterpretationShadow.compare(legacy, List.of(raw()), Runnable::run);
        check(RecordedInterpretationShadow.diagnostics().get("unavailable") == before.get("unavailable") + 1,
                "older read ports default to unavailable, not inferred authority");
    }
    private static SemanticReadRecords.Page semantic() {
        return new SemanticReadRecords.Page(MemoryReadSession.Status.PARTIAL, List.of(new SemanticReadRecords.Entry(
                UUID.randomUUID(), PLAYER, AT, "약속한 말", .9, 5, 10)), Optional.empty());
    }
    private static void semanticSeeds() {
        var a = semantic(); var b = semantic();
        var linked = entry(UUID.randomUUID(), "약속은 취소할게", true);
        var first = page(List.of(linked), true); var second = page(List.of(), false); var third = page(List.of(linked), false);
        var read = session(first, second, third); var before = RecordedInterpretationShadow.diagnostics();
        RecordedInterpretationShadow.compareSemantic(read, List.of(a, b), Runnable::run);
        var after = RecordedInterpretationShadow.diagnostics();
        check(read.calledSeeds.isEmpty() && read.calledSemanticSeeds.equals(List.of(a, b, a)), "semantic seed retains original typed identity, never turns into raw page");
        check(read.calledCursors.get(2).equals(first.next()), "semantic continuation keeps its seed binding");
        check(after.get("linkedCards") == before.get("linkedCards") + 1 && after.get("completed") == before.get("completed") + 1,
                "semantic-seeded cancellation remains a deduplicated candidate link");
        var pending = new CompletableFuture<InterpretationReadRecords.Page>();
        var revoked = new Session(List.of(pending));
        before = RecordedInterpretationShadow.diagnostics();
        RecordedInterpretationShadow.compareSemantic(revoked, List.of(a), Runnable::run);
        revoked.revoked.add(a); pending.complete(first);
        after = RecordedInterpretationShadow.diagnostics();
        check(after.get("unavailable") == before.get("unavailable") + 1 && after.get("cards").equals(before.get("cards")),
                "semantic gate/page revocation discards in-flight derived result");
        var olderPort = new MemoryReadSession() {
            public CompletableFuture<MemoryReadSession.Page> query(Query q, Optional<MemoryReadSession.Cursor> c, Budget budget) { throw new AssertionError(); }
            public boolean current(MemoryReadSession.Page p) { throw new AssertionError("no fabricated raw source"); }
            public boolean current(SemanticReadRecords.Page p) { return p == a; }
        };
        before = RecordedInterpretationShadow.diagnostics();
        RecordedInterpretationShadow.compareSemantic(olderPort, List.of(a), Runnable::run);
        check(RecordedInterpretationShadow.diagnostics().get("unavailable") == before.get("unavailable") + 1,
                "missing semantic interpretation capability stays unavailable without RAW fallback");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
