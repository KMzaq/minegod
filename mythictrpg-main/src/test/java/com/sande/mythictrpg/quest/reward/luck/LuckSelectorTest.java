package com.sande.mythictrpg.quest.reward.luck;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import java.util.Map;

public final class LuckSelectorTest {
    private static final ResourceLocation POOL = id("test_pool");
    private static int assertions;
    public static void main(String[] args) {
        var model = LuckModel.DEFAULT;
        check(model.eventWeight(100, 1, 17) > 166 && model.eventWeight(100, 1, 17) < 167,
                "Initial event modifier is configurable exp(17*quality*.03)");
        check(model.rewardWeight(100, 3, 17) > 768 && model.rewardWeight(100, 3, 17) < 770,
                "Initial quality-three reward weight at luck XVII");
        check(model.eventWeight(100, -1, 17) < 100 && model.eventWeight(100, 0, 17) == 100,
                "Negative outcomes are reduced, neutral outcomes unchanged");
        check(model.quantity(50, 17, true) == 64 && model.quantity(10, 17, true) == 13,
                "Quantity bonus respects both configured bonus cap and item reward cap");
        check(model.quantity(10, 17, false) == 10, "Equipment without explicit quantity opt-in is never duplicated");
        check(model.quantity(10, 1000, true) == model.quantity(10, 17, true), "Luck level hard cap is XVII");
        check(model.quantity(10, -1, true) == 10, "Negative active levels do not create negative quantities");
        var components = new CompoundTag(); components.putString("minecraft:custom_name", "\"retained\"");
        var material = new LuckyItemCandidate("material", new NpcRewardEntry(effect("stone"), 1, components), 10, 10, 0, 100, true);
        var single = new LuckyItemPool(POOL, List.of(material));
        var selected = LuckSelector.select(single, 17, 456, model);
        check(selected.reward().count() == 13 && selected.reward().components().equals(components)
                && selected.level() == 17 && selected.candidateId().equals("material"), "Resolved result freezes count, identity, components and luck");
        selected.reward().components().putString("minecraft:custom_name", "changed");
        check(selected.reward().components().equals(components), "Frozen component data cannot be mutated through accessors");
        var common = candidate("a_common", "cobblestone", 0);
        var rare = candidate("b_rare", "diamond", 3);
        var pool = new LuckyItemPool(POOL, List.of(common, rare));
        var reverse = new LuckyItemPool(POOL, List.of(rare, common));
        int ordinaryRare = 0, luckyRare = 0;
        for (int seed = 1; seed <= 4096; seed++) {
            var noLuck = LuckSelector.select(pool, 0, seed, model);
            var luck = LuckSelector.select(pool, 17, seed, model);
            if (noLuck.candidateId().equals("b_rare")) ordinaryRare++;
            if (luck.candidateId().equals("b_rare")) luckyRare++;
            if (!luck.equals(LuckSelector.select(reverse, 17, seed, model))) throw new AssertionError("Authored JSON order changed deterministic result");
        }
        check(ordinaryRare > 1700 && ordinaryRare < 2400 && luckyRare > 3400 && luckyRare > ordinaryRare,
                "Authored rarity changes distribution across fixed source seeds, not just text");
        check(LuckSelector.select(single, 17, 456, model).equals(selected), "Identical source/state does not reroll");
        rejects(() -> new LuckModel(Double.NaN, 0, 0, 1), "NaN coefficient rejected");
        rejects(() -> new LuckModel(0, 0, 0, 64), "Unbounded count bonus rejected");
        rejects(() -> candidate("bad", "stone", 6), "Unbounded quality rejected");
        rejects(() -> LuckSelector.weightedIndex(List.of(0.0), 1), "Zero weights rejected");
        rejects(() -> LuckSelector.weightedIndex(List.of(Double.POSITIVE_INFINITY), 1), "Infinite weights rejected");
        rejects(() -> new LuckyItemPool(POOL, List.of(common, common)), "Duplicate candidate identifiers rejected");
        schema();
        System.out.println("LuckSelectorTest: " + assertions + " assertions passed");
    }
    private static void schema() {
        JsonObject pool = json("""
                {"schema_version":1,"kind":"item_pool","candidates":[
                  {"id":"material","item":{"item_id":"minecraft:iron_ingot"},"min_count":2,"max_count":4,"quality":1,"weight":100}]}
                """);
        var parsed = LuckDefinitions.parse(Map.of(POOL, pool));
        check(!parsed.pools().get(POOL).candidates().getFirst().quantityBonusAllowed(), "Missing quantity opt-in defaults false");
        check(parsed.model().equals(LuckModel.DEFAULT), "Default coefficients apply only to explicitly authored pools/events");
        var model = json("""
                {"schema_version":1,"kind":"model","rarity_per_level":0,"quantity_per_level":0,
                 "event_quality_per_level":0,"max_bonus_count":0}
                """);
        check(LuckDefinitions.parse(Map.of(POOL, pool, id("model"), model)).model().eventQualityPerLevel() == 0,
                "Tuning may explicitly disable each bonus");
        rejects(() -> LuckDefinitions.parse(Map.of(id("one"), model, id("two"), model)), "Duplicate global models rejected atomically");
        var typo = pool.deepCopy(); typo.addProperty("global_randomize", true);
        rejects(() -> LuckDefinitions.parse(Map.of(POOL, typo)), "Unknown authoring field rejected");
        var fractional = pool.deepCopy(); fractional.getAsJsonArray("candidates").get(0).getAsJsonObject().addProperty("quality", 1.5);
        rejects(() -> LuckDefinitions.parse(Map.of(POOL, fractional)), "Fractional quality rejected");
        check(LuckDefinitions.parse(Map.of()).pools().isEmpty(), "No authored pool creates no random reward");
    }
    private static LuckyItemCandidate candidate(String name, String item, int quality) {
        return new LuckyItemCandidate(name, new NpcRewardEntry(effect(item), 1), 1, 1, quality, 100, false);
    }
    private static JsonObject json(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mythictrpg", path); }
    private static ResourceLocation effect(String path) { return ResourceLocation.withDefaultNamespace(path); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
}
