package com.sande.mythictrpg.rumor;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Immutable semantic proposals. No provider receives a world, player, ledger or mutation capability. */
public final class SocialReview {
    public enum Kind { RUMOR, RECEPTION, RECOVERY }
    public enum Verdict { SKIP, PUBLISH, ACCEPT, DOUBT, IGNORE, RECOVER }
    /** Capacity/preemption is a deferral, not a failed semantic decision. */
    public static final class Busy extends RuntimeException { public Busy(){super("foreground priority / social worker busy");} }
    public record Line(String role, String text) {
        public Line { if (!Set.of("PLAYER", "GOD").contains(role)) throw new IllegalArgumentException("speaker"); text = bounded(text, 1200); }
    }
    public record Request(UUID id, Kind kind, String godId, int affinity, String evidence,
                          List<Line> conversation, String guidance) {
        public Request {
            Objects.requireNonNull(id); Objects.requireNonNull(kind);
            if (!godId.isEmpty()) CourierSettings.identifier(godId);
            if ((kind == Kind.RUMOR) != godId.isEmpty()) throw new IllegalArgumentException("review god scope");
            if (affinity < -1000 || affinity > 1000) throw new IllegalArgumentException("affinity");
            evidence = bounded(evidence, 600); conversation = List.copyOf(conversation); guidance = bounded(guidance, 1200);
            if (conversation.size() > 6 || kind != Kind.RECOVERY && !conversation.isEmpty()) throw new IllegalArgumentException("conversation scope");
        }
    }
    public record Answer(Verdict verdict, String quote, String claim, String epithet,
                         String reason, boolean needsWorldVerification, boolean otherSubjects) {
        public Answer {
            Objects.requireNonNull(verdict); quote = bounded(quote, 600); claim = bounded(claim, 300);
            epithet = bounded(epithet, 60); reason = bounded(reason, 400);
        }
    }
    @FunctionalInterface public interface Provider { CompletableFuture<Answer> review(Request request); }
    private static volatile Provider provider = request -> CompletableFuture.failedFuture(new IllegalStateException("social provider unavailable"));
    public static void configure(Provider value) { provider = Objects.requireNonNull(value); }
    static CompletableFuture<Answer> submit(Request request) { return provider.review(request); }
    /** Structural/evidence gates, not a claim that semantic inference is infallible. */
    public static boolean valid(Request request, Answer answer) {
        if (answer == null || answer.reason().isBlank() || answer.needsWorldVerification() || answer.otherSubjects()) return false;
        if (answer.verdict() == Verdict.SKIP) return true;
        return switch (request.kind()) {
            case RUMOR -> answer.verdict() == Verdict.PUBLISH && answer.quote().equals(request.evidence()) && !answer.claim().isBlank();
            case RECEPTION -> Set.of(Verdict.ACCEPT, Verdict.DOUBT, Verdict.IGNORE).contains(answer.verdict())
                    && answer.quote().equals(request.evidence()) && answer.claim().isEmpty() && answer.epithet().isEmpty();
            case RECOVERY -> answer.verdict() == Verdict.RECOVER && !answer.quote().isBlank()
                    && request.conversation().stream().anyMatch(l -> l.role().equals("PLAYER") && l.text().contains(answer.quote()))
                    && answer.claim().isEmpty() && answer.epithet().isEmpty();
        };
    }
    private static String bounded(String value, int max) {
        if (value == null || value.length() > max || value.indexOf('\u0000') >= 0) throw new IllegalArgumentException("social text budget");
        return value;
    }
    private SocialReview() { }
}
