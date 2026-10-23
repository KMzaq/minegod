package com.sande.mythictrpg.understanding;

import java.util.*;
import java.util.function.Predicate;
import static com.sande.mythictrpg.understanding.PurposeEvidence.*;

/** Deterministic, rebuildable projection of permitted evidence. No LLM confidence or game mutations. */
public final class PurposeState {
    public static final int RULES_VERSION = 1;
    public static final int MAX_EVIDENCE = 4096, MAX_GOALS = 64;
    public enum Intention { UNKNOWN, INTEREST, DESIRED, CONSIDERING, DECIDED }
    public enum Progress { NONE, INFORMATION, PREPARING, PURSUING, ACHIEVED }
    public enum Strength { NONE, LIMITED, REPEATED, CONVERGING }
    public enum Lifecycle { ACTIVE, PAUSED, WITHDRAWN, DISOWNED, ACHIEVED }
    public record State(Goal goal, Intention statedIntention, Progress observedProgress, Strength strength,
            Lifecycle lifecycle, boolean inferredPursuit, boolean conflictingEvidence, int independentEpisodes,
            int evidenceFamilies, int priority, long lastOrder, List<UUID> support, List<UUID> counterevidence) {
        public State { support = List.copyOf(support); counterevidence = List.copyOf(counterevidence); }
    }
    public record Projection(int rulesVersion, UUID worldId, UUID playerId, long startOrder,
            boolean complete, List<State> goals) {
        public Projection { goals = List.copyOf(goals); }
    }
    private PurposeState() { }

