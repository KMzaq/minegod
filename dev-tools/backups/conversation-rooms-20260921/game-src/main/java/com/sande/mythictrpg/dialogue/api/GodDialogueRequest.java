package com.sande.mythictrpg.dialogue.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Server-domain request. Target players are supplied to the sending service, not retained here. */
public record GodDialogueRequest(ResourceLocation godId, Component dialogueText,
        DialoguePriority priority, DialogueDisplayOptions displayOptions) {
    public GodDialogueRequest {
        Objects.requireNonNull(godId, "godId");
        dialogueText = Objects.requireNonNull(dialogueText, "dialogueText").copy();
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(displayOptions, "displayOptions");
    }

    public static GodDialogueRequest literal(ResourceLocation godId, String text) {
        return new GodDialogueRequest(godId, Component.literal(text), DialoguePriority.NORMAL,
                DialogueDisplayOptions.defaults());
    }

    @Override
    public Component dialogueText() {
        return dialogueText.copy();
    }
}
