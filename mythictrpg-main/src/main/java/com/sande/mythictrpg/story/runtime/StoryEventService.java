package com.sande.mythictrpg.story.runtime;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.engine.ConditionContexts;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.server.DialoguePresentationService;
import com.sande.mythictrpg.dialogue.server.DialogueSendStatus;
import com.sande.mythictrpg.relation.DynamicGodRelationState;
import com.sande.mythictrpg.relation.GodRelationService;
import com.sande.mythictrpg.relation.GodRelationTransitionManager;
import com.sande.mythictrpg.story.api.StoryStateView.StoryActorView;
import com.sande.mythictrpg.story.api.StoryStateView.StoryKnowledgeHolder;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import com.sande.mythictrpg.story.signal.StorySignal;
import com.sande.mythictrpg.story.signal.StorySignalTypes;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.*;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import com.sande.mythictrpg.data.player.GodIdentifiedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/** Server-thread authority for causal Story signals, one-time outcomes and effects. */
public final class StoryEventService {
    public static final StoryEventService INSTANCE = new StoryEventService();
    public static final int MAX_SIGNALS_PER_DRAIN = 256;
    public static final int MAX_DUE_PER_TICK = 64;

    private final Queue<StorySignal> pending = new ArrayDeque<>();
    private final PriorityQueue<ScheduledSignal> due = new PriorityQueue<>(
            Comparator.comparingLong(ScheduledSignal::dueGameTime)
                    .thenComparing(value -> value.scheduleId().toString()));
    private final Set<UUID> queuedSchedules = new HashSet<>();
    private boolean draining;
    private MinecraftServer activeServer;

    private StoryEventService() {}

    public StorySubmissionResult submit(MinecraftServer server, StorySignal signal) {
        requireServerThread(server);
        if (!StoryRuntimeState.get(server).isReady())
            return StorySubmissionResult.rejected("Story state is unavailable");
        pending.add(signal);
        int processed = drain(server, MAX_SIGNALS_PER_DRAIN);
        return new StorySubmissionResult(Status.ACCEPTED, "Story signal queued", processed, List.of());
    }

    public StoryChoiceResult choose(ServerPlayer player, String instanceId, long expectedRevision,
            ResourceLocation outcomeId) {
        requireServerThread(player.server);
        StoryRuntimeState state = StoryRuntimeState.get(player.server);
        EventInstance instance = state.eventInstance(instanceId).orElse(null);
        if (instance == null) return StoryChoiceResult.rejected("Story event instance was not found");
        if (instance.status() != EventStatus.WAITING_FOR_CHOICE || instance.revision() != expectedRevision)
            return StoryChoiceResult.rejected("Story choice is stale or no longer waiting");
        if (!instance.frozenAudiencePlayerIds().contains(player.getUUID()))
            return StoryChoiceResult.rejected("Player is not an audience member of this Story event");
        EventDefinition definition = StoryDefinitionManager.INSTANCE.event(instance.eventId()).orElse(null);
        if (definition == null || !definition.fingerprint().equals(instance.definitionFingerprint())) {
            state.putEvent(instance.withStatus(EventStatus.RECOVERY_REQUIRED));
            return StoryChoiceResult.rejected("Story definition changed while the choice was open");
        }
        OutcomeDefinition outcome = eligibleOutcomes(player.server, definition, instance.scope(),
                instance.initiatingPlayerId()).stream().filter(value -> value.id().equals(outcomeId))
                .findFirst().orElse(null);
        if (outcome == null) return StoryChoiceResult.rejected("Story outcome is not currently selectable");
        resolve(player.server, definition, instance, outcome, OptionalLong.empty());
        return new StoryChoiceResult(true, "Story choice committed", instanceId, outcomeId);
    }

    public void onServerStarted(ServerStartedEvent event) {
        activeServer = event.getServer();
        rebuildScheduleQueue(activeServer);
        resumePendingInstances(activeServer);
    }

