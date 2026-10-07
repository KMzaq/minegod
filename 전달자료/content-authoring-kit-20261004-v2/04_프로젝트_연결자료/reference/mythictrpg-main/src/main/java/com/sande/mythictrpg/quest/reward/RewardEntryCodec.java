package com.sande.mythictrpg.quest.reward;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/** Strict shared JSON/NBT codec for table, direct and persisted choice rewards. */
public final class RewardEntryCodec {
    private RewardEntryCodec() {
    }

    public static RewardEntry parse(JsonObject json, String location) {
        String type = string(json, "type", location);
        return switch (type) {
            case "item" -> {
                rejectUnknown(json, Set.of("type", "itemId", "count", "components"), location);
                ResourceLocation itemId = id(string(json, "itemId", location), location + ".itemId");
                if (!BuiltInRegistries.ITEM.containsKey(itemId)) {
                    throw new IllegalArgumentException("Unknown reward item " + itemId + " at " + location);
                }
                CompoundTag components = new CompoundTag();
                if (json.has("components")) {
                    if (!json.get("components").isJsonObject())
                        throw new IllegalArgumentException("Item components must be an object at " + location);
                    if (json.get("components").toString().length() > 16_384)
                        throw new IllegalArgumentException("Item components are too large at " + location);
                    components = (CompoundTag) com.mojang.serialization.JsonOps.INSTANCE.convertTo(
                            net.minecraft.nbt.NbtOps.INSTANCE, json.get("components"));
                }
                yield new NpcRewardEntry(itemId, integer(json, "count", location), components);
            }
            case "affinity" -> {
                rejectUnknown(json, Set.of("type", "amount"), location);
                yield new AffinityRewardEntry(integer(json, "amount", location));
            }
            case "blessing" -> {
                rejectUnknown(json, Set.of("type", "effectId", "durationTicks", "amplifier"), location);
                ResourceLocation effectId = id(string(json, "effectId", location), location + ".effectId");
                if (!BuiltInRegistries.MOB_EFFECT.containsKey(effectId)) {
                    throw new IllegalArgumentException("Unknown blessing effect " + effectId + " at " + location);
                }
                BlessingRewardEntry blessing = new BlessingRewardEntry(effectId, integer(json, "durationTicks", location),
                        integer(json, "amplifier", location));
                if (blessing.permanent() && BuiltInRegistries.MOB_EFFECT.get(effectId).isInstantenous())
                    throw new IllegalArgumentException("Instant effects cannot be permanent blessings at " + location);
                yield blessing;
            }
            case "title" -> {
                rejectUnknown(json, Set.of("type", "titleId", "displayName"), location);
                yield new TitleRewardEntry(id(string(json, "titleId", location), location + ".titleId"),
                        string(json, "displayName", location));
            }
            case "watch" -> {
                rejectUnknown(json, Set.of("type", "godId", "displayName"), location);
                yield new WatchRewardEntry(id(string(json, "godId", location), location + ".godId"),
                        string(json, "displayName", location));
            }
            case "currency" -> {
                rejectUnknown(json, Set.of("type", "amount"), location);
                yield new CurrencyRewardEntry(longInteger(json, "amount", location));
            }
            case "unlock_shop_product" -> {
                rejectUnknown(json, Set.of("type", "shopId", "productId"), location);
                yield new UnlockShopProductRewardEntry(
                        id(string(json, "shopId", location), location + ".shopId"),
                        id(string(json, "productId", location), location + ".productId"));
            }
            default -> throw new IllegalArgumentException("Unknown reward type '" + type + "' at " + location);
        };
    }

