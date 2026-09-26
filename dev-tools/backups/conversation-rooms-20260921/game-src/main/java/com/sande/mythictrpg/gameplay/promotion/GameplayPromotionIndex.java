package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GameplayPromotionIndex {
    public static final int MAX_RUNTIME_CANDIDATES = 64;
    private static final GameplayPromotionIndex EMPTY =
            new GameplayPromotionIndex(Map.of(), Map.of(), Map.of());

    private final Map<ResourceLocation, Map<PromotionLookupKey, List<GameplayPromotionDefinition>>>
            exactByObservation;
    private final Map<ResourceLocation, List<GameplayPromotionDefinition>> wildcardByObservation;
    private final Map<ResourceLocation, GameplayPromotionAdapter<?, ?>> adapters;

    private GameplayPromotionIndex(
            Map<ResourceLocation, Map<PromotionLookupKey, List<GameplayPromotionDefinition>>>
                    exactByObservation,
            Map<ResourceLocation, List<GameplayPromotionDefinition>> wildcardByObservation,
            Map<ResourceLocation, GameplayPromotionAdapter<?, ?>> adapters) {
        this.exactByObservation = exactByObservation;
        this.wildcardByObservation = wildcardByObservation;
        this.adapters = adapters;
    }

    public static GameplayPromotionIndex empty() {
        return EMPTY;
    }

    static GameplayPromotionIndex build(List<GameplayPromotionDefinition> definitions,
            Map<ResourceLocation, GameplayPromotionAdapter<?, ?>> adapters) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(adapters, "adapters");

        Map<ResourceLocation, MutableBuckets> mutable = new LinkedHashMap<>();
        definitions.stream().sorted(GameplayPromotionDefinition.ORDERING).forEach(definition -> {
            GameplayPromotionAdapter<?, ?> adapter = adapters.get(definition.observationTypeId());
            if (adapter == null) {
                throw new IllegalArgumentException("No gameplay promotion adapter is registered for observation "
                        + definition.observationTypeId());
            }
            PromotionIndexTarget target = adapter.compileTargetUntyped(definition.id(), definition.matcher());
            MutableBuckets buckets = mutable.computeIfAbsent(
                    definition.observationTypeId(), ignored -> new MutableBuckets());
            if (target instanceof PromotionIndexTarget.Exact exact) {
                buckets.exact.computeIfAbsent(exact.key(), ignored -> new ArrayList<>()).add(definition);
            } else {
                buckets.wildcard.add(definition);
            }
        });

        Map<ResourceLocation, Map<PromotionLookupKey, List<GameplayPromotionDefinition>>> exact =
                new LinkedHashMap<>();
        Map<ResourceLocation, List<GameplayPromotionDefinition>> wildcard = new LinkedHashMap<>();
        mutable.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            MutableBuckets buckets = entry.getValue();
            validateBucketLimits(entry.getKey(), buckets);
            Map<PromotionLookupKey, List<GameplayPromotionDefinition>> exactBuckets = new LinkedHashMap<>();
            buckets.exact.forEach((key, values) -> exactBuckets.put(key, List.copyOf(values)));
            exact.put(entry.getKey(), Map.copyOf(exactBuckets));
            wildcard.put(entry.getKey(), List.copyOf(buckets.wildcard));
        });
        return new GameplayPromotionIndex(Map.copyOf(exact), Map.copyOf(wildcard), Map.copyOf(adapters));
    }

    public List<GameplayPromotionDefinition> exactCandidates(ResourceLocation observationTypeId,
            PromotionLookupKey lookupKey) {
        Objects.requireNonNull(observationTypeId, "observationTypeId");
        Objects.requireNonNull(lookupKey, "lookupKey");
        Map<PromotionLookupKey, List<GameplayPromotionDefinition>> buckets =
                exactByObservation.get(observationTypeId);
        return buckets == null ? List.of() : buckets.getOrDefault(lookupKey, List.of());
    }

    public List<GameplayPromotionDefinition> wildcardCandidates(ResourceLocation observationTypeId) {
        Objects.requireNonNull(observationTypeId, "observationTypeId");
        return wildcardByObservation.getOrDefault(observationTypeId, List.of());
    }

    public List<GameplayPromotionDefinition> match(GameplayObservation<?> observation) {
        Objects.requireNonNull(observation, "observation");
        GameplayPromotionAdapter<?, ?> adapter = adapters.get(observation.type().id());
        if (adapter == null) {
            return List.of();
        }

        GameplayObservationPayload payload = observation.payload();
        PromotionLookupKey lookupKey = adapter.runtimeKeyUntyped(payload);
        List<GameplayPromotionDefinition> candidates = mergeOrdered(
                exactCandidates(observation.type().id(), lookupKey),
                wildcardCandidates(observation.type().id()));
        return candidates.stream()
                .filter(definition -> adapter.matchesUntyped(payload, definition.matcher()))
                .toList();
    }

    Optional<GameplayActionEvidence> createEvidence(GameplayPromotionDefinition definition,
            GameplayObservation<?> observation) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(observation, "observation");
        if (!definition.observationTypeId().equals(observation.type().id())) {
            return Optional.empty();
        }
        GameplayPromotionAdapter<?, ?> adapter = adapters.get(definition.observationTypeId());
        if (adapter == null) {
            return Optional.empty();
        }
        return adapter.createEvidenceUntyped(observation.payload(), definition.matcher());
    }

    public Map<ResourceLocation, Map<PromotionLookupKey, List<GameplayPromotionDefinition>>> exactEntries() {
        return exactByObservation;
    }

    public Map<ResourceLocation, List<GameplayPromotionDefinition>> wildcardEntries() {
        return wildcardByObservation;
    }

    private static void validateBucketLimits(ResourceLocation observationTypeId, MutableBuckets buckets) {
        int wildcardSize = buckets.wildcard.size();
        if (wildcardSize > MAX_RUNTIME_CANDIDATES) {
            throw new IllegalArgumentException("Wildcard promotion bucket for " + observationTypeId
                    + " exceeds maximum of " + MAX_RUNTIME_CANDIDATES);
        }
        buckets.exact.forEach((key, values) -> {
            int effectiveSize = Math.addExact(values.size(), wildcardSize);
            if (effectiveSize > MAX_RUNTIME_CANDIDATES) {
                throw new IllegalArgumentException("Effective promotion bucket for " + observationTypeId
                        + " and lookup key " + key + " exceeds maximum of " + MAX_RUNTIME_CANDIDATES);
            }
        });
    }

    private static List<GameplayPromotionDefinition> mergeOrdered(
            List<GameplayPromotionDefinition> exact,
            List<GameplayPromotionDefinition> wildcard) {
        if (exact.isEmpty()) {
            return wildcard;
        }
        if (wildcard.isEmpty()) {
            return exact;
        }

        List<GameplayPromotionDefinition> merged = new ArrayList<>(exact.size() + wildcard.size());
        Set<ResourceLocation> seen = new LinkedHashSet<>();
        int exactIndex = 0;
        int wildcardIndex = 0;
        while (exactIndex < exact.size() || wildcardIndex < wildcard.size()) {
            GameplayPromotionDefinition next;
            if (wildcardIndex >= wildcard.size()
                    || exactIndex < exact.size() && GameplayPromotionDefinition.ORDERING.compare(
                    exact.get(exactIndex), wildcard.get(wildcardIndex)) <= 0) {
                next = exact.get(exactIndex++);
            } else {
                next = wildcard.get(wildcardIndex++);
            }
            if (seen.add(next.id())) {
                merged.add(next);
            }
        }
        return List.copyOf(merged);
    }

    private static final class MutableBuckets {
        private final Map<PromotionLookupKey, List<GameplayPromotionDefinition>> exact =
                new LinkedHashMap<>();
        private final List<GameplayPromotionDefinition> wildcard = new ArrayList<>();
    }
}
