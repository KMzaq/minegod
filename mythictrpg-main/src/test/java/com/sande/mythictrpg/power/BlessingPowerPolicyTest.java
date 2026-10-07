package com.sande.mythictrpg.power;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.util.*;

public final class BlessingPowerPolicyTest {
    private static int checks;
    private static final ResourceLocation STRENGTH = ResourceLocation.parse("minecraft:strength");
    private static final ResourceLocation NIGHT = ResourceLocation.parse("minecraft:night_vision");
    public static void main(String[] args) throws Exception {
        JsonObject json = JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/mythictrpg/mythictrpg/blessing_power/default.json"))).getAsJsonObject();
        var table = BlessingPowerPolicy.parse("mythictrpg:default", json);
        check(table.scores().size() == 85, "all 17 effects and five levels");
        check(score(table, "strength", 0) > score(table, "speed", 0), "combat contribution differs");
        check(score(table, "speed", 0) > score(table, "night_vision", 0), "utility does not count as combat strength");
        check(score(table, "resistance", 4) > score(table, "resistance", 0) * 5, "nonlinear high resistance weight");
        check(score(table, "fire_resistance", 4) == score(table, "fire_resistance", 0), "amplifier-insensitive utility does not invent growth");
        Set<String> beforeMilk = BlessingPowerPolicy.effectInputs(Map.of(STRENGTH, 1), Map.of(STRENGTH, 1));
        Set<String> afterMilk = BlessingPowerPolicy.effectInputs(Map.of(STRENGTH, 1), Map.of());
        check(beforeMilk.equals(afterMilk), "milk and death do not lower owned power");
        check(beforeMilk.size() == 1 && BlessingPowerPolicy.total(beforeMilk, table.scores()) == 26, "no active/owned double counting");
        check(BlessingPowerPolicy.effectInputs(Map.of(STRENGTH, 1), Map.of(STRENGTH, 0)).equals(afterMilk), "weaker potion cannot reduce owned score");
        check(BlessingPowerPolicy.effectInputs(Map.of(STRENGTH, 1), Map.of(STRENGTH, 2)).equals(Set.of("minecraft:strength#2")), "stronger temporary effect counted once");
        check(BlessingPowerPolicy.total(BlessingPowerPolicy.effectInputs(Map.of(NIGHT, 4), Map.of()), table.scores()) == 0, "zero is explicitly authored");
        check(BlessingPowerPolicy.total(beforeMilk, table.withOverrides(Map.of("minecraft:strength#1", 77.0))) == 77, "explicit world policy wins");
        check(score(table, "strength", 1) == 26, "overrides do not mutate shared table");
        rejects(() -> BlessingPowerPolicy.total(Set.of("other:unknown#0"), table.scores()), "unknown effect is not silently zero");
        rejects(() -> BlessingPowerPolicy.total(Set.of("minecraft:strength#5"), table.scores()), "unscored level is unknown");
        rejects(() -> BlessingPowerPolicy.effectInputs(Map.of(STRENGTH, -1), Map.of()), "negative level");
        rejects(() -> BlessingPowerPolicy.effectInputs(Map.of(), Map.of(STRENGTH, 256)), "level bound");
        for (String field : List.of("schemaVersion", "effects")) {
            var missing = json.deepCopy(); missing.remove(field); rejects(() -> BlessingPowerPolicy.parse("test:x", missing), "mandatory " + field);
        }
        var duplicate = json.deepCopy(); duplicate.getAsJsonArray("effects").add(duplicate.getAsJsonArray("effects").get(0).deepCopy());
        rejects(() -> BlessingPowerPolicy.parse("test:x", duplicate), "duplicate effect");
        for (JsonElement invalid : List.of(new JsonPrimitive(-1), new JsonPrimitive("12"), new JsonPrimitive(Double.POSITIVE_INFINITY))) {
            var bad = json.deepCopy(); bad.getAsJsonArray("effects").get(0).getAsJsonObject().getAsJsonArray("scores").set(0, invalid);
            rejects(() -> BlessingPowerPolicy.parse("test:x", bad), "bad score");
        }
        var descending = json.deepCopy(); descending.getAsJsonArray("effects").get(0).getAsJsonObject().getAsJsonArray("scores").set(1, new JsonPrimitive(1));
        rejects(() -> BlessingPowerPolicy.parse("test:x", descending), "decreasing levels rejected");
        var fields = json.deepCopy(); fields.addProperty("modelMayChooseScores", true);
        rejects(() -> BlessingPowerPolicy.parse("test:x", fields), "unknown field");
        rejects(() -> BlessingPowerPolicy.total(Set.of("a", "b"), Map.of("a", Double.MAX_VALUE, "b", Double.MAX_VALUE)), "overflow");
        System.out.println("BlessingPowerPolicyTest: " + checks + " assertions passed");
    }
    private static double score(BlessingPowerPolicy policy, String effect, int amplifier) { return policy.scores().get("minecraft:" + effect + "#" + amplifier); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static void rejects(Runnable work, String message) { try { work.run(); } catch (RuntimeException expected) { checks++; return; } throw new AssertionError(message); }
}
