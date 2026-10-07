package com.sande.mythictrpg.story.definition;

import com.sande.mythictrpg.condition.api.ConditionNode;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Immutable data-pack definitions used by the server-authoritative Story Engine. */
public final class StoryDefinitions {
    private StoryDefinitions() {
    }

    public enum ActorType { GOD, ENTITY, FACTION }
    public enum ExistenceState { SEALED, ACTIVE, DEFEATED, DESTROYED }
    public enum AvailabilityState { AVAILABLE, ABSENT }
    public enum ScopeType { SERVER, PLAYER, TEAM }
    public enum NarrativeRole { SIDE, MAIN_ENTRY, MAIN, WORLD, ENDING }
    public enum TriggerMode { IMMEDIATE, LATCHED }
    public enum RepeatType { ONCE, LIMITED, COOLDOWN }
    public enum ResolutionPolicy { FIXED, FIRST_MATCH, WEIGHTED_ONCE, PLAYER_CHOICE }
    public enum FailurePolicy { BLOCK_AND_REPORT, CANCEL }
    public enum DisclosureMode { FULL, PARTIAL, WITHHOLD, CONDITIONAL, COVER_STORY }
    public enum EffectType {
        SET_FACT,
        SET_ACTOR_STATE,
        MOVE_ACTOR,
        GRANT_KNOWLEDGE,
        SCHEDULE_EVENT,
        RELATION_TRANSITION,
        EMIT_PRESENTATION
    }
    public enum ScopeSelector { EVENT_SCOPE, SERVER, TRIGGER_PLAYER, TRIGGER_TEAM }
    public enum HolderSelector {
        TRIGGER_PLAYER,
        ACTOR,
        TRIGGER_TEAM,
        ALL_PLAYERS_CURRENT_AND_FUTURE
    }
    public enum AudienceSelector { TRIGGER_PLAYER, TRIGGER_TEAM, ALL_ONLINE_PLAYERS }
    public enum PresentationKind {
        DIRECT_DIALOGUE, CONVERSATION_CONTEXT, OBSERVABLE_CLUE, INVESTIGATION_RESULT, JOURNAL_ONLY
    }
    public enum GenerationPolicy { SCRIPTED_ONLY, AI_PARAPHRASE, AI_FLAVOR }
    public enum CanonicalDeliveryPolicy { FLAVOR_ONLY, FACT_BEARING, CRITICAL }
    public enum OfflineDeliveryPolicy { DROP_FLAVOR, ON_NEXT_LOGIN, JOURNAL_IMMEDIATELY }

    public enum RoomAudienceMode { OWNER_ONLY, PRIVATE_ROOM, PUBLIC, NEVER }

    /** Explicit permission to disclose narrative text, never permission to accept its action. */
    public record RoomAudiencePolicy(RoomAudienceMode mode, Set<ResourceLocation> allowedGodIds) {
        public RoomAudiencePolicy { Objects.requireNonNull(mode); allowedGodIds = Set.copyOf(allowedGodIds); }
        public static RoomAudiencePolicy ownerOnly() { return new RoomAudiencePolicy(RoomAudienceMode.OWNER_ONLY, Set.of()); }
        public boolean permits(boolean publicRoom, int playerCount, Set<ResourceLocation> gods, ResourceLocation speaker) {
            if (playerCount < 1 || mode == RoomAudienceMode.NEVER) return false;
            if (mode == RoomAudienceMode.OWNER_ONLY && (publicRoom || playerCount != 1 || gods.size() != 1 || !gods.contains(speaker))) return false;
            if (mode == RoomAudienceMode.PRIVATE_ROOM && publicRoom) return false;
            return allowedGodIds.isEmpty() || gods.stream().filter(god -> !god.equals(speaker)).allMatch(allowedGodIds::contains);
        }
    }

