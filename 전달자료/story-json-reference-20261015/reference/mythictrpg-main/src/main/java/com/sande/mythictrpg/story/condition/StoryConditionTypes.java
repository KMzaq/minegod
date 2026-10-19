package com.sande.mythictrpg.story.condition;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.ConditionDependency;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.registry.ConditionType;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import com.sande.mythictrpg.story.api.StoryStateView.HolderType;
import com.sande.mythictrpg.story.api.StoryStateView.StoryKnowledgeHolder;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.definition.StoryDefinitions.AvailabilityState;
import com.sande.mythictrpg.story.definition.StoryDefinitions.ExistenceState;
import com.sande.mythictrpg.story.definition.StoryDefinitions.ScopeSelector;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class StoryConditionTypes {
    public static final ResourceLocation STORY_FACT = id("story_fact");
    public static final ResourceLocation STORY_EVENT = id("story_event");
    public static final ResourceLocation STORY_ACTOR_STATE = id("story_actor_state");
    public static final ResourceLocation STORY_ACTOR_LOCATION = id("story_actor_location");
    public static final ResourceLocation STORY_LOCATION_EMPTY = id("story_location_empty");
    public static final ResourceLocation STORY_KNOWLEDGE = id("story_knowledge");
    public static final ConditionDependency STORY_FACT_DEPENDENCY = dependency("story_facts");
    public static final ConditionDependency STORY_EVENT_DEPENDENCY = dependency("story_events");
    public static final ConditionDependency STORY_ACTOR_DEPENDENCY = dependency("story_actors");
    public static final ConditionDependency STORY_KNOWLEDGE_DEPENDENCY = dependency("story_knowledge");

    private StoryConditionTypes() {}

    public static void register(ConditionTypeRegistry registry) {
        registry.register(new ConditionType<>(STORY_FACT, StoryFactNode.class, Set.of(),
                (json, children) -> {
                    fields(json, "type", "fact", "scope", "expected");
                    return new StoryFactNode(STORY_FACT, resource(json, "fact"),
                            enumValue(json, "scope", ScopeSelector.class), bool(json, "expected", true));
                }, (node, context, children) -> scope(node.scopeSelector(), context)
                        .map(storyScope -> context.story().fact(storyScope, node.factId()) == node.expected()
                                ? ConditionResult.MATCH : ConditionResult.NO_MATCH)
                        .orElse(ConditionResult.UNKNOWN), node -> Set.of(STORY_FACT_DEPENDENCY)));
        registry.register(new ConditionType<>(STORY_EVENT, StoryEventNode.class, Set.of(),
                (json, children) -> {
                    fields(json, "type", "event", "scope", "status", "outcome");
                    return new StoryEventNode(STORY_EVENT, resource(json, "event"),
                            enumValue(json, "scope", ScopeSelector.class), optionalString(json, "status"),
                            optionalResource(json, "outcome"));
                }, (node, context, children) -> scope(node.scopeSelector(), context).map(storyScope ->
                        context.story().latestEvent(node.eventId(), storyScope).map(event -> {
                            boolean status = node.status().map(value -> event.status().equals(value)).orElse(true);
                            boolean outcome = node.outcomeId().map(value -> event.selectedOutcomeId()
                                    .map(value::equals).orElse(false)).orElse(true);
                            return status && outcome ? ConditionResult.MATCH : ConditionResult.NO_MATCH;
                        }).orElse(ConditionResult.NO_MATCH)).orElse(ConditionResult.UNKNOWN),
                node -> Set.of(STORY_EVENT_DEPENDENCY)));
        registry.register(new ConditionType<>(STORY_ACTOR_STATE, StoryActorStateNode.class, Set.of(),
                (json, children) -> {
                    fields(json, "type", "actor", "existence", "availability");
                    return new StoryActorStateNode(STORY_ACTOR_STATE, resource(json, "actor"),
                            optionalEnum(json, "existence", ExistenceState.class),
                            optionalEnum(json, "availability", AvailabilityState.class));
                }, (node, context, children) -> {
                    if (!context.story().isReady()) return ConditionResult.UNKNOWN;
                    return context.story().actor(node.actorId()).map(actor ->
                            node.existence().map(value -> actor.existence() == value).orElse(true)
                                    && node.availability().map(value -> actor.availability() == value).orElse(true)
                                    ? ConditionResult.MATCH : ConditionResult.NO_MATCH)
                            .orElse(ConditionResult.UNKNOWN);
                }, node -> Set.of(STORY_ACTOR_DEPENDENCY)));
        registry.register(new ConditionType<>(STORY_ACTOR_LOCATION, StoryActorLocationNode.class, Set.of(),
                (json, children) -> {
                    fields(json, "type", "actor", "location");
                    return new StoryActorLocationNode(STORY_ACTOR_LOCATION, resource(json, "actor"),
                            resource(json, "location"));
                }, (node, context, children) -> {
                    if (!context.story().isReady()) return ConditionResult.UNKNOWN;
                    return context.story().actor(node.actorId()).map(actor -> actor.locationId()
                                    .map(node.locationId()::equals).orElse(false)
                                    ? ConditionResult.MATCH : ConditionResult.NO_MATCH)
                            .orElse(ConditionResult.UNKNOWN);
                }, node -> Set.of(STORY_ACTOR_DEPENDENCY)));
        registry.register(new ConditionType<>(STORY_LOCATION_EMPTY, StoryLocationEmptyNode.class, Set.of(),
                (json, children) -> {
                    fields(json, "type", "location");
                    return new StoryLocationEmptyNode(STORY_LOCATION_EMPTY, resource(json, "location"));
                }, (node, context, children) -> !context.story().isReady() ? ConditionResult.UNKNOWN
                        : context.story().actorsAt(node.locationId()).isEmpty()
                                ? ConditionResult.MATCH : ConditionResult.NO_MATCH,
                node -> Set.of(STORY_ACTOR_DEPENDENCY)));
        registry.register(new ConditionType<>(STORY_KNOWLEDGE, StoryKnowledgeNode.class, Set.of(),
                (json, children) -> {
                    fields(json, "type", "holder", "actor", "fact", "minimumLevel");
                    HolderType holderType = enumValue(json, "holder", HolderType.class);
                    Optional<ResourceLocation> actor = optionalResource(json, "actor");
                    if ((holderType == HolderType.ACTOR) != actor.isPresent())
                        throw new JsonParseException("ACTOR story_knowledge holder requires actor");
                    return new StoryKnowledgeNode(STORY_KNOWLEDGE, holderType, actor,
                            resource(json, "fact"), integer(json, "minimumLevel"));
                }, (node, context, children) -> {
                    StoryKnowledgeHolder holder;
                    if (node.holderType() == HolderType.ACTOR) {
                        holder = StoryKnowledgeHolder.actor(node.actorId().orElseThrow());
                    } else {
                        UUID player = context.targetPlayerId().orElse(null);
                        if (player == null) return ConditionResult.UNKNOWN;
                        holder = StoryKnowledgeHolder.player(player);
                    }
                    return context.story().isReady()
                            && context.story().knowledgeLevel(holder, node.factId()) >= node.minimumLevel()
                            ? ConditionResult.MATCH : context.story().isReady()
                                    ? ConditionResult.NO_MATCH : ConditionResult.UNKNOWN;
                }, node -> Set.of(STORY_KNOWLEDGE_DEPENDENCY)));
    }

    public record StoryFactNode(ResourceLocation typeId, ResourceLocation factId,
            ScopeSelector scopeSelector, boolean expected) implements ConditionNode {}
    public record StoryEventNode(ResourceLocation typeId, ResourceLocation eventId,
            ScopeSelector scopeSelector, Optional<String> status,
            Optional<ResourceLocation> outcomeId) implements ConditionNode {}
    public record StoryActorStateNode(ResourceLocation typeId, ResourceLocation actorId,
            Optional<ExistenceState> existence, Optional<AvailabilityState> availability) implements ConditionNode {}
    public record StoryActorLocationNode(ResourceLocation typeId, ResourceLocation actorId,
            ResourceLocation locationId) implements ConditionNode {}
    public record StoryLocationEmptyNode(ResourceLocation typeId,
            ResourceLocation locationId) implements ConditionNode {}
    public record StoryKnowledgeNode(ResourceLocation typeId, HolderType holderType,
            Optional<ResourceLocation> actorId, ResourceLocation factId, int minimumLevel) implements ConditionNode {
        public StoryKnowledgeNode {
            if (minimumLevel < 1 || minimumLevel > 32)
                throw new IllegalArgumentException("minimumLevel must be within 1..32");
        }
    }

    private static Optional<StoryScopeKey> scope(ScopeSelector selector, ConditionContext context) {
        if (!context.story().isReady()) return Optional.empty();
        return switch (selector) {
            case SERVER -> Optional.of(StoryScopeKey.server());
            case EVENT_SCOPE -> context.storyScope();
            case TRIGGER_PLAYER -> context.targetPlayerId().map(StoryScopeKey::player);
            case TRIGGER_TEAM -> context.storyScope().filter(value ->
                    value.type() == com.sande.mythictrpg.story.definition.StoryDefinitions.ScopeType.TEAM);
        };
    }

    private static void fields(JsonObject json, String... allowed) {
        Set<String> values = Set.of(allowed);
        json.keySet().forEach(key -> {
            if (!values.contains(key)) throw new JsonParseException("Unknown field '" + key + "'");
        });
    }
    private static String string(JsonObject json, String field) {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()
                || !json.getAsJsonPrimitive(field).isString())
            throw new JsonParseException("Missing string '" + field + "'");
        String value = json.get(field).getAsString().trim();
        if (value.isEmpty()) throw new JsonParseException("Blank string '" + field + "'");
        return value;
    }
    private static Optional<String> optionalString(JsonObject json, String field) {
        return json.has(field) ? Optional.of(string(json, field).toUpperCase(java.util.Locale.ROOT))
                : Optional.empty();
    }
    private static ResourceLocation resource(JsonObject json, String field) {
        String value = string(json, field); ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !value.contains(":")) throw new JsonParseException("Invalid ID in " + field);
        return id;
    }
    private static Optional<ResourceLocation> optionalResource(JsonObject json, String field) {
        return json.has(field) ? Optional.of(resource(json, field)) : Optional.empty();
    }
    private static int integer(JsonObject json, String field) {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()
                || !json.getAsJsonPrimitive(field).isNumber())
            throw new JsonParseException("Missing integer '" + field + "'");
        double raw = json.get(field).getAsDouble(); int value = json.get(field).getAsInt();
        if (!Double.isFinite(raw) || raw != value) throw new JsonParseException(field + " must be integer");
        return value;
    }
    private static boolean bool(JsonObject json, String field, boolean fallback) {
        if (!json.has(field)) return fallback;
        if (!json.get(field).isJsonPrimitive() || !json.getAsJsonPrimitive(field).isBoolean())
            throw new JsonParseException(field + " must be boolean");
        return json.get(field).getAsBoolean();
    }
    private static <E extends Enum<E>> E enumValue(JsonObject json, String field, Class<E> type) {
        try { return Enum.valueOf(type, string(json, field).toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new JsonParseException("Unknown " + field, exception); }
    }
    private static <E extends Enum<E>> Optional<E> optionalEnum(JsonObject json, String field, Class<E> type) {
        return json.has(field) ? Optional.of(enumValue(json, field, type)) : Optional.empty();
    }
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mythictrpg", path);
    }
    private static ConditionDependency dependency(String path) { return new ConditionDependency(id(path)); }
}
