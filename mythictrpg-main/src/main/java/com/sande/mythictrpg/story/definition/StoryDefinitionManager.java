package com.sande.mythictrpg.story.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.engine.ConditionTreeParser;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import com.sande.mythictrpg.relation.GodRelationTransitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitionSnapshot.SignalKey;
import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiFunction;

/** Strict, atomic loader for every Story Engine definition family. */
public final class StoryDefinitionManager extends SimplePreparableReloadListener<StoryDefinitionSnapshot> {
    public static final StoryDefinitionManager INSTANCE = new StoryDefinitionManager();

    private static final FileToIdConverter ACTORS = converter("story_actors");
    private static final FileToIdConverter LOCATIONS = converter("story_locations");
    private static final FileToIdConverter FACTS = converter("story_facts");
    private static final FileToIdConverter COVERS = converter("story_cover_stories");
    private static final FileToIdConverter DISCLOSURES = converter("story_disclosure_policies");
    private static final FileToIdConverter EVENTS = converter("story_events");
    private static final FileToIdConverter HOOKS = converter("story_hooks");
    private static final FileToIdConverter PRESENTATIONS = converter("story_presentations");
    private static final ConditionTreeParser CONDITIONS = new ConditionTreeParser(ConditionTypeRegistry.INSTANCE);

    private volatile StoryDefinitionSnapshot snapshot = StoryDefinitionSnapshot.empty();

