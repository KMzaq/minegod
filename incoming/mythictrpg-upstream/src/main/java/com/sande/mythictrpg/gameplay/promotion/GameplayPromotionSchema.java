package com.sande.mythictrpg.gameplay.promotion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.observation.FeedingOutcome;
import com.sande.mythictrpg.gameplay.sampling.VanillaStatisticKey;
import com.sande.mythictrpg.gameplay.sampling.WatchedMetricDefinition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class GameplayPromotionSchema {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema_version", "observation", "signal", "priority", "attempt_cooldown_ticks", "match");
    private static final Set<String> VANILLA_THRESHOLD_FIELDS = Set.of(
            "stat_type", "stat", "metric", "threshold", "sampling_interval_ticks");
    private static final Set<String> METRIC_FIELDS = Set.of("type", "subject");
    private static final Set<String> BLOCK_BROKEN_FIELDS = Set.of("block", "dimension");
    private static final Set<String> MATURE_CROP_FIELDS = Set.of("crop");
    private static final Set<String> ENTITY_KILLED_FIELDS = Set.of("entity", "dimension");
    private static final Set<String> ITEM_FIRST_OBTAINED_FIELDS = Set.of("item");
    private static final Set<String> ANIMAL_FED_FIELDS = Set.of("entity", "food", "outcome");
    private static final Set<String> ANIMAL_BRED_FIELDS = Set.of("child", "parents");
    private static final Set<String> PLAYER_DIED_FIELDS = Set.of("damage_type");

    private GameplayPromotionSchema() {
    }

    static GameplayPromotionDefinition parse(ResourceLocation id, JsonObject json,
            Map<ResourceLocation, GameplayPromotionAdapter<?, ?>> adapters) {
        rejectUnknownFields(json, ROOT_FIELDS, "gameplay promotion");
        int version = requiredInt(json, "schema_version");
        if (version != GameplayPromotionDefinition.CURRENT_SCHEMA_VERSION) {
            if (version > GameplayPromotionDefinition.CURRENT_SCHEMA_VERSION) {
                throw new JsonParseException("Gameplay promotion " + id
                        + " uses unknown future schema_version " + version + " (supported: "
                        + GameplayPromotionDefinition.CURRENT_SCHEMA_VERSION + ")");
            }
            throw new JsonParseException("Gameplay promotion " + id
                    + " uses unsupported schema_version " + version);
        }

        ResourceLocation observation = requiredId(json, "observation");
        GameplayPromotionAdapter<?, ?> adapter = adapters.get(observation);
        if (adapter == null) {
            throw new JsonParseException("No gameplay promotion adapter is registered for observation "
                    + observation);
        }
        ResourceLocation signal = requiredId(json, "signal");
        int priority = requiredInt(json, "priority");
        long cooldown = optionalLong(json, "attempt_cooldown_ticks",
                GameplayPromotionDefinition.DEFAULT_ATTEMPT_COOLDOWN_TICKS);
        JsonElement matchValue = required(json, "match");
        if (!matchValue.isJsonObject()) {
            throw new JsonParseException("Field 'match' must be an object");
        }

        try {
            return new GameplayPromotionDefinition(id, observation, signal, priority, cooldown,
                    adapter.parseMatcher(matchValue.getAsJsonObject()));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException("Invalid gameplay promotion " + id + ": "
                    + exception.getMessage(), exception);
        }
    }

    static VanillaStatThresholdPromotionMatcher parseVanillaThresholdMatcher(JsonObject json) {
        rejectUnknownFields(json, VANILLA_THRESHOLD_FIELDS, "Vanilla threshold match");
        ResourceLocation statType = requiredId(json, "stat_type");
        if (!statType.equals(VanillaStatisticKey.CUSTOM_STAT_TYPE)) {
            throw new JsonParseException("Only minecraft:custom statistics are supported: " + statType);
        }
        ResourceLocation statistic = requiredId(json, "stat");
        if (!BuiltInRegistries.CUSTOM_STAT.containsKey(statistic)) {
            throw new JsonParseException("Unsupported Vanilla custom statistic: " + statistic);
        }
        GameplayMetricKey metric = parseMetric(required(json, "metric"));
        long threshold = requiredLong(json, "threshold");
        int interval = requiredInt(json, "sampling_interval_ticks");
        if (threshold < 1 || threshold > Integer.MAX_VALUE) {
            throw new JsonParseException("Field 'threshold' must be between 1 and " + Integer.MAX_VALUE);
        }
        if (interval < WatchedMetricDefinition.MIN_INTERVAL_TICKS
                || interval > WatchedMetricDefinition.MAX_INTERVAL_TICKS) {
            throw new JsonParseException("Field 'sampling_interval_ticks' must be between "
                    + WatchedMetricDefinition.MIN_INTERVAL_TICKS + " and "
                    + WatchedMetricDefinition.MAX_INTERVAL_TICKS);
        }
        return new VanillaStatThresholdPromotionMatcher(
                new VanillaStatisticKey(statType, statistic), metric, threshold, interval);
    }

    static BlockBrokenPromotionMatcher parseBlockBrokenMatcher(JsonObject json) {
        rejectUnknownFields(json, BLOCK_BROKEN_FIELDS, "block_broken match");
        Optional<ResourceLocation> block = optionalId(json, "block");
        block.ifPresent(id -> requireRegistered(id, BuiltInRegistries.BLOCK.containsKey(id), "block"));
        return new BlockBrokenPromotionMatcher(block, optionalId(json, "dimension"));
    }

    static MatureCropHarvestPromotionMatcher parseMatureCropHarvestMatcher(JsonObject json) {
        rejectUnknownFields(json, MATURE_CROP_FIELDS, "mature_crop_harvested match");
        Optional<ResourceLocation> crop = optionalId(json, "crop");
        crop.ifPresent(id -> requireRegistered(id, BuiltInRegistries.BLOCK.containsKey(id), "crop"));
        return new MatureCropHarvestPromotionMatcher(crop);
    }

    static EntityKilledPromotionMatcher parseEntityKilledMatcher(JsonObject json) {
        rejectUnknownFields(json, ENTITY_KILLED_FIELDS, "entity_killed match");
        Optional<ResourceLocation> entity = optionalId(json, "entity");
        entity.ifPresent(id -> requireRegistered(
                id, BuiltInRegistries.ENTITY_TYPE.containsKey(id), "entity"));
        return new EntityKilledPromotionMatcher(entity, optionalId(json, "dimension"));
    }

    static ItemFirstObtainedPromotionMatcher parseItemFirstObtainedMatcher(JsonObject json) {
        rejectUnknownFields(json, ITEM_FIRST_OBTAINED_FIELDS, "item_first_obtained match");
        Optional<ResourceLocation> item = optionalId(json, "item");
        item.ifPresent(id -> requireRegistered(id, BuiltInRegistries.ITEM.containsKey(id), "item"));
        return new ItemFirstObtainedPromotionMatcher(item);
    }

    static AnimalFedPromotionMatcher parseAnimalFedMatcher(JsonObject json) {
        rejectUnknownFields(json, ANIMAL_FED_FIELDS, "animal_fed match");
        Optional<ResourceLocation> entity = optionalId(json, "entity");
        entity.ifPresent(id -> requireRegistered(
                id, BuiltInRegistries.ENTITY_TYPE.containsKey(id), "entity"));
        Optional<ResourceLocation> food = optionalId(json, "food");
        food.ifPresent(id -> requireRegistered(id, BuiltInRegistries.ITEM.containsKey(id), "food"));
        return new AnimalFedPromotionMatcher(entity, food, optionalFeedingOutcome(json, "outcome"));
    }

    static AnimalBredPromotionMatcher parseAnimalBredMatcher(JsonObject json) {
        rejectUnknownFields(json, ANIMAL_BRED_FIELDS, "animal_bred match");
        Optional<ResourceLocation> child = optionalId(json, "child");
        child.ifPresent(id -> requireRegistered(
                id, BuiltInRegistries.ENTITY_TYPE.containsKey(id), "child"));

        JsonElement parentsValue = json.get("parents");
        Optional<AnimalParentPair> parents = Optional.empty();
        if (parentsValue != null) {
            if (parentsValue.isJsonNull() || !parentsValue.isJsonArray()) {
                throw new JsonParseException("Field 'parents' must be an array of exactly two entity IDs");
            }
            var array = parentsValue.getAsJsonArray();
            if (array.size() != 2) {
                throw new JsonParseException("Field 'parents' must contain exactly two entity IDs");
            }
            ResourceLocation first = idElement(array.get(0), "parents[0]");
            ResourceLocation second = idElement(array.get(1), "parents[1]");
            requireRegistered(first, BuiltInRegistries.ENTITY_TYPE.containsKey(first), "parents[0]");
            requireRegistered(second, BuiltInRegistries.ENTITY_TYPE.containsKey(second), "parents[1]");
            parents = Optional.of(AnimalParentPair.of(first, second));
        }
        return new AnimalBredPromotionMatcher(child, parents);
    }

    static PlayerDiedPromotionMatcher parsePlayerDiedMatcher(JsonObject json) {
        rejectUnknownFields(json, PLAYER_DIED_FIELDS, "player_died match");
        if (!json.has("damage_type")) {
            return new PlayerDiedPromotionMatcher(DamageTypeCriterion.Any.INSTANCE);
        }
        JsonElement value = json.get("damage_type");
        if (value.isJsonNull()) {
            return new PlayerDiedPromotionMatcher(DamageTypeCriterion.Missing.INSTANCE);
        }
        return new PlayerDiedPromotionMatcher(new DamageTypeCriterion.Exact(
                idElement(value, "damage_type")));
    }

    private static GameplayMetricKey parseMetric(JsonElement value) {
        if (!value.isJsonObject()) {
            throw new JsonParseException("Field 'metric' must be an object");
        }
        JsonObject metric = value.getAsJsonObject();
        rejectUnknownFields(metric, METRIC_FIELDS, "metric");
        ResourceLocation type = requiredId(metric, "type");
        Optional<ResourceLocation> subject = optionalId(metric, "subject");
        return new GameplayMetricKey(type, subject);
    }

    private static void rejectUnknownFields(JsonObject json, Set<String> allowed, String location) {
        json.keySet().forEach(field -> {
            if (!allowed.contains(field)) {
                throw new JsonParseException("Unknown field '" + field + "' in " + location);
            }
        });
    }

    private static JsonElement required(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || value.isJsonNull()) {
            throw new JsonParseException("Missing required field '" + field + "'");
        }
        return value;
    }

    private static int requiredInt(JsonObject json, String field) {
        long value = requiredIntegral(json, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new JsonParseException("Field '" + field + "' is outside the integer range");
        }
        return (int) value;
    }

    private static long requiredLong(JsonObject json, String field) {
        return requiredIntegral(json, field);
    }

    private static long optionalLong(JsonObject json, String field, long defaultValue) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull() ? defaultValue : integral(value, field);
    }

    private static long requiredIntegral(JsonObject json, String field) {
        return integral(required(json, field), field);
    }

    private static long integral(JsonElement value, String field) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException("Field '" + field + "' must be an integer");
        }
        try {
            return new BigDecimal(value.getAsString()).toBigIntegerExact().longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new JsonParseException("Field '" + field + "' must be an integer in the long range", exception);
        }
    }

    private static ResourceLocation requiredId(JsonObject json, String field) {
        JsonElement value = required(json, field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("Field '" + field + "' must be a namespaced ID string");
        }
        return parseId(value.getAsString(), field);
    }

    private static Optional<ResourceLocation> optionalId(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (value.isJsonNull()) {
            throw new JsonParseException("Field '" + field + "' cannot be null");
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("Field '" + field + "' must be a namespaced ID string");
        }
        return Optional.of(parseId(value.getAsString(), field));
    }

    private static Optional<FeedingOutcome> optionalFeedingOutcome(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("Field '" + field + "' must be a feeding outcome string");
        }
        return Optional.of(switch (value.getAsString()) {
            case "love_mode" -> FeedingOutcome.LOVE_MODE;
            case "growth_accelerated" -> FeedingOutcome.GROWTH_ACCELERATED;
            default -> throw new JsonParseException("Unknown feeding outcome in field '" + field
                    + "': " + value.getAsString());
        });
    }

    private static ResourceLocation idElement(JsonElement value, String field) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("Field '" + field + "' must be a namespaced ID string");
        }
        return parseId(value.getAsString(), field);
    }

    private static void requireRegistered(ResourceLocation id, boolean registered, String field) {
        if (!registered) {
            throw new JsonParseException("Unknown registered ID in field '" + field + "': " + id);
        }
    }

    private static ResourceLocation parseId(String raw, String field) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null || !raw.contains(":")) {
            throw new JsonParseException("Field '" + field + "' must be a valid namespaced ID");
        }
        return id;
    }
}
