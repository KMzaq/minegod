package com.sande.mythictrpg.content;

import com.google.gson.*;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.condition.api.ConditionNode;
import com.sande.mythictrpg.condition.engine.ConditionTreeParser;
import com.sande.mythictrpg.condition.registry.ConditionTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;
import java.io.Reader;
import java.util.*;

/** Optional authored systems. Empty definitions enable no encounters, damage changes or locks. */
public final class ContentDefinitions extends SimplePreparableReloadListener<ContentDefinitions.Snapshot> {
    public static final ContentDefinitions INSTANCE = new ContentDefinitions();
    public record Investigation(ResourceLocation id, ResourceLocation dimension, BlockPos position, ResourceLocation block, double radius,
            ResourceLocation event, Optional<ResourceLocation> heldItem, int heldCount, long cooldownTicks,
            ConditionNode condition, CompoundTag components) {
        public Investigation(ResourceLocation id, ResourceLocation dimension, BlockPos position, ResourceLocation block, double radius,
                ResourceLocation event, Optional<ResourceLocation> heldItem, int heldCount, long cooldownTicks, ConditionNode condition) {
            this(id, dimension, position, block, radius, event, heldItem, heldCount, cooldownTicks, condition, new CompoundTag());
        }
        public Investigation { position = position.immutable(); components = components.copy(); }
        @Override public CompoundTag components() { return components.copy(); }
    }
    public record Admission(boolean playerExclusive, Set<ResourceLocation> resources) {
        public Admission { resources = Set.copyOf(resources); }
    }
    public record Countermeasure(ResourceLocation id, ResourceLocation raid, ResourceLocation damageType,
            ResourceLocation item, boolean consume, double multiplier, CompoundTag components) {
        public Countermeasure(ResourceLocation id, ResourceLocation raid, ResourceLocation damageType,
                ResourceLocation item, boolean consume, double multiplier) {
            this(id, raid, damageType, item, consume, multiplier, new CompoundTag());
        }
        public Countermeasure {
            Objects.requireNonNull(id); Objects.requireNonNull(raid); Objects.requireNonNull(damageType); Objects.requireNonNull(item);
            if (!Double.isFinite(multiplier) || multiplier < 0 || multiplier >= 1) throw new IllegalArgumentException("Countermeasure multiplier");
            components = components.copy();
            if (components.toString().length() > 16384) throw new IllegalArgumentException("Countermeasure components too large");
        }
        @Override public CompoundTag components() { return components.copy(); }
    }
    public record Snapshot(Map<ResourceLocation, Investigation> investigations,
            Map<ResourceLocation, Admission> admissions, Map<ResourceLocation, Countermeasure> countermeasures) {
        public Snapshot {
            investigations = Map.copyOf(investigations); admissions = Map.copyOf(admissions);
            countermeasures = Map.copyOf(countermeasures);
        }
        public static Snapshot empty() { return new Snapshot(Map.of(), Map.of(), Map.of()); }
    }
    private volatile Snapshot snapshot = Snapshot.empty();
    public Snapshot snapshot() { return snapshot; }
    @Override protected Snapshot prepare(ResourceManager resources, ProfilerFiller profiler) {
        var investigations = new LinkedHashMap<ResourceLocation, Investigation>();
        var admissions = new LinkedHashMap<ResourceLocation, Admission>();
        var counters = new LinkedHashMap<ResourceLocation, Countermeasure>();
        read(resources, "investigations").forEach((id, json) -> investigations.put(id, investigation(id, json)));
        read(resources, "story_admission").forEach((id, json) -> admissions.put(id, admission(json)));
        read(resources, "raid_countermeasures").forEach((id, json) -> counters.put(id, countermeasure(id, json)));
        // One selected tool per raid/damage pair: file ordering must never decide which item is consumed.
        var pairs = new HashSet<String>();
        for (var counter : counters.values()) if (!pairs.add(counter.raid() + "/" + counter.damageType()))
            throw new IllegalArgumentException("Ambiguous raid countermeasure: " + counter.id());
        var perRaid = new HashMap<ResourceLocation, Integer>();
        for (var counter : counters.values()) if (perRaid.merge(counter.raid(), 1, Integer::sum) > 32)
            throw new IllegalArgumentException("Countermeasure limit per raid");
        return new Snapshot(investigations, admissions, counters);
    }
    @Override protected void apply(Snapshot value, ResourceManager resources, ProfilerFiller profiler) { snapshot = value; }
    static Map<ResourceLocation, JsonObject> read(ResourceManager resources, String path) {
        var converter = FileToIdConverter.json("mythictrpg/" + path);
        var result = new LinkedHashMap<ResourceLocation, JsonObject>();
        var files = converter.listMatchingResources(resources);
        if (files.size() > 256) throw new IllegalArgumentException("Content definition limit: " + path);
        files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            try (Reader reader = entry.getValue().openAsReader()) {
                var text = new StringBuilder(); char[] buffer = new char[4096]; int n;
                while ((n = reader.read(buffer)) != -1) {
                    if (text.length() + n > 65536) throw new IllegalArgumentException("Content definition too large");
                    text.append(buffer, 0, n);
                }
                result.put(converter.fileToId(entry.getKey()), JsonParser.parseString(text.toString()).getAsJsonObject());
            } catch (Exception failure) { throw new IllegalArgumentException("Invalid " + entry.getKey(), failure); }
        });
        return result;
    }
    static Investigation investigation(ResourceLocation id, JsonObject json) {
        fieldsWithComponents(json, "schemaVersion", "dimension", "position", "block", "radius", "event", "heldItem", "heldCount", "cooldownTicks", "condition");
        version(json);
        var pos = json.getAsJsonArray("position");
        if (pos == null || pos.size() != 3) throw new IllegalArgumentException("position must contain x/y/z");
        var position = new BlockPos(integer(pos.get(0)), integer(pos.get(1)), integer(pos.get(2)));
        double radius = number(json, "radius");
        if (radius < 1 || radius > 8 || Math.abs((long) position.getX()) > 29999984 || Math.abs((long) position.getZ()) > 29999984)
            throw new IllegalArgumentException("Investigation position/radius");
        String held = string(json, "heldItem");
        var item = held.equals("NONE") ? Optional.<ResourceLocation>empty() : Optional.of(id(held));
        int count = integer(json.get("heldCount"));
        int cooldown = integer(json.get("cooldownTicks"));
        if (count < 1 || count > 64 || cooldown < 20 || cooldown > 72000) throw new IllegalArgumentException("Investigation count/cooldown");
        return new Investigation(id, id(string(json, "dimension")), position, id(string(json, "block")), radius, id(string(json, "event")), item,
                count, cooldown, new ConditionTreeParser(ConditionTypeRegistry.INSTANCE).parse(json.get("condition")), components(json));
    }
    static Admission admission(JsonObject json) {
        fields(json, "schemaVersion", "playerExclusive", "resources"); version(json);
        var values = json.getAsJsonArray("resources");
        if (values == null || values.size() > 16) throw new IllegalArgumentException("Admission resource limit");
        var keys = new LinkedHashSet<ResourceLocation>();
        for (var value : values) {
            ResourceLocation resource = id(value.getAsString());
            if (resource.getNamespace().equals("mythictrpg") && resource.getPath().startsWith("content_player/"))
                throw new IllegalArgumentException("Reserved player resource namespace");
            if (!keys.add(resource)) throw new IllegalArgumentException("Duplicate resource");
        }
        return new Admission(bool(json, "playerExclusive"), keys);
    }
    static Countermeasure countermeasure(ResourceLocation id, JsonObject json) {
        fieldsWithComponents(json, "schemaVersion", "raid", "damageType", "item", "consume", "multiplier"); version(json);
        double multiplier = number(json, "multiplier");
        if (multiplier < 0 || multiplier >= 1) throw new IllegalArgumentException("Countermeasure multiplier must be in [0,1)");
        return new Countermeasure(id, id(string(json, "raid")), id(string(json, "damageType")),
                id(string(json, "item")), bool(json, "consume"), multiplier, components(json));
    }
    private static void fieldsWithComponents(JsonObject json, String... required) {
        var copy = json.deepCopy(); copy.remove("components"); fields(copy, required);
    }
    private static CompoundTag components(JsonObject json) {
        if (!json.has("components")) return new CompoundTag();
        if (!json.get("components").isJsonObject() || json.get("components").toString().length() > 16384)
            throw new IllegalArgumentException("Bounded components object required");
        return (CompoundTag) com.mojang.serialization.JsonOps.INSTANCE.convertTo(net.minecraft.nbt.NbtOps.INSTANCE, json.get("components"));
    }
    static void fields(JsonObject json, String... fields) {
        if (!json.keySet().equals(Set.of(fields))) throw new IllegalArgumentException("Missing or unknown content fields");
    }
    static void version(JsonObject json) {
        if (integer(json.get("schemaVersion")) != 1) throw new IllegalArgumentException("Content schema version");
    }
    static String string(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
            throw new IllegalArgumentException("String required: " + key);
        return value.getAsString();
    }
    static boolean bool(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Boolean required");
        return value.getAsBoolean();
    }
    static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Integer required");
        return value.getAsBigDecimal().intValueExact();
    }
    static double number(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble()))
            throw new IllegalArgumentException("Finite number required");
        return value.getAsDouble();
    }
    static ResourceLocation id(String value) {
        if (!value.contains(":")) throw new IllegalArgumentException("Namespaced ID required");
        return ResourceLocation.parse(value);
    }
}
