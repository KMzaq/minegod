package com.sande.mythictrpg.quest.reward;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.Optional;
import com.sande.mythictrpg.quest.reward.blessing.GodRank;
import com.sande.mythictrpg.quest.reward.blessing.ResolvedBlessing;

/** Strict shared JSON/NBT codec for table, direct and persisted choice rewards. */
public final class RewardEntryCodec {
    private RewardEntryCodec() {
    }

    public static RewardEntry parse(JsonObject json, String location) {
        String type = string(json, "type", location);
        return switch (type) {
            case "equipment" -> {
                rejectUnknown(json, Set.of("type", "definitionId"), location);
                yield new EquipmentRewardEntry(id(string(json,"definitionId",location),location+".definitionId"));
            }
            case "learn_skill", "accessory", "special_skill" -> {
                rejectUnknown(json, Set.of("type", "definitionId"), location);
                var kind = java.util.Arrays.stream(ProgressionRewardEntry.Kind.values()).filter(k -> k.type().equals(type)).findFirst().orElseThrow();
                yield new ProgressionRewardEntry(kind,id(string(json,"definitionId",location),location+".definitionId"));
            }
            case "lucky_item" -> {
                rejectUnknown(json, Set.of("type", "poolId"), location);
                yield new LuckyRewardEntry(id(string(json, "poolId", location), location + ".poolId"));
            }
            case "adaptive_blessing" -> {
                rejectUnknown(json, Set.of("type", "definitionId"), location);
                yield new AdaptiveBlessingRewardEntry(id(string(json, "definitionId", location), location + ".definitionId"));
            }
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
            case EquipmentRewardEntry equipment -> {
                tag.putString("type","equipment"); tag.putString("definitionId",equipment.definitionId().toString());
            }
            case ProgressionRewardEntry progression -> {
                tag.putString("type",progression.kind().type()); tag.putString("definitionId",progression.definitionId().toString());
            }
            case ResolvedCurrencyRewardEntry currency -> {
                tag.putString("type","resolved_currency"); tag.putLong("baseAmount",currency.baseAmount()); tag.putLong("bonusAmount",currency.bonusAmount());
            }
            case LuckyRewardEntry lucky -> {
                tag.putString("type", "lucky_item"); tag.putString("poolId", lucky.poolId().toString());
            }
            case ResolvedLuckyRewardEntry lucky -> {
                tag.putString("type", "resolved_lucky_item"); tag.putString("poolId", lucky.poolId().toString());
                tag.put("item", save(lucky.selection().reward())); tag.putInt("luckLevel", lucky.selection().level());
                tag.putString("candidateId", lucky.selection().candidateId());
            }
            case AdaptiveBlessingRewardEntry adaptive -> {
                tag.putString("type", "adaptive_blessing");
                tag.putString("definitionId", adaptive.definitionId().toString());
            }
            case ResolvedBlessingRewardEntry resolved -> {
                var value = resolved.blessing();
                tag.putString("type", "resolved_blessing");
                tag.putString("definitionId", value.definitionId().toString());
                tag.putString("godId", value.godId().toString());
                tag.putString("godRank", value.godRank().name());
                tag.putString("candidateId", value.candidateId());
                value.effectId().ifPresent(effect -> tag.putString("effectId", effect.toString()));
                tag.putInt("level", value.level());
                tag.putDouble("intrinsicScore", value.intrinsicScore());
            }
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
            case "equipment" -> new EquipmentRewardEntry(id(tag.getString("definitionId"),"definitionId"));
            case "learn_skill", "accessory", "special_skill" -> new ProgressionRewardEntry(
                    java.util.Arrays.stream(ProgressionRewardEntry.Kind.values()).filter(k -> k.type().equals(type)).findFirst().orElseThrow(),
                    id(tag.getString("definitionId"),"definitionId"));
            case "resolved_currency" -> new ResolvedCurrencyRewardEntry(requireLong(tag,"baseAmount"),requireLong(tag,"bonusAmount"));
            case "lucky_item" -> new LuckyRewardEntry(id(tag.getString("poolId"), "poolId"));
            case "resolved_lucky_item" -> {
                if (!tag.contains("item", Tag.TAG_COMPOUND) || !tag.getCompound("item").getString("type").equals("item"))
                    throw new IllegalArgumentException("Luck result must be a concrete item");
                yield new ResolvedLuckyRewardEntry(id(tag.getString("poolId"), "poolId"),
                        new com.sande.mythictrpg.quest.reward.luck.LuckyRewardSelection((NpcRewardEntry) load(tag.getCompound("item")),
                                requireInt(tag, "luckLevel"), tag.getString("candidateId")));
            }
            case "adaptive_blessing" -> new AdaptiveBlessingRewardEntry(id(tag.getString("definitionId"), "definitionId"));
            case "resolved_blessing" -> {
                if (!tag.contains("intrinsicScore", Tag.TAG_DOUBLE)
                        || tag.contains("effectId") && !tag.contains("effectId", Tag.TAG_STRING))
                    throw new IllegalArgumentException("Invalid stored resolved blessing");
                yield new ResolvedBlessingRewardEntry(new ResolvedBlessing(
                        id(tag.getString("definitionId"), "definitionId"), id(tag.getString("godId"), "godId"),
                        GodRank.valueOf(tag.getString("godRank")), tag.getString("candidateId"),
                        tag.contains("effectId") ? Optional.of(id(tag.getString("effectId"), "effectId")) : Optional.empty(),
                        requireInt(tag, "level"), tag.getDouble("intrinsicScore")));
            }
            case "item" -> {
                if (tag.contains("components") && !tag.contains("components", Tag.TAG_COMPOUND))
                    throw new IllegalArgumentException("Invalid persisted item components");
                yield new NpcRewardEntry(id(tag.getString("itemId"), "itemId"),
                        requireInt(tag, "count"), tag.getCompound("components"));
            }
            case "affinity" -> new AffinityRewardEntry(requireInt(tag, "amount"));
            case "blessing" -> restoreBlessing(id(tag.getString("effectId"), "effectId"),
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

    /** Previously valid Resistance V is migrated to IV; authored JSON remains strict. */
    static BlessingRewardEntry restoreBlessing(ResourceLocation effectId, int durationTicks, int amplifier) {
        int migrated = effectId.equals(ResourceLocation.withDefaultNamespace("resistance")) && amplifier == 4 ? 3 : amplifier;
        return new BlessingRewardEntry(effectId, durationTicks, migrated);
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
