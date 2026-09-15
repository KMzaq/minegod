package com.sande.mythictrpg.interaction.content;

import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.director.InteractionPlan;

import java.util.Objects;

public record ContentPreparationRequest(InteractionSignal<?> signal, InteractionPlan plan) {
    public ContentPreparationRequest {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(plan, "plan");
    }
}
