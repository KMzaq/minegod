package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.api.InteractionSignalType;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GameplayPromotionSignalFactory {
    private GameplayPromotionSignalFactory() {
    }

    public static Optional<InteractionSignal<GameplayActionPayload>> create(
            GameplayPromotionSnapshot snapshot,
            GameplayPromotionDefinition definition,
            GameplayObservation<?> observation) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(observation, "observation");
        if (!definition.equals(snapshot.definitions().get(definition.id()))
                || !definition.observationTypeId().equals(observation.type().id())) {
            return Optional.empty();
        }
        InteractionSignalType<GameplayActionPayload> signalType =
                snapshot.signalTypes().get(definition.signalId());
        if (signalType == null || signalType.mode() != InteractionMode.SPONTANEOUS
                || signalType.payloadType() != GameplayActionPayload.class) {
            return Optional.empty();
        }
        try {
            Optional<GameplayActionEvidence> evidence =
                    snapshot.index().createEvidence(definition, observation);
            if (evidence.isEmpty()) {
                return Optional.empty();
            }
            GameplayActionPayload payload = new GameplayActionPayload(
                    definition.id(), observation.type().id(), observation.subjectId(), evidence.orElseThrow());
            return Optional.of(new InteractionSignal<>(signalType, observation.initiatingPlayerId(),
                    Set.of(), payload));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
