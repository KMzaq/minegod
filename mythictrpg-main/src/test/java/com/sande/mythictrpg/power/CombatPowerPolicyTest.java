package com.sande.mythictrpg.power;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sande.mythictrpg.quest.dynamic.CombatPowerAssessment;
import net.minecraft.nbt.*;
import java.util.*;

/** Synthetic numbers verify contract mechanics; no production content/balance is authored here. */
public final class CombatPowerPolicyTest {
    private static int checks;
    private static final String POLICY = """
        {"schemaVersion":1,"aggregation":"SUM_SLOT_BEST","basePower":2,
         "equipmentCoefficient":1,"effectCoefficient":2,"permanentCoefficient":3,
         "permanentMode":"ACTUAL_PERMANENT_MODIFIERS_ONLY","lowRatioExclusive":0.5,
         "items":[],"effects":[{"effectId":"minecraft:strength","amplifier":0,"score":4}],
         "permanentModifiers":[{"attributeId":"minecraft:generic.attack_damage","modifierId":"test:earned",
           "operation":"ADD_VALUE","coefficient":2}],
         "recommendations":[{"progress":{"test:story":{"minimum":0,"maximum":4}},"recommendedPower":40},
           {"progress":{"test:story":{"minimum":5,"maximum":10}},"recommendedPower":100}]}
        """;
    public static void main(String[] args) {
        policy(); invalidPolicies(); persistence();
        System.out.println("CombatPowerPolicyTest: " + checks + " assertions passed");
    }
    private static JsonObject json() { return JsonParser.parseString(POLICY).getAsJsonObject(); }
    private static CombatPowerPolicy parse(JsonObject json) { return CombatPowerPolicy.parse("test:policy", json); }
    private static void policy() {
        CombatPowerPolicy policy = parse(json());
        var gear = List.of(new CombatPowerPolicy.Gear("weapon", 5), new CombatPowerPolicy.Gear("weapon", 12), new CombatPowerPolicy.Gear("body", 8));
        var summed = policy.evaluate(gear, Map.of(), Set.of(), Map.of());
        check(summed.actualPower() == 22 && summed.catchUpRewardTierBonus() == 0, "best per slot, not every acquisition summed");
        var maximumJson = json(); maximumJson.addProperty("aggregation", "MAX_ALL");
        var maximum = parse(maximumJson).evaluate(gear, Map.of(), Set.of(), Map.of());
        check(maximum.actualPower() == 14 && maximum.catchUpRewardTierBonus() == 1, "explicit max-all choice");
        var exact = policy.evaluate(List.of(new CombatPowerPolicy.Gear("weapon", 18)), Map.of(), Set.of(), Map.of());
        check(exact.actualPower() == 20 && exact.catchUpRewardTierBonus() == 0, "strict threshold, not <=");
        check(policy.evaluate(gear, Map.of("test:story", 5), Set.of(), Map.of()).catchUpRewardTierBonus() == 1, "world progress recommended row");
        check(unavailable(policy.evaluate(gear, Map.of("test:story", 11), Set.of(), Map.of())), "no authored progress row");
        var adjusted = policy.evaluate(gear, Map.of(), Set.of("minecraft:strength#0"),
                Map.of("minecraft:generic.attack_damage#test:earned#ADD_VALUE", 2.0));
        check(adjusted.actualPower() == 42, "explicit active-effect and actual modifier coefficients");
        check(unavailable(policy.evaluate(gear, Map.of(), Set.of("minecraft:strength#1"), Map.of())), "unknown amplifier failclosed");
        check(unavailable(policy.evaluate(gear, Map.of(), Set.of(), Map.of("unknown", 99.0))), "unknown permanent source failclosed");
        check(unavailable(policy.evaluate(List.of(new CombatPowerPolicy.Gear("weapon", Double.MAX_VALUE)), Map.of(),
                Set.of(), Map.of("minecraft:generic.attack_damage#test:earned#ADD_VALUE", Double.MAX_VALUE))), "overflow failclosed");
        var overlap = json(); overlap.getAsJsonArray("recommendations").add(overlap.getAsJsonArray("recommendations").get(0).deepCopy());
        check(unavailable(parse(overlap).evaluate(gear, Map.of(), Set.of(), Map.of())), "ambiguous ranges do not pick arbitrary row");
    }
    private static void invalidPolicies() {
        for (String field : List.copyOf(json().keySet())) {
            var missing = json(); missing.remove(field); rejects(() -> parse(missing), "mandatory field " + field);
        }
        var unknown = json(); unknown.addProperty("modelPower", 50); rejects(() -> parse(unknown), "unknown fields");
        var percent = json(); percent.addProperty("lowRatioExclusive", 1); rejects(() -> parse(percent), "threshold bound");
        var numericString = json(); numericString.addProperty("schemaVersion", "1"); rejects(() -> parse(numericString), "numeric strings not accepted");
        var unsupported = json(); unsupported.addProperty("permanentMode", "GUESS_FROM_TITLE"); rejects(() -> parse(unsupported), "no imaginary permanent source");
        var notFinite = json(); notFinite.addProperty("basePower", Double.POSITIVE_INFINITY); rejects(() -> parse(notFinite), "nonfinite coefficient");
        var duplicate = json(); duplicate.getAsJsonArray("effects").add(duplicate.getAsJsonArray("effects").get(0).deepCopy());
        rejects(() -> parse(duplicate), "duplicate effect input");
    }
    private static void persistence() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(); RewardPowerState state = new RewardPowerState();
        state.beginPlayer(a, true); state.beginPlayer(b, false);
        CompoundTag strong = item("test:strong"), weak = item("test:weak");
        state.record(a, strong); state.record(a, weak); state.record(a, strong);
        check(state.history(a).orElseThrow().items().size() == 2, "retain all distinct variants, deduplicate repeated reward");
        check(state.history(b).orElseThrow().items().isEmpty() && !state.history(b).orElseThrow().complete(), "separate player legacy coverage");
        state.beginPlayer(b, true); check(!state.history(b).orElseThrow().complete(), "cannot upgrade unknown coverage on next login");
        var exposed = state.history(a).orElseThrow().items().getFirst(); exposed.putString("id", "test:tampered");
        check(state.history(a).orElseThrow().items().getFirst().getString("id").equals("test:strong"), "immutable acquisition evidence");
        UUID session = UUID.randomUUID(); state.cleanSession(session);
        CompoundTag saved = state.save(new CompoundTag(), null); var restored = RewardPowerState.load(saved, null);
        check(restored.ready() && restored.cleanSession().equals(session), "NBT session persistence");
        check(restored.history(a).orElseThrow().complete() && restored.history(a).orElseThrow().items().size() == 2, "history NBT round trip");
        restored.invalidateCoverage("UNCLEAN_OR_UNVERIFIED_RESTART");
        check(!restored.history(a).orElseThrow().complete() && restored.history(a).orElseThrow().items().size() == 2, "gap does not prune stronger gear");
        var corrupt = saved.copy(); corrupt.getList("players", Tag.TAG_COMPOUND).getCompound(0).remove("items");
        var rejected = RewardPowerState.load(corrupt, null);
        check(!rejected.ready() && rejected.save(new CompoundTag(), null).equals(corrupt), "malformed evidence preserved, not empty-complete");
        var wrongList = saved.copy(); ListTag strings = new ListTag(); strings.add(StringTag.valueOf("not a player")); wrongList.put("players", strings);
        check(!RewardPowerState.load(wrongList, null).ready(), "wrong list element type failclosed");
        for (int index = 0; index < 513; index++) state.record(a, item("test:variant_" + index));
        check(!state.history(a).orElseThrow().complete() && state.history(a).orElseThrow().items().contains(strong), "bounded history never evicts old best");
    }
    private static CompoundTag item(String id) { CompoundTag tag = new CompoundTag(); tag.putString("id", id); tag.putInt("count", 1); return tag; }
    private static boolean unavailable(CombatPowerAssessment assessment) { return assessment.status() == CombatPowerAssessment.Status.UNAVAILABLE && assessment.catchUpRewardTierBonus() == 0; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static void rejects(Runnable runnable, String message) { try { runnable.run(); } catch (RuntimeException expected) { checks++; return; } throw new AssertionError(message); }
}
