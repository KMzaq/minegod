package com.sande.mythictrpg.ai.intent;

import com.sande.mythictrpg.ai.example.DialogueExampleTag;
import com.sande.mythictrpg.ai.tone.PlayerSpeechTone;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Advisory classification of the current player turn. It is not a game action, quest decision, or fact about the
 * world. The result only steers bounded example retrieval and is kept inside the requesting conversation turn.
 */
public record ConversationIntent(Set<DialogueExampleTag> tags, Set<String> knowledgeKeywords,
        Set<PlayerSpeechTone> playerToneTags, ConversationAct conversationAct, int confidence, Source source) {
    public enum Source {
        HEURISTIC,
        LOCAL_LLM
    }

    public ConversationIntent {
        Objects.requireNonNull(tags, "tags");
        EnumSet<DialogueExampleTag> checked = EnumSet.noneOf(DialogueExampleTag.class);
        for (DialogueExampleTag tag : tags) {
            if (tag != null && isRoutable(tag)) {
                checked.add(tag);
            }
        }
        tags = Set.copyOf(checked);
        java.util.LinkedHashSet<String> checkedKeywords = new java.util.LinkedHashSet<>();
        if (knowledgeKeywords != null) {
            for (String keyword : knowledgeKeywords) {
                String normalized = keyword == null ? "" : keyword.trim();
                if (normalized.length() >= 2 && normalized.length() <= 64
                        && normalized.matches("[\\p{L}\\p{N}_ -]+")) {
                    checkedKeywords.add(normalized);
                }
                if (checkedKeywords.size() == 5) {
                    break;
                }
            }
        }
        knowledgeKeywords = Set.copyOf(checkedKeywords);
        java.util.EnumSet<PlayerSpeechTone> checkedTones = java.util.EnumSet.noneOf(PlayerSpeechTone.class);
        if (playerToneTags != null) {
            checkedTones.addAll(playerToneTags);
        }
        playerToneTags = Set.copyOf(checkedTones);
        conversationAct = conversationAct == null ? ConversationAct.UNSPECIFIED : conversationAct;
        confidence = Math.max(0, Math.min(100, confidence));
        source = source == null ? Source.HEURISTIC : source;
    }

    public static ConversationIntent heuristicFallback() {
        return new ConversationIntent(Set.of(), Set.of(), Set.of(), ConversationAct.UNSPECIFIED, 0, Source.HEURISTIC);
    }

    public ConversationIntent withConversationAct(ConversationAct act) {
        return new ConversationIntent(tags, knowledgeKeywords, playerToneTags, act, confidence, source);
    }

    /** Compatibility constructor for callers that only classify dialogue/example tags and search terms. */
    public ConversationIntent(Set<DialogueExampleTag> tags, Set<String> knowledgeKeywords, int confidence, Source source) {
        this(tags, knowledgeKeywords, Set.of(), ConversationAct.UNSPECIFIED, confidence, source);
    }

    /** Compatibility constructor for callers that only classify dialogue/example tags. */
    public ConversationIntent(Set<DialogueExampleTag> tags, int confidence, Source source) {
        this(tags, Set.of(), Set.of(), ConversationAct.UNSPECIFIED, confidence, source);
    }

    public static boolean isRoutable(DialogueExampleTag tag) {
        return tag != null && tag.name().startsWith("S_");
    }
}