    public static CompoundTag save(RewardEntry reward) {
        CompoundTag tag = new CompoundTag();
        switch (reward) {
            case NpcRewardEntry item -> {
                tag.putString("type", "item");
                tag.putString("itemId", item.itemId().toString());
                tag.putInt("count", item.count());
                if (!item.components().isEmpty()) tag.put("components", item.components());
            }
            case AffinityRewardEntry affinity -> {
                tag.putString("type", "affinity");
                tag.putInt("amount", affinity.amount());
            }
            case BlessingRewardEntry blessing -> {
                tag.putString("type", "blessing");
                tag.putString("effectId", blessing.effectId().toString());
                tag.putInt("durationTicks", blessing.durationTicks());
                tag.putInt("amplifier", blessing.amplifier());
            }
            case TitleRewardEntry title -> {
                tag.putString("type", "title");
                tag.putString("titleId", title.titleId().toString());
                tag.putString("displayName", title.displayName());
            }
            case WatchRewardEntry watch -> {
                tag.putString("type", "watch");
                tag.putString("godId", watch.godId().toString());
                tag.putString("displayName", watch.displayName());
            }
            case CurrencyRewardEntry currency -> {
                tag.putString("type", "currency");
                tag.putLong("amount", currency.amount());
            }
            case UnlockShopProductRewardEntry unlock -> {
                tag.putString("type", "unlock_shop_product");
                tag.putString("shopId", unlock.shopId().toString());
                tag.putString("productId", unlock.productId().toString());
            }
        }
        return tag;
    }

    public static RewardEntry load(CompoundTag tag) {
        String type = tag.getString("type");
        return switch (type) {
            case "item" -> {
                if (tag.contains("components") && !tag.contains("components", Tag.TAG_COMPOUND))
                    throw new IllegalArgumentException("Invalid persisted item components");
                yield new NpcRewardEntry(id(tag.getString("itemId"), "itemId"),
                        requireInt(tag, "count"), tag.getCompound("components"));
            }
            case "affinity" -> new AffinityRewardEntry(requireInt(tag, "amount"));
            case "blessing" -> new BlessingRewardEntry(id(tag.getString("effectId"), "effectId"),
                    requireInt(tag, "durationTicks"), requireInt(tag, "amplifier"));
            case "title" -> new TitleRewardEntry(id(tag.getString("titleId"), "titleId"),
                    tag.getString("displayName"));
            case "watch" -> new WatchRewardEntry(id(tag.getString("godId"), "godId"), tag.getString("displayName"));
            case "currency" -> new CurrencyRewardEntry(requireLong(tag, "amount"));
            case "unlock_shop_product" -> new UnlockShopProductRewardEntry(
                    id(tag.getString("shopId"), "shopId"), id(tag.getString("productId"), "productId"));
            default -> throw new IllegalArgumentException("Unknown persisted reward type '" + type + "'");
        };
    }

    private static void rejectUnknown(JsonObject json, Set<String> allowed, String location) {
        json.keySet().forEach(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("Unknown reward field '" + field + "' at " + location);
            }
        });
    }

    private static String string(JsonObject json, String key, String location) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException("Missing string '" + key + "' at " + location);
        }
        String value = json.get(key).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Blank string '" + key + "' at " + location);
        }
        return value;
    }

    private static int integer(JsonObject json, String key, String location) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("Missing integer '" + key + "' at " + location);
        }
        double raw = json.get(key).getAsDouble();
        int value = json.get(key).getAsInt();
        if (!Double.isFinite(raw) || raw != value) {
            throw new IllegalArgumentException("Field '" + key + "' must be an integer at " + location);
        }
        return value;
    }

    private static int requireInt(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw new IllegalArgumentException("Missing persisted integer '" + key + "'");
        }
        double raw = tag.getDouble(key);
        int value = tag.getInt(key);
        if (!Double.isFinite(raw) || raw != value)
            throw new IllegalArgumentException("Invalid persisted integer '" + key + "'");
        return value;
    }

    private static long requireLong(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw new IllegalArgumentException("Missing persisted integer '" + key + "'");
        }
        return tag.getLong(key);
    }

    private static long longInteger(JsonObject json, String key, String location) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("Missing integer '" + key + "' at " + location);
        }
        double raw = json.get(key).getAsDouble();
        long value = json.get(key).getAsLong();
        if (!Double.isFinite(raw) || raw != value) {
            throw new IllegalArgumentException("Field '" + key + "' must be an integer at " + location);
        }
        return value;
    }

    private static ResourceLocation id(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !value.contains(":")) {
            throw new IllegalArgumentException("Invalid namespaced ID in " + field + ": " + value);
        }
        return id;
    }
}
