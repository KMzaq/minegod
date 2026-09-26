package com.sande.mythictrpg.interaction.rule;

import com.sande.mythictrpg.data.god.GodDefinitionManager;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Signal lookup built on reload; category expansion uses the God snapshot's derived category index. */
public final class InteractionRuleSeedIndex {
    private static final InteractionRuleSeedIndex EMPTY = new InteractionRuleSeedIndex(Map.of());
    private static final int MAX_UNRESOLVED_IDS = 16;

    private final Map<ResourceLocation, List<InteractionRuleBinding>> bindingsBySignal;

    private InteractionRuleSeedIndex(Map<ResourceLocation, List<InteractionRuleBinding>> bindingsBySignal) {
        this.bindingsBySignal = bindingsBySignal;
    }

    public static InteractionRuleSeedIndex empty() {
        return EMPTY;
    }

    public static InteractionRuleSeedIndex build(List<InteractionRule> rules) {
        Map<ResourceLocation, List<InteractionRuleBinding>> mutable = new LinkedHashMap<>();
        rules.forEach(rule -> mutable.computeIfAbsent(rule.signalType(), ignored -> new ArrayList<>())
                .addAll(rule.bindings()));
        Map<ResourceLocation, List<InteractionRuleBinding>> immutable = new LinkedHashMap<>();
        mutable.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> immutable.put(entry.getKey(), List.copyOf(entry.getValue())));
        return new InteractionRuleSeedIndex(Map.copyOf(immutable));
    }

    public SeedResolution resolve(ResourceLocation signalType,
            GodDefinitionManager.ProgressionSnapshot gods) {
        List<InteractionRuleBinding> bindings = bindingsBySignal.get(signalType);
        if (bindings == null) {
            return new SeedResolution(false, Map.of(), List.of());
        }

        Map<ResourceLocation, MutableSeed> mutable = new LinkedHashMap<>();
        Set<ResourceLocation> unresolved = new LinkedHashSet<>();
        for (InteractionRuleBinding binding : bindings) {
            if (binding.godId().isPresent()) {
                ResourceLocation godId = binding.godId().orElseThrow();
                if (gods.definitions().containsKey(godId)) {
                    mutable.computeIfAbsent(godId, ignored -> new MutableSeed()).add(binding);
                } else if (unresolved.size() < MAX_UNRESOLVED_IDS) {
                    unresolved.add(godId);
                }
                continue;
            }
            gods.categoryIndex().godsInCategory(binding.godCategoryId().orElseThrow()).stream()
                    .sorted().forEach(godId ->
                            mutable.computeIfAbsent(godId, ignored -> new MutableSeed()).add(binding));
        }

        Map<ResourceLocation, SeedScore> seeds = new LinkedHashMap<>();
        mutable.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> seeds.put(entry.getKey(), entry.getValue().freeze()));
        return new SeedResolution(true, Map.copyOf(seeds), unresolved.stream().sorted().toList());
    }

    public record SeedScore(long score, List<ResourceLocation> reasons, List<ScorePart> parts) {
        public SeedScore {
            reasons = List.copyOf(reasons);
            parts = List.copyOf(parts);
        }
    }

    public record ScorePart(int score, ResourceLocation reason) {
    }

    public record SeedResolution(boolean signalMapped, Map<ResourceLocation, SeedScore> seeds,
            List<ResourceLocation> unresolvedExactGods) {
        public SeedResolution {
            seeds = Map.copyOf(seeds);
            unresolvedExactGods = List.copyOf(unresolvedExactGods);
        }
    }

    private static final class MutableSeed {
        private long score;
        private final Set<ResourceLocation> reasons = new LinkedHashSet<>();
        private final List<ScorePart> parts = new ArrayList<>();

        private void add(InteractionRuleBinding binding) {
            score = Math.addExact(score, binding.score());
            reasons.add(binding.reason());
            parts.add(new ScorePart(binding.score(), binding.reason()));
        }

        private SeedScore freeze() {
            List<ResourceLocation> sortedReasons = reasons.stream().sorted().toList();
            List<ScorePart> sortedParts = parts.stream()
                    .sorted(Comparator.comparing(ScorePart::reason).thenComparingInt(ScorePart::score))
                    .toList();
            return new SeedScore(score, sortedReasons, sortedParts);
        }
    }
}
