package com.sande.mythictrpg.interaction.spontaneous;

import com.sande.mythictrpg.interaction.api.InteractionSignal;

@FunctionalInterface
public interface InteractionContentPreparerResolver {
    InteractionContentPreparerResolver UNAVAILABLE = signal ->
            ContentPreparerResolution.unavailable();

    ContentPreparerResolution resolve(InteractionSignal<?> signal);
}
