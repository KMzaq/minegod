package com.sande.mythictrpg.gameplay.sampling;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WatchedMetricSnapshot {
    public static final int MAX_WATCHES = 256;
    public static final int MAX_UNIQUE_SOURCES = 64;
    private static final WatchedMetricSnapshot EMPTY = new WatchedMetricSnapshot(List.of(), Map.of(), Map.of());

    private final List<WatchedMetricDefinition> definitions;
    private final Map<ResourceLocation, WatchedMetricDefinition> byWatchId;
    private final Map<VanillaStatisticSource, SourceGroup> sourceGroups;

    private WatchedMetricSnapshot(List<WatchedMetricDefinition> definitions,
            Map<ResourceLocation, WatchedMetricDefinition> byWatchId,
            Map<VanillaStatisticSource, SourceGroup> sourceGroups) {
        this.definitions = definitions;
        this.byWatchId = byWatchId;
        this.sourceGroups = sourceGroups;
    }

    public static WatchedMetricSnapshot empty() {
        return EMPTY;
    }

    public static WatchedMetricSnapshot of(Collection<WatchedMetricDefinition> definitions) {
        if (definitions.isEmpty()) {
            return EMPTY;
        }
        if (definitions.size() > MAX_WATCHES) {
            throw new IllegalArgumentException("Too many watched metrics: " + definitions.size()
                    + " (maximum: " + MAX_WATCHES + ")");
        }

        List<WatchedMetricDefinition> sorted = definitions.stream()
                .sorted(java.util.Comparator.comparing(WatchedMetricDefinition::watchId))
                .toList();
        Map<ResourceLocation, WatchedMetricDefinition> byId = new LinkedHashMap<>();
        Map<VanillaStatisticSource, List<WatchedMetricDefinition>> grouped = new LinkedHashMap<>();
        for (WatchedMetricDefinition definition : sorted) {
            if (byId.putIfAbsent(definition.watchId(), definition) != null) {
                throw new IllegalArgumentException("Duplicate watched metric ID: " + definition.watchId());
            }
            grouped.computeIfAbsent(definition.source(), ignored -> new ArrayList<>()).add(definition);
        }
        if (grouped.size() > MAX_UNIQUE_SOURCES) {
            throw new IllegalArgumentException("Too many unique statistic sources: " + grouped.size()
                    + " (maximum: " + MAX_UNIQUE_SOURCES + ")");
        }

        Map<VanillaStatisticSource, SourceGroup> immutableGroups = new LinkedHashMap<>();
        grouped.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            int effectiveInterval = entry.getValue().stream()
                    .mapToInt(WatchedMetricDefinition::intervalTicks).min().orElseThrow();
            immutableGroups.put(entry.getKey(),
                    new SourceGroup(entry.getKey(), effectiveInterval, List.copyOf(entry.getValue())));
        });
        return new WatchedMetricSnapshot(List.copyOf(sorted), Map.copyOf(byId), Map.copyOf(immutableGroups));
    }

    public boolean isEmpty() {
        return definitions.isEmpty();
    }

    public List<WatchedMetricDefinition> definitions() {
        return definitions;
    }

    public Map<ResourceLocation, WatchedMetricDefinition> byWatchId() {
        return byWatchId;
    }

    public Map<VanillaStatisticSource, SourceGroup> sourceGroups() {
        return sourceGroups;
    }

    public record SourceGroup(VanillaStatisticSource source, int intervalTicks,
            List<WatchedMetricDefinition> watches) {
        public SourceGroup {
            watches = List.copyOf(watches);
        }
    }
}
