package com.sande.mythictrpg.relation;

import com.google.gson.JsonArray;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict loader for relation transitions. Model output can select only an aiEnabled entry from this registry. */
public final class GodRelationTransitionManager extends
        SimplePreparableReloadListener<Map<ResourceLocation, GodRelationTransition>> {
    public static final GodRelationTransitionManager INSTANCE = new GodRelationTransitionManager();
    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("mythictrpg/god_relation_transitions");
    private volatile Map<ResourceLocation, GodRelationTransition> transitions = Map.of();

    private GodRelationTransitionManager() {
    }

    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(this);
    }

    public Optional<GodRelationTransition> find(ResourceLocation id) {
        return Optional.ofNullable(transitions.get(id));
    }

    public List<GodRelationTransition> aiTransitionsFor(ResourceLocation actingGodId) {
        return transitions.values().stream().filter(GodRelationTransition::aiEnabled)
                .filter(value -> value.actingGodId().equals(actingGodId))
                .sorted(java.util.Comparator.comparing(GodRelationTransition::id)).toList();
    }

    public Set<ResourceLocation> ids() {
        return Set.copyOf(transitions.keySet());
    }

    @Override
    protected Map<ResourceLocation, GodRelationTransition> prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, GodRelationTransition> parsed = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        CONVERTER.listMatchingResources(resources).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parse(entry.getKey(), entry.getValue(), parsed, errors));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Rejected God relation transition reload with "
                    + errors.size() + " error(s)");
        }
        return Map.copyOf(parsed);
    }

    private static void parse(ResourceLocation file, Resource resource,
            Map<ResourceLocation, GodRelationTransition> destination, List<String> errors) {
        ResourceLocation id = CONVERTER.fileToId(file);
        try (Reader reader = resource.openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("Root must be an object");
            }
            JsonObject json = root.getAsJsonObject();
            rejectUnknown(json, Set.of("schemaVersion", "actingGodId", "aiEnabled", "maxApplications",
                    "summary", "changes"));
            if (integer(json, "schemaVersion") != 1) {
                throw new IllegalArgumentException("Unsupported schemaVersion");
            }
            JsonArray rawChanges = array(json, "changes");
            List<GodRelationTransitionChange> changes = new ArrayList<>();
            Set<GodRelationKey> directedKeys = new LinkedHashSet<>();
            rawChanges.forEach(raw -> {
                if (!raw.isJsonObject()) {
                    throw new IllegalArgumentException("changes entries must be objects");
                }
                JsonObject change = raw.getAsJsonObject();
                rejectUnknown(change, Set.of("sourceGodId", "targetGodId", "scoreDelta", "addTags", "removeTags"));
                GodRelationTransitionChange decoded = new GodRelationTransitionChange(
                        namespaced(string(change, "sourceGodId"), "changes.sourceGodId"),
                        namespaced(string(change, "targetGodId"), "changes.targetGodId"),
                        optionalInteger(change, "scoreDelta", 0),
                        tags(change, "addTags"), tags(change, "removeTags"));
                if (!directedKeys.add(decoded.key())) {
                    throw new IllegalArgumentException("Duplicate directed change " + decoded.key());
                }
                changes.add(decoded);
            });
            GodRelationTransition transition = new GodRelationTransition(id,
                    namespaced(string(json, "actingGodId"), "actingGodId"),
                    optionalBoolean(json, "aiEnabled", false), optionalInteger(json, "maxApplications", 1),
                    string(json, "summary"), changes);
            if (destination.putIfAbsent(id, transition) != null) {
                throw new IllegalArgumentException("Duplicate God relation transition " + id);
            }
        } catch (Exception exception) {
            errors.add(id + ": " + exception.getMessage());
            MythicTrpg.LOGGER.error("God relation transition {} failed", id, exception);
        }
    }

    @Override
    protected void apply(Map<ResourceLocation, GodRelationTransition> prepared,
            ResourceManager resources, ProfilerFiller profiler) {
        transitions = prepared;
        MythicTrpg.LOGGER.info("Loaded {} God relation transitions", transitions.size());
    }

    private static Set<GodRelationTag> tags(JsonObject json, String key) {
        if (!json.has(key)) {
            return Set.of();
        }
        JsonArray array = array(json, key);
        LinkedHashSet<GodRelationTag> tags = new LinkedHashSet<>();
        array.forEach(value -> {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(key + " entries must be strings");
            }
            if (!tags.add(GodRelationTag.parse(value.getAsString()))) {
                throw new IllegalArgumentException("Duplicate tag in " + key + ": " + value.getAsString());
            }
        });
        return Set.copyOf(tags);
    }

    private static JsonArray array(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            throw new IllegalArgumentException("Missing array '" + key + "'");
        }
        return json.getAsJsonArray(key);
    }

    private static String string(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException("Missing string '" + key + "'");
        }
        String value = json.get(key).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Blank string '" + key + "'");
        }
        return value;
    }

    private static int integer(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("Missing integer '" + key + "'");
        }
        double value = json.get(key).getAsDouble();
        if (!Double.isFinite(value) || value != (int) value) {
            throw new IllegalArgumentException("Field '" + key + "' must be an integer");
        }
        return (int) value;
    }

    private static int optionalInteger(JsonObject json, String key, int fallback) {
        return json.has(key) ? integer(json, key) : fallback;
    }

    private static boolean optionalBoolean(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        if (!json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException("Field '" + key + "' must be a boolean");
        }
        return json.get(key).getAsBoolean();
    }

    private static ResourceLocation namespaced(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !value.contains(":")) {
            throw new IllegalArgumentException("Invalid namespaced ID in " + field + ": " + value);
        }
        return id;
    }

    private static void rejectUnknown(JsonObject json, Set<String> allowed) {
        json.keySet().forEach(key -> {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unknown God relation transition field '" + key + "'");
            }
        });
    }
}
