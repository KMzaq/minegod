package com.sande.mythictrpg.story.runtime;

import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationSink;
import com.sande.mythictrpg.story.signal.StorySignal;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.UUID;

/** Reuses canonical gameplay observations without converting playerless Story events to dialogue signals. */
public final class StoryGameplayObservationAdapter implements GameplayObservationSink {
    public static final StoryGameplayObservationAdapter INSTANCE = new StoryGameplayObservationAdapter();
    private StoryGameplayObservationAdapter() {}

    @Override
    public void accept(MinecraftServer server, GameplayObservation<?> observation) {
        StoryEventService.INSTANCE.submit(server, new StorySignal(UUID.randomUUID(), observation.type().id(),
                Optional.of(observation.initiatingPlayerId()), observation.subjectId(), Optional.empty(),
                Optional.empty(), observation.gameTime()));
    }
}