    /** Filter BEFORE aggregation. A caller must revalidate source access, not a cached global profile. */
    public static Projection project(UUID world, UUID player, long startOrder, Collection<PurposeEvidence> evidence,
            boolean complete, Predicate<PurposeEvidence> permitted) {
        Objects.requireNonNull(world); Objects.requireNonNull(player); Objects.requireNonNull(permitted);
        if (startOrder < 0 || evidence.size() > MAX_EVIDENCE) throw new IllegalArgumentException("PURPOSE_READ_BOUND");
        var unique = new LinkedHashMap<String, PurposeEvidence>();
        for (var e : evidence) {
            if (!world.equals(e.worldId()) || !player.equals(e.playerId()) || e.sourceOrder() <= startOrder || !e.usable()
                    || !permitted.test(e)) continue;
            var old = unique.putIfAbsent(e.identity(), e);
            if (old != null && !old.equals(e)) throw new IllegalArgumentException("PURPOSE_SOURCE_CONFLICT");
        }
        var groups = new LinkedHashMap<Goal, List<PurposeEvidence>>();
        unique.values().stream().sorted(Comparator.comparingLong(PurposeEvidence::sourceOrder)
                .thenComparing(e -> e.sourceId().toString())).forEach(e -> groups.computeIfAbsent(e.goal(), ignored -> new ArrayList<>()).add(e));
        if (groups.size() > MAX_GOALS) throw new IllegalArgumentException("PURPOSE_GOAL_BOUND");
        var goals = groups.entrySet().stream().map(e -> reduce(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(State::priority).reversed()
                        .thenComparing(Comparator.comparingLong(State::lastOrder).reversed())
                        .thenComparing(s -> s.goal().targetId()).thenComparing(s -> s.goal().kind())).toList();
        return new Projection(RULES_VERSION, world, player, startOrder, complete, goals);
    }

    private static State reduce(Goal goal, List<PurposeEvidence> ordered) {
        Intention intention = Intention.UNKNOWN;
        Progress progress = Progress.NONE;
        Lifecycle lifecycle = Lifecycle.ACTIVE;
        var episodes = new HashSet<UUID>();
        var families = new HashSet<String>();
        var behaviorEpisodes = new HashSet<UUID>();
        var behaviorFamilies = new HashSet<Signal>();
        var support = new LinkedHashSet<UUID>();
        var counters = new LinkedHashSet<UUID>();
        boolean conflicting = false, significantChoice = false;
        long last = 0;
        for (var e : ordered) {
            last = e.sourceOrder();
            if (e.context() != Context.DIRECTED) {
                // A recorded choice/visit remains an observation, but cannot prove this target's motivation.
                counters.add(e.sourceId()); continue;
            }
            boolean statement = e.origin() == Origin.SELF_SPEECH;
            if (statement && Set.of(Signal.WITHDRAW, Signal.DISOWN, Signal.PAUSE).contains(e.signal())) {
                lifecycle = switch (e.signal()) { case WITHDRAW -> Lifecycle.WITHDRAWN; case DISOWN -> Lifecycle.DISOWNED; default -> Lifecycle.PAUSED; };
                intention = Intention.UNKNOWN; progress = Progress.NONE;
                counters.addAll(support); counters.add(e.sourceId()); support.clear();
                episodes.clear(); families.clear(); behaviorEpisodes.clear(); behaviorFamilies.clear();
                conflicting = false; significantChoice = false; continue;
            }
            boolean explicitlyReopened = statement && Set.of(Signal.DESIRE, Signal.CONSIDER, Signal.DECIDE, Signal.RESUME).contains(e.signal());
            if (lifecycle != Lifecycle.ACTIVE) {
                if (explicitlyReopened) {
                    lifecycle = Lifecycle.ACTIVE; intention = Intention.UNKNOWN; progress = Progress.NONE;
                    episodes.clear(); families.clear(); behaviorEpisodes.clear(); behaviorFamilies.clear(); support.clear();
                    significantChoice = false; conflicting = false;
                } else {
                    counters.add(e.sourceId());
                    if (e.origin() == Origin.GAME_ACTION) conflicting = true;
                    continue;
                }
            }
            support.add(e.sourceId()); episodes.add(e.episodeId());
            if (statement) {
                families.add("SPEECH");
                intention = switch (e.signal()) {
                    case ASK -> intention == Intention.UNKNOWN ? Intention.INTEREST : intention;
                    case DESIRE -> Intention.DESIRED;
                    case CONSIDER -> Intention.CONSIDERING;
                    case DECIDE -> Intention.DECIDED;
                    default -> intention;
                };
                // Asking is an observed information-seeking act, not a meeting decision.
                if (e.signal() == Signal.ASK && progress == Progress.NONE) progress = Progress.INFORMATION;
            } else {
                families.add(e.signal().name()); behaviorEpisodes.add(e.episodeId()); behaviorFamilies.add(e.signal());
                if (e.consequentialChoice()) significantChoice = true;
                progress = switch (e.signal()) {
                    case COMPLETE -> Progress.ACHIEVED;
                    case PURSUE -> max(progress, Progress.PURSUING);
                    case PREPARE, CHOOSE -> max(progress, Progress.PREPARING);
                    case EXPLORE -> max(progress, Progress.INFORMATION);
                    default -> progress;
                };
                if (e.signal() == Signal.COMPLETE) lifecycle = Lifecycle.ACHIEVED;
            }
        }
        boolean inferred = lifecycle == Lifecycle.ACTIVE && progress != Progress.ACHIEVED
                && behaviorEpisodes.size() >= 2 && behaviorFamilies.size() >= 2;
        if (inferred) progress = max(progress, Progress.PURSUING);
        Strength strength = episodes.isEmpty() ? Strength.NONE : episodes.size() == 1 ? Strength.LIMITED
                : families.size() >= 2 ? Strength.CONVERGING : Strength.REPEATED;
        // An ordering hint, not a probability or permission. One episode cannot farm unlimited priority.
        int priority = lifecycle == Lifecycle.ACTIVE ? Math.min(episodes.size(), 6)
                + Math.min(families.size(), 3) + (significantChoice ? 2 : 0) : 0;
        return new State(goal, intention, progress, strength, lifecycle, inferred, conflicting,
                episodes.size(), families.size(), priority, last, List.copyOf(support), List.copyOf(counters));
    }
    private static Progress max(Progress a, Progress b) { return a.ordinal() >= b.ordinal() ? a : b; }
}
