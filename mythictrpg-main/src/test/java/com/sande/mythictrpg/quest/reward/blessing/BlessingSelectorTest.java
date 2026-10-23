package com.sande.mythictrpg.quest.reward.blessing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/** Deterministic authored selection, cap, allowlist, schema and fallback regression checks. */
public final class BlessingSelectorTest {
    private static final ResourceLocation GOD = id("test_god"), DEFINITION = id("test_blessing");
    private static final ResourceLocation STRENGTH = effect("strength"), SPEED = effect("speed"),
            LUCK = effect("luck"), RESISTANCE = effect("resistance");
    private static final ToDoubleFunction<Map<ResourceLocation, Integer>> SCORE = levels ->
            10 * levels.getOrDefault(STRENGTH, 0) + levels.getOrDefault(SPEED, 0);
    private static int assertions;

    public static void main(String[] args) {
        rankAndLevels();
        absoluteAndRelative();
        allowlistCapsAndFallback();
        preferenceAndDeterminism();
        schemaAndCrossReferences();
        System.out.println("BlessingSelectorTest: " + assertions + " assertions passed");
    }

    private static void rankAndLevels() {
        check(GodRank.values().length == 6, "Exactly six independent god ranks");
        for (GodRank rank : GodRank.values()) check(GodRank.parse(rank.serializedName()) == rank, "Rank roundtrip " + rank);
        check(BlessingLevels.amplifier(STRENGTH, 17) == 16 && BlessingLevels.level(STRENGTH, 16) == 17,
                "Public XVII converts exactly once to amplifier 16");
        check(BlessingLevels.amplifier(RESISTANCE, 4) == 3, "Resistance IV is amplifier 3");
        rejects(() -> BlessingLevels.amplifier(RESISTANCE, 5), "Resistance V rejected");
        rejects(() -> BlessingLevels.level(STRENGTH, 17), "Level XVIII rejected");
        rejects(() -> new ResolvedBlessing(DEFINITION, GOD, GodRank.LOW, "ownership_only", Optional.empty(), 1, 2),
                "Ownership-only result cannot contain a fabricated effect level");
        rejects(() -> new ResolvedBlessing(DEFINITION, GOD, GodRank.LOW, "test", Optional.of(STRENGTH), 1, Double.NaN),
                "Non-finite intrinsic score rejected");
    }

    private static void absoluteAndRelative() {
        var baseline = new HashMap<>(Map.of(STRENGTH, 5));
        var low = select(def(BlessingDefinition.Fallback.NONE, floor("fixed_three", STRENGTH, 3)),
                policy(Map.of(STRENGTH, rule(17, 0))), caps(Map.of(STRENGTH, 17)), baseline);
        check(low.resolved().isEmpty() && low.status() == BlessingSelector.Status.NO_USEFUL_CANDIDATE,
                "Existing V plus lower fixed III is not falsely advertised as an improvement");
        var high = select(def(BlessingDefinition.Fallback.NONE, floor("fixed_eight", STRENGTH, 8)),
                policy(Map.of(STRENGTH, rule(17, 0))), caps(Map.of(STRENGTH, 17)), baseline);
        check(high.resolved().orElseThrow().level() == 8 && high.beforeScore() == 50 && high.afterScore() == 80,
                "V plus fixed VIII previews whole recomputed VIII state");
        var upgraded = select(def(BlessingDefinition.Fallback.NONE, upgrade("plus_one", STRENGTH, 1)),
                policy(Map.of(STRENGTH, rule(17, 0))), caps(Map.of(STRENGTH, 17)), Map.of(STRENGTH, 10));
        check(upgraded.resolved().orElseThrow().level() == 11, "X plus authored one resolves once to XI");
        check(baseline.equals(Map.of(STRENGTH, 5)), "Pure selection does not mutate current state");
        check(upgraded.resolved().orElseThrow().intrinsicScore() == 7,
                "Intrinsic ownership score remains separate from effect-stat preview");
        rejects(() -> select(def(BlessingDefinition.Fallback.NONE, floor("one", STRENGTH, 1)),
                policy(Map.of(STRENGTH, rule(17, 0))), caps(Map.of(STRENGTH, 17)), Map.of(STRENGTH, -1)),
                "Invalid baseline levels fail closed");
    }

