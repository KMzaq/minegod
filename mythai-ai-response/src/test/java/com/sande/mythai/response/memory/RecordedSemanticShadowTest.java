package com.sande.mythai.response.memory;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.EmbeddingRecords.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Scoped fake pages only; does not grant native game authority. */
final class RecordedSemanticShadowTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static final MemoryReadSession.Query QUERY = new MemoryReadSession.Query("옛 약속 기억해?", Optional.empty(), Optional.empty());
    static QueryVector vector() { return new QueryVector(RecordedEmbeddingTest.SPACE, RecordingRecords.sha256(QUERY.text()), new float[]{1, 0}); }
    private static final class Session implements MemoryReadSession {
        int calls, currentCalls;
        boolean revoke, fail, extraRows, overflowBytes, asynchronous;
        String text = "약속한 말";
        double similarity = .9;
        final List<SemanticReadRecords.Page> issued = new ArrayList<>();
        final List<CompletableFuture<SemanticReadRecords.Page>> pending = new ArrayList<>();
        final UUID message = UUID.randomUUID();
        public CompletableFuture<Page> query(Query q, Optional<Cursor> c, Budget b) { throw new AssertionError("no legacy/raw fallback query"); }
        public boolean current(Page p) { throw new AssertionError("no fabricated raw page"); }
        public CompletableFuture<SemanticReadRecords.Page> semantic(Query q, QueryVector v, Optional<SemanticReadRecords.Cursor> cursor, Budget budget) {
            check(q.equals(QUERY) && Arrays.equals(v.values(), vector().values()), "same exact query/vector");
            check(cursor.isEmpty() && calls == 0 || !issued.isEmpty() && cursor.equals(issued.getLast().next()), "own registered cursor chain");
            check(budget.rows() <= 4 && budget.utf8Bytes() <= 16384, "bounded shadow request"); calls++;
            if (fail) return CompletableFuture.failedFuture(new IllegalStateException("fixture"));
            var e = new SemanticReadRecords.Entry(message, new ActorRef(ActorKind.PLAYER, UUID.randomUUID().toString()),
                    Instant.parse("2026-01-01T00:00:00Z"), text, similarity, text.length(), text.length());
            var page = new SemanticReadRecords.Page(Status.PARTIAL, extraRows ? Collections.nCopies(8, e) : overflowBytes ? Collections.nCopies(4, e) : List.of(e), Optional.of(SemanticReadRecords.Cursor.unregistered()));
            issued.add(page);
            if (asynchronous) { var f = new CompletableFuture<SemanticReadRecords.Page>(); pending.add(f); return f; }
            return CompletableFuture.completedFuture(page);
        }
        public boolean current(SemanticReadRecords.Page p) { currentCalls++; return !revoke && issued.stream().anyMatch(existing -> existing == p); }
    }
    static void run() {
        var before = RecordedSemanticShadow.diagnostics(); var normal = new Session();
        RecordedSemanticShadow.compare(normal, QUERY, vector(), .75, Runnable::run);
        var after = RecordedSemanticShadow.diagnostics();
        check(normal.calls == 3, "three pages maximum");
        check(after.get("completed") == before.get("completed") + 1 && after.get("matches") == before.get("matches") + 1, "duplicate source counted once");
        var low = new Session(); low.similarity = .2; before = RecordedSemanticShadow.diagnostics();
        RecordedSemanticShadow.compare(low, QUERY, vector(), .75, Runnable::run);
        check(RecordedSemanticShadow.diagnostics().get("matches").equals(before.get("matches")), "weak nearest vector not counted as match");
        for (int mode = 0; mode < 4; mode++) {
            var invalid = new Session(); invalid.fail = mode == 0; invalid.extraRows = mode == 1;
            invalid.overflowBytes = mode == 2;
            invalid.text = mode == 2 ? "가".repeat(1500) : "말"; invalid.revoke = mode == 3;
            before = RecordedSemanticShadow.diagnostics();
            RecordedSemanticShadow.compare(invalid, QUERY, vector(), .75, Runnable::run);
            check(RecordedSemanticShadow.diagnostics().get("unavailable") == before.get("unavailable") + 1, "failure isolated once");
            check(RecordedSemanticShadow.diagnostics().get("completed").equals(before.get("completed")), "failed comparison not reported successful");
        }
        var revoked = new Session(); revoked.asynchronous = true;
        var other = new Session(); other.asynchronous = true;
        before = RecordedSemanticShadow.diagnostics();
        RecordedSemanticShadow.compare(revoked, QUERY, vector(), .75, Runnable::run);
        RecordedSemanticShadow.compare(other, QUERY, vector(), .75, Runnable::run);
        revoked.revoke = true; revoked.pending.getFirst().complete(revoked.issued.getFirst());
        for (int i = 0; i < 3; i++) other.pending.get(i).complete(other.issued.get(i));
        after = RecordedSemanticShadow.diagnostics();
        check(after.get("unavailable") == before.get("unavailable") + 1 && after.get("completed") == before.get("completed") + 1, "parallel sessions do not share revocation/pages");
        check(after.keySet().equals(Set.of("completed", "unavailable", "matches")), "no private IDs/text in diagnostics");
        System.out.println("RecordedSemanticShadowTest: " + checks + " checks passed");
    }
}
