package com.sande.mythictrpg.interaction.start;

import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.content.PreparedInteractionContent;
import com.sande.mythictrpg.interaction.director.InteractionPlan;

import java.util.Objects;

public record InteractionStartRequest(InteractionSignal<?> signal, InteractionPlan plan,
        PreparedInteractionContent content) {
    public InteractionStartRequest {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(content, "content");
    }
}
