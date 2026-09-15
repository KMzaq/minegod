package com.sande.mythictrpg.ai.example;

import java.util.List;
import java.util.Objects;

/**
 * One reusable instruction and short demonstration for a single dialogue tag.  These entries deliberately express
 * only one concern at a time (for example P_GRUFF or S_ITEM_REQUEST), so prompt assembly can compose live NPC,
 * relationship, emotion, and situation state without maintaining every cross-product as content.
 */
public record TagDialogueGuidance(DialogueExampleTag tag, int priority, List<String> rules,
        List<DialogueExampleTurn> dialogue) {
    public TagDialogueGuidance {
        Objects.requireNonNull(tag, "tag");
        if (priority < 1 || priority > 100) {
            throw new IllegalArgumentException("Tag guidance priority must be between 1 and 100");
        }
        rules = rules == null ? List.of() : rules.stream().filter(Objects::nonNull).map(String::trim)
                .filter(rule -> !rule.isEmpty()).limit(4).toList();
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("Tag guidance " + tag + " requires at least one rule");
        }
        dialogue = dialogue == null ? List.of() : List.copyOf(dialogue);
        if (!dialogue.isEmpty() && dialogue.size() < 2) {
            throw new IllegalArgumentException("Tag guidance " + tag + " requires at least two dialogue turns");
        }
    }

    /** Returns a rule-only entry when the prompt's bounded example budget has been spent. */
    public TagDialogueGuidance withoutDialogue() {
        return dialogue.isEmpty() ? this : new TagDialogueGuidance(tag, priority, rules, List.of());
    }
}
