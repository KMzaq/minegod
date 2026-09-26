package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.gameplay.sampling.WatchedMetricSnapshot;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignalType;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record GameplayPromotionSnapshot(
        Map<ResourceLocation, GameplayPromotionDefinition> definitions,
        List<GameplayPromotionDefinition> orderedDefinitions,
        GameplayPromotionIndex index,
        WatchedMetricSnapshot watchedMetrics,
        Map<ResourceLocation, InteractionSignalType<GameplayActionPayload>> signalTypes,
        long generation
) {
    public GameplayPromotionSnapshot {
        definitions = Map.copyOf(definitions);
        orderedDefinitions = List.copyOf(orderedDefinitions);
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(watchedMetrics, "watchedMetrics");
        Objects.requireNonNull(signalTypes, "signalTypes");
        signalTypes.forEach((id, type) -> {
            Objects.requireNonNull(id, "signal type ID");
            Objects.requireNonNull(type, "signal type");
            if (!id.equals(type.id()) || type.mode() != InteractionMode.SPONTANEOUS
                    || type.payloadType() != GameplayActionPayload.class) {
                throw new IllegalArgumentException("Invalid gameplay promotion signal type contract: " + id);
            }
        });
        signalTypes = Collections.unmodifiableMap(new LinkedHashMap<>(signalTypes));
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
    }

    public static GameplayPromotionSnapshot empty(long generation) {
        return new GameplayPromotionSnapshot(Map.of(), List.of(), GameplayPromotionIndex.empty(),
                WatchedMetricSnapshot.empty(), Map.of(), generation);
    }
}
