package com.sande.mythictrpg.interaction.spontaneous;

import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.start.InteractionStartResult;

@FunctionalInterface
public interface SpontaneousInteractionObserver {
    SpontaneousInteractionObserver NO_OP = (signal, result) -> {
    };

    void onCompleted(InteractionSignal<?> signal, InteractionStartResult result);
}
