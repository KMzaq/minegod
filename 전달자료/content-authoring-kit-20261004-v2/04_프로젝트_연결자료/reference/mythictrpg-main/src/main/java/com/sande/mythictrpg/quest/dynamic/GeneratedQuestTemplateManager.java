package com.sande.mythictrpg.quest.dynamic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import net.minecraft.core.registries.BuiltInRegistries;
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

/** Strict loader for the complete allow-list of AI-selectable generated SIDE quests. */
public final class GeneratedQuestTemplateManager extends
        SimplePreparableReloadListener<Map<ResourceLocation, GeneratedQuestTemplate>> {
    public static final GeneratedQuestTemplateManager INSTANCE = new GeneratedQuestTemplateManager();
    private static final FileToIdConverter CONVERTER =
            FileToIdConverter.json("mythictrpg/generated_quest_templates");
    private static final Set<ResourceLocation> ALLOWED_OBSERVATIONS = Set.of(
            GameplayObservationTypes.ENTITY_KILLED.id(),
            GameplayObservationTypes.BLOCK_BROKEN.id(),
            GameplayObservationTypes.MATURE_CROP_HARVESTED.id(),
            GameplayObservationTypes.ANIMAL_FED.id(),
            GameplayObservationTypes.ANIMAL_BRED.id());
    private volatile Map<ResourceLocation, GeneratedQuestTemplate> templates = Map.of();

    private GeneratedQuestTemplateManager() {
    }

    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(this);
    }

    public Optional<GeneratedQuestTemplate> find(ResourceLocation id, ResourceLocation godId) {
        GeneratedQuestTemplate template = templates.get(id);
        return template != null && template.godId().equals(godId) ? Optional.of(template) : Optional.empty();
    }

    public List<GeneratedQuestTemplate> templatesFor(ResourceLocation godId) {
        return templates.values().stream().filter(value -> value.godId().equals(godId)).toList();
    }

    @Override
    protected Map<ResourceLocation, GeneratedQuestTemplate> prepare(
            ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, GeneratedQuestTemplate> parsed = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        CONVERTER.listMatchingResources(resources).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parse(entry.getKey(), entry.getValue(), parsed, errors));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Rejected generated quest template reload with "
                    + errors.size() + " error(s)");
        }
        return Map.copyOf(parsed);
    }

    private static void parse(ResourceLocation file, Resource resource,
            Map<ResourceLocation, GeneratedQuestTemplate> destination, List<String> errors) {
        ResourceLocation id = CONVERTER.fileToId(file);
        try (Reader reader = resource.openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("Root must be an object");
            }
            JsonObject json = root.getAsJsonObject();
            rejectUnknown(json, Set.of("schemaVersion", "godId", "objective", "worldProgress",
                    "reward", "cooldownTicks", "expiresAfterTicks", "completionMode", "returnLocation"));
            if (integer(json, "schemaVersion") != 1) {
                throw new IllegalArgumentException("Unsupported schemaVersion");
            }
            JsonObject objective = object(json, "objective");
            rejectUnknown(objective, Set.of("observation", "subject", "count"));
            ResourceLocation observation = namespaced(string(objective, "observation"), "objective.observation");
            if (!ALLOWED_OBSERVATIONS.contains(observation)) {
                throw new IllegalArgumentException("Unsafe or unsupported objective observation " + observation);
            }
            ResourceLocation subject = namespaced(string(objective, "subject"), "objective.subject");
            validateSubject(observation, subject);

            JsonObject progress = object(json, "worldProgress");
            rejectUnknown(progress, Set.of("trackId", "minimum", "maximum"));
            JsonObject reward = object(json, "reward");
            rejectUnknown(reward, Set.of("tableId", "baseTier", "maximumTier", "catchUpMaximumBonus"));

            GeneratedQuestTemplate template = new GeneratedQuestTemplate(id,
                    namespaced(string(json, "godId"), "godId"), observation, subject,
                    integer(objective, "count"),
                    namespaced(string(progress, "trackId"), "worldProgress.trackId"),
                    integer(progress, "minimum"), integer(progress, "maximum"),
                    namespaced(string(reward, "tableId"), "reward.tableId"),
                    integer(reward, "baseTier"), integer(reward, "maximumTier"),
                    integer(reward, "catchUpMaximumBonus"),
                    integer(json, "cooldownTicks"), integer(json, "expiresAfterTicks"),
                    json.has("completionMode")
                            ? com.sande.mythictrpg.quest.QuestCompletionMode.parse(string(json, "completionMode"))
                            : com.sande.mythictrpg.quest.QuestCompletionMode.PLAYER_RETURN_TO_NPC,
                    json.has("returnLocation")
                            ? Optional.of(com.sande.mythictrpg.quest.QuestContactLocation.parse(object(json, "returnLocation")))
                            : Optional.empty());
            if (destination.putIfAbsent(id, template) != null) {
                throw new IllegalArgumentException("Duplicate generated quest template " + id);
            }
        } catch (Exception exception) {
            errors.add(id + ": " + exception.getMessage());
            MythicTrpg.LOGGER.error("Generated quest template {} failed", id, exception);
        }
    }

    private static void validateSubject(ResourceLocation observation, ResourceLocation subject) {
        boolean block = observation.equals(GameplayObservationTypes.BLOCK_BROKEN.id())
                || observation.equals(GameplayObservationTypes.MATURE_CROP_HARVESTED.id());
        if (block ? !BuiltInRegistries.BLOCK.containsKey(subject)
                : !BuiltInRegistries.ENTITY_TYPE.containsKey(subject)) {
            throw new IllegalArgumentException("Unknown objective subject " + subject);
        }
    }

    @Override
    protected void apply(Map<ResourceLocation, GeneratedQuestTemplate> prepared,
            ResourceManager resources, ProfilerFiller profiler) {
        templates = prepared;
        MythicTrpg.LOGGER.info("Loaded {} generated SIDE quest templates", templates.size());
    }

    private static JsonObject object(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonObject()) {
            throw new IllegalArgumentException("Missing object '" + key + "'");
        }
        return json.getAsJsonObject(key);
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
                throw new IllegalArgumentException("Unknown generated quest field '" + key + "'");
            }
        });
    }
}
