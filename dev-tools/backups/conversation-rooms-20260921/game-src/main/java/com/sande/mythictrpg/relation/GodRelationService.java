package com.sande.mythictrpg.relation;

import com.sande.mythictrpg.data.god.GodDefinitionManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.signal.StorySignal;
import com.sande.mythictrpg.story.signal.StorySignalTypes;

/** Server-authoritative validation and atomic commit boundary for authored relation transitions. */
public final class GodRelationService {
    public static final GodRelationService INSTANCE = new GodRelationService();

    private GodRelationService() {
    }

    public GodRelationTransitionResult preview(MinecraftServer server, GodRelationTransition transition) {
        return resolve(DynamicGodRelationState.get(server), transition, false,
                server.overworld().getGameTime(), GodRelationService::isKnownGod);
    }

    public GodRelationTransitionResult apply(MinecraftServer server, GodRelationTransition transition) {
        requireServerThread(server);
        GodRelationTransitionResult result = resolve(DynamicGodRelationState.get(server), transition, true,
                server.overworld().getGameTime(), GodRelationService::isKnownGod);
        if (result.status() == GodRelationTransitionResult.Status.APPLIED) {
            StoryEventService.INSTANCE.submit(server, StorySignal.of(StorySignalTypes.RELATION_TRANSITIONED,
                    java.util.Optional.empty(), java.util.Optional.of(transition.id()),
                    server.overworld().getGameTime()));
        }
        return result;
    }

    static GodRelationTransitionResult resolveForTesting(DynamicGodRelationState state,
            GodRelationTransition transition, boolean commit, long gameTime,
            Predicate<ResourceLocation> knownGod) {
        return resolve(state, transition, commit, gameTime, knownGod);
    }

    private static GodRelationTransitionResult resolve(DynamicGodRelationState state,
            GodRelationTransition transition, boolean commit, long gameTime,
            Predicate<ResourceLocation> knownGod) {
        if (!state.isReady()) {
            return rejected("God relation state is unavailable: "
                    + state.rejectionReason().orElse("unknown reason"));
        }
        if (state.applicationCount(transition.id()) >= transition.maxApplications()) {
            return rejected("God relation transition application limit reached: " + transition.id());
        }
        Map<GodRelationKey, PendingChange> pending = new LinkedHashMap<>();
        try {
            for (GodRelationTransitionChange change : transition.changes()) {
                requireKnownGod(change.sourceGodId(), knownGod);
                requireKnownGod(change.targetGodId(), knownGod);
                merge(pending, change.key(), change.scoreDelta(), change.addTags(), change.removeTags());
                Set<GodRelationTag> symmetricAdds = symmetric(change.addTags());
                Set<GodRelationTag> symmetricRemoves = symmetric(change.removeTags());
                if (!symmetricAdds.isEmpty() || !symmetricRemoves.isEmpty()) {
                    merge(pending, change.key().reversed(), 0, symmetricAdds, symmetricRemoves);
                }
            }
            Map<GodRelationKey, GodRelationSnapshot> replacements = new LinkedHashMap<>();
            List<GodRelationAppliedChange> applied = new ArrayList<>();
            for (Map.Entry<GodRelationKey, PendingChange> entry : pending.entrySet()) {
                GodRelationSnapshot previous = state.find(entry.getKey().sourceGodId(),
                        entry.getKey().targetGodId()).orElseGet(() -> GodRelationSnapshot.neutral(entry.getKey()));
                PendingChange change = entry.getValue();
                int score = Math.addExact(previous.score(), change.scoreDelta);
                if (score < GodRelationSnapshot.MIN_SCORE || score > GodRelationSnapshot.MAX_SCORE) {
                    throw new IllegalArgumentException("Relation score would exceed -1000..1000 for " + entry.getKey());
                }
                EnumSet<GodRelationTag> tags = previous.tags().isEmpty()
                        ? EnumSet.noneOf(GodRelationTag.class) : EnumSet.copyOf(previous.tags());
                tags.removeAll(change.removeTags);
                tags.addAll(change.addTags);
                GodRelationRules.requireCompatible(tags);
                long revision = previous.revision() + 1;
                Set<GodRelationTag> added = difference(tags, previous.tags());
                Set<GodRelationTag> removed = difference(previous.tags(), tags);
                List<GodRelationHistoryEntry> history = new ArrayList<>(previous.recentHistory());
                history.add(new GodRelationHistoryEntry(revision, gameTime, transition.id(),
                        previous.score(), score, added, removed));
                if (history.size() > DynamicGodRelationState.MAX_HISTORY_PER_DIRECTION) {
                    history = new ArrayList<>(history.subList(history.size()
                            - DynamicGodRelationState.MAX_HISTORY_PER_DIRECTION, history.size()));
                }
                GodRelationSnapshot next = new GodRelationSnapshot(entry.getKey(), score, tags,
                        revision, gameTime, transition.id(), history);
                replacements.put(entry.getKey(), next);
                applied.add(new GodRelationAppliedChange(entry.getKey(), previous.score(), score,
                        previous.tags(), tags, revision));
            }
            if (commit) {
                state.commit(replacements, transition.id(), transition.maxApplications());
            }
            return new GodRelationTransitionResult(commit
                    ? GodRelationTransitionResult.Status.APPLIED : GodRelationTransitionResult.Status.PREVIEW,
                    commit ? "God relation transition applied" : "God relation transition is valid", applied);
        } catch (RuntimeException exception) {
            return rejected(exception.getMessage());
        }
    }

