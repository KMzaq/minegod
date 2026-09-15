package com.sande.mythictrpg.shop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.economy.CurrencyState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
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

/** Strict Datapack catalog for infinite-stock purchase and sale shops. */
public final class ShopCatalogManager extends SimplePreparableReloadListener<Map<ResourceLocation, ShopDefinition>> {
    public static final ShopCatalogManager INSTANCE = new ShopCatalogManager();
    private static final FileToIdConverter CONVERTER = FileToIdConverter.json("mythictrpg/shops");
    private volatile Map<ResourceLocation, ShopDefinition> shops = Map.of();

    private ShopCatalogManager() {
    }

    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(this);
    }

    public Optional<ShopDefinition> find(ResourceLocation shopId) {
        return Optional.ofNullable(shops.get(shopId));
    }

    public Optional<ShopProductDefinition> product(ShopProductKey key) {
        return find(key.shopId()).flatMap(shop -> shop.product(key.productId()));
    }

    public List<ShopDefinition> shops(ShopType type) {
        return shops.values().stream().filter(shop -> shop.type() == type)
                .sorted((left, right) -> left.id().compareTo(right.id())).toList();
    }

    public Set<ResourceLocation> ids() {
        return shops.keySet();
    }

    @Override
    protected Map<ResourceLocation, ShopDefinition> prepare(ResourceManager resources, ProfilerFiller profiler) {
        Map<ResourceLocation, ShopDefinition> parsed = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        CONVERTER.listMatchingResources(resources).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parse(entry.getKey(), entry.getValue(), parsed, errors));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Rejected shop catalog reload with " + errors.size() + " error(s)");
        }
        return Map.copyOf(parsed);
    }

    private static void parse(ResourceLocation file, Resource resource,
            Map<ResourceLocation, ShopDefinition> destination, List<String> errors) {
        ResourceLocation shopId = CONVERTER.fileToId(file);
        try (Reader reader = resource.openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) throw new IllegalArgumentException("Root must be an object");
            JsonObject json = root.getAsJsonObject();
            rejectUnknown(json, Set.of("schemaVersion", "type", "displayName", "products"), "shop");
            if (integer(json, "schemaVersion") != 1) {
                throw new IllegalArgumentException("Unsupported schemaVersion");
            }
            if (!json.has("products") || !json.get("products").isJsonArray()) {
                throw new IllegalArgumentException("Shop requires a products array");
            }
            List<ShopProductDefinition> products = new ArrayList<>();
            int index = 0;
            for (JsonElement element : json.getAsJsonArray("products")) {
                if (!element.isJsonObject()) throw new IllegalArgumentException("Product must be an object");
                products.add(parseProduct(element.getAsJsonObject(), "products[" + index + "]"));
                index++;
            }
            ShopDefinition shop = new ShopDefinition(shopId, ShopType.parse(string(json, "type")),
                    string(json, "displayName"), products);
            if (destination.putIfAbsent(shopId, shop) != null) {
                throw new IllegalArgumentException("Duplicate shop " + shopId);
            }
        } catch (Exception exception) {
            errors.add(shopId + ": " + exception.getMessage());
            MythicTrpg.LOGGER.error("Shop catalog {} failed", shopId, exception);
        }
    }

    private static ShopProductDefinition parseProduct(JsonObject json, String location) {
        rejectUnknown(json, Set.of("id", "displayName", "item", "price", "unlockedByDefault"), location);
        ResourceLocation productId = id(string(json, "id"), location + ".id");
        if (!json.has("item") || !json.get("item").isJsonObject()) {
            throw new IllegalArgumentException(location + " requires item object");
        }
        JsonObject item = json.getAsJsonObject("item");
        rejectUnknown(item, Set.of("id", "count", "components"), location + ".item");
        ResourceLocation itemId = id(string(item, "id"), location + ".item.id");
        if (!BuiltInRegistries.ITEM.containsKey(itemId)) {
            throw new IllegalArgumentException("Unknown item " + itemId + " at " + location);
        }
        int count = integer(item, "count");
        int maximumStack = BuiltInRegistries.ITEM.get(itemId).getDefaultInstance().getMaxStackSize();
        if (count < 1 || count > maximumStack) {
            throw new IllegalArgumentException("Item count must be 1.." + maximumStack + " for " + itemId);
        }
        CompoundTag itemData = new CompoundTag();
        itemData.putString("id", itemId.toString());
        itemData.putInt("count", count);
        if (item.has("components")) {
            if (!item.get("components").isJsonObject()) {
                throw new IllegalArgumentException(location + ".item.components must be an object");
            }
            var converted = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, item.get("components"));
            if (!(converted instanceof CompoundTag components)) {
                throw new IllegalArgumentException(location + ".item.components did not decode as a compound");
            }
            itemData.put("components", components);
        }
        long price = longInteger(json, "price");
        if (price < 1L || price > CurrencyState.MAX_BALANCE) {
            throw new IllegalArgumentException("Product price must be 1.." + CurrencyState.MAX_BALANCE);
        }
        return new ShopProductDefinition(productId, string(json, "displayName"), itemData,
                price, optionalBoolean(json, "unlockedByDefault", false));
    }

    @Override
    protected void apply(Map<ResourceLocation, ShopDefinition> prepared,
            ResourceManager resources, ProfilerFiller profiler) {
        shops = prepared;
        int products = prepared.values().stream().mapToInt(shop -> shop.products().size()).sum();
        MythicTrpg.LOGGER.info("Loaded {} shops with {} products", shops.size(), products);
    }

    private static void rejectUnknown(JsonObject json, Set<String> allowed, String location) {
        json.keySet().forEach(field -> {
            if (!allowed.contains(field)) throw new IllegalArgumentException(
                    "Unknown " + location + " field '" + field + "'");
        });
    }

    private static String string(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException("Missing string '" + key + "'");
        }
        String value = json.get(key).getAsString().trim();
        if (value.isBlank()) throw new IllegalArgumentException("Blank string '" + key + "'");
        return value;
    }

    private static int integer(JsonObject json, String key) {
        long value = longInteger(json, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Field '" + key + "' is outside integer range");
        }
        return (int) value;
    }

    private static long longInteger(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("Missing integer '" + key + "'");
        }
        double raw = json.get(key).getAsDouble();
        long value = json.get(key).getAsLong();
        if (!Double.isFinite(raw) || raw != value) {
            throw new IllegalArgumentException("Field '" + key + "' must be an integer");
        }
        return value;
    }

    private static boolean optionalBoolean(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) return fallback;
        if (!json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException("Field '" + key + "' must be boolean");
        }
        return json.get(key).getAsBoolean();
    }

    private static ResourceLocation id(String value, String field) {
        ResourceLocation parsed = ResourceLocation.tryParse(value);
        if (parsed == null || !value.contains(":")) {
            throw new IllegalArgumentException("Invalid namespaced ID in " + field + ": " + value);
        }
        return parsed;
    }
}
