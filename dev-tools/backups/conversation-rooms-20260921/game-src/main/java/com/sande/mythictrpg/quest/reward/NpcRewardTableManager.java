package com.sande.mythictrpg.quest.reward;

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

/** Loads NPC reward grade tables from Datapacks. */
public final class NpcRewardTableManager extends SimplePreparableReloadListener<NpcRewardTableManager.Prepared> {
    public static final NpcRewardTableManager INSTANCE = new NpcRewardTableManager();
    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("mythictrpg/reward_tables");
    private volatile Map<ResourceLocation, NpcRewardTable> tables = Map.of();
    private volatile Map<ResourceLocation, ResourceLocation> defaultTablesByGod = Map.of();

    private NpcRewardTableManager() {
    }

    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(this);
    }

    public Optional<NpcRewardTable> find(ResourceLocation id) {
        return Optional.ofNullable(tables.get(id));
    }

    public Optional<NpcRewardTable> defaultForGod(ResourceLocation godId) {
        ResourceLocation tableId = defaultTablesByGod.get(godId);
        return tableId == null ? Optional.empty() : find(tableId);
    }

    @Override
    protected Prepared prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, NpcRewardTable> parsed = new LinkedHashMap<>();
        Map<ResourceLocation, ResourceLocation> defaults = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        CONVERTER.listMatchingResources(resources).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parse(entry.getKey(), entry.getValue(), parsed, defaults, errors));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Rejected NPC reward table reload with " + errors.size() + " error(s)");
        }
        return new Prepared(Map.copyOf(parsed), Map.copyOf(defaults));
    }

    private static void parse(ResourceLocation file, Resource resource,
            Map<ResourceLocation, NpcRewardTable> destination,
            Map<ResourceLocation, ResourceLocation> defaults, List<String> errors) {
        ResourceLocation id = CONVERTER.fileToId(file);
        try (Reader reader = resource.openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("Root must be an object");
            }
            JsonObject json = root.getAsJsonObject();
            int schema = integer(json, "schemaVersion");
            if (schema == 1) {
                rejectUnknown(json, Set.of("schemaVersion", "npcId", "tiers"), "reward table");
            } else if (schema == 2) {
                rejectUnknown(json, Set.of("schemaVersion", "godId", "defaultForGod", "tiers"),
                        "reward table");
            } else {
                throw new IllegalArgumentException("Unsupported schemaVersion " + schema);
            }
            ResourceLocation godId = id(string(json, schema == 1 ? "npcId" : "godId"),
                    schema == 1 ? "npcId" : "godId");
            boolean defaultForGod = schema == 1 || optionalBoolean(json, "defaultForGod", false);
            if (!json.has("tiers") || !json.get("tiers").isJsonArray()) {
                throw new IllegalArgumentException("Missing array 'tiers'");
            }
            Map<Integer, NpcRewardTier> tiers = new LinkedHashMap<>();
            for (JsonElement tierElement : json.getAsJsonArray("tiers")) {
                if (!tierElement.isJsonObject()) {
                    throw new IllegalArgumentException("Tier must be an object");
                }
                JsonObject tierJson = tierElement.getAsJsonObject();
                rejectUnknown(tierJson, Set.of("tier", "rewards"), "reward tier");
                int tier = integer(tierJson, "tier");
                if (!tierJson.has("rewards") || !tierJson.get("rewards").isJsonArray()) {
                    throw new IllegalArgumentException("Tier requires array 'rewards'");
                }
                List<RewardEntry> rewards = new ArrayList<>();
                int rewardIndex = 0;
                for (JsonElement rewardElement : tierJson.getAsJsonArray("rewards")) {
                    if (!rewardElement.isJsonObject()) {
                        throw new IllegalArgumentException("Reward must be an object");
                    }
                    JsonObject reward = rewardElement.getAsJsonObject();
                    if (schema == 1 && !"item".equals(string(reward, "type"))) {
                        throw new IllegalArgumentException("Legacy schemaVersion 1 supports only item rewards");
                    }
                    rewards.add(RewardEntryCodec.parse(reward,
                            "tiers[" + tier + "].rewards[" + rewardIndex + "]"));
                    rewardIndex++;
                }
                if (tiers.putIfAbsent(tier, new NpcRewardTier(tier, rewards)) != null) {
                    throw new IllegalArgumentException("Duplicate reward tier " + tier);
                }
            }
            if (destination.putIfAbsent(id, new NpcRewardTable(id, godId, tiers)) != null) {
                throw new IllegalArgumentException("Duplicate reward table ID " + id);
            }
            if (defaultForGod && defaults.putIfAbsent(godId, id) != null) {
                throw new IllegalArgumentException("God " + godId + " has more than one default reward table");
            }
        } catch (Exception exception) {
            errors.add(id + ": " + exception.getMessage());
            MythicTrpg.LOGGER.error("NPC reward table {} failed", id, exception);
        }
    }

    @Override
    protected void apply(Prepared prepared,
            ResourceManager resources, ProfilerFiller profiler) {
        tables = prepared.tables();
        defaultTablesByGod = prepared.defaultTablesByGod();
        MythicTrpg.LOGGER.info("Loaded {} God reward tables ({} defaults)",
                tables.size(), defaultTablesByGod.size());
    }

    private static void rejectUnknown(JsonObject json, Set<String> allowed, String location) {
        json.keySet().forEach(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("Unknown " + location + " field '" + field + "'");
            }
        });
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
        int value = json.get(key).getAsInt();
        if (json.get(key).getAsDouble() != value) {
            throw new IllegalArgumentException("Field '" + key + "' must be an integer");
        }
        return value;
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

    private static ResourceLocation id(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !value.contains(":")) {
            throw new IllegalArgumentException("Invalid namespaced ID in " + field + ": " + value);
        }
        return id;
    }

    protected record Prepared(Map<ResourceLocation, NpcRewardTable> tables,
            Map<ResourceLocation, ResourceLocation> defaultTablesByGod) {
    }
}
