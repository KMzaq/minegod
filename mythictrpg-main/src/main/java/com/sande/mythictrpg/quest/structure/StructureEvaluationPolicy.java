package com.sande.mythictrpg.quest.structure;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Datapack-authored God preference profile; contains no God-specific Java behavior. */
public record StructureEvaluationPolicy(ResourceLocation id, ResourceLocation godId,
        RegionLimit region, Limits limits, int buildWeight, int environmentWeight,
        boolean allowDerived, boolean allowReuse, List<Criterion> criteria,
        Optional<VisualProfile> visualProfile) {
    public StructureEvaluationPolicy {
        Objects.requireNonNull(id); Objects.requireNonNull(godId); Objects.requireNonNull(region);
        Objects.requireNonNull(limits); criteria = List.copyOf(criteria);
        visualProfile = visualProfile == null ? Optional.empty() : visualProfile;
        if (buildWeight < 0 || environmentWeight < 0 || buildWeight + environmentWeight != 100) {
            throw new IllegalArgumentException("buildWeight + environmentWeight must equal 100");
        }
        if (criteria.isEmpty()) throw new IllegalArgumentException("A structure policy needs criteria");
        Set<String> ids = new java.util.HashSet<>();
        for (Criterion criterion : criteria) if (!ids.add(criterion.id())) {
            throw new IllegalArgumentException("Duplicate criterion ID " + criterion.id());
        }
    }

    /** Compatibility constructor for policies and tests which do not opt into visual evaluation. */
    public StructureEvaluationPolicy(ResourceLocation id, ResourceLocation godId,
            RegionLimit region, Limits limits, int buildWeight, int environmentWeight,
            boolean allowDerived, boolean allowReuse, List<Criterion> criteria) {
        this(id, godId, region, limits, buildWeight, environmentWeight,
                allowDerived, allowReuse, criteria, Optional.empty());
    }

    public record VisualProfile(List<String> preferredStyles, List<String> favoredTypes,
            String guidance, int visualWeight, double minimumConfidence) {
        public VisualProfile {
            preferredStyles = boundedList(preferredStyles, 12, 48);
            favoredTypes = boundedList(favoredTypes, 12, 48);
            guidance = Objects.requireNonNullElse(guidance, "").replaceAll("\\s+", " ").trim();
            if (guidance.length() > 1_200) guidance = guidance.substring(0, 1_200);
            if (preferredStyles.isEmpty() || guidance.isBlank()) {
                throw new IllegalArgumentException("Visual profile needs preferredStyles and guidance");
            }
            if (visualWeight < 0 || visualWeight > 30) {
                throw new IllegalArgumentException("visualWeight must be within 0..30");
            }
            if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0.5D || minimumConfidence > 0.95D) {
                throw new IllegalArgumentException("minimumConfidence must be within 0.5..0.95");
            }
        }

        private static List<String> boundedList(List<String> values, int maximumItems, int maximumLength) {
            if (values == null) return List.of();
            return values.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank())
                    .map(v -> v.length() <= maximumLength ? v : v.substring(0, maximumLength))
                    .limit(maximumItems).toList();
        }
    }

    public record RegionLimit(int maxWidth, int maxDepth) {
        public RegionLimit {
            if (maxWidth < 1 || maxDepth < 1)
                throw new IllegalArgumentException("Policy recommended region dimensions must be positive");
        }
    }

    public record Limits(int maxTrackedBlocks, int minimumPlayerPlacedBlocks,
            int maxSnapshotCells, int maxFloodFillCells, int maxEnvironmentSamples,
            int environmentHorizontalRadius, int environmentVerticalRadius) {
        public Limits {
            if (maxTrackedBlocks < 1 || maxTrackedBlocks > 100_000)
                throw new IllegalArgumentException("maxTrackedBlocks is outside 1..100000");
            if (minimumPlayerPlacedBlocks < 1 || minimumPlayerPlacedBlocks > maxTrackedBlocks)
                throw new IllegalArgumentException("minimumPlayerPlacedBlocks is invalid");
            if (maxSnapshotCells < 1_000 || maxSnapshotCells > 1_000_000
                    || maxFloodFillCells < 1_000 || maxFloodFillCells > maxSnapshotCells)
                throw new IllegalArgumentException("Snapshot/flood-fill limits are invalid");
            if (maxEnvironmentSamples < 4 || maxEnvironmentSamples > 256
                    || environmentHorizontalRadius < 1 || environmentHorizontalRadius > 8
                    || environmentVerticalRadius < 1 || environmentVerticalRadius > 5)
                throw new IllegalArgumentException("Environment sample limits are invalid");
        }
    }

    public record Criterion(String id, Scope scope, String type, double weight,
            Optional<ResourceLocation> tag, Set<ResourceLocation> blocks,
            Set<ResourceLocation> biomes, Optional<ResourceLocation> biomeTag,
            double minimum, double target, double maximum, Curve curve,
            List<String> components) {
        public Criterion {
            id = Objects.requireNonNull(id).trim(); type = Objects.requireNonNull(type).trim();
            Objects.requireNonNull(scope); Objects.requireNonNull(curve);
            tag = tag == null ? Optional.empty() : tag; biomeTag = biomeTag == null ? Optional.empty() : biomeTag;
            blocks = Set.copyOf(blocks); biomes = Set.copyOf(biomes); components = List.copyOf(components);
            if (id.isBlank() || type.isBlank() || !Double.isFinite(weight) || weight <= 0.0D)
                throw new IllegalArgumentException("Criterion identity/weight is invalid");
            if (!Double.isFinite(minimum) || !Double.isFinite(target) || !Double.isFinite(maximum)
                    || minimum > target || target > maximum)
                throw new IllegalArgumentException("Criterion normalization must satisfy minimum <= target <= maximum");
        }
    }

    public enum Scope { BUILD, ENVIRONMENT }
    public enum Curve { LINEAR, SQRT, SQUARE }
}
