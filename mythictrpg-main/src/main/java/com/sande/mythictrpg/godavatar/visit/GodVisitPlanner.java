package com.sande.mythictrpg.godavatar.visit;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Only immutable, game-filtered data crosses into AI. A decision never moves an entity by itself. */
public final class GodVisitPlanner {
    public record Candidate(UUID structureId, String name, String evaluation) { }
    public record Request(UUID requestId, String godId, UUID playerId, String trigger, int affinity,
            long minecraftTime, boolean raining, List<Candidate> candidates, Optional<Dialogue> dialogue) {
        public Request { candidates = List.copyOf(candidates); Objects.requireNonNull(dialogue); }
    }
    public record Dialogue(UUID roomId, long revision, long turnSequence, List<Line> history) {
        public Dialogue { history = List.copyOf(history); }
    }
    public record Line(UUID messageId, String role, String speakerId, String text) { }
    public record Decision(Optional<UUID> structureId) { public Decision { Objects.requireNonNull(structureId); } }
    public interface Provider { CompletableFuture<Decision> choose(Request request); }
    private static Provider provider;
    public static void install(Provider value) { provider = Objects.requireNonNull(value); }
    public static boolean available() { return provider != null; }
    public static CompletableFuture<Decision> choose(Request request) {
        return provider == null ? CompletableFuture.failedFuture(new IllegalStateException("Visit AI unavailable")) : provider.choose(request);
    }
    private GodVisitPlanner() { }
}
