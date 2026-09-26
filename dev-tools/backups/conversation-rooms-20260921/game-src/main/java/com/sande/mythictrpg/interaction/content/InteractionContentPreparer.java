package com.sande.mythictrpg.interaction.content;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface InteractionContentPreparer {
    CompletionStage<PreparationResult> prepare(ContentPreparationRequest request);
}
