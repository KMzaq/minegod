package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

/** One observed gameplay action that counts as progress effort for a quest. */
public record QuestRelevantAction(ResourceLocation observationTypeId, Optional<ResourceLocation> subjectId) {
    public QuestRelevantAction {
        Objects.requireNonNull(observationTypeId, "observationTypeId");
        subjectId = subjectId == null ? Optional.empty() : subjectId;
    }

    public boolean matches(GameplayObservation<?> observation) {
        return observationTypeId.equals(observation.type().id())
                && subjectId.map(expected -> observation.subjectId().filter(expected::equals).isPresent())
                        .orElse(true);
    }
}