    public record ActorDefinition(ResourceLocation id, ActorType type, Optional<ResourceLocation> godId,
            ExistenceState initialExistence, AvailabilityState initialAvailability,
            Optional<ResourceLocation> initialLocationId, Set<ResourceLocation> tags) {
        public ActorDefinition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            godId = copy(godId, "godId");
            Objects.requireNonNull(initialExistence, "initialExistence");
            Objects.requireNonNull(initialAvailability, "initialAvailability");
            initialLocationId = copy(initialLocationId, "initialLocationId");
            tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
            if ((initialExistence == ExistenceState.SEALED
                    || initialExistence == ExistenceState.DEFEATED
                    || initialExistence == ExistenceState.DESTROYED)
                    && initialAvailability != AvailabilityState.ABSENT) {
                throw new IllegalArgumentException("Non-active Story actor must initially be absent: " + id);
            }
            if (type == ActorType.GOD && godId.isEmpty()) {
                throw new IllegalArgumentException("GOD Story actor requires godId: " + id);
            }
            if (type != ActorType.GOD && godId.isPresent()) {
                throw new IllegalArgumentException("Only GOD Story actors may declare godId: " + id);
            }
        }
    }

    public record LocationDefinition(ResourceLocation id, String titleTranslationKey,
            Optional<ResourceLocation> dimensionId, Set<ResourceLocation> tags) {
        public LocationDefinition {
            Objects.requireNonNull(id, "id");
            titleTranslationKey = bounded(titleTranslationKey, "titleTranslationKey", 256);
            dimensionId = copy(dimensionId, "dimensionId");
            tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
        }
    }

    public record FactKnowledgeLevel(int level, String canonicalTranslationKey, Optional<RoomAudiencePolicy> disclosure) {
        public FactKnowledgeLevel(int level, String canonicalTranslationKey) { this(level, canonicalTranslationKey, Optional.empty()); }
        public FactKnowledgeLevel {
            disclosure = Objects.requireNonNull(disclosure);
            if (level < 1 || level > 32) {
                throw new IllegalArgumentException("Story fact level must be within 1..32");
            }
            canonicalTranslationKey = bounded(canonicalTranslationKey, "canonicalTranslationKey", 256);
        }
    }

    public record FactDefinition(ResourceLocation id, Set<ScopeType> allowedScopes, boolean defaultValue,
            String adminSummary, List<FactKnowledgeLevel> levels, Set<ResourceLocation> keywords) {
        public FactDefinition {
            Objects.requireNonNull(id, "id");
            allowedScopes = Set.copyOf(Objects.requireNonNull(allowedScopes, "allowedScopes"));
            if (allowedScopes.isEmpty()) {
                throw new IllegalArgumentException("Story fact requires at least one allowed scope: " + id);
            }
            adminSummary = bounded(adminSummary, "adminSummary", 512);
            levels = List.copyOf(Objects.requireNonNull(levels, "levels"));
            if (levels.isEmpty()) {
                throw new IllegalArgumentException("Story fact requires at least one knowledge level: " + id);
            }
            for (int index = 0; index < levels.size(); index++) {
                if (levels.get(index).level() != index + 1) {
                    throw new IllegalArgumentException("Story fact levels must be consecutive from 1: " + id);
                }
            }
            keywords = Set.copyOf(Objects.requireNonNull(keywords, "keywords"));
        }

        public int maximumLevel() {
            return levels.size();
        }
    }

    public record CoverStoryDefinition(ResourceLocation id, List<String> canonicalTranslationKeys,
            Set<ResourceLocation> allowedSpeakerActorIds, RoomAudiencePolicy disclosure) {
        public CoverStoryDefinition(ResourceLocation id, List<String> keys, Set<ResourceLocation> speakers) {
            this(id, keys, speakers, RoomAudiencePolicy.ownerOnly());
        }
        public CoverStoryDefinition {
            Objects.requireNonNull(disclosure);
            Objects.requireNonNull(id, "id");
            canonicalTranslationKeys = List.copyOf(Objects.requireNonNull(
                    canonicalTranslationKeys, "canonicalTranslationKeys"));
            if (canonicalTranslationKeys.isEmpty() || canonicalTranslationKeys.size() > 8) {
                throw new IllegalArgumentException("Cover Story requires 1..8 translation keys: " + id);
            }
            canonicalTranslationKeys = canonicalTranslationKeys.stream()
                    .map(value -> bounded(value, "canonicalTranslationKeys", 256)).toList();
            allowedSpeakerActorIds = Set.copyOf(Objects.requireNonNull(
                    allowedSpeakerActorIds, "allowedSpeakerActorIds"));
            if (allowedSpeakerActorIds.isEmpty()) {
                throw new IllegalArgumentException("Cover Story requires at least one allowed speaker: " + id);
            }
        }
    }

    public record DisclosurePolicy(ResourceLocation id, DisclosureMode mode, OptionalInt maximumDisclosedLevel,
            Optional<ConditionNode> condition, Optional<ResourceLocation> lockedPolicyId,
            Optional<ResourceLocation> coverStoryId) {
        public DisclosurePolicy {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(mode, "mode");
            maximumDisclosedLevel = Objects.requireNonNull(maximumDisclosedLevel, "maximumDisclosedLevel");
            condition = copy(condition, "condition");
            lockedPolicyId = copy(lockedPolicyId, "lockedPolicyId");
            coverStoryId = copy(coverStoryId, "coverStoryId");
            if (maximumDisclosedLevel.isPresent() && maximumDisclosedLevel.getAsInt() < 1) {
                throw new IllegalArgumentException("maximumDisclosedLevel must be positive");
            }
            if (mode == DisclosureMode.CONDITIONAL && condition.isEmpty()) {
                throw new IllegalArgumentException("CONDITIONAL disclosure requires condition");
            }
            if (mode == DisclosureMode.COVER_STORY && coverStoryId.isEmpty()) {
                throw new IllegalArgumentException("COVER_STORY disclosure requires coverStoryId");
            }
        }
    }

    public record TriggerDefinition(ResourceLocation signalType, Optional<ResourceLocation> subjectId,
            TriggerMode mode, long expiresAfterTicks) {
        public TriggerDefinition {
            Objects.requireNonNull(signalType, "signalType");
            subjectId = copy(subjectId, "subjectId");
            Objects.requireNonNull(mode, "mode");
            if (mode == TriggerMode.IMMEDIATE && expiresAfterTicks != 0) {
                throw new IllegalArgumentException("IMMEDIATE Story trigger expiry must be zero");
            }
            if (mode == TriggerMode.LATCHED && (expiresAfterTicks == 0 || expiresAfterTicks < -1)) {
                throw new IllegalArgumentException("LATCHED expiry must be -1 or positive");
            }
        }
    }

    public record ActorBinding(String role, ResourceLocation actorId) {
        public ActorBinding {
            role = bounded(role, "role", 64).toLowerCase(Locale.ROOT);
            Objects.requireNonNull(actorId, "actorId");
        }
    }

    public record RepeatPolicy(RepeatType type, int maximumApplications, long cooldownTicks) {
        public RepeatPolicy {
            Objects.requireNonNull(type, "type");
            if (maximumApplications < 1 || maximumApplications > 1000) {
                throw new IllegalArgumentException("Story repeat maximumApplications must be within 1..1000");
            }
            if (cooldownTicks < 0) {
                throw new IllegalArgumentException("Story repeat cooldownTicks cannot be negative");
            }
            if (type == RepeatType.ONCE && (maximumApplications != 1 || cooldownTicks != 0)) {
                throw new IllegalArgumentException("ONCE repeat policy must be exactly one application");
            }
            if (type == RepeatType.COOLDOWN && cooldownTicks < 1) {
                throw new IllegalArgumentException("COOLDOWN repeat policy requires positive cooldownTicks");
            }
        }
    }

    public sealed interface Effect permits Effect.SetFact, Effect.SetActorState, Effect.MoveActor,
            Effect.GrantKnowledge, Effect.ScheduleEvent, Effect.RelationTransition, Effect.EmitPresentation {
        ResourceLocation effectId();
        EffectType type();

        record SetFact(ResourceLocation effectId, ResourceLocation factId, ScopeSelector scope,
                boolean value) implements Effect {
            public SetFact { requireEffect(effectId, factId, scope); }
            @Override public EffectType type() { return EffectType.SET_FACT; }
        }

        record SetActorState(ResourceLocation effectId, ResourceLocation actorId,
                Optional<ExistenceState> existence, Optional<AvailabilityState> availability) implements Effect {
            public SetActorState {
                requireEffect(effectId, actorId);
                existence = copy(existence, "existence");
                availability = copy(availability, "availability");
                if (existence.isEmpty() && availability.isEmpty()) {
                    throw new IllegalArgumentException("set_actor_state requires at least one change");
                }
            }
            @Override public EffectType type() { return EffectType.SET_ACTOR_STATE; }
        }

        record MoveActor(ResourceLocation effectId, ResourceLocation actorId,
                Optional<ResourceLocation> locationId) implements Effect {
            public MoveActor {
                requireEffect(effectId, actorId);
                locationId = copy(locationId, "locationId");
            }
            @Override public EffectType type() { return EffectType.MOVE_ACTOR; }
        }

        record GrantKnowledge(ResourceLocation effectId, HolderSelector holder,
                Optional<ResourceLocation> actorId, ResourceLocation factId, int level,
                ResourceLocation disclosurePolicyId) implements Effect {
            public GrantKnowledge {
                requireEffect(effectId, factId, holder, disclosurePolicyId);
                actorId = copy(actorId, "actorId");
                if (level < 1 || level > 32) {
                    throw new IllegalArgumentException("Story knowledge grant level must be within 1..32");
                }
                if ((holder == HolderSelector.ACTOR) != actorId.isPresent()) {
                    throw new IllegalArgumentException("ACTOR knowledge holder requires exactly one actorId");
                }
            }
            @Override public EffectType type() { return EffectType.GRANT_KNOWLEDGE; }
        }

        record ScheduleEvent(ResourceLocation effectId, ResourceLocation eventId,
                long delayTicks) implements Effect {
            public ScheduleEvent {
                requireEffect(effectId, eventId);
                if (delayTicks < 1 || delayTicks > 63_072_000L) {
                    throw new IllegalArgumentException("Story schedule delay must be within 1..63072000 ticks");
                }
            }
            @Override public EffectType type() { return EffectType.SCHEDULE_EVENT; }
        }

        record RelationTransition(ResourceLocation effectId,
                ResourceLocation transitionId) implements Effect {
            public RelationTransition { requireEffect(effectId, transitionId); }
            @Override public EffectType type() { return EffectType.RELATION_TRANSITION; }
        }

        record EmitPresentation(ResourceLocation effectId, ResourceLocation presentationId,
                AudienceSelector audience) implements Effect {
            public EmitPresentation { requireEffect(effectId, presentationId, audience); }
            @Override public EffectType type() { return EffectType.EMIT_PRESENTATION; }
        }
    }

    public record OutcomeDefinition(ResourceLocation id, int weight,
            Optional<ConditionNode> additionalConditions, List<Effect> effects,
            Optional<String> displayName, Optional<String> description) {
        public OutcomeDefinition(ResourceLocation id, int weight,
                Optional<ConditionNode> additionalConditions, List<Effect> effects) {
            this(id, weight, additionalConditions, effects, Optional.empty(), Optional.empty());
        }

        public OutcomeDefinition {
            Objects.requireNonNull(id, "id");
            if (weight < 1 || weight > 1_000_000) {
                throw new IllegalArgumentException("Story outcome weight must be within 1..1000000");
            }
            additionalConditions = copy(additionalConditions, "additionalConditions");
            effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
            displayName = copy(displayName, "displayName")
                    .map(value -> bounded(value, "displayName", 120));
            description = copy(description, "description")
                    .map(value -> bounded(value, "description", 600));
            if (effects.isEmpty() || effects.size() > 64) {
                throw new IllegalArgumentException("Story outcome requires 1..64 effects");
            }
            long distinct = effects.stream().map(Effect::effectId).distinct().count();
            if (distinct != effects.size()) {
                throw new IllegalArgumentException("Duplicate Story effect ID in outcome " + id);
            }
        }
    }

    public record ChoicePolicy(long timeoutTicks, Optional<ResourceLocation> defaultOutcomeId,
            Optional<String> displayName, Optional<String> description) {
        public ChoicePolicy(long timeoutTicks, Optional<ResourceLocation> defaultOutcomeId) {
            this(timeoutTicks, defaultOutcomeId, Optional.empty(), Optional.empty());
        }

        public ChoicePolicy {
            defaultOutcomeId = copy(defaultOutcomeId, "defaultOutcomeId");
            displayName = copy(displayName, "displayName")
                    .map(value -> bounded(value, "choice displayName", 120));
            description = copy(description, "description")
                    .map(value -> bounded(value, "choice description", 600));
            if (timeoutTicks < -1 || timeoutTicks == 0) {
                throw new IllegalArgumentException("Choice timeout must be -1 or positive");
            }
            if ((timeoutTicks > 0) != defaultOutcomeId.isPresent()) {
                throw new IllegalArgumentException("Timed Story choice requires defaultOutcomeId");
            }
        }
    }

    public record EventDefinition(ResourceLocation id, NarrativeRole narrativeRole, ScopeType scope,
            List<TriggerDefinition> triggers, ConditionNode prerequisites, List<ActorBinding> actors,
            RepeatPolicy repeatPolicy, ResolutionPolicy resolutionPolicy,
            boolean randomNarrativeExplicitlyAllowed, List<OutcomeDefinition> outcomes,
            Optional<ChoicePolicy> choicePolicy, FailurePolicy failurePolicy, String fingerprint) {
        public EventDefinition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(narrativeRole, "narrativeRole");
            Objects.requireNonNull(scope, "scope");
            triggers = List.copyOf(Objects.requireNonNull(triggers, "triggers"));
            if (triggers.isEmpty() || triggers.size() > 16) {
                throw new IllegalArgumentException("Story event requires 1..16 triggers: " + id);
            }
            Objects.requireNonNull(prerequisites, "prerequisites");
            actors = List.copyOf(Objects.requireNonNull(actors, "actors"));
            Objects.requireNonNull(repeatPolicy, "repeatPolicy");
            Objects.requireNonNull(resolutionPolicy, "resolutionPolicy");
            outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
            if (outcomes.isEmpty() || outcomes.size() > 32) {
                throw new IllegalArgumentException("Story event requires 1..32 outcomes: " + id);
            }
            if (outcomes.stream().map(OutcomeDefinition::id).distinct().count() != outcomes.size()) {
                throw new IllegalArgumentException("Duplicate Story outcome ID: " + id);
            }
            choicePolicy = copy(choicePolicy, "choicePolicy");
            Objects.requireNonNull(failurePolicy, "failurePolicy");
            fingerprint = bounded(fingerprint, "fingerprint", 128);
            if ((narrativeRole == NarrativeRole.MAIN || narrativeRole == NarrativeRole.ENDING)
                    && resolutionPolicy == ResolutionPolicy.WEIGHTED_ONCE
                    && !randomNarrativeExplicitlyAllowed) {
                throw new IllegalArgumentException("MAIN/ENDING weighted event requires explicit random opt-in: " + id);
            }
            if ((resolutionPolicy == ResolutionPolicy.PLAYER_CHOICE) != choicePolicy.isPresent()) {
                throw new IllegalArgumentException("PLAYER_CHOICE requires choicePolicy and other policies forbid it: " + id);
            }
            if (resolutionPolicy == ResolutionPolicy.FIXED && outcomes.size() != 1) {
                throw new IllegalArgumentException("FIXED Story event requires exactly one outcome: " + id);
            }
        }
    }

    public record HookDefinition(ResourceLocation id, ResourceLocation targetEventId,
            Set<ResourceLocation> allowedSpeakerActorIds, ConditionNode availabilityConditions,
            ScopeType targetScope, String titleTranslationKey, String summaryTranslationKey,
            int cooldownTicks, int maximumAcceptances, boolean confirmationRequired, RoomAudiencePolicy disclosure) {
        public HookDefinition(ResourceLocation id, ResourceLocation event, Set<ResourceLocation> speakers,
                ConditionNode conditions, ScopeType scope, String title, String summary, int cooldown, int maximum, boolean confirmation) {
            this(id, event, speakers, conditions, scope, title, summary, cooldown, maximum, confirmation, RoomAudiencePolicy.ownerOnly());
        }
        public HookDefinition {
            Objects.requireNonNull(disclosure);
            requireEffect(id, targetEventId, targetScope);
            allowedSpeakerActorIds = Set.copyOf(Objects.requireNonNull(
                    allowedSpeakerActorIds, "allowedSpeakerActorIds"));
            Objects.requireNonNull(availabilityConditions, "availabilityConditions");
            titleTranslationKey = bounded(titleTranslationKey, "titleTranslationKey", 256);
            summaryTranslationKey = bounded(summaryTranslationKey, "summaryTranslationKey", 256);
            if (cooldownTicks < 0 || maximumAcceptances < 1 || maximumAcceptances > 1000) {
                throw new IllegalArgumentException("Invalid Story Hook cooldown or acceptance limit");
            }
        }
    }

    public record FactDisclosureRequest(ResourceLocation factId, int maximumLevel, boolean required) {
        public FactDisclosureRequest {
            Objects.requireNonNull(factId, "factId");
            if (maximumLevel < 1 || maximumLevel > 32) {
                throw new IllegalArgumentException("Fact disclosure maximumLevel must be within 1..32");
            }
        }
    }

    public record PresentationDefinition(ResourceLocation id, PresentationKind kind,
            GenerationPolicy generationPolicy, CanonicalDeliveryPolicy canonicalDeliveryPolicy,
            Optional<ResourceLocation> speakerActorId, List<FactDisclosureRequest> factRequests,
            List<String> fallbackTranslationKeys, Set<ResourceLocation> performanceTags,
            List<ResourceLocation> offeredHookIds, OfflineDeliveryPolicy offlineDeliveryPolicy,
            int maximumAiTurns, Optional<RoomAudiencePolicy> roomDisclosure) {
        public PresentationDefinition(ResourceLocation id, PresentationKind kind, GenerationPolicy generation,
                CanonicalDeliveryPolicy delivery, Optional<ResourceLocation> speaker, List<FactDisclosureRequest> facts,
                List<String> fallback, Set<ResourceLocation> performance, List<ResourceLocation> hooks,
                OfflineDeliveryPolicy offline, int maximum) {
            this(id, kind, generation, delivery, speaker, facts, fallback, performance, hooks, offline, maximum, Optional.empty());
        }
        public PresentationDefinition {
            roomDisclosure = Objects.requireNonNull(roomDisclosure);
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(generationPolicy, "generationPolicy");
            Objects.requireNonNull(canonicalDeliveryPolicy, "canonicalDeliveryPolicy");
            speakerActorId = copy(speakerActorId, "speakerActorId");
            factRequests = List.copyOf(Objects.requireNonNull(factRequests, "factRequests"));
            fallbackTranslationKeys = List.copyOf(Objects.requireNonNull(
                    fallbackTranslationKeys, "fallbackTranslationKeys"));
            if (fallbackTranslationKeys.isEmpty() || fallbackTranslationKeys.size() > 4) {
                throw new IllegalArgumentException("Story presentation requires 1..4 fallback keys: " + id);
            }
            fallbackTranslationKeys = fallbackTranslationKeys.stream()
                    .map(value -> bounded(value, "fallbackTranslationKeys", 256)).toList();
            performanceTags = Set.copyOf(Objects.requireNonNull(performanceTags, "performanceTags"));
            offeredHookIds = List.copyOf(Objects.requireNonNull(offeredHookIds, "offeredHookIds"));
            Objects.requireNonNull(offlineDeliveryPolicy, "offlineDeliveryPolicy");
            if (maximumAiTurns < 1 || maximumAiTurns > 4) {
                throw new IllegalArgumentException("maximumAiTurns must be within 1..4: " + id);
            }
            if (canonicalDeliveryPolicy == CanonicalDeliveryPolicy.CRITICAL
                    && generationPolicy != GenerationPolicy.SCRIPTED_ONLY) {
                throw new IllegalArgumentException("CRITICAL presentation must be SCRIPTED_ONLY: " + id);
            }
            if (canonicalDeliveryPolicy == CanonicalDeliveryPolicy.FACT_BEARING && factRequests.isEmpty()) {
                throw new IllegalArgumentException("FACT_BEARING presentation requires factRequests: " + id);
            }
            if (generationPolicy == GenerationPolicy.AI_FLAVOR && !factRequests.isEmpty()) {
                throw new IllegalArgumentException("AI_FLAVOR may not receive factRequests: " + id);
            }
            if (generationPolicy != GenerationPolicy.SCRIPTED_ONLY && speakerActorId.isEmpty()) {
                throw new IllegalArgumentException("AI presentation requires a speakerActorId: " + id);
            }
            if ((kind == PresentationKind.OBSERVABLE_CLUE || kind == PresentationKind.JOURNAL_ONLY)
                    && generationPolicy != GenerationPolicy.SCRIPTED_ONLY) {
                throw new IllegalArgumentException(kind + " must be SCRIPTED_ONLY: " + id);
            }
        }
    }

    private static <T> Optional<T> copy(Optional<T> value, String field) {
        return Objects.requireNonNull(value, field);
    }

    private static String bounded(String value, String field, int maximum) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " must be 1.." + maximum + " characters");
        }
        return value;
    }

    private static void requireEffect(Object... values) {
        for (Object value : values) {
            Objects.requireNonNull(value, "Story effect field");
        }
    }
}
