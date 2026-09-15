package com.sande.mythictrpg.condition.builtin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionContext;
import com.sande.mythictrpg.condition.api.ConditionDependency;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.api.ConditionScope;
import com.sande.mythictrpg.condition.registry.ConditionType;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import com.sande.mythictrpg.data.god.GodAccessService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class BuiltinConditionTypes {
    public static final ResourceLocation ALWAYS = id("always");
    public static final ResourceLocation ALL = id("all");
    public static final ResourceLocation ANY = id("any");
    public static final ResourceLocation NOT = id("not");
    public static final ResourceLocation GOD_AFFINITY = id("god_affinity");
    public static final ResourceLocation ITEM_EVER_OBTAINED = id("item_ever_obtained");
    public static final ResourceLocation BIOME = id("biome");
    public static final ResourceLocation TIME_RANGE = id("time_range");
    public static final ResourceLocation Y_RANGE = id("y_range");
    public static final ResourceLocation GOD_UNLOCKED = id("god_unlocked");
    public static final ConditionDependency PLAYER_AFFINITY_DEPENDENCY = dependency("player_affinity");
    public static final ConditionDependency GOD_DEFINITIONS_DEPENDENCY = dependency("god_definitions");
    public static final ConditionDependency PLAYER_ITEM_HISTORY_DEPENDENCY = dependency("player_item_history");
    public static final ConditionDependency PLAYER_ENVIRONMENT_DEPENDENCY = dependency("player_environment");
    public static final ConditionDependency BIOME_DEPENDENCY = dependency("biome");
    public static final ConditionDependency LEVEL_TIME_DEPENDENCY = dependency("level_time");
    public static final ConditionDependency PLAYER_POSITION_DEPENDENCY = dependency("player_position");
    public static final ConditionDependency Y_DEPENDENCY = dependency("y");
    public static final ConditionDependency WORLD_GOD_STATE_DEPENDENCY = dependency("world_god_state");

    private static final Set<ConditionScope> PLAYER_SCOPES = Set.of(
            ConditionScope.PLAYER,
            ConditionScope.ANY_PLAYER,
            ConditionScope.ALL_PLAYERS,
            ConditionScope.ANY_ONLINE_PLAYER
    );
    private static final Set<ConditionScope> PLAYER_ONLY = Set.of(ConditionScope.PLAYER);
    private static final Set<ConditionScope> WORLD_ONLY = Set.of(ConditionScope.WORLD);

    private BuiltinConditionTypes() {
    }

    public static void register(ConditionTypeRegistry registry) {
        registry.register(new ConditionType<>(ALWAYS, AlwaysCondition.class, Set.of(),
                (json, children) -> {
                    requireOnlyFields(json, Set.of("type"));
                    return new AlwaysCondition(ALWAYS);
                },
                (condition, context, children) -> ConditionResult.MATCH,
                condition -> Set.of()));
        registry.register(compositeType(ALL, BuiltinConditionTypes::evaluateAll));
        registry.register(compositeType(ANY, BuiltinConditionTypes::evaluateAny));
        registry.register(new ConditionType<>(NOT, CompositeCondition.class, Set.of(),
                (json, childParser) -> {
                    requireOnlyFields(json, Set.of("type", "condition"));
                    JsonElement child = required(json, "condition");
                    return new CompositeCondition(NOT, List.of(childParser.apply(child)));
                },
                (condition, context, children) -> children.evaluate(condition.children().getFirst(), context).negate(),
                condition -> Set.of()));
        registry.register(new ConditionType<>(GOD_AFFINITY, GodAffinityCondition.class, PLAYER_SCOPES,
                BuiltinConditionTypes::decodeGodAffinity,
                BuiltinConditionTypes::evaluateGodAffinity,
                condition -> condition.godCategory().isPresent()
                        ? Set.of(PLAYER_AFFINITY_DEPENDENCY, GOD_DEFINITIONS_DEPENDENCY)
                        : Set.of(PLAYER_AFFINITY_DEPENDENCY)));
        registry.register(new ConditionType<>(ITEM_EVER_OBTAINED, ItemEverObtainedCondition.class, PLAYER_SCOPES,
                BuiltinConditionTypes::decodeItemEverObtained,
                BuiltinConditionTypes::evaluateItemEverObtained,
                condition -> Set.of(PLAYER_ITEM_HISTORY_DEPENDENCY)));
        registry.register(new ConditionType<>(BIOME, BiomeCondition.class, PLAYER_ONLY,
                BuiltinConditionTypes::decodeBiome,
                BuiltinConditionTypes::evaluateBiome,
                condition -> Set.of(PLAYER_ENVIRONMENT_DEPENDENCY, BIOME_DEPENDENCY)));
        registry.register(new ConditionType<>(TIME_RANGE, TimeRangeCondition.class, PLAYER_ONLY,
                BuiltinConditionTypes::decodeTimeRange,
                BuiltinConditionTypes::evaluateTimeRange,
                condition -> Set.of(PLAYER_ENVIRONMENT_DEPENDENCY, LEVEL_TIME_DEPENDENCY)));
        registry.register(new ConditionType<>(Y_RANGE, YRangeCondition.class, PLAYER_ONLY,
                BuiltinConditionTypes::decodeYRange,
                BuiltinConditionTypes::evaluateYRange,
                condition -> Set.of(PLAYER_POSITION_DEPENDENCY, Y_DEPENDENCY)));
        registry.register(new ConditionType<>(GOD_UNLOCKED, GodUnlockedCondition.class, WORLD_ONLY,
                BuiltinConditionTypes::decodeGodUnlocked,
                BuiltinConditionTypes::evaluateGodUnlocked,
                condition -> Set.of(WORLD_GOD_STATE_DEPENDENCY, GOD_DEFINITIONS_DEPENDENCY)));
    }

    private static ConditionType<CompositeCondition> compositeType(ResourceLocation id,
            ConditionType.Evaluator<CompositeCondition> evaluator) {
        return new ConditionType<>(id, CompositeCondition.class, Set.of(),
                (json, childParser) -> {
                    requireOnlyFields(json, Set.of("type", "conditions"));
                    JsonElement value = required(json, "conditions");
                    if (!value.isJsonArray()) {
                        throw new JsonParseException("Field 'conditions' must be an array for " + id);
                    }
                    JsonArray array = value.getAsJsonArray();
                    if (array.isEmpty()) {
                        throw new JsonParseException("Condition " + id + " must contain at least one child");
                    }
                    List<ConditionNode> children = new ArrayList<>();
                    array.forEach(child -> children.add(childParser.apply(child)));
                    return new CompositeCondition(id, children);
                }, evaluator, condition -> Set.of());
    }

    private static GodAffinityCondition decodeGodAffinity(JsonObject json,
            Function<JsonElement, ConditionNode> childParser) {
        requireOnlyFields(json, Set.of("type", "scope", "god", "god_category", "minimum"));
        ConditionScope scope;
        try {
            scope = ConditionScope.parse(requiredString(json, "scope"));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException(exception.getMessage(), exception);
        }
        Optional<ResourceLocation> god = optionalId(json, "god");
        Optional<ResourceLocation> category = optionalId(json, "god_category");
        if (god.isPresent() == category.isPresent()) {
            throw new JsonParseException("god_affinity requires exactly one of 'god' or 'god_category'");
        }
        return new GodAffinityCondition(GOD_AFFINITY, scope, god, category, requiredInt(json, "minimum"));
    }

    private static ItemEverObtainedCondition decodeItemEverObtained(JsonObject json,
            Function<JsonElement, ConditionNode> childParser) {
        requireOnlyFields(json, Set.of("type", "scope", "item"));
        ConditionScope scope = requiredScope(json);
        ResourceLocation item = requiredId(json, "item");
        if (!BuiltInRegistries.ITEM.containsKey(item)) {
            throw new JsonParseException("Unknown registered item in field 'item': " + item);
        }
        return new ItemEverObtainedCondition(ITEM_EVER_OBTAINED, scope, item);
    }

    private static BiomeCondition decodeBiome(JsonObject json,
            Function<JsonElement, ConditionNode> childParser) {
        requireOnlyFields(json, Set.of("type", "scope", "biome"));
        return new BiomeCondition(BIOME, requiredScope(json), requiredId(json, "biome"));
    }

    private static TimeRangeCondition decodeTimeRange(JsonObject json,
            Function<JsonElement, ConditionNode> childParser) {
        requireOnlyFields(json, Set.of("type", "scope", "start", "end"));
        int start = requiredInt(json, "start");
        int end = requiredInt(json, "end");
        if (start < 0 || start >= 24000 || end < 0 || end >= 24000) {
            throw new JsonParseException("time_range start and end must be in range 0..23999");
        }
        if (start == end) {
            throw new JsonParseException("time_range start and end must differ");
        }
        return new TimeRangeCondition(TIME_RANGE, requiredScope(json), start, end);
    }

    private static YRangeCondition decodeYRange(JsonObject json,
            Function<JsonElement, ConditionNode> childParser) {
        requireOnlyFields(json, Set.of("type", "scope", "minimum", "maximum"));
        OptionalInt minimum = optionalInt(json, "minimum");
        OptionalInt maximum = optionalInt(json, "maximum");
        if (minimum.isEmpty() && maximum.isEmpty()) {
            throw new JsonParseException("y_range requires 'minimum', 'maximum', or both");
        }
        if (minimum.isPresent() && maximum.isPresent() && minimum.getAsInt() > maximum.getAsInt()) {
            throw new JsonParseException("y_range minimum must not exceed maximum");
        }
        return new YRangeCondition(Y_RANGE, requiredScope(json), minimum, maximum);
    }

    private static GodUnlockedCondition decodeGodUnlocked(JsonObject json,
            Function<JsonElement, ConditionNode> childParser) {
        requireOnlyFields(json, Set.of("type", "scope", "god"));
        return new GodUnlockedCondition(GOD_UNLOCKED, requiredScope(json), requiredId(json, "god"));
    }

    private static ConditionResult evaluateAll(CompositeCondition condition, ConditionContext context,
            ConditionType.ChildEvaluator children) {
        boolean unknown = false;
        for (ConditionNode child : condition.children()) {
            ConditionResult result = children.evaluate(child, context);
            if (result == ConditionResult.NO_MATCH) {
                return ConditionResult.NO_MATCH;
            }
            unknown |= result == ConditionResult.UNKNOWN;
        }
        return unknown ? ConditionResult.UNKNOWN : ConditionResult.MATCH;
    }

    private static ConditionResult evaluateAny(CompositeCondition condition, ConditionContext context,
            ConditionType.ChildEvaluator children) {
        boolean unknown = false;
        for (ConditionNode child : condition.children()) {
            ConditionResult result = children.evaluate(child, context);
            if (result == ConditionResult.MATCH) {
                return ConditionResult.MATCH;
            }
            unknown |= result == ConditionResult.UNKNOWN;
        }
        return unknown ? ConditionResult.UNKNOWN : ConditionResult.NO_MATCH;
    }

    private static ConditionResult evaluateGodAffinity(GodAffinityCondition condition, ConditionContext context,
            ConditionType.ChildEvaluator children) {
        if (!context.players().isReady()) {
            return ConditionResult.UNKNOWN;
        }
        return switch (condition.conditionScope()) {
            case PLAYER -> context.targetPlayerId()
                    .map(context.players()::find)
                    .flatMap(Function.identity())
                    .map(profile -> matchesAffinity(profile, condition, context))
                    .orElse(ConditionResult.UNKNOWN);
            case ANY_PLAYER -> evaluateAnyProfile(context.players().activeProfiles(), condition, context);
            case ALL_PLAYERS -> evaluateAllProfiles(context.players().activeProfiles(), condition, context);
            case ANY_ONLINE_PLAYER -> {
                Map<UUID, PlayerMythProfile> active = context.players().activeProfiles();
                Map<UUID, PlayerMythProfile> online = new java.util.LinkedHashMap<>();
                context.server().onlinePlayerIds().forEach(id -> {
                    PlayerMythProfile profile = active.get(id);
                    if (profile != null) {
                        online.put(id, profile);
                    }
                });
                yield evaluateAnyProfile(online, condition, context);
            }
            case WORLD, SERVER -> ConditionResult.UNKNOWN;
        };
    }

    private static ConditionResult evaluateItemEverObtained(ItemEverObtainedCondition condition,
            ConditionContext context, ConditionType.ChildEvaluator children) {
        if (!context.players().isReady()) {
            return ConditionResult.UNKNOWN;
        }
        return switch (condition.conditionScope()) {
            case PLAYER -> context.targetPlayerId()
                    .flatMap(context.players()::find)
                    .map(profile -> matchesItemHistory(profile, condition))
                    .orElse(ConditionResult.UNKNOWN);
            case ANY_PLAYER -> evaluateAnyItemProfile(context.players().activeProfiles(), condition);
            case ALL_PLAYERS -> evaluateAllItemProfiles(context.players().activeProfiles(), condition);
            case ANY_ONLINE_PLAYER -> {
                Map<UUID, PlayerMythProfile> active = context.players().activeProfiles();
                Map<UUID, PlayerMythProfile> online = new java.util.LinkedHashMap<>();
                context.server().onlinePlayerIds().forEach(id -> {
                    PlayerMythProfile profile = active.get(id);
                    if (profile != null) {
                        online.put(id, profile);
                    }
                });
                yield evaluateAnyItemProfile(online, condition);
            }
            case WORLD, SERVER -> ConditionResult.UNKNOWN;
        };
    }

    private static ConditionResult evaluateBiome(BiomeCondition condition, ConditionContext context,
            ConditionType.ChildEvaluator children) {
        return context.environment()
                .flatMap(environment -> environment.biomeId())
                .map(id -> id.equals(condition.biome()) ? ConditionResult.MATCH : ConditionResult.NO_MATCH)
                .orElse(ConditionResult.UNKNOWN);
    }

    private static ConditionResult evaluateTimeRange(TimeRangeCondition condition, ConditionContext context,
            ConditionType.ChildEvaluator children) {
        return context.environment().map(environment -> {
            int time = environment.dayTime();
            boolean matches = condition.start() < condition.end()
                    ? time >= condition.start() && time < condition.end()
                    : time >= condition.start() || time < condition.end();
            return matches ? ConditionResult.MATCH : ConditionResult.NO_MATCH;
        }).orElse(ConditionResult.UNKNOWN);
    }

    private static ConditionResult evaluateYRange(YRangeCondition condition, ConditionContext context,
            ConditionType.ChildEvaluator children) {
        return context.environment().map(environment -> {
            int y = environment.position().getY();
            boolean aboveMinimum = condition.minimum().isEmpty() || y >= condition.minimum().getAsInt();
            boolean belowMaximum = condition.maximum().isEmpty() || y <= condition.maximum().getAsInt();
            return aboveMinimum && belowMaximum ? ConditionResult.MATCH : ConditionResult.NO_MATCH;
        }).orElse(ConditionResult.UNKNOWN);
    }

    private static ConditionResult evaluateGodUnlocked(GodUnlockedCondition condition, ConditionContext context,
            ConditionType.ChildEvaluator children) {
        return GodAccessService.effectiveUnlockStatus(condition.god(), context.world(), context.gods())
                .map(unlocked -> unlocked ? ConditionResult.MATCH : ConditionResult.NO_MATCH)
                .orElse(ConditionResult.UNKNOWN);
    }

    private static ConditionResult evaluateAnyItemProfile(Map<UUID, PlayerMythProfile> profiles,
            ItemEverObtainedCondition condition) {
        if (profiles.isEmpty()) {
            return ConditionResult.NO_MATCH;
        }
        return profiles.values().stream().anyMatch(profile -> profile.obtainedItems().contains(condition.item()))
                ? ConditionResult.MATCH : ConditionResult.NO_MATCH;
    }

    private static ConditionResult evaluateAllItemProfiles(Map<UUID, PlayerMythProfile> profiles,
            ItemEverObtainedCondition condition) {
        if (profiles.isEmpty()) {
            return ConditionResult.NO_MATCH;
        }
        return profiles.values().stream().allMatch(profile -> profile.obtainedItems().contains(condition.item()))
                ? ConditionResult.MATCH : ConditionResult.NO_MATCH;
    }

    private static ConditionResult matchesItemHistory(PlayerMythProfile profile,
            ItemEverObtainedCondition condition) {
        return profile.obtainedItems().contains(condition.item())
                ? ConditionResult.MATCH : ConditionResult.NO_MATCH;
    }

    private static ConditionResult evaluateAnyProfile(Map<UUID, PlayerMythProfile> profiles,
            GodAffinityCondition condition, ConditionContext context) {
        if (profiles.isEmpty()) {
            return ConditionResult.NO_MATCH;
        }
        boolean unknown = false;
        for (PlayerMythProfile profile : profiles.values()) {
            ConditionResult result = matchesAffinity(profile, condition, context);
            if (result == ConditionResult.MATCH) {
                return ConditionResult.MATCH;
            }
            unknown |= result == ConditionResult.UNKNOWN;
        }
        return unknown ? ConditionResult.UNKNOWN : ConditionResult.NO_MATCH;
    }

    private static ConditionResult evaluateAllProfiles(Map<UUID, PlayerMythProfile> profiles,
            GodAffinityCondition condition, ConditionContext context) {
        if (profiles.isEmpty()) {
            return ConditionResult.NO_MATCH;
        }
        boolean unknown = false;
        for (PlayerMythProfile profile : profiles.values()) {
            ConditionResult result = matchesAffinity(profile, condition, context);
            if (result == ConditionResult.NO_MATCH) {
                return ConditionResult.NO_MATCH;
            }
            unknown |= result == ConditionResult.UNKNOWN;
        }
        return unknown ? ConditionResult.UNKNOWN : ConditionResult.MATCH;
    }

    private static ConditionResult matchesAffinity(PlayerMythProfile profile, GodAffinityCondition condition,
            ConditionContext context) {
        if (condition.god().isPresent()) {
            return profile.affinities().getOrDefault(condition.god().orElseThrow(), 0) >= condition.minimum()
                    ? ConditionResult.MATCH : ConditionResult.NO_MATCH;
        }
        if (!context.gods().isReady()) {
            return ConditionResult.UNKNOWN;
        }
        Set<ResourceLocation> members = context.gods().godsInCategory(condition.godCategory().orElseThrow());
        if (members.isEmpty()) {
            return ConditionResult.NO_MATCH;
        }
        return members.stream().anyMatch(god -> profile.affinities().getOrDefault(god, 0) >= condition.minimum())
                ? ConditionResult.MATCH : ConditionResult.NO_MATCH;
    }

    private static JsonElement required(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || value.isJsonNull()) {
            throw new JsonParseException("Missing required field '" + field + "'");
        }
        return value;
    }

    private static String requiredString(JsonObject json, String field) {
        JsonElement value = required(json, field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("Field '" + field + "' must be a string");
        }
        return value.getAsString();
    }

    private static int requiredInt(JsonObject json, String field) {
        JsonElement value = required(json, field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("Field '" + field + "' must be an integer");
        }
        int parsed = value.getAsInt();
        if (value.getAsDouble() != parsed) {
            throw new JsonParseException("Field '" + field + "' must be an integer");
        }
        return parsed;
    }

    private static OptionalInt optionalInt(JsonObject json, String field) {
        if (!json.has(field)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(requiredInt(json, field));
    }

    private static ConditionScope requiredScope(JsonObject json) {
        try {
            return ConditionScope.parse(requiredString(json, "scope"));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException(exception.getMessage(), exception);
        }
    }

    private static ResourceLocation requiredId(JsonObject json, String field) {
        return optionalId(json, field)
                .orElseThrow(() -> new JsonParseException("Missing required field '" + field + "'"));
    }

    private static Optional<ResourceLocation> optionalId(JsonObject json, String field) {
        if (!json.has(field)) {
            return Optional.empty();
        }
        String value = requiredString(json, field);
        ResourceLocation id = ResourceLocation.tryParse(value);
        int separator = value.indexOf(':');
        if (id == null || separator <= 0 || separator == value.length() - 1) {
            throw new JsonParseException("Field '" + field + "' must be a namespaced ID");
        }
        return Optional.of(id);
    }

    private static void requireOnlyFields(JsonObject json, Set<String> allowed) {
        json.keySet().forEach(field -> {
            if (!allowed.contains(field)) {
                throw new JsonParseException("Unknown field '" + field + "'");
            }
        });
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    private static ConditionDependency dependency(String path) {
        return new ConditionDependency(id(path));
    }
}