    private static void allowlistCapsAndFallback() {
        var preferred = def(BlessingDefinition.Fallback.NONE, floor("unique_luck", LUCK, 1), floor("speed", SPEED, 2));
        var result = select(preferred, policy(Map.of(SPEED, rule(17, 0))), caps(Map.of(SPEED, 17, LUCK, 17)), Map.of());
        check(result.resolved().orElseThrow().effectId().orElseThrow().equals(SPEED),
                "Unique effects never appear without the specific god allowlist");
        var bound = def(BlessingDefinition.Fallback.NONE, upgrade("strength_increment", STRENGTH, 1), floor("speed", SPEED, 2));
        result = select(bound, policy(Map.of(STRENGTH, rule(17, 0), SPEED, rule(17, 1))),
                caps(Map.of(STRENGTH, 5, SPEED, 2)), Map.of(STRENGTH, 10));
        check(result.resolved().orElseThrow().effectId().orElseThrow().equals(SPEED),
                "Weak rank cannot use current strong baseline to exceed its cap");
        result = select(bound, policy(Map.of(STRENGTH, rule(5, 0), SPEED, rule(17, 1))),
                caps(Map.of(STRENGTH, 17, SPEED, 17)), Map.of(STRENGTH, 5));
        check(result.resolved().orElseThrow().effectId().orElseThrow().equals(SPEED), "God cap remains stricter than rank cap");
        result = select(def(BlessingDefinition.Fallback.NONE, upgrade("plus_two", STRENGTH, 2)),
                policy(Map.of(STRENGTH, rule(17, 0))), caps(Map.of(STRENGTH, 11)), Map.of(STRENGTH, 10));
        check(result.resolved().isEmpty(), "An increment exceeding the cap is not silently reduced");
        var ownership = select(def(BlessingDefinition.Fallback.ONLY_OWNERSHIP, floor("fixed", STRENGTH, 1)),
                policy(Map.of(STRENGTH, rule(17, 0))), caps(Map.of(STRENGTH, 17)), Map.of(STRENGTH, 10));
        check(ownership.status() == BlessingSelector.Status.OWNERSHIP_ONLY && ownership.resolved().orElseThrow().ownershipOnly()
                && ownership.resolved().orElseThrow().level() == 0 && ownership.beforeScore() == ownership.afterScore(),
                "Authored ownership fallback creates a retained blessing without a fake active effect");
        rejects(() -> ownership.resolved().orElseThrow().amplifier(), "Ownership-only cannot become an accidental amplifier");
        rejects(() -> new GodBlessingPolicy(GOD, Map.of(RESISTANCE, rule(5, 0))), "God policy cannot raise global resistance cap");
        rejects(() -> caps(Map.of(RESISTANCE, 5)), "Rank policy cannot raise global resistance cap");
    }

    private static void preferenceAndDeterminism() {
        var candidates = def(BlessingDefinition.Fallback.NONE, floor("strength", STRENGTH, 17), floor("luck", LUCK, 1));
        var result = select(candidates, policy(Map.of(STRENGTH, rule(17, 20), LUCK, rule(17, 0))),
                caps(Map.of(STRENGTH, 17, LUCK, 17)), Map.of());
        check(result.resolved().orElseThrow().effectId().orElseThrow().equals(LUCK) && result.afterScore() == 0,
                "Authored preferred unique effect wins even when its direct combat score is zero");
        var two = def(BlessingDefinition.Fallback.NONE, floor("speed", SPEED, 4), floor("strength", STRENGTH, 2));
        result = select(two, policy(Map.of(STRENGTH, rule(17, 0), SPEED, rule(17, 0))), caps(Map.of(STRENGTH, 17, SPEED, 17)), Map.of());
        check(result.resolved().orElseThrow().effectId().orElseThrow().equals(STRENGTH), "Equal preference uses full score gain");
        var tied = def(BlessingDefinition.Fallback.NONE, floor("z_last", LUCK, 1), floor("a_first", LUCK, 1));
        var reversed = def(BlessingDefinition.Fallback.NONE, floor("a_first", LUCK, 1), floor("z_last", LUCK, 1));
        var god = policy(Map.of(LUCK, rule(17, 0)));
        var rank = caps(Map.of(LUCK, 17));
        check(select(tied, god, rank, Map.of()).equals(select(reversed, god, rank, Map.of()))
                && select(tied, god, rank, Map.of()).resolved().orElseThrow().candidateId().equals("a_first"),
                "Same state chooses deterministic candidate regardless of JSON candidate order");
        rejects(() -> BlessingSelector.select(tied, god, rank, Map.of(), ignored -> Double.NaN), "Non-finite preview rejected");
    }

