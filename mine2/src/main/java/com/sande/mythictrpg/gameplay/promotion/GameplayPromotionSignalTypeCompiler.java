package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignalType;
import com.sande.mythictrpg.interaction.api.InteractionSignalTypes;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class GameplayPromotionSignalTypeCompiler {
    private GameplayPromotionSignalTypeCompiler() {
    }

    static Map<ResourceLocation, InteractionSignalType<GameplayActionPayload>> compile(
            List<GameplayPromotionDefinition> definitions) {
        return compile(definitions, InteractionSignalTypes.knownTypes());
    }

    static Map<ResourceLocation, InteractionSignalType<GameplayActionPayload>> compile(
            List<GameplayPromotionDefinition> definitions,
            Map<ResourceLocation, InteractionSignalType<?>> knownTypes) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(knownTypes, "knownTypes");
        Map<ResourceLocation, InteractionSignalType<GameplayActionPayload>> compiled =
                new LinkedHashMap<>();
        definitions.stream().map(GameplayPromotionDefinition::signalId).distinct().sorted()
                .forEach(signalId -> compiled.put(signalId, compileType(signalId, knownTypes.get(signalId))));
        return Collections.unmodifiableMap(compiled);
    }

    private static InteractionSignalType<GameplayActionPayload> compileType(
            ResourceLocation signalId, InteractionSignalType<?> knownType) {
        if (knownType == null) {
            return new InteractionSignalType<>(signalId, InteractionMode.SPONTANEOUS,
                    GameplayActionPayload.class);
        }
        if (knownType.mode() != InteractionMode.SPONTANEOUS
                || knownType.payloadType() != GameplayActionPayload.class) {
            throw new IllegalArgumentException("Static interaction signal type " + signalId
                    + " conflicts with the gameplay promotion contract");
        }
        return checkedGameplayType(knownType);
    }

    @SuppressWarnings("unchecked")
    private static InteractionSignalType<GameplayActionPayload> checkedGameplayType(
            InteractionSignalType<?> knownType) {
        return (InteractionSignalType<GameplayActionPayload>) knownType;
    }
}