    public void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (activeServer != server) {
            activeServer = server;
            rebuildScheduleQueue(server);
        }
        processDue(server);
        processChoiceTimeouts(server);
        if (server.overworld().getGameTime() % 100L == 0L) retryExternalEffects(server, false);
        expireAndReevaluateLatched(server);
        drain(server, MAX_SIGNALS_PER_DRAIN);
    }

    public void onServerStopped(ServerStoppedEvent event) {
        pending.clear(); due.clear(); queuedSchedules.clear(); draining = false; activeServer = null;
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        StoryRuntimeState state = StoryRuntimeState.get(player.server);
        state.presentations().values().stream()
                .filter(value -> value.audiencePlayerId().equals(player.getUUID()))
                .filter(value -> value.status() == PresentationStatus.PENDING)
                .sorted(Comparator.comparingLong(PresentationOpportunity::createdAtGameTime))
                .forEach(value -> com.sande.mythictrpg.story.presentation.StoryPresentationService.INSTANCE
                        .dispatch(player.server, value));
    }

    public void onGodIdentified(GodIdentifiedEvent event) {
        submit(event.server(), StorySignal.of(StorySignalTypes.GOD_IDENTIFIED,
                Optional.of(event.playerId()), Optional.of(event.godId()),
                event.server().overworld().getGameTime()));
    }

    public StorySubmissionResult triggerRegistered(MinecraftServer server, ResourceLocation eventId,
            Optional<ServerPlayer> initiatingPlayer) {
        requireServerThread(server);
        EventDefinition definition = StoryDefinitionManager.INSTANCE.event(eventId).orElse(null);
        if (definition == null) return StorySubmissionResult.rejected("Unknown Story event " + eventId);
        StorySignal signal = StorySignal.of(StorySignalTypes.ADMIN_TRIGGERED,
                initiatingPlayer.map(ServerPlayer::getUUID), Optional.of(eventId),
                server.overworld().getGameTime());
        AttemptResult attempt = attempt(server, definition, signal, null);
        return new StorySubmissionResult(attempt.started ? Status.STARTED : Status.REJECTED,
                attempt.reason, 1, attempt.instanceId == null ? List.of() : List.of(attempt.instanceId));
    }

    StorySubmissionResult triggerHook(MinecraftServer server, HookDefinition hook, ServerPlayer player) {
        requireServerThread(server);
        EventDefinition definition = StoryDefinitionManager.INSTANCE.event(hook.targetEventId()).orElse(null);
        if (definition == null) return StorySubmissionResult.rejected("Story Hook target event is missing");
        StorySignal signal = StorySignal.of(StorySignalTypes.STORY_HOOK_ACCEPTED,
                Optional.of(player.getUUID()), Optional.of(hook.id()), server.overworld().getGameTime());
        TriggerDefinition trigger = matchingTrigger(definition, signal);
        if (trigger == null) return StorySubmissionResult.rejected("Story Hook target trigger is not registered");
        AttemptResult result = attempt(server, definition, signal, trigger);
        return new StorySubmissionResult(result.started ? Status.STARTED : Status.REJECTED, result.reason, 1,
                result.instanceId == null ? List.of() : List.of(result.instanceId));
    }

    void resetScenarioForTesting(MinecraftServer server, Set<ResourceLocation> eventIds,
            Set<ResourceLocation> actorIds, Set<ResourceLocation> factIds, Set<ResourceLocation> hookIds) {
        requireServerThread(server);
        due.removeIf(value -> eventIds.contains(value.targetEventId()));
        queuedSchedules.clear();
        queuedSchedules.addAll(due.stream().map(ScheduledSignal::scheduleId).toList());
        StoryRuntimeState.get(server).resetForTesting(eventIds, actorIds, factIds, hookIds);
    }

    private int drain(MinecraftServer server, int maximum) {
        if (draining) return 0;
        draining = true;
        int processed = 0;
        try {
            while (processed < maximum && !pending.isEmpty()) {
                StorySignal signal = pending.remove();
                for (EventDefinition definition : StoryDefinitionManager.INSTANCE.snapshot()
                        .candidates(signal.typeId(), signal.subjectId())) {
                    TriggerDefinition trigger = matchingTrigger(definition, signal);
                    if (trigger != null) attempt(server, definition, signal, trigger);
                }
                processed++;
            }
        } finally {
            draining = false;
        }
        return processed;
    }

    private AttemptResult attempt(MinecraftServer server, EventDefinition definition, StorySignal signal,
            TriggerDefinition trigger) {
        StoryRuntimeState state = StoryRuntimeState.get(server);
        ScopeResolution scope = resolveScope(server, definition.scope(), signal.initiatingPlayerId());
        if (scope == null) return AttemptResult.rejected("Story event scope requires an initiating player");
        if (!repeatAllowed(state, definition, scope.scope, signal.gameTime()))
            return AttemptResult.rejected("Story event repeat policy rejected the signal");
        ConditionResult prerequisites = ConditionEngine.INSTANCE.evaluate(definition.prerequisites(),
                ConditionContexts.forStory(server, signal.initiatingPlayerId(), scope.scope));
        if (prerequisites != ConditionResult.MATCH) {
            if (trigger != null && trigger.mode() == TriggerMode.LATCHED) {
                latch(state, definition, trigger, signal, scope);
                return AttemptResult.rejected("Story event trigger latched until prerequisites match");
            }
            state.addAudit(signal.gameTime(), prerequisites == ConditionResult.UNKNOWN
                            ? "BLOCKED_UNKNOWN_CONDITION" : "PREREQUISITE_NO_MATCH",
                    definition.id() + " scope=" + scope.scope);
            return AttemptResult.rejected("Story event prerequisites did not match");
        }

        int sequence = state.nextSequence(definition.id(), scope.scope);
        String instanceId = definition.id() + "|" + scope.scope.type().name().toLowerCase()
                + ":" + scope.scope.key() + "|" + sequence;
        EventInstance instance = new EventInstance(instanceId, definition.id(), scope.scope,
                scope.frozenAudience, signal.initiatingPlayerId(), signal.signalId(), EventStatus.TRIGGERED,
                1, definition.fingerprint(), Optional.empty(), OptionalLong.empty(), signal.gameTime(),
                OptionalLong.empty(), Set.of(), sequence);
        state.putEvent(instance);

        if (definition.resolutionPolicy() == ResolutionPolicy.PLAYER_CHOICE) {
            state.putEvent(instance.withStatus(EventStatus.WAITING_FOR_CHOICE));
            state.addAudit(signal.gameTime(), "WAITING_FOR_CHOICE", instanceId);
            return AttemptResult.started(instanceId);
        }
        List<OutcomeDefinition> eligible = eligibleOutcomes(server, definition, scope.scope,
                signal.initiatingPlayerId());
        if (eligible.isEmpty()) {
            state.putEvent(instance.withStatus(definition.failurePolicy() == FailurePolicy.CANCEL
                    ? EventStatus.CANCELLED : EventStatus.RECOVERY_REQUIRED));
            state.addAudit(signal.gameTime(), "BLOCKED_NO_VALID_OUTCOME", instanceId);
            return AttemptResult.rejected("Story event has no valid outcome");
        }
        Selection selection = select(server, definition, scope.scope, sequence, eligible);
        resolve(server, definition, instance, selection.outcome, selection.roll);
        return AttemptResult.started(instanceId);
    }

    private void resolve(MinecraftServer server, EventDefinition definition, EventInstance original,
            OutcomeDefinition outcome, OptionalLong roll) {
        StoryRuntimeState state = StoryRuntimeState.get(server);
        long gameTime = server.overworld().getGameTime();
        String validation = validateEffects(state, definition, original, outcome);
        if (validation != null) {
            state.putEvent(original.withStatus(EventStatus.RECOVERY_REQUIRED));
            state.addAudit(gameTime, "EFFECT_VALIDATION_FAILED", original.instanceId() + ": " + validation);
            return;
        }
        EventInstance decided = original.withDecision(outcome.id(), roll, EventStatus.DECIDED, gameTime);
        state.putEvent(decided);

        Set<ResourceLocation> applied = new LinkedHashSet<>();
        List<Effect.RelationTransition> externalRelations = new ArrayList<>();
        for (Effect effect : outcome.effects()) {
            if (effect instanceof Effect.RelationTransition relation) externalRelations.add(relation);
            else {
                applyCanonical(server, state, decided, effect, gameTime);
                applied.add(effect.effectId());
            }
        }
        EventInstance committed = decided.withAppliedEffects(applied, EventStatus.COMMITTED);
        state.putEvent(committed);
        for (Effect.RelationTransition relation : externalRelations) {
            var transition = GodRelationTransitionManager.INSTANCE.find(relation.transitionId()).orElse(null);
            if (transition == null || !GodRelationService.INSTANCE.apply(server, transition).succeeded()) {
                state.putEvent(committed.withAppliedEffects(applied, EventStatus.EXTERNAL_EFFECT_PENDING));
                state.addAudit(gameTime, "EXTERNAL_EFFECT_FAILED",
                        committed.instanceId() + ": " + relation.transitionId());
                return;
            }
            applied.add(relation.effectId());
        }
        EventInstance resolved = committed.withAppliedEffects(applied, EventStatus.RESOLVED);
        state.putEvent(resolved);
        state.addAudit(gameTime, "EVENT_RESOLVED", resolved.instanceId() + " -> " + outcome.id());
        pending.add(new StorySignal(UUID.randomUUID(), StorySignalTypes.EVENT_RESOLVED,
                resolved.initiatingPlayerId(), Optional.of(definition.id()), Optional.empty(),
                Optional.of(resolved.instanceId()), gameTime));
        reevaluateLatched(server);
    }

    private void applyCanonical(MinecraftServer server, StoryRuntimeState state, EventInstance instance,
            Effect effect, long gameTime) {
        if (effect instanceof Effect.SetFact value) {
            StoryScopeKey scope = resolveEffectScope(value.scope(), instance);
            if (state.setFact(scope, value.factId(), value.value())) {
                pending.add(new StorySignal(UUID.randomUUID(), StorySignalTypes.FACT_CHANGED,
                        instance.initiatingPlayerId(), Optional.of(value.factId()), Optional.empty(),
                        Optional.of(instance.instanceId()), gameTime));
            }
        } else if (effect instanceof Effect.SetActorState value) {
            StoryActorView previous = state.actor(value.actorId()).orElseThrow();
            ExistenceState existence = value.existence().orElse(previous.existence());
            AvailabilityState availability = value.availability().orElse(previous.availability());
            state.putActorState(value.actorId(), existence, availability, previous.locationId(),
                    gameTime, instance.eventId());
            pending.add(actorSignal(instance, value.actorId(), gameTime));
        } else if (effect instanceof Effect.MoveActor value) {
            StoryActorView previous = state.actor(value.actorId()).orElseThrow();
            state.putActorState(value.actorId(), previous.existence(), previous.availability(),
                    value.locationId(), gameTime, instance.eventId());
            pending.add(actorSignal(instance, value.actorId(), gameTime));
        } else if (effect instanceof Effect.GrantKnowledge value) {
            grantKnowledge(state, instance, value, gameTime);
        } else if (effect instanceof Effect.ScheduleEvent value) {
            EventDefinition target = StoryDefinitionManager.INSTANCE.event(value.eventId()).orElseThrow();
            ScopeResolution targetScope = resolveScope(server, target.scope(), instance.initiatingPlayerId());
            if (targetScope == null) throw new IllegalStateException("Scheduled player event has no player");
            ScheduledSignal schedule = new ScheduledSignal(UUID.randomUUID(), value.eventId(), targetScope.scope,
                    targetScope.frozenAudience, instance.initiatingPlayerId(), gameTime + value.delayTicks(),
                    instance.instanceId(), value.effectId());
            state.putSchedule(schedule); enqueueSchedule(schedule);
        } else if (effect instanceof Effect.EmitPresentation value) {
            for (UUID playerId : presentationAudience(server, instance, value.audience())) {
                PresentationOpportunity opportunity = new PresentationOpportunity(UUID.randomUUID(),
                        instance.instanceId(), instance.revision(), value.presentationId(), playerId,
                        PresentationStatus.PENDING, gameTime);
                state.putPresentation(opportunity);
                com.sande.mythictrpg.story.presentation.StoryPresentationService.INSTANCE
                        .dispatch(server, opportunity);
            }
        }
    }

    private String validateEffects(StoryRuntimeState state, EventDefinition definition,
            EventInstance instance, OutcomeDefinition outcome) {
        Map<ResourceLocation, StoryActorView> simulatedActors = new HashMap<>();
        for (Effect effect : outcome.effects()) {
            if (effect instanceof Effect.SetFact value) {
                StoryScopeKey scope;
                try { scope = resolveEffectScope(value.scope(), instance); }
                catch (RuntimeException exception) { return exception.getMessage(); }
                FactDefinition fact = StoryDefinitionManager.INSTANCE.fact(value.factId()).orElse(null);
                if (fact == null || !fact.allowedScopes().contains(scope.type()))
                    return "Fact does not allow resolved scope: " + value.factId();
            } else if (effect instanceof Effect.SetActorState value) {
                StoryActorView previous = simulatedActors.containsKey(value.actorId())
                        ? simulatedActors.get(value.actorId()) : state.actor(value.actorId()).orElse(null);
                if (previous == null) return "Unknown actor " + value.actorId();
                ExistenceState existence = value.existence().orElse(previous.existence());
                AvailabilityState availability = value.availability().orElse(previous.availability());
                if ((existence == ExistenceState.SEALED || existence == ExistenceState.DEFEATED
                        || existence == ExistenceState.DESTROYED) && availability != AvailabilityState.ABSENT)
                    return "Non-active actor must be absent: " + value.actorId();
                if (previous.existence() == ExistenceState.DESTROYED && existence != ExistenceState.DESTROYED)
                    return "Destroyed actor cannot be restored by set_actor_state: " + value.actorId();
                simulatedActors.put(value.actorId(), new StoryActorView(value.actorId(), existence,
                        availability, previous.locationId(), previous.revision() + 1));
            } else if (effect instanceof Effect.MoveActor value) {
                StoryActorView previous = simulatedActors.containsKey(value.actorId())
                        ? simulatedActors.get(value.actorId()) : state.actor(value.actorId()).orElse(null);
                if (previous == null) return "Unknown actor " + value.actorId();
                simulatedActors.put(value.actorId(), new StoryActorView(value.actorId(), previous.existence(),
                        previous.availability(), value.locationId(), previous.revision() + 1));
            } else if (effect instanceof Effect.GrantKnowledge value) {
                if ((value.holder() == HolderSelector.TRIGGER_PLAYER
                        || value.holder() == HolderSelector.TRIGGER_TEAM)
                        && instance.initiatingPlayerId().isEmpty()) return "Knowledge grant requires trigger player";
            } else if (effect instanceof Effect.ScheduleEvent value) {
                EventDefinition target = StoryDefinitionManager.INSTANCE.event(value.eventId()).orElse(null);
                if (target == null) return "Unknown scheduled event " + value.eventId();
                if (target.scope() != ScopeType.SERVER && instance.initiatingPlayerId().isEmpty())
                    return "Scheduled target scope requires trigger player";
            } else if (effect instanceof Effect.EmitPresentation value) {
                if (value.audience() != AudienceSelector.ALL_ONLINE_PLAYERS
                        && instance.initiatingPlayerId().isEmpty()) return "Presentation audience requires player";
            }
        }
        return null;
    }

    private void grantKnowledge(StoryRuntimeState state, EventInstance instance,
            Effect.GrantKnowledge value, long gameTime) {
        switch (value.holder()) {
            case TRIGGER_PLAYER -> state.grantKnowledge(StoryKnowledgeHolder.player(
                    instance.initiatingPlayerId().orElseThrow()), value.factId(), value.level(),
                    value.disclosurePolicyId(), instance.instanceId(), gameTime);
            case ACTOR -> state.grantKnowledge(StoryKnowledgeHolder.actor(value.actorId().orElseThrow()),
                    value.factId(), value.level(), value.disclosurePolicyId(), instance.instanceId(), gameTime);
            case TRIGGER_TEAM -> instance.frozenAudiencePlayerIds().forEach(player -> state.grantKnowledge(
                    StoryKnowledgeHolder.player(player), value.factId(), value.level(),
                    value.disclosurePolicyId(), instance.instanceId(), gameTime));
            case ALL_PLAYERS_CURRENT_AND_FUTURE -> state.grantPublicPlayerKnowledge(value.factId(), value.level());
        }
    }

    private List<OutcomeDefinition> eligibleOutcomes(MinecraftServer server, EventDefinition definition,
            StoryScopeKey scope, Optional<UUID> playerId) {
        return definition.outcomes().stream().filter(outcome -> outcome.additionalConditions()
                .map(condition -> ConditionEngine.INSTANCE.evaluate(condition,
                        ConditionContexts.forStory(server, playerId, scope)) == ConditionResult.MATCH)
                .orElse(true)).toList();
    }

    private Selection select(MinecraftServer server, EventDefinition definition, StoryScopeKey scope,
            int sequence, List<OutcomeDefinition> eligible) {
        if (definition.resolutionPolicy() == ResolutionPolicy.FIXED
                || definition.resolutionPolicy() == ResolutionPolicy.FIRST_MATCH)
            return new Selection(eligible.getFirst(), OptionalLong.empty());
        long total = eligible.stream().mapToLong(OutcomeDefinition::weight).sum();
        long seed = server.overworld().getSeed() ^ stableHash(definition.id() + "|" + scope + "|"
                + sequence + "|" + definition.fingerprint());
        long roll = Math.floorMod(mix64(seed), total);
        long cursor = 0;
        for (OutcomeDefinition outcome : eligible) {
            cursor += outcome.weight();
            if (roll < cursor) return new Selection(outcome, OptionalLong.of(roll));
        }
        throw new IllegalStateException("Story weighted selection failed");
    }

    private boolean repeatAllowed(StoryRuntimeState state, EventDefinition definition,
            StoryScopeKey scope, long gameTime) {
        if (state.hasOpenInstance(definition.id(), scope)) return false;
        int resolved = state.resolvedCount(definition.id(), scope);
        if (resolved >= definition.repeatPolicy().maximumApplications()) return false;
        if (definition.repeatPolicy().type() == RepeatType.COOLDOWN) {
            OptionalLong last = state.lastResolvedGameTime(definition.id(), scope);
            return last.isEmpty() || gameTime - last.getAsLong() >= definition.repeatPolicy().cooldownTicks();
        }
        return true;
    }

    private ScopeResolution resolveScope(MinecraftServer server, ScopeType type, Optional<UUID> playerId) {
        if (type == ScopeType.SERVER) {
            Set<UUID> audience = playerId.map(Set::of).orElseGet(Set::of);
            return new ScopeResolution(StoryScopeKey.server(), audience);
        }
        ServerPlayer player = playerId.map(id -> server.getPlayerList().getPlayer(id)).orElse(null);
        if (player == null) return null;
        if (type == ScopeType.PLAYER)
            return new ScopeResolution(StoryScopeKey.player(player.getUUID()), Set.of(player.getUUID()));
        StoryTeamResolver.TeamSnapshot team = StoryTeamResolver.resolve(player);
        return new ScopeResolution(StoryScopeKey.team(team.stableTeamId()), team.frozenMembers());
    }

    private StoryScopeKey resolveEffectScope(ScopeSelector selector, EventInstance instance) {
        return switch (selector) {
            case EVENT_SCOPE -> instance.scope();
            case SERVER -> StoryScopeKey.server();
            case TRIGGER_PLAYER -> StoryScopeKey.player(instance.initiatingPlayerId().orElseThrow(() ->
                    new IllegalStateException("Story effect requires trigger player")));
            case TRIGGER_TEAM -> instance.scope().type() == ScopeType.TEAM ? instance.scope()
                    : throwScope("Story effect requires TEAM event scope");
        };
    }

    private static StoryScopeKey throwScope(String message) { throw new IllegalStateException(message); }

    private Set<UUID> presentationAudience(MinecraftServer server, EventInstance instance,
            AudienceSelector selector) {
        return switch (selector) {
            case TRIGGER_PLAYER -> Set.of(instance.initiatingPlayerId().orElseThrow());
            case TRIGGER_TEAM -> instance.frozenAudiencePlayerIds();
            case ALL_ONLINE_PLAYERS -> server.getPlayerList().getPlayers().stream()
                    .map(ServerPlayer::getUUID).collect(java.util.stream.Collectors.toUnmodifiableSet());
        };
    }

    private void latch(StoryRuntimeState state, EventDefinition definition, TriggerDefinition trigger,
            StorySignal signal, ScopeResolution scope) {
        String key = definition.id() + "|" + scope.scope + "|" + trigger.signalType() + "|"
                + trigger.subjectId().map(ResourceLocation::toString).orElse("*");
        OptionalLong expires = trigger.expiresAfterTicks() < 0 ? OptionalLong.empty()
                : OptionalLong.of(signal.gameTime() + trigger.expiresAfterTicks());
        state.putLatched(new LatchedTrigger(key, definition.id(), scope.scope, scope.frozenAudience,
                signal.initiatingPlayerId(), signal.signalId(), signal.subjectId(), signal.gameTime(), expires));
    }

    private void expireAndReevaluateLatched(MinecraftServer server) {
        StoryRuntimeState state = StoryRuntimeState.get(server);
        long now = server.overworld().getGameTime();
        state.latchedTriggers().values().stream().filter(value -> value.expiresAtGameTime().isPresent()
                && value.expiresAtGameTime().getAsLong() <= now).map(LatchedTrigger::key).toList()
                .forEach(key -> { state.removeLatched(key); state.addAudit(now, "LATCH_EXPIRED", key); });
        reevaluateLatched(server);
    }

    private void reevaluateLatched(MinecraftServer server) {
        StoryRuntimeState state = StoryRuntimeState.get(server);
        for (LatchedTrigger latched : state.latchedTriggers().values()) {
            EventDefinition definition = StoryDefinitionManager.INSTANCE.event(latched.eventId()).orElse(null);
            if (definition == null) continue;
            ConditionResult result = ConditionEngine.INSTANCE.evaluate(definition.prerequisites(),
                    ConditionContexts.forStory(server, latched.initiatingPlayerId(), latched.scope()));
            if (result != ConditionResult.MATCH) continue;
            state.removeLatched(latched.key());
            StorySignal signal = new StorySignal(latched.signalId(), StorySignalTypes.SCHEDULED_DUE,
                    latched.initiatingPlayerId(), latched.subjectId(), Optional.empty(), Optional.empty(),
                    server.overworld().getGameTime());
            attempt(server, definition, signal, null);
        }
    }

    private void processDue(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        int processed = 0;
        while (processed < MAX_DUE_PER_TICK && !due.isEmpty() && due.peek().dueGameTime() <= now) {
            ScheduledSignal schedule = due.remove(); queuedSchedules.remove(schedule.scheduleId());
            if (StoryRuntimeState.get(server).removeSchedule(schedule.scheduleId()).isEmpty()) continue;
            EventDefinition target = StoryDefinitionManager.INSTANCE.event(schedule.targetEventId()).orElse(null);
            if (target == null) {
                StoryRuntimeState.get(server).addAudit(now, "ORPHANED_DEFINITION", schedule.targetEventId().toString());
                continue;
            }
            StorySignal signal = new StorySignal(schedule.scheduleId(), StorySignalTypes.SCHEDULED_DUE,
                    schedule.initiatingPlayerId(), Optional.of(schedule.targetEventId()), Optional.empty(),
                    Optional.of(schedule.causeInstanceId()), now);
            attempt(server, target, signal, matchingTrigger(target, signal)); processed++;
        }
    }

    private void processChoiceTimeouts(MinecraftServer server) {
        StoryRuntimeState state = StoryRuntimeState.get(server);
        long now = server.overworld().getGameTime();
        for (EventInstance instance : state.eventInstances().values()) {
            if (instance.status() != EventStatus.WAITING_FOR_CHOICE) continue;
            EventDefinition definition = StoryDefinitionManager.INSTANCE.event(instance.eventId()).orElse(null);
            if (definition == null || !definition.fingerprint().equals(instance.definitionFingerprint())) continue;
            ChoicePolicy choice = definition.choicePolicy().orElse(null);
            if (choice == null || choice.timeoutTicks() < 1
                    || instance.triggeredAtGameTime() + choice.timeoutTicks() > now) continue;
            OutcomeDefinition outcome = definition.outcomes().stream()
                    .filter(value -> value.id().equals(choice.defaultOutcomeId().orElseThrow()))
                    .filter(value -> value.additionalConditions().map(condition -> ConditionEngine.INSTANCE.evaluate(
                            condition, ConditionContexts.forStory(server, instance.initiatingPlayerId(),
                                    instance.scope())) == ConditionResult.MATCH).orElse(true))
                    .findFirst().orElse(null);
            if (outcome == null) {
                state.putEvent(instance.withStatus(EventStatus.RECOVERY_REQUIRED));
                state.addAudit(now, "CHOICE_TIMEOUT_NO_DEFAULT", instance.instanceId());
            } else {
                resolve(server, definition, instance, outcome, OptionalLong.empty());
            }
        }
    }

    private void retryExternalEffects(MinecraftServer server, boolean recoveryAfterRestart) {
        StoryRuntimeState state = StoryRuntimeState.get(server);
        long now = server.overworld().getGameTime();
        for (EventInstance instance : state.eventInstances().values()) {
            if (instance.status() != EventStatus.EXTERNAL_EFFECT_PENDING
                    && !(recoveryAfterRestart && instance.status() == EventStatus.COMMITTED)) continue;
            EventDefinition definition = StoryDefinitionManager.INSTANCE.event(instance.eventId()).orElse(null);
            if (definition == null || !definition.fingerprint().equals(instance.definitionFingerprint())) {
                state.putEvent(instance.withStatus(EventStatus.RECOVERY_REQUIRED));
                continue;
            }
            OutcomeDefinition outcome = instance.selectedOutcomeId().flatMap(selected -> definition.outcomes()
                    .stream().filter(value -> value.id().equals(selected)).findFirst()).orElse(null);
            if (outcome == null) {
                state.putEvent(instance.withStatus(EventStatus.RECOVERY_REQUIRED));
                continue;
            }
            Set<ResourceLocation> applied = new LinkedHashSet<>(instance.appliedEffectIds());
            boolean failed = false;
            for (Effect effect : outcome.effects()) {
                if (!(effect instanceof Effect.RelationTransition relation)
                        || applied.contains(relation.effectId())) continue;
                var transition = GodRelationTransitionManager.INSTANCE.find(relation.transitionId()).orElse(null);
                boolean alreadyAppliedAfterRestart = recoveryAfterRestart && transition != null
                        && transition.maxApplications() == 1
                        && DynamicGodRelationState.get(server).applicationCount(transition.id()) >= 1;
                if (!alreadyAppliedAfterRestart
                        && (transition == null || !GodRelationService.INSTANCE.apply(server, transition).succeeded())) {
                    failed = true;
                    break;
                }
                applied.add(relation.effectId());
            }
            if (failed) continue;
            EventInstance resolved = instance.withAppliedEffects(applied, EventStatus.RESOLVED);
            state.putEvent(resolved);
            state.addAudit(now, "EXTERNAL_EFFECT_RECOVERED", resolved.instanceId());
            pending.add(new StorySignal(UUID.randomUUID(), StorySignalTypes.EVENT_RESOLVED,
                    resolved.initiatingPlayerId(), Optional.of(resolved.eventId()), Optional.empty(),
                    Optional.of(resolved.instanceId()), now));
        }
    }

    private void enqueueSchedule(ScheduledSignal schedule) {
        if (queuedSchedules.add(schedule.scheduleId())) due.add(schedule);
    }

    private void rebuildScheduleQueue(MinecraftServer server) {
        due.clear(); queuedSchedules.clear();
        StoryRuntimeState.get(server).schedules().values().forEach(this::enqueueSchedule);
    }

    private void resumePendingInstances(MinecraftServer server) {
        StoryRuntimeState state = StoryRuntimeState.get(server);
        state.eventInstances().values().stream().filter(value -> value.status() == EventStatus.DECIDED
                || value.status() == EventStatus.COMMITTED || value.status() == EventStatus.EXTERNAL_EFFECT_PENDING)
                .forEach(value -> {
                    EventDefinition definition = StoryDefinitionManager.INSTANCE.event(value.eventId()).orElse(null);
                    if (definition == null || !definition.fingerprint().equals(value.definitionFingerprint())) {
                        state.putEvent(value.withStatus(EventStatus.RECOVERY_REQUIRED));
                    }
                });
        retryExternalEffects(server, true);
    }

    private static TriggerDefinition matchingTrigger(EventDefinition definition, StorySignal signal) {
        return definition.triggers().stream().filter(trigger -> trigger.signalType().equals(signal.typeId()))
                .filter(trigger -> trigger.subjectId().isEmpty() || trigger.subjectId().equals(signal.subjectId()))
                .findFirst().orElse(null);
    }

    private static StorySignal actorSignal(EventInstance instance, ResourceLocation actorId, long gameTime) {
        return new StorySignal(UUID.randomUUID(), StorySignalTypes.ACTOR_STATE_CHANGED,
                instance.initiatingPlayerId(), Optional.of(actorId), Optional.empty(),
                Optional.of(instance.instanceId()), gameTime);
    }

    private static long stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= item & 0xffL; hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Story Engine must run on the server thread");
    }

    private record ScopeResolution(StoryScopeKey scope, Set<UUID> frozenAudience) {}
    private record Selection(OutcomeDefinition outcome, OptionalLong roll) {}
    private record AttemptResult(boolean started, String reason, String instanceId) {
        static AttemptResult started(String id) { return new AttemptResult(true, "Story event started", id); }
        static AttemptResult rejected(String reason) { return new AttemptResult(false, reason, null); }
    }

    public enum Status { ACCEPTED, STARTED, REJECTED }
    public record StorySubmissionResult(Status status, String reason, int processedSignals,
            List<String> startedInstanceIds) {
        static StorySubmissionResult rejected(String reason) {
            return new StorySubmissionResult(Status.REJECTED, reason, 0, List.of());
        }
    }
    public record StoryChoiceResult(boolean accepted, String reason, String instanceId,
            ResourceLocation outcomeId) {
        static StoryChoiceResult rejected(String reason) {
            return new StoryChoiceResult(false, reason, "", ResourceLocation.fromNamespaceAndPath("mythictrpg", "none"));
        }
    }
}
