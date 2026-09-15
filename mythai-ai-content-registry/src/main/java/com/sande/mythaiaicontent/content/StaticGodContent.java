package com.sande.mythaiaicontent.content;

import java.util.List;
import java.util.Objects;

/** A fixed-content response suitable for the AI Response Module; it intentionally excludes all mutable game state. */
public record StaticGodContent(GodContentProfile profile, List<ResolvedLoreKnowledge> explicitlyReferencedLore,
        List<DialogueExample> signatureExamples) {
    public StaticGodContent {
        Objects.requireNonNull(profile, "profile");
        explicitlyReferencedLore = explicitlyReferencedLore == null ? List.of() : List.copyOf(explicitlyReferencedLore);
        signatureExamples = signatureExamples == null ? List.of() : List.copyOf(signatureExamples);
    }
}
