package com.sande.mythictrpg.quest.reward.blessing;

import net.minecraft.resources.ResourceLocation;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/** Pure, deterministic acquisition selection. Callers supply long-lived baseline effects, excluding potions. */
public final class BlessingSelector {
    private BlessingSelector() { }
    public enum Status { RESOLVED, OWNERSHIP_ONLY, NO_USEFUL_CANDIDATE, INVALID_POLICY }
    public record Selection(Status status, Optional<ResolvedBlessing> resolved,
            double beforeScore, double afterScore, String reason) { }
    private record Scored(BlessingCandidate candidate, int level, int priority, double afterScore) { }

    public static Selection select(BlessingDefinition definition, GodBlessingPolicy godPolicy,
            BlessingRankPolicy rankPolicy, Map<ResourceLocation, Integer> currentLevels,
            ToDoubleFunction<Map<ResourceLocation, Integer>> scorer) {
        Objects.requireNonNull(definition);
        Objects.requireNonNull(godPolicy);
        Objects.requireNonNull(rankPolicy);
        Objects.requireNonNull(scorer);
        if (!definition.godId().equals(godPolicy.godId())) {
            throw new IllegalArgumentException("Blessing definition belongs to another god");
        }
        var baseline = Map.copyOf(currentLevels);
        baseline.forEach((effect, level) -> {
            if (level < 0 || level > 256) throw new IllegalArgumentException("Invalid current public effect level");
        });
        double before = finite(scorer.applyAsDouble(baseline));
        Comparator<Scored> preferred = Comparator.comparingInt(Scored::priority)
                .thenComparing(Comparator.comparingDouble(Scored::afterScore).reversed())
                .thenComparing(scored -> scored.candidate().id());
        Scored best = null;
        for (var candidate : definition.candidates()) {
            var allowed = godPolicy.effects().get(candidate.effectId());
            Integer rankCap = rankPolicy.effectCaps().get(candidate.effectId());
            if (allowed == null || rankCap == null) continue;
            int current = baseline.getOrDefault(candidate.effectId(), 0);
            int target = candidate.mode() == BlessingCandidate.Mode.FLOOR
                    ? candidate.level() : current + candidate.level();
            int cap = Math.min(allowed.maxLevel(), rankCap);
            // A promised increment is never silently changed, and a weaker god never exceeds its cap.
            if (target <= current || target > cap || target > BlessingLevels.maximum(candidate.effectId())) continue;
            var after = new HashMap<>(baseline);
            after.put(candidate.effectId(), target);
            var scored = new Scored(candidate, target, allowed.priority(), finite(scorer.applyAsDouble(Map.copyOf(after))));
            if (best == null || preferred.compare(scored, best) < 0) best = scored;
        }
        if (best != null) {
            var result = new ResolvedBlessing(definition.id(), definition.godId(), rankPolicy.rank(),
                    best.candidate().id(), Optional.of(best.candidate().effectId()), best.level(), definition.intrinsicScore());
            return new Selection(Status.RESOLVED, Optional.of(result), before, best.afterScore(), "useful_authored_candidate");
        }
        if (definition.fallback() == BlessingDefinition.Fallback.ONLY_OWNERSHIP) {
            var result = new ResolvedBlessing(definition.id(), definition.godId(), rankPolicy.rank(),
                    "ownership_only", Optional.empty(), 0, definition.intrinsicScore());
            return new Selection(Status.OWNERSHIP_ONLY, Optional.of(result), before, before, "authored_ownership_fallback");
        }
        return new Selection(Status.NO_USEFUL_CANDIDATE, Optional.empty(), before, before, "no_useful_authored_candidate");
    }

    private static double finite(double score) {
        if (!Double.isFinite(score)) throw new IllegalArgumentException("Blessing scorer must return a finite value");
        return score;
    }
}
