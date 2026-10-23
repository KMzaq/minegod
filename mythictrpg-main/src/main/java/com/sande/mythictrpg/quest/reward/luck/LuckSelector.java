package com.sande.mythictrpg.quest.reward.luck;

import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic selection without global mutable RNG. The caller supplies its player/source seed. */
public final class LuckSelector {
    private LuckSelector() { }
    public record WeightedSelection(int index, long roll) { }

    public static LuckyRewardSelection select(LuckyItemPool pool, int level, long seed, LuckModel model) {
        Objects.requireNonNull(pool);
        Objects.requireNonNull(model);
        int effectiveLevel = LuckModel.level(level);
        var candidates = pool.candidates().stream().sorted(Comparator.comparing(LuckyItemCandidate::id)).toList();
        var weights = candidates.stream().map(candidate -> model.rewardWeight(candidate.weight(), candidate.quality(), effectiveLevel)).toList();
        var candidate = candidates.get(weightedIndex(weights, seed).index());
        int count = candidate.minCount() + (int) Math.floorMod(mix64(seed ^ 0x59a0939179abf42dL),
                (long) candidate.maxCount() - candidate.minCount() + 1);
        count = model.quantity(count, effectiveLevel, candidate.quantityBonusAllowed());
        return new LuckyRewardSelection(new NpcRewardEntry(candidate.item().itemId(), count, candidate.item().components()),
                effectiveLevel, candidate.id());
    }

    /** The 53-bit roll is persisted alongside a Story decision; it is never rerolled on recovery. */
    public static WeightedSelection weightedIndex(List<Double> weights, long seed) {
        if (weights.isEmpty() || weights.size() > 32) throw new IllegalArgumentException("Weighted luck needs 1..32 entries");
        double total = 0;
        for (double weight : weights) {
            if (!Double.isFinite(weight) || weight <= 0) throw new IllegalArgumentException("Luck weights must be finite and positive");
            total += weight;
        }
        if (!Double.isFinite(total)) throw new IllegalArgumentException("Luck weight sum must be finite");
        long roll = mix64(seed) >>> 11;
        double target = roll * 0x1.0p-53 * total;
        double cursor = 0;
        for (int index = 0; index < weights.size(); index++) {
            cursor += weights.get(index);
            if (target < cursor) return new WeightedSelection(index, roll);
        }
        return new WeightedSelection(weights.size() - 1, roll);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