    private static void merge(Map<GodRelationKey, PendingChange> pending, GodRelationKey key,
            int scoreDelta, Set<GodRelationTag> addTags, Set<GodRelationTag> removeTags) {
        PendingChange target = pending.computeIfAbsent(key, ignored -> new PendingChange());
        target.scoreDelta = Math.addExact(target.scoreDelta, scoreDelta);
        for (GodRelationTag tag : addTags) {
            if (target.removeTags.contains(tag)) {
                throw new IllegalArgumentException("Transition both adds and removes " + tag + " for " + key);
            }
            target.addTags.add(tag);
        }
        for (GodRelationTag tag : removeTags) {
            if (target.addTags.contains(tag)) {
                throw new IllegalArgumentException("Transition both adds and removes " + tag + " for " + key);
            }
            target.removeTags.add(tag);
        }
    }

    private static Set<GodRelationTag> symmetric(Set<GodRelationTag> source) {
        EnumSet<GodRelationTag> result = EnumSet.noneOf(GodRelationTag.class);
        source.stream().filter(GodRelationTag::symmetric).forEach(result::add);
        return Set.copyOf(result);
    }

    private static Set<GodRelationTag> difference(Set<GodRelationTag> left, Set<GodRelationTag> right) {
        EnumSet<GodRelationTag> result = left.isEmpty()
                ? EnumSet.noneOf(GodRelationTag.class) : EnumSet.copyOf(left);
        result.removeAll(right);
        return Set.copyOf(result);
    }

    private static boolean isKnownGod(ResourceLocation godId) {
        return GodDefinitionManager.INSTANCE.find(godId).isPresent();
    }

    private static void requireKnownGod(ResourceLocation godId, Predicate<ResourceLocation> knownGod) {
        if (!knownGod.test(godId)) {
            throw new IllegalArgumentException("Unknown God in relation transition: " + godId);
        }
    }

    private static GodRelationTransitionResult rejected(String reason) {
        return new GodRelationTransitionResult(GodRelationTransitionResult.Status.REJECTED,
                reason == null || reason.isBlank() ? "God relation transition was rejected" : reason, List.of());
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("God relations may only change on the server thread");
        }
    }

    private static final class PendingChange {
        private int scoreDelta;
        private final EnumSet<GodRelationTag> addTags = EnumSet.noneOf(GodRelationTag.class);
        private final EnumSet<GodRelationTag> removeTags = EnumSet.noneOf(GodRelationTag.class);
    }
}
