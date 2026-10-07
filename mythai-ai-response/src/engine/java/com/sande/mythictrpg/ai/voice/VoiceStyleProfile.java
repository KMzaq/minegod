package com.sande.mythictrpg.ai.voice;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * Reusable, ID-addressed writing guidance. Voice profiles are not world knowledge and do not grant a character any
 * game authority; they only constrain how that character expresses an otherwise valid response.
 */
public record VoiceStyleProfile(ResourceLocation id, List<String> guidance) {
    public VoiceStyleProfile {
        Objects.requireNonNull(id, "id");
        guidance = guidance == null ? List.of() : guidance.stream().filter(Objects::nonNull)
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (guidance.isEmpty()) {
            throw new IllegalArgumentException("Voice style " + id + " requires at least one guidance line");
        }
    }
}
