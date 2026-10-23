package com.sande.mythictrpg.quest.reward.luck;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Explicit lucky-item pools and global tuning, loaded as an atomic immutable snapshot. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class LuckDefinitions extends SimplePreparableReloadListener<LuckDefinitions.Snapshot> {
    public static final LuckDefinitions INSTANCE = new LuckDefinitions();
    private static final FileToIdConverter FILES = FileToIdConverter.json("mythictrpg/luck");
    private volatile Snapshot snapshot = new Snapshot(Map.of(), LuckModel.DEFAULT);
    private LuckDefinitions() { }
    public record Snapshot(Map<ResourceLocation, LuckyItemPool> pools, LuckModel model) {
        public Snapshot { pools = Map.copyOf(pools); java.util.Objects.requireNonNull(model); }
    }
    @SubscribeEvent public static void registerReload(AddReloadListenerEvent event) { event.addListener(INSTANCE); }
    public Optional<LuckyItemPool> find(ResourceLocation poolId) { return Optional.ofNullable(snapshot.pools().get(poolId)); }
    public LuckModel model() { return snapshot.model(); }
    public Snapshot snapshot() { return snapshot; }

    public static Snapshot parse(Map<ResourceLocation, JsonObject> documents) {
        if (documents.size() > 2048) throw new IllegalArgumentException("Maximum 2048 luck documents");
        Map<ResourceLocation, LuckyItemPool> pools = new LinkedHashMap<>();
        LuckModel model = LuckModel.DEFAULT;
        boolean modelFound = false;
        for (var entry : documents.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            try {
                JsonObject json = entry.getValue();
                if (integer(json, "schema_version") != 1) throw new IllegalArgumentException("Unsupported luck schema_version");
                switch (string(json, "kind")) {
                    case "item_pool" -> pools.put(entry.getKey(), pool(entry.getKey(), json));
                    case "model" -> {
                        if (modelFound) throw new IllegalArgumentException("Duplicate global luck model");
                        modelFound = true;
                        fields(json, Set.of("schema_version", "kind", "rarity_per_level", "quantity_per_level", "event_quality_per_level", "max_bonus_count"));
                        model = new LuckModel(number(json, "rarity_per_level"), number(json, "quantity_per_level"),
                                number(json, "event_quality_per_level"), integer(json, "max_bonus_count"));
                    }
                    default -> throw new IllegalArgumentException("Unknown luck document kind");
                }
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid luck document " + entry.getKey() + ": " + exception.getMessage(), exception);
            }
        }
        return new Snapshot(pools, model);
    }

    private static LuckyItemPool pool(ResourceLocation id, JsonObject json) {
        fields(json, Set.of("schema_version", "kind", "candidates"));
        var list = required(json, "candidates");
        if (!list.isJsonArray() || list.getAsJsonArray().isEmpty() || list.getAsJsonArray().size() > 32) {
            throw new IllegalArgumentException("Lucky item pool requires 1..32 candidates");
        }
        var candidates = new ArrayList<LuckyItemCandidate>();
        for (var entry : list.getAsJsonArray()) {
            var candidate = object(entry, "candidate");
            fields(candidate, Set.of("id", "item", "min_count", "max_count", "quality", "weight", "quantity_bonus_allowed"));
            var item = object(required(candidate, "item"), "item");
            fields(item, Set.of("item_id", "components"));
            CompoundTag components = new CompoundTag();
            if (item.has("components")) {
                var componentJson = object(item.get("components"), "components");
                if (componentJson.toString().length() > 16_384) throw new IllegalArgumentException("Lucky item components exceed 16384 characters");
                components = (CompoundTag) JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, componentJson);
            }
            boolean quantity = false;
            if (candidate.has("quantity_bonus_allowed")) {
                var value = required(candidate, "quantity_bonus_allowed");
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("quantity_bonus_allowed must be a boolean");
                }
                quantity = value.getAsBoolean();
            }
            candidates.add(new LuckyItemCandidate(string(candidate, "id"),
                    new NpcRewardEntry(id(string(item, "item_id")), 1, components),
                    integer(candidate, "min_count"), integer(candidate, "max_count"),
                    integer(candidate, "quality"), integer(candidate, "weight"), quantity));
        }
        return new LuckyItemPool(id, candidates);
    }

    @Override protected Snapshot prepare(ResourceManager resources, ProfilerFiller profiler) {
        var files = FILES.listMatchingResources(resources);
        if (files.size() > 2048) throw new IllegalArgumentException("Maximum 2048 luck documents");
        Map<ResourceLocation, JsonObject> documents = new LinkedHashMap<>();
        for (var entry : files.entrySet()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                documents.put(FILES.fileToId(entry.getKey()), object(JsonParser.parseReader(reader), "luck document"));
            } catch (Exception exception) {
                throw new IllegalArgumentException("Failed to read luck document " + entry.getKey(), exception);
            }
        }
        var parsed = parse(documents);
        parsed.pools().values().forEach(pool -> pool.candidates().forEach(candidate -> {
            if (!BuiltInRegistries.ITEM.containsKey(candidate.item().itemId())
                    || candidate.item().itemId().equals(ResourceLocation.withDefaultNamespace("air"))) {
                throw new IllegalArgumentException("Unknown/empty lucky item " + candidate.item().itemId());
            }
        }));
        return parsed;
    }
    @Override protected void apply(Snapshot prepared, ResourceManager resources, ProfilerFiller profiler) {
        snapshot = prepared;
        MythicTrpg.LOGGER.info("Loaded {} explicitly authored lucky item pools", prepared.pools().size());
    }
    private static void fields(JsonObject json, Set<String> fields) {
        json.keySet().forEach(field -> { if (!fields.contains(field)) throw new IllegalArgumentException("Unknown luck field " + field); });
    }
    private static JsonElement required(JsonObject json, String field) {
        var value = json.get(field);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing luck field " + field);
        return value;
    }
    private static JsonObject object(JsonElement value, String field) {
        if (!value.isJsonObject()) throw new IllegalArgumentException(field + " must be an object");
        return value.getAsJsonObject();
    }
    private static String string(JsonObject json, String field) {
        var value = required(json, field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(field + " must be a nonblank string");
        }
        return value.getAsString();
    }
    private static double number(JsonObject json, String field) {
        var value = required(json, field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) {
            throw new IllegalArgumentException(field + " must be a finite number");
        }
        return value.getAsDouble();
    }
    private static int integer(JsonObject json, String field) {
        double number = number(json, field);
        if (number != (int) number) throw new IllegalArgumentException(field + " must be an integer");
        return (int) number;
    }
    private static ResourceLocation id(String value) {
        var id = ResourceLocation.tryParse(value);
        if (id == null || !value.contains(":")) throw new IllegalArgumentException("Invalid namespaced luck ID " + value);
        return id;
    }
}
