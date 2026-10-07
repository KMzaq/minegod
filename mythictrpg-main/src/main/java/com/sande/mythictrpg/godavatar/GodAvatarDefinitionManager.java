package com.sande.mythictrpg.godavatar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Atomic reload of authored avatar capabilities; file ID is the canonical God ID. */
public final class GodAvatarDefinitionManager extends SimplePreparableReloadListener<Map<ResourceLocation, GodAvatarDefinition>> {
    public static final GodAvatarDefinitionManager INSTANCE = new GodAvatarDefinitionManager();
    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("mythictrpg/god_avatars");
    private volatile Map<ResourceLocation, GodAvatarDefinition> definitions = Map.of();
    private volatile long generation;

    private GodAvatarDefinitionManager() {}

    public void onAddReloadListeners(AddReloadListenerEvent event) { event.addListener(this); }
    public Optional<GodAvatarDefinition> find(ResourceLocation godId) { return Optional.ofNullable(definitions.get(godId)); }
    public long generation() { return generation; }

    @Override protected Map<ResourceLocation, GodAvatarDefinition> prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, GodAvatarDefinition> parsed = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        CONVERTER.listMatchingResources(resources).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parse(entry.getKey(), entry.getValue(), parsed, errors));
        if (!errors.isEmpty()) throw new IllegalStateException("Rejected God avatar reload: " + String.join("; ", errors));
        return Map.copyOf(parsed);
    }

    private static void parse(ResourceLocation file, Resource resource,
            Map<ResourceLocation, GodAvatarDefinition> parsed, List<String> errors) {
        ResourceLocation godId = CONVERTER.fileToId(file);
        try (Reader reader = resource.openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            parsed.put(godId, decode(godId, object(root, "root")));
        } catch (Exception exception) {
            String message = file + " from '" + resource.sourcePackId() + "': " + exception.getMessage();
            errors.add(message);
            MythicTrpg.LOGGER.error("Invalid God avatar {}", message, exception);
        }
    }

    public static GodAvatarDefinition decode(ResourceLocation godId, JsonObject root) {
        fields(root, "formatVersion", "appearance", "stats", "movement", "combat", "placement", "interactionRange");
        if (integer(root, "formatVersion") != 1) throw new IllegalArgumentException("Unsupported formatVersion");
        JsonObject appearance = nested(root, "appearance", "textureVariant", "scale", "model");
        JsonObject stats = nested(root, "stats", "maxHealth", "movementSpeed", "attackDamage", "armor", "followRange");
        JsonObject movement = nested(root, "movement", "enabled", "wander", "visit", "navigationSpeed",
                "maxCommandDistance", "maxVisitDistance");
        JsonObject combat = nested(root, "combat", "enabled", "damageable", "retaliate", "raidControl");
        JsonObject placement = nested(root, "placement", "onEncounter", "spawnRadius");
        return new GodAvatarDefinition(godId,
                new GodAvatarDefinition.Appearance(integer(appearance, "textureVariant"),
                        (float) number(appearance, "scale"), appearance.has("model")
                                ? GodAvatarDefinition.SkinModel.fromJson(string(appearance, "model"))
                                : GodAvatarDefinition.SkinModel.CLASSIC),
                new GodAvatarDefinition.Stats(number(stats, "maxHealth"), number(stats, "movementSpeed"),
                        number(stats, "attackDamage"), number(stats, "armor"), number(stats, "followRange")),
                new GodAvatarDefinition.Movement(bool(movement, "enabled"), bool(movement, "wander"),
                        bool(movement, "visit"), number(movement, "navigationSpeed"),
                        number(movement, "maxCommandDistance"), number(movement, "maxVisitDistance")),
                new GodAvatarDefinition.Combat(bool(combat, "enabled"), bool(combat, "damageable"),
                        bool(combat, "retaliate"), bool(combat, "raidControl")),
                new GodAvatarDefinition.Placement(bool(placement, "onEncounter"), integer(placement, "spawnRadius")),
                number(root, "interactionRange"));
    }

    @Override protected void apply(Map<ResourceLocation, GodAvatarDefinition> prepared,
            ResourceManager resources, ProfilerFiller profiler) {
        definitions = prepared;
        generation++;
        MythicTrpg.LOGGER.info("Loaded {} God avatar definitions (generation {}).", prepared.size(), generation);
    }

    private static JsonObject nested(JsonObject parent, String key, String... allowed) {
        JsonObject value = object(required(parent, key), key);
        fields(value, allowed);
        return value;
    }
    private static JsonObject object(JsonElement value, String field) {
        if (!value.isJsonObject()) throw new IllegalArgumentException(field + " must be an object");
        return value.getAsJsonObject();
    }
    private static JsonElement required(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) throw new IllegalArgumentException("Missing " + key);
        return object.get(key);
    }
    private static void fields(JsonObject object, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (String name : object.keySet()) if (!names.contains(name))
            throw new IllegalArgumentException("Unknown field " + name);
    }
    private static double number(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(key + " must be numeric");
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException(key + " must be finite");
        return number;
    }
    private static int integer(JsonObject object, String key) {
        double value = number(object, key);
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException(key + " must be an integer");
        return (int) value;
    }
    private static String string(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(key + " must be a string");
        return value.getAsString();
    }
    private static boolean bool(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException(key + " must be boolean");
        return value.getAsBoolean();
    }
}
