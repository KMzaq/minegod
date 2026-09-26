package com.sande.mythictrpg.ai.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record AiConversationTurnSnapshot(ResourceLocation speakerGodId, String text) {
    public static final int MAX_TEXT_LENGTH = 1024;

    public AiConversationTurnSnapshot {
        Objects.requireNonNull(speakerGodId, "speakerGodId");
        text = Objects.requireNonNull(text, "text").trim();
        if (text.isEmpty() || text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("AI conversation seed text length is invalid");
        }
    }
}
