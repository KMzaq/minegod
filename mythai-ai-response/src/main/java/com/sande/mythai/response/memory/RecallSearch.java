package com.sande.mythai.response.memory;

import java.time.*;
import java.util.*;

/** Bounded, network-free raw-evidence retrieval; NOT semantic embedding or automatic fact extraction. */
public final class RecallSearch {
    public enum Status { FOUND, AMBIGUOUS, NO_MATCH, UNAVAILABLE, PENDING_INDEX }
    public record Result(RecallQuery query, Status status, List<MemoryJournal.Entry> selected,
            Map<UUID, String> reasons, Set<UUID> pending, long elapsedNanos, String reason) {
        public Result { selected = List.copyOf(selected); reasons = Map.copyOf(reasons); pending = Set.copyOf(pending); }
    }
    private record Match(MemoryJournal.Entry entry, int score, String reason) {}
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final java.util.regex.Pattern PLAN = java.util.regex.Pattern.compile("(계획|일정|예정|약속|기로|갈 거|갈거|갈게|가게.*[됐됬]|간다고|할 거|할거|내일|모레)");
    private RecallSearch() {}
    public static Result unavailable(RecallQuery query, String reason) {
        return new Result(query, Status.UNAVAILABLE, List.of(), Map.of(), Set.of(), 0, reason);
    }
    public static Result search(MemoryJournal.ReadView view, RecallQuery query, RecallSettings settings,
            Set<String> recent, List<String> recentPlayers, long now, long budgetNanos) {
        long start = System.nanoTime();
        if (!view.key().equals(query.scope().key()) || !view.audience().equals(query.scope().audience()))
            return unavailable(query, "scope_mismatch");
        if (!view.ready()) return new Result(query, view.failed() ? Status.UNAVAILABLE : Status.PENDING_INDEX,
                List.of(), Map.of(), Set.of(), 0, "journal_not_ready");
        if (budgetNanos <= 0) return unavailable(query, "search_budget");
        List<MemoryJournal.Entry> allowed = view.entries(); // already restricted by game-issued scope
        List<Match> matches = new ArrayList<>();
        List<MemoryJournal.Entry> raw = new ArrayList<>();
        MemoryJournal.Entry returning = null;
        List<String> context = MemoryRecallPolicy.contextQueries(query.text(), recentPlayers);
        var queryTerms = MemoryJournal.lexical(query.text());
        var contextTerms = context.stream().map(MemoryJournal::lexical).toList();
        boolean planQuestion = query.planQuestion();
        LocalDate target = query.planQuestion() ? date(query.text(), query.askedAt(), settings.timeBasis()) : null;
        LocalDate saidOn = recordedDate(query.text(), query.askedAt(), settings.timeBasis());
        for (var entry : allowed) {
            if (System.nanoTime() - start > budgetNanos) return unavailable(query, "search_budget");
            if (entry.source() == MemoryJournal.Source.HEARSAY_NPC || entry.occurredAt() > now
                    || (!entry.important() && now - entry.occurredAt() > Duration.ofDays(30).toMillis())) continue;
            if (query.explicit() && entry.source() != MemoryJournal.Source.PLAYER_STATEMENT) continue;
            if (!query.explicit() && (recent.contains(entry.text()) || entry.text().equals(query.text()))) continue;
            if (!query.explicit() && MemoryRecallPolicy.returning(query.text())
                    && entry.source() == MemoryJournal.Source.PLAYER_STATEMENT
                    && !entry.session().equals(query.scope().generation())
                    && now - entry.occurredAt() <= Duration.ofHours(6).toMillis()
                    && (returning == null || entry.occurredAt() > returning.occurredAt())) returning = entry;
            boolean plan = looksLikePlan(entry.text());
            if (query.explicit() && planQuestion && !plan) continue;
            // Questions are retained in the journal but cannot answer themselves on a later turn.
            if (query.explicit() && (RecallQuery.explicitRecall(entry.text()) || RecallQuery.bareFollowUp(entry.text()))) continue;
            if (saidOn != null && !day(entry.occurredAt()).equals(saidOn)) continue;
            var candidateTerms = MemoryJournal.lexical(entry.text());
            boolean lexical = MemoryJournal.related(queryTerms, candidateTerms);
            boolean contextual = contextTerms.stream().anyMatch(t -> MemoryJournal.related(t, candidateTerms));
            LocalDate eventDate = plan ? date(entry.text(), entry.occurredAt(), settings.timeBasis()) : null;
            // A recording-date condition must not turn 'yesterday's tomorrow' into tomorrow from today.
            LocalDate expected = saidOn == null ? target : relativeToDay(query.text(), saidOn, settings.timeBasis(), true);
            boolean timeMatch = expected != null && expected.equals(eventDate);
            boolean wrongDate = expected != null && eventDate != null && !expected.equals(eventDate);
            if (wrongDate) continue;
            if (query.explicit()) raw.add(entry);
            int score = lexical ? 40 : contextual ? 20 : 0;
            String reason = lexical ? "lexical" : "player_context";
            if (query.explicit() && query.planQuestion() && plan) {
                score += timeMatch ? 100 : 50;
                reason = timeMatch ? "plan_time" : "plan_raw";
            }
            if (score > 0) matches.add(new Match(entry, score, reason));
        }
        if (returning != null) {
            var anchor = returning;
            if (matches.stream().noneMatch(m -> m.entry().id().equals(anchor.id()))) matches.add(new Match(anchor, 1, "return_anchor"));
        }
        // No arbitrary nearest memory becomes FOUND. A recent raw fallback is explicitly uncertain.
        boolean fallback = query.explicit() && matches.isEmpty();
        if (fallback) {
            raw.sort(Comparator.comparingLong(MemoryJournal.Entry::occurredAt).reversed());
            raw.stream().limit(6).filter(e -> !query.planQuestion() || looksLikePlan(e.text()))
                    .limit(3).forEach(e -> matches.add(new Match(e, 1, "recent_raw_uncertain")));
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed()
                .thenComparing(Comparator.comparingLong((Match m) -> m.entry().occurredAt()).reversed())
                .thenComparing(m -> m.entry().id()));
        var top = matches.stream().limit(12).limit(3).toList();
        var selected = top.stream().map(Match::entry).sorted(Comparator.comparingLong(MemoryJournal.Entry::occurredAt)
                .thenComparing(MemoryJournal.Entry::id)).toList();
        Map<UUID,String> reasons = new LinkedHashMap<>();
        top.forEach(m -> reasons.put(m.entry().id(), m.reason()));
        boolean uncertainty = fallback || top.size() > 1 || top.stream().anyMatch(m -> uncertainClaim(m.entry().text()));
        Status status = selected.isEmpty() ? Status.NO_MATCH : uncertainty ? Status.AMBIGUOUS : Status.FOUND;
        Set<UUID> pending = new HashSet<>(view.pending()); pending.retainAll(reasons.keySet());
        if (System.nanoTime() - start > budgetNanos) return unavailable(query, "search_budget");
        return new Result(query, status, selected, reasons, pending, System.nanoTime() - start,
                query.explicit() ? "raw_recall" : "association");
    }
    public static boolean looksLikePlan(String text) {
        return PLAN.matcher(text).find();
    }
    private static boolean uncertainClaim(String text) {
        return text.matches(".*(말고|취소|변경|아니라|농담|장난|라면|다면|일지도|라고 했|다고 말했|다고 했|가 말|가 얘기).*");
    }
    public static LocalDate date(String text, long utteredAt, RecallSettings.TimeBasis basis) {
        return relativeToDay(text, day(utteredAt), basis, false);
    }
    private static LocalDate relativeToDay(String text, LocalDate base, RecallSettings.TimeBasis basis, boolean ignoreReported) {
        if (basis != RecallSettings.TimeBasis.REAL_KST || text.matches(".*(게임|마크|잠자|아침이 되면).*") ) return null;
        // Exclude the recording-date phrase before interpreting the event date.
        String value = ignoreReported ? text.replaceFirst("(그제|어제|오늘)\\s*(말한|얘기한|이야기한)", "") : text;
        if (value.contains("모레")) return base.plusDays(2);
        if (value.contains("내일")) return base.plusDays(1);
        if (value.contains("어제")) return base.minusDays(1);
        if (value.contains("오늘")) return base;
        return null;
    }
    private static LocalDate recordedDate(String text, long now, RecallSettings.TimeBasis basis) {
        if (basis != RecallSettings.TimeBasis.REAL_KST) return null;
        if (text.matches(".*어제\\s*(말한|얘기한|이야기한).*")) return day(now).minusDays(1);
        if (text.matches(".*오늘\\s*(말한|얘기한|이야기한).*")) return day(now);
        return null;
    }
    private static LocalDate day(long time) { return Instant.ofEpochMilli(time).atZone(KST).toLocalDate(); }
}
