package com.sande.mythictrpg.interaction.content;

import com.sande.mythictrpg.dialogue.api.DialogueDisplayOptions;
import com.sande.mythictrpg.dialogue.api.DialoguePriority;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Deterministic debug/test producer. It contains no production God dialogue. */
public final class ScriptedInteractionContentPreparer implements InteractionContentPreparer {
    private final Component text;

    public ScriptedInteractionContentPreparer(Component text) {
        this.text = Objects.requireNonNull(text, "text").copy();
    }

    @Override
    public CompletionStage<PreparationResult> prepare(ContentPreparationRequest request) {
        PreparedDialogueTurn turn = new PreparedDialogueTurn(
                request.plan().participants().primaryGodId(), text,
                DialoguePriority.NORMAL, DialogueDisplayOptions.defaults());
        return CompletableFuture.completedFuture(PreparationResult.prepared(
                new PreparedInteractionContent(java.util.List.of(turn))));
    }
}
