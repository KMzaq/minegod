package com.sande.mythictrpg.story.signal;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A bounded, typed wake-up signal. It never contains executable text. */
public record StorySignal(UUID signalId, ResourceLocation typeId, Optional<UUID> initiatingPlayerId,
        Optional<ResourceLocation> subjectId, Optional<ResourceLocation> logicalLocationId,
        Optional<String> sourceEventInstanceId, long gameTime) {
    public StorySignal {
        Objects.requireNonNull(signalId, "signalId");
        Objects.requireNonNull(typeId, "typeId");
        initiatingPlayerId = Objects.requireNonNull(initiatingPlayerId, "initiatingPlayerId");
        subjectId = Objects.requireNonNull(subjectId, "subjectId");
        logicalLocationId = Objects.requireNonNull(logicalLocationId, "logicalLocationId");
        sourceEventInstanceId = Objects.requireNonNull(sourceEventInstanceId, "sourceEventInstanceId")
                .map(String::trim);
        if (gameTime < 0) throw new IllegalArgumentException("Story signal gameTime cannot be negative");
    }

    public static StorySignal of(ResourceLocation typeId, Optional<UUID> playerId,
            Optional<ResourceLocation> subjectId, long gameTime) {
        return new StorySignal(UUID.randomUUID(), typeId, playerId, subjectId,
                Optional.empty(), Optional.empty(), gameTime);
    }
}
