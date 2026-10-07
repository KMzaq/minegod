package com.sande.mythictrpg.godavatar.activity;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Immutable game-filtered perceptions. The provider cannot pick coordinates or issue commands. */
public final class GodActivityPlanner {
    public record Candidate(String choiceId, String definitionId, String kind, String mode,
            String place, String evidence, List<String> peers) {
        public Candidate { peers = List.copyOf(peers); }
    }
    public record Peer(String godId, String activity) { }
    public record Speech(String godId, String text) { }
    public record Request(UUID requestId, String godId, long revision, long minecraftTime, boolean raining,
            String currentActivity, List<String> recentActivities, List<Candidate> candidates, List<Peer> peers,
            NpcActivityMemory.View experience) {
        public Request(UUID requestId, String godId, long revision, long minecraftTime, boolean raining,
                String currentActivity, List<String> recentActivities, List<Candidate> candidates, List<Peer> peers) {
            this(requestId, godId, revision, minecraftTime, raining, currentActivity, recentActivities, candidates, peers,
                    NpcActivityMemory.View.empty(godId));
        }
        public Request {
            Objects.requireNonNull(experience);
            recentActivities = List.copyOf(recentActivities); candidates = List.copyOf(candidates); peers = List.copyOf(peers);
            if (candidates.size() > 32 || peers.size() > 3 || recentActivities.size() > 8 || !godId.equals(experience.godId()))
                throw new IllegalArgumentException("Activity request budget");
        }
    }
    public record Decision(UUID requestId, String choiceId, List<Speech> speech, NpcActivityMemory.Affect activityAffect) {
        public Decision(UUID requestId, String choiceId, List<Speech> speech) {
            this(requestId, choiceId, speech, NpcActivityMemory.Affect.empty());
        }
        public Decision { speech = List.copyOf(speech); Objects.requireNonNull(activityAffect); }
    }
    public interface Provider { CompletableFuture<Decision> choose(Request request); }
    private static Provider provider;
    public static void install(Provider value) { provider = Objects.requireNonNull(value); }
    public static boolean available() { return provider != null; }
    public static CompletableFuture<Decision> choose(Request request) {
        return provider == null ? CompletableFuture.failedFuture(new IllegalStateException("Activity AI unavailable")) : provider.choose(request);
    }
    private GodActivityPlanner() { }
}