    private static void schemaAndCrossReferences() {
        var definition = json("""
                {"schema_version":1,"kind":"definition","god_id":"mythictrpg:test_god","intrinsic_score":7,
                 "fallback":"none","candidates":[{"id":"lucky","effect":"minecraft:luck","mode":"floor","level":1}]}
                """);
        var god = json("""
                {"schema_version":1,"kind":"god_policy","god_id":"mythictrpg:test_god",
                 "effects":{"minecraft:luck":{"max_level":1,"priority":0}}}
                """);
        var rank = json("""
                {"schema_version":1,"kind":"rank_policy","rank":"lowest","effect_caps":{"minecraft:luck":1}}
                """);
        var documents = Map.of(DEFINITION, definition, id("policy/test"), god, id("rank/lowest"), rank);
        var parsed = BlessingDefinitions.parse(documents);
        check(parsed.definitions().get(DEFINITION).intrinsicScore() == 7
                && parsed.godPolicies().get(GOD).effects().get(LUCK).priority() == 0
                && parsed.rankPolicies().get(GodRank.LOWEST).effectCaps().get(LUCK) == 1,
                "Definition, god allowlist and independent rank caps load as one snapshot");
        rejects(() -> BlessingDefinitions.parse(Map.of(DEFINITION, definition)), "Missing god allowlist rejected atomically");
        var duplicate = new HashMap<>(documents);
        duplicate.put(id("duplicate_rank"), rank);
        rejects(() -> BlessingDefinitions.parse(duplicate), "Duplicate rank policies rejected");
        var bad = definition.deepCopy();
        bad.addProperty("implicit_boost", true);
        rejects(() -> BlessingDefinitionSchema.definition(DEFINITION, bad), "Unknown authoring fields rejected");
        var fractional = definition.deepCopy();
        fractional.getAsJsonArray("candidates").get(0).getAsJsonObject().addProperty("level", 1.5);
        rejects(() -> BlessingDefinitionSchema.definition(DEFINITION, fractional), "Fractional public level rejected");
        var unallowed = definition.deepCopy();
        unallowed.getAsJsonArray("candidates").get(0).getAsJsonObject().addProperty("effect", "minecraft:water_breathing");
        rejects(() -> BlessingDefinitions.parse(Map.of(DEFINITION, unallowed, id("policy/test"), god)),
                "Definition cannot inject a unique effect outside its god allowlist");
        check(BlessingDefinitions.parse(Map.of()).definitions().isEmpty(), "No authored files means no adaptive behavior");
        rejects(() -> parsed.definitions().clear(), "Published snapshot is immutable");
    }

    private static BlessingSelector.Selection select(BlessingDefinition def, GodBlessingPolicy god,
            BlessingRankPolicy rank, Map<ResourceLocation, Integer> levels) {
        return BlessingSelector.select(def, god, rank, levels, SCORE);
    }
    private static BlessingDefinition def(BlessingDefinition.Fallback fallback, BlessingCandidate... candidates) {
        return new BlessingDefinition(DEFINITION, GOD, 7, List.of(candidates), fallback);
    }
    private static BlessingCandidate floor(String id, ResourceLocation effect, int level) {
        return new BlessingCandidate(id, effect, BlessingCandidate.Mode.FLOOR, level);
    }
    private static BlessingCandidate upgrade(String id, ResourceLocation effect, int increment) {
        return new BlessingCandidate(id, effect, BlessingCandidate.Mode.UPGRADE, increment);
    }
    private static GodBlessingPolicy policy(Map<ResourceLocation, GodBlessingPolicy.EffectRule> effects) { return new GodBlessingPolicy(GOD, effects); }
    private static GodBlessingPolicy.EffectRule rule(int level, int priority) { return new GodBlessingPolicy.EffectRule(level, priority); }
    private static BlessingRankPolicy caps(Map<ResourceLocation, Integer> effects) { return new BlessingRankPolicy(GodRank.LOW, effects); }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mythictrpg", path); }
    private static ResourceLocation effect(String path) { return ResourceLocation.withDefaultNamespace(path); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
}
