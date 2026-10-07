package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.experiencecontract.ExperienceView;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.util.*;
import java.util.concurrent.*;

/** Consumer isolation only; actual game-issued proof and SQLite tests live in the game module. */
public final class RecordedObservationShadowTest {
    private static int checks;
    private static final UUID OBSERVATION = UUID.randomUUID(), SUBJECT = UUID.randomUUID();
    private static void check(boolean result, String label) { checks++; if (!result) throw new AssertionError(label); }
    private static ObservationReadRecords.Entry entry() {
        UUID event = UUID.randomUUID();
        return new ObservationReadRecords.Entry(new SourceRef(UUID.randomUUID(), UUID.randomUUID(), SourceKind.ACTION_OBSERVED,
                "action-ledger-v1", event.toString(), 1, "a".repeat(64)), UUID.randomUUID(), "test:a", SUBJECT,
                new ExperienceView.Event(OBSERVATION, event, 1, "DIRECT_WATCH", "MATURE_CROP_REMOVED",
                        "minecraft:wheat", "BLOCK_REMOVED_NOT_ITEM_ACQUISITION", "NOT_DISCLOSED"));
    }
    private static final class Session implements MemoryReadSession {
        int calls, revokedPage = -1;
        boolean fail, unavailable, tooMany, asynchronous;
        final List<ObservationReadRecords.Page> issued = new ArrayList<>();
        final List<CompletableFuture<ObservationReadRecords.Page>> pending = new ArrayList<>();
        @Override public CompletableFuture<Page> query(Query query, Optional<Cursor> cursor, Budget budget) {
            throw new AssertionError("observation must not be a manufactured chat message or fallback query");
        }
        @Override public boolean current(Page page) { throw new AssertionError("no fabricated RAW page"); }
        @Override public CompletableFuture<ObservationReadRecords.Page> observations(Query query, Optional<ObservationReadRecords.Cursor> cursor, Budget budget) {
            check(query.text().isEmpty() && query.fromInclusive().isEmpty() && query.untilExclusive().isEmpty(), "bounded recent observation query, not invented UTC/semantic match");
            check(cursor.isEmpty() && calls == 0 || !issued.isEmpty() && cursor.equals(issued.getLast().next()), "exact continuation chain");
            check(budget.rows() <= 4 && budget.utf8Bytes() <= 8192, "consumer read limits"); calls++;
            if (fail) return CompletableFuture.failedFuture(new IllegalStateException("fixture"));
            var page = new ObservationReadRecords.Page(unavailable ? Status.UNAVAILABLE : Status.PARTIAL,
                    unavailable ? List.of() : tooMany ? Collections.nCopies(8, entry()) : List.of(entry()),
                    unavailable ? Optional.empty() : Optional.of(ObservationReadRecords.Cursor.unregistered()));
            issued.add(page);
            if (asynchronous) { var result = new CompletableFuture<ObservationReadRecords.Page>(); pending.add(result); return result; }
            return CompletableFuture.completedFuture(page);
        }
        @Override public boolean current(ObservationReadRecords.Page page) {
            for (int i = 0; i < issued.size(); i++) if (issued.get(i) == page) return i != revokedPage;
            return false;
        }
    }
    public static void main(String[] args) {
        var normal = new Session(); var before = RecordedObservationShadow.diagnostics();
        RecordedObservationShadow.compare(() -> Optional.of(normal), Set.of(OBSERVATION), Runnable::run);
        var after = RecordedObservationShadow.diagnostics();
        check(normal.calls == 3 && after.get("completed") == before.get("completed") + 1, "bounded independent comparison completes");
        check(after.get("legacyMatches") == before.get("legacyMatches") + 1 && after.get("legacySelected") == before.get("legacySelected") + 1,
                "same observation returned more than once is counted once");
        check(RecordedObservationShadow.wireBytes(entry()) > 0, "typed full card serializes without reflection on Instant");
        for (int mode = 0; mode < 4; mode++) {
            var bad = new Session(); bad.fail = mode == 0; bad.unavailable = mode == 1; bad.tooMany = mode == 2; bad.revokedPage = mode == 3 ? 0 : -1;
            before = RecordedObservationShadow.diagnostics();
            RecordedObservationShadow.compare(() -> Optional.of(bad), Set.of(), Runnable::run);
            after = RecordedObservationShadow.diagnostics();
            check(after.get("unavailable") == before.get("unavailable") + 1 && after.get("completed").equals(before.get("completed")), "unsupported/failed/revoked source never falls back or reports completed");
        }
        var revoked = new Session(); revoked.asynchronous = true;
        var other = new Session(); other.asynchronous = true;
        before = RecordedObservationShadow.diagnostics();
        RecordedObservationShadow.compare(() -> Optional.of(revoked), Set.of(OBSERVATION), Runnable::run);
        RecordedObservationShadow.compare(() -> Optional.of(other), Set.of(OBSERVATION), Runnable::run);
        revoked.pending.getFirst().complete(revoked.issued.getFirst());
        revoked.revokedPage = 0;
        revoked.pending.get(1).complete(revoked.issued.get(1));
        for (int i = 0; i < 3; i++) other.pending.get(i).complete(other.issued.get(i));
        after = RecordedObservationShadow.diagnostics();
        check(revoked.calls == 2 && after.get("unavailable") == before.get("unavailable") + 1, "new page cannot launder earlier revoked evidence");
        check(after.get("completed") == before.get("completed") + 1 && after.get("legacyMatches") == before.get("legacyMatches") + 1, "independent room comparison survives other room withdrawal");
        before = RecordedObservationShadow.diagnostics();
        RecordedObservationShadow.compare(Optional::empty, Set.of(), Runnable::run);
        check(RecordedObservationShadow.diagnostics().equals(before), "OFF/absent authority does nothing");
        RecordedObservationShadow.compare(() -> { throw new IllegalStateException("offline"); }, Set.of(), Runnable::run);
        check(RecordedObservationShadow.diagnostics().get("unavailable") == before.get("unavailable") + 1, "optional opening failure stays isolated");
        var stopped = new Session();
        before = RecordedObservationShadow.diagnostics();
        RecordedObservationShadow.compare(() -> Optional.of(stopped), Set.of(), task -> { throw new RejectedExecutionException("closed"); });
        check(RecordedObservationShadow.diagnostics().get("unavailable") == before.get("unavailable") + 1, "closed dispatcher is contained");
        check(RecordedObservationShadow.diagnostics().keySet().equals(Set.of("completed", "unavailable", "legacySelected", "legacyMatches")), "no body/subject/God/source disclosure in aggregate diagnostics");
        System.out.println("RecordedObservationShadowTest: " + checks + " checks passed; no game or LLM");
    }
}
