package com.sande.mythictrpg.interaction.content;

import com.sande.mythictrpg.dialogue.api.DialogueDisplayOptions;
import com.sande.mythictrpg.dialogue.api.DialoguePriority;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record PreparedDialogueTurn(ResourceLocation speakerGodId, Component text,
        DialoguePriority priority, DialogueDisplayOptions displayOptions) {
    public PreparedDialogueTurn {
        Objects.requireNonNull(speakerGodId, "speakerGodId");
        text = Objects.requireNonNull(text, "text").copy();
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(displayOptions, "displayOptions");
    }

    @Override
    public Component text() {
        return text.copy();
    }
}