    private StoryDefinitionManager() {
    }

    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(this);
    }

    public StoryDefinitionSnapshot snapshot() {
        return snapshot;
    }

    public Optional<EventDefinition> event(ResourceLocation id) { return Optional.ofNullable(snapshot.events().get(id)); }
    public Optional<ActorDefinition> actor(ResourceLocation id) { return Optional.ofNullable(snapshot.actors().get(id)); }
    public Optional<FactDefinition> fact(ResourceLocation id) { return Optional.ofNullable(snapshot.facts().get(id)); }
    public Optional<DisclosurePolicy> disclosure(ResourceLocation id) {
        return Optional.ofNullable(snapshot.disclosurePolicies().get(id));
    }
    public Optional<HookDefinition> hook(ResourceLocation id) { return Optional.ofNullable(snapshot.hooks().get(id)); }
    public Optional<PresentationDefinition> presentation(ResourceLocation id) {
        return Optional.ofNullable(snapshot.presentations().get(id));
    }

    @Override
    protected StoryDefinitionSnapshot prepare(ResourceManager resources, ProfilerFiller profiler) {
        List<String> errors = new ArrayList<>();
        Map<ResourceLocation, LocationDefinition> locations = load(resources, LOCATIONS,
                StoryDefinitionManager::parseLocation, errors);
        Map<ResourceLocation, ActorDefinition> actors = load(resources, ACTORS,
                StoryDefinitionManager::parseActor, errors);
        Map<ResourceLocation, FactDefinition> facts = load(resources, FACTS,
                StoryDefinitionManager::parseFact, errors);
        Map<ResourceLocation, CoverStoryDefinition> covers = load(resources, COVERS,
                StoryDefinitionManager::parseCover, errors);
        Map<ResourceLocation, DisclosurePolicy> disclosures = load(resources, DISCLOSURES,
                StoryDefinitionManager::parseDisclosure, errors);
        Map<ResourceLocation, PresentationDefinition> presentations = load(resources, PRESENTATIONS,
                StoryDefinitionManager::parsePresentation, errors);
        Map<ResourceLocation, EventDefinition> events = load(resources, EVENTS,
                StoryDefinitionManager::parseEvent, errors);
        Map<ResourceLocation, HookDefinition> hooks = load(resources, HOOKS,
                StoryDefinitionManager::parseHook, errors);

        validateReferences(actors, locations, facts, covers, disclosures, events, hooks, presentations, errors);
        if (!errors.isEmpty()) {
            errors.forEach(error -> MythicTrpg.LOGGER.error("Story definition error: {}", error));
            throw new IllegalStateException("Rejected Story definition reload with " + errors.size() + " error(s)");
        }

        Map<SignalKey, List<EventDefinition>> index = new LinkedHashMap<>();
        events.values().forEach(definition -> definition.triggers().forEach(trigger ->
                index.computeIfAbsent(new SignalKey(trigger.signalType(), trigger.subjectId()),
                        ignored -> new ArrayList<>()).add(definition)));
        return new StoryDefinitionSnapshot(actors, locations, facts, covers, disclosures, events, hooks,
                presentations, index, snapshot.generation() + 1);
    }

    @Override
    protected void apply(StoryDefinitionSnapshot prepared, ResourceManager resources, ProfilerFiller profiler) {
        snapshot = prepared;
        MythicTrpg.LOGGER.info("Loaded Story definitions: {} actors, {} locations, {} facts, {} events, {} hooks",
                snapshot.actors().size(), snapshot.locations().size(), snapshot.facts().size(),
                snapshot.events().size(), snapshot.hooks().size());
    }

    private static <T> Map<ResourceLocation, T> load(ResourceManager resources, FileToIdConverter converter,
            BiFunction<ResourceLocation, JsonObject, T> parser, List<String> errors) {
        Map<ResourceLocation, T> result = new LinkedHashMap<>();
        converter.listMatchingResources(resources).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ResourceLocation id = converter.fileToId(entry.getKey());
                    try (Reader reader = entry.getValue().openAsReader()) {
                        JsonElement root = JsonParser.parseReader(reader);
                        if (!root.isJsonObject()) throw new IllegalArgumentException("Root must be an object");
                        T value = parser.apply(id, root.getAsJsonObject());
                        if (result.putIfAbsent(id, value) != null) {
                            throw new IllegalArgumentException("Duplicate definition " + id);
                        }
                    } catch (Exception exception) {
                        errors.add(id + ": " + exception.getMessage());
                    }
                });
        return Map.copyOf(result);
    }

    private static LocationDefinition parseLocation(ResourceLocation id, JsonObject json) {
        fields(json, "schemaVersion", "titleTranslationKey", "dimensionId", "tags"); version(json);
        return new LocationDefinition(id, string(json, "titleTranslationKey"), optionalId(json, "dimensionId"),
                ids(json, "tags"));
    }

    private static ActorDefinition parseActor(ResourceLocation id, JsonObject json) {
        fields(json, "schemaVersion", "type", "godId", "initialExistence", "initialAvailability",
                "initialLocationId", "tags"); version(json);
        return new ActorDefinition(id, enumValue(json, "type", ActorType.class), optionalId(json, "godId"),
                enumValue(json, "initialExistence", ExistenceState.class),
                enumValue(json, "initialAvailability", AvailabilityState.class),
                optionalId(json, "initialLocationId"), ids(json, "tags"));
    }

    private static FactDefinition parseFact(ResourceLocation id, JsonObject json) {
        fields(json, "schemaVersion", "allowedScopes", "defaultValue", "adminSummary", "levels", "keywords");
        version(json);
        Set<ScopeType> scopes = enums(json, "allowedScopes", ScopeType.class);
        List<FactKnowledgeLevel> levels = new ArrayList<>();
        int expected = 1;
        for (JsonElement raw : array(json, "levels")) {
            JsonObject level = object(raw, "levels entry");
            fields(level, "level", "canonicalTranslationKey", "disclosure");
            FactKnowledgeLevel parsed = new FactKnowledgeLevel(integer(level, "level"),
                    string(level, "canonicalTranslationKey"), optionalRoomDisclosure(level, "disclosure"));
            if (parsed.level() != expected++) throw new IllegalArgumentException("Fact levels must start at 1");
            levels.add(parsed);
        }
        return new FactDefinition(id, scopes, bool(json, "defaultValue", false), string(json, "adminSummary"),
                levels, ids(json, "keywords"));
    }

    private static CoverStoryDefinition parseCover(ResourceLocation id, JsonObject json) {
        fields(json, "schemaVersion", "canonicalTranslationKeys", "allowedSpeakerActorIds", "disclosure"); version(json);
        return new CoverStoryDefinition(id, strings(json, "canonicalTranslationKeys"),
                ids(json, "allowedSpeakerActorIds"), optionalRoomDisclosure(json, "disclosure").orElseGet(RoomAudiencePolicy::ownerOnly));
    }

    private static DisclosurePolicy parseDisclosure(ResourceLocation id, JsonObject json) {
        fields(json, "schemaVersion", "mode", "maximumDisclosedLevel", "condition", "lockedPolicyId",
                "coverStoryId"); version(json);
        Optional<ConditionNode> condition = json.has("condition")
                ? Optional.of(CONDITIONS.parse(json.get("condition"))) : Optional.empty();
        OptionalInt max = json.has("maximumDisclosedLevel")
                ? OptionalInt.of(integer(json, "maximumDisclosedLevel")) : OptionalInt.empty();
        return new DisclosurePolicy(id, enumValue(json, "mode", DisclosureMode.class), max, condition,
                optionalId(json, "lockedPolicyId"), optionalId(json, "coverStoryId"));
    }

    private static PresentationDefinition parsePresentation(ResourceLocation id, JsonObject json) {
        fields(json, "schemaVersion", "kind", "generationPolicy", "canonicalDeliveryPolicy",
                "speakerActorId", "factRequests", "fallbackTranslationKeys", "performanceTags",
                "offeredHookIds", "offlineDeliveryPolicy", "maximumAiTurns", "roomDisclosure"); version(json);
        List<FactDisclosureRequest> requests = new ArrayList<>();
        for (JsonElement raw : optionalArray(json, "factRequests")) {
            JsonObject value = object(raw, "factRequests entry");
            fields(value, "factId", "maximumLevel", "required");
            requests.add(new FactDisclosureRequest(id(value, "factId"), integer(value, "maximumLevel"),
                    bool(value, "required", false)));
        }
        return new PresentationDefinition(id,
                json.has("kind") ? enumValue(json, "kind", PresentationKind.class)
                        : PresentationKind.DIRECT_DIALOGUE,
                json.has("generationPolicy") ? enumValue(json, "generationPolicy", GenerationPolicy.class)
                        : GenerationPolicy.SCRIPTED_ONLY,
                json.has("canonicalDeliveryPolicy")
                        ? enumValue(json, "canonicalDeliveryPolicy", CanonicalDeliveryPolicy.class)
                        : CanonicalDeliveryPolicy.FLAVOR_ONLY,
                optionalId(json, "speakerActorId"), requests, strings(json, "fallbackTranslationKeys"),
                ids(json, "performanceTags"), List.copyOf(ids(json, "offeredHookIds")),
                json.has("offlineDeliveryPolicy")
                        ? enumValue(json, "offlineDeliveryPolicy", OfflineDeliveryPolicy.class)
                        : OfflineDeliveryPolicy.ON_NEXT_LOGIN,
                json.has("maximumAiTurns") ? integer(json, "maximumAiTurns") : 1,
                optionalRoomDisclosure(json, "roomDisclosure"));
    }

    private static EventDefinition parseEvent(ResourceLocation id, JsonObject json) {
        fields(json, "schemaVersion", "narrativeRole", "scope", "triggers", "prerequisites", "actors",
                "repeatPolicy", "resolutionPolicy", "randomNarrativeExplicitlyAllowed", "outcomes",
                "choicePolicy", "failurePolicy"); version(json);
        List<TriggerDefinition> triggers = new ArrayList<>();
        for (JsonElement raw : array(json, "triggers")) {
            JsonObject value = object(raw, "triggers entry");
            fields(value, "signal", "subject", "mode", "expiresAfterTicks");
            TriggerMode mode = enumValue(value, "mode", TriggerMode.class);
            long expiry = value.has("expiresAfterTicks") ? longInteger(value, "expiresAfterTicks")
                    : mode == TriggerMode.IMMEDIATE ? 0 : -1;
            triggers.add(new TriggerDefinition(id(value, "signal"), optionalId(value, "subject"), mode, expiry));
        }
        List<ActorBinding> actors = new ArrayList<>();
        for (JsonElement raw : optionalArray(json, "actors")) {
            JsonObject value = object(raw, "actors entry");
            fields(value, "role", "actor");
            actors.add(new ActorBinding(string(value, "role"), id(value, "actor")));
        }
        JsonObject repeat = object(json.get("repeatPolicy"), "repeatPolicy");
        fields(repeat, "type", "maximumApplications", "cooldownTicks");
        RepeatType repeatType = enumValue(repeat, "type", RepeatType.class);
        RepeatPolicy repeatPolicy = new RepeatPolicy(repeatType,
                repeat.has("maximumApplications") ? integer(repeat, "maximumApplications")
                        : repeatType == RepeatType.ONCE ? 1 : 2,
                repeat.has("cooldownTicks") ? longInteger(repeat, "cooldownTicks") : 0);
        List<OutcomeDefinition> outcomes = new ArrayList<>();
        for (JsonElement raw : array(json, "outcomes")) {
            JsonObject value = object(raw, "outcomes entry");
            fields(value, "id", "weight", "additionalConditions", "effects", "displayName", "description");
            Optional<ConditionNode> extra = value.has("additionalConditions")
                    ? Optional.of(CONDITIONS.parse(value.get("additionalConditions"))) : Optional.empty();
            List<Effect> effects = new ArrayList<>();
            for (JsonElement effect : array(value, "effects")) {
                effects.add(parseEffect(object(effect, "effects entry")));
            }
            outcomes.add(new OutcomeDefinition(id(value, "id"), value.has("weight")
                    ? integer(value, "weight") : 1, extra, effects,
                    value.has("displayName") ? Optional.of(string(value, "displayName")) : Optional.empty(),
                    value.has("description") ? Optional.of(string(value, "description")) : Optional.empty()));
        }
        Optional<ChoicePolicy> choice = Optional.empty();
        if (json.has("choicePolicy")) {
            JsonObject value = object(json.get("choicePolicy"), "choicePolicy");
            fields(value, "timeoutTicks", "defaultOutcomeId", "displayName", "description");
            choice = Optional.of(new ChoicePolicy(longInteger(value, "timeoutTicks"),
                    optionalId(value, "defaultOutcomeId"),
                    value.has("displayName") ? Optional.of(string(value, "displayName")) : Optional.empty(),
                    value.has("description") ? Optional.of(string(value, "description")) : Optional.empty()));
        }
        return new EventDefinition(id, enumValue(json, "narrativeRole", NarrativeRole.class),
                enumValue(json, "scope", ScopeType.class), triggers,
                CONDITIONS.parse(json.get("prerequisites")), actors, repeatPolicy,
                enumValue(json, "resolutionPolicy", ResolutionPolicy.class),
                bool(json, "randomNarrativeExplicitlyAllowed", false), outcomes, choice,
                enumValue(json, "failurePolicy", FailurePolicy.class), fingerprint(json));
    }

    private static Effect parseEffect(JsonObject json) {
        ResourceLocation effectId = id(json, "effectId");
        EffectType type = enumValue(json, "type", EffectType.class);
        return switch (type) {
            case SET_FACT -> {
                fields(json, "effectId", "type", "fact", "scope", "value");
                yield new Effect.SetFact(effectId, id(json, "fact"),
                        enumValue(json, "scope", ScopeSelector.class), bool(json, "value", false));
            }
            case SET_ACTOR_STATE -> {
                fields(json, "effectId", "type", "actor", "existence", "availability");
                yield new Effect.SetActorState(effectId, id(json, "actor"),
                        optionalEnum(json, "existence", ExistenceState.class),
                        optionalEnum(json, "availability", AvailabilityState.class));
            }
            case MOVE_ACTOR -> {
                fields(json, "effectId", "type", "actor", "location");
                yield new Effect.MoveActor(effectId, id(json, "actor"), optionalId(json, "location"));
            }
            case GRANT_KNOWLEDGE -> {
                fields(json, "effectId", "type", "holder", "actor", "fact", "level", "disclosurePolicy");
                yield new Effect.GrantKnowledge(effectId, enumValue(json, "holder", HolderSelector.class),
                        optionalId(json, "actor"), id(json, "fact"), integer(json, "level"),
                        id(json, "disclosurePolicy"));
            }
            case SCHEDULE_EVENT -> {
                fields(json, "effectId", "type", "event", "delayTicks");
                yield new Effect.ScheduleEvent(effectId, id(json, "event"), longInteger(json, "delayTicks"));
            }
            case RELATION_TRANSITION -> {
                fields(json, "effectId", "type", "transition");
                yield new Effect.RelationTransition(effectId, id(json, "transition"));
            }
            case EMIT_PRESENTATION -> {
                fields(json, "effectId", "type", "presentation", "audience");
                yield new Effect.EmitPresentation(effectId, id(json, "presentation"),
                        enumValue(json, "audience", AudienceSelector.class));
            }
        };
    }

    private static HookDefinition parseHook(ResourceLocation id, JsonObject json) {
        fields(json, "schemaVersion", "targetEventId", "allowedSpeakerActorIds", "availabilityConditions",
                "targetScope", "titleTranslationKey", "summaryTranslationKey", "cooldownTicks",
                "maximumAcceptances", "confirmationRequired", "disclosure"); version(json);
        return new HookDefinition(id, id(json, "targetEventId"), ids(json, "allowedSpeakerActorIds"),
                CONDITIONS.parse(json.get("availabilityConditions")), enumValue(json, "targetScope", ScopeType.class),
                string(json, "titleTranslationKey"), string(json, "summaryTranslationKey"),
                integer(json, "cooldownTicks"), integer(json, "maximumAcceptances"),
                bool(json, "confirmationRequired", true), optionalRoomDisclosure(json, "disclosure").orElseGet(RoomAudiencePolicy::ownerOnly));
    }

    private static Optional<RoomAudiencePolicy> optionalRoomDisclosure(JsonObject json, String field) {
        if (!json.has(field)) return Optional.empty();
        JsonObject rule = object(json.get(field), field);
        fields(rule, "mode", "allowedGodIds");
        return Optional.of(new RoomAudiencePolicy(enumValue(rule, "mode", RoomAudienceMode.class), ids(rule, "allowedGodIds")));
    }

    private static void validateReferences(Map<ResourceLocation, ActorDefinition> actors,
            Map<ResourceLocation, LocationDefinition> locations, Map<ResourceLocation, FactDefinition> facts,
            Map<ResourceLocation, CoverStoryDefinition> covers,
            Map<ResourceLocation, DisclosurePolicy> disclosures, Map<ResourceLocation, EventDefinition> events,
            Map<ResourceLocation, HookDefinition> hooks,
            Map<ResourceLocation, PresentationDefinition> presentations, List<String> errors) {
        actors.values().forEach(actor -> actor.initialLocationId().filter(value -> !locations.containsKey(value))
                .ifPresent(value -> errors.add(actor.id() + " references missing location " + value)));
        covers.values().forEach(cover -> cover.allowedSpeakerActorIds().stream()
                .filter(value -> !actors.containsKey(value))
                .forEach(value -> errors.add(cover.id() + " references missing actor " + value)));
        disclosures.values().forEach(policy -> {
            policy.lockedPolicyId().filter(value -> !disclosures.containsKey(value))
                    .ifPresent(value -> errors.add(policy.id() + " references missing disclosure " + value));
            policy.coverStoryId().filter(value -> !covers.containsKey(value))
                    .ifPresent(value -> errors.add(policy.id() + " references missing cover story " + value));
        });
        detectDisclosureCycles(disclosures, errors);
        presentations.values().forEach(value -> {
            value.speakerActorId().filter(id -> !actors.containsKey(id))
                    .ifPresent(missing -> errors.add(value.id() + " references missing speaker " + missing));
            value.factRequests().forEach(request -> {
                FactDefinition fact = facts.get(request.factId());
                if (fact == null) errors.add(value.id() + " references missing fact " + request.factId());
                else if (request.maximumLevel() > fact.maximumLevel())
                    errors.add(value.id() + " disclosure level exceeds fact maximum: " + request.factId());
            });
            value.offeredHookIds().stream().filter(id -> !hooks.containsKey(id))
                    .forEach(id -> errors.add(value.id() + " references missing hook " + id));
        });
        events.values().forEach(event -> {
            event.actors().stream().filter(binding -> !actors.containsKey(binding.actorId()))
                    .forEach(binding -> errors.add(event.id() + " references missing actor " + binding.actorId()));
            event.choicePolicy().flatMap(ChoicePolicy::defaultOutcomeId)
                    .filter(id -> event.outcomes().stream().noneMatch(outcome -> outcome.id().equals(id)))
                    .ifPresent(value -> errors.add(event.id() + " references missing default outcome " + value));
            event.outcomes().forEach(outcome -> outcome.effects().forEach(effect -> validateEffect(
                    event, effect, actors, locations, facts, disclosures, events, presentations, errors)));
        });
        hooks.values().forEach(hook -> {
            if (!events.containsKey(hook.targetEventId())) {
                errors.add(hook.id() + " references missing target event " + hook.targetEventId());
            } else if (events.get(hook.targetEventId()).scope() != hook.targetScope()) {
                errors.add(hook.id() + " targetScope does not match target event scope");
            } else if (events.get(hook.targetEventId()).triggers().stream().noneMatch(trigger ->
                    trigger.signalType().equals(com.sande.mythictrpg.story.signal.StorySignalTypes.STORY_HOOK_ACCEPTED)
                            && trigger.subjectId().filter(hook.id()::equals).isPresent())) {
                errors.add(hook.id() + " target event has no matching story_hook_accepted trigger");
            }
            hook.allowedSpeakerActorIds().stream().filter(id -> !actors.containsKey(id))
                    .forEach(id -> errors.add(hook.id() + " references missing actor " + id));
        });
    }

    private static void validateEffect(EventDefinition event, Effect effect,
            Map<ResourceLocation, ActorDefinition> actors, Map<ResourceLocation, LocationDefinition> locations,
            Map<ResourceLocation, FactDefinition> facts, Map<ResourceLocation, DisclosurePolicy> disclosures,
            Map<ResourceLocation, EventDefinition> events,
            Map<ResourceLocation, PresentationDefinition> presentations, List<String> errors) {
        String prefix = event.id() + "/" + effect.effectId() + ": ";
        if (effect instanceof Effect.SetFact value) {
            FactDefinition fact = facts.get(value.factId());
            if (fact == null) errors.add(prefix + "missing fact " + value.factId());
            else if (value.scope() == ScopeSelector.SERVER && !fact.allowedScopes().contains(ScopeType.SERVER))
                errors.add(prefix + "fact does not allow SERVER scope");
        } else if (effect instanceof Effect.SetActorState value) {
            if (!actors.containsKey(value.actorId())) errors.add(prefix + "missing actor " + value.actorId());
        } else if (effect instanceof Effect.MoveActor value) {
            if (!actors.containsKey(value.actorId())) errors.add(prefix + "missing actor " + value.actorId());
            value.locationId().filter(id -> !locations.containsKey(id))
                    .ifPresent(id -> errors.add(prefix + "missing location " + id));
        } else if (effect instanceof Effect.GrantKnowledge value) {
            FactDefinition fact = facts.get(value.factId());
            if (fact == null) errors.add(prefix + "missing fact " + value.factId());
            else if (value.level() > fact.maximumLevel()) errors.add(prefix + "knowledge level exceeds fact maximum");
            if (!disclosures.containsKey(value.disclosurePolicyId()))
                errors.add(prefix + "missing disclosure " + value.disclosurePolicyId());
            value.actorId().filter(id -> !actors.containsKey(id))
                    .ifPresent(id -> errors.add(prefix + "missing actor " + id));
        } else if (effect instanceof Effect.ScheduleEvent value) {
            if (!events.containsKey(value.eventId())) errors.add(prefix + "missing scheduled event " + value.eventId());
        } else if (effect instanceof Effect.RelationTransition value) {
            if (!GodRelationTransitionManager.INSTANCE.ids().isEmpty()
                    && !GodRelationTransitionManager.INSTANCE.ids().contains(value.transitionId())) {
                errors.add(prefix + "missing relation transition " + value.transitionId());
            }
        } else if (effect instanceof Effect.EmitPresentation value) {
            if (!presentations.containsKey(value.presentationId()))
                errors.add(prefix + "missing presentation " + value.presentationId());
        }
    }

    private static void detectDisclosureCycles(Map<ResourceLocation, DisclosurePolicy> policies,
            List<String> errors) {
        for (ResourceLocation start : policies.keySet()) {
            Set<ResourceLocation> seen = new LinkedHashSet<>();
            ResourceLocation current = start;
            while (current != null && seen.add(current)) {
                current = policies.get(current) == null ? null
                        : policies.get(current).lockedPolicyId().orElse(null);
            }
            if (current != null) errors.add("Disclosure policy cycle from " + start + " through " + current);
        }
    }

    private static FileToIdConverter converter(String folder) {
        return FileToIdConverter.json("mythictrpg/" + folder);
    }

    private static void version(JsonObject json) {
        if (integer(json, "schemaVersion") != 1) throw new IllegalArgumentException("Unsupported schemaVersion");
    }

    private static void fields(JsonObject json, String... allowed) {
        Set<String> set = Set.of(allowed);
        json.keySet().forEach(key -> {
            if (!set.contains(key)) throw new IllegalArgumentException("Unknown field '" + key + "'");
        });
    }

    private static JsonObject object(JsonElement value, String field) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(field + " must be an object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject json, String field) {
        if (!json.has(field) || !json.get(field).isJsonArray())
            throw new IllegalArgumentException("Missing array '" + field + "'");
        return json.getAsJsonArray(field);
    }

    private static JsonArray optionalArray(JsonObject json, String field) {
        return json.has(field) ? array(json, field) : new JsonArray();
    }

    private static String string(JsonObject json, String field) {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()
                || !json.getAsJsonPrimitive(field).isString())
            throw new IllegalArgumentException("Missing string '" + field + "'");
        String value = json.get(field).getAsString().trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Blank string '" + field + "'");
        return value;
    }

    private static int integer(JsonObject json, String field) {
        long value = longInteger(json, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException(field + " is outside integer range");
        return (int) value;
    }

    private static long longInteger(JsonObject json, String field) {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()
                || !json.getAsJsonPrimitive(field).isNumber())
            throw new IllegalArgumentException("Missing integer '" + field + "'");
        double raw = json.get(field).getAsDouble();
        long value = json.get(field).getAsLong();
        if (!Double.isFinite(raw) || raw != value) throw new IllegalArgumentException(field + " must be an integer");
        return value;
    }

    private static boolean bool(JsonObject json, String field, boolean fallback) {
        if (!json.has(field)) return fallback;
        if (!json.get(field).isJsonPrimitive() || !json.getAsJsonPrimitive(field).isBoolean())
            throw new IllegalArgumentException(field + " must be boolean");
        return json.get(field).getAsBoolean();
    }

    private static ResourceLocation id(JsonObject json, String field) {
        String raw = string(json, field);
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null || !raw.contains(":")) throw new IllegalArgumentException("Invalid ID in " + field + ": " + raw);
        return id;
    }

    private static Optional<ResourceLocation> optionalId(JsonObject json, String field) {
        return json.has(field) ? Optional.of(id(json, field)) : Optional.empty();
    }

    private static Set<ResourceLocation> ids(JsonObject json, String field) {
        if (!json.has(field)) return Set.of();
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (JsonElement value : array(json, field)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException(field + " entries must be strings");
            String raw = value.getAsString();
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null || !raw.contains(":")) throw new IllegalArgumentException("Invalid ID in " + field);
            if (!result.add(id)) throw new IllegalArgumentException("Duplicate ID in " + field + ": " + id);
        }
        return Set.copyOf(result);
    }

    private static List<String> strings(JsonObject json, String field) {
        List<String> result = new ArrayList<>();
        for (JsonElement value : array(json, field)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException(field + " entries must be strings");
            String text = value.getAsString().trim();
            if (text.isEmpty()) throw new IllegalArgumentException("Blank value in " + field);
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static <E extends Enum<E>> E enumValue(JsonObject json, String field, Class<E> type) {
        try { return Enum.valueOf(type, string(json, field).toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown " + field, exception); }
    }

    private static <E extends Enum<E>> Optional<E> optionalEnum(JsonObject json, String field, Class<E> type) {
        return json.has(field) ? Optional.of(enumValue(json, field, type)) : Optional.empty();
    }

    private static <E extends Enum<E>> Set<E> enums(JsonObject json, String field, Class<E> type) {
        Set<E> result = new LinkedHashSet<>();
        for (JsonElement value : array(json, field)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException(field + " entries must be strings");
            E parsed;
            try { parsed = Enum.valueOf(type, value.getAsString().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown value in " + field); }
            if (!result.add(parsed)) throw new IllegalArgumentException("Duplicate value in " + field);
        }
        return Set.copyOf(result);
    }

    private static String fingerprint(JsonObject json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    json.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
