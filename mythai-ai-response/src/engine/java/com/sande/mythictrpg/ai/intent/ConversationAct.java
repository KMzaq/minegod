package com.sande.mythictrpg.ai.intent;

import java.util.Locale;

/**
 * A reply-focused reading of the current player turn. Unlike a gameplay situation tag, it exists only to help an NPC
 * continue the immediate conversation without mistaking a correction or an answer for a brand-new small-talk prompt.
 */
public enum ConversationAct {
    UNSPECIFIED,
    GREETING,
    CASUAL_FEELING,
    SEEKING_COMPANY,
    ANSWERING_NPC_QUESTION,
    SHARING_RECENT_EVENT,
    CORRECTING_NPC,
    REQUESTING_ACTIVITY,
    CASUAL_BANTER;

    public static ConversationAct fromWire(String raw) {
        try {
            return valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNSPECIFIED;
        }
    }

    /** Strong local signals override a broad LLM small-talk label; otherwise the classifier's reading is retained. */
    public static ConversationAct resolve(ConversationAct classified, String rawText, String latestNpcText) {
        String text = rawText == null ? "" : rawText.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return classified == null ? UNSPECIFIED : classified;
        }
        if (startsWithAny(text, "아니", "아니라", "그게 아니라", "말했잖", "그랬잖")) {
            return CORRECTING_NPC;
        }
        if (containsAny(text, "너랑 얘기", "너와 얘기", "대화하러", "말하러 왔", "같이 얘기")) {
            return SEEKING_COMPANY;
        }
        if (containsAny(text, "재밌는 거", "재밌는거", "뭐 할 거", "뭐할 거", "할 만한 거")) {
            return REQUESTING_ACTIVITY;
        }
        // A question mark in an NPC line is not enough to reinterpret every following player line as an answer.
        // It may be a rhetorical question, while the player may be asking for clarification, pushing back, joking,
        // or changing the topic. Keep the classifier's contextual reading unless the player gives a very clear,
        // short answer signal.
        if (latestNpcText != null && latestNpcText.contains("?") && isExplicitAnswer(text)) {
            return ANSWERING_NPC_QUESTION;
        }
        if (isRecentEventShare(text)) {
            return SHARING_RECENT_EVENT;
        }
        if (containsAny(text, "심심", "지루", "피곤", "졸려", "배고파", "외로")) {
            return CASUAL_FEELING;
        }
        return classified == null ? UNSPECIFIED : classified;
    }

    public String replyGuidance() {
        return switch (this) {
            case GREETING -> "Answer the greeting naturally and briefly.";
            case CASUAL_FEELING -> "Acknowledge the feeling in the moment. Do not define, diagnose, or lecture about it.";
            case SEEKING_COMPANY -> "The player explicitly wants to talk with this NPC. Receive that warmly or in-character, then continue the exchange; do not ask whether they want to talk.";
            case ANSWERING_NPC_QUESTION -> "The player is answering the NPC's previous question. React to that answer and move forward; never ask the same question again.";
            case SHARING_RECENT_EVENT -> "The player is sharing a recent event, not asking for information. React to the event naturally; do not reinterpret it as a lore question or a lesson.";
            case CORRECTING_NPC -> "The player says the NPC misunderstood them. Acknowledge the correction, then answer the corrected meaning directly. Do not repeat the misunderstanding.";
            case REQUESTING_ACTIVITY -> "Offer one small, conversational possibility or respond playfully. Do not invent a quest, reward, item, or world event.";
            case CASUAL_BANTER -> "Continue the light back-and-forth instead of explaining the player's words.";
            case UNSPECIFIED -> "Respond directly to the literal player message and the recent exchange.";
        };
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRecentEventShare(String text) {
        if (text.endsWith("?")) {
            return false;
        }
        boolean hasRecentMarker = containsAny(text, "아까", "방금", "오늘", "어제", "지난번", "조금 전");
        boolean hasPastEventForm = text.endsWith("했어") || text.endsWith("했다") || text.endsWith("봤어")
                || text.endsWith("봤다") || text.endsWith("왔어") || text.endsWith("왔는데") || text.endsWith("이겼어")
                || text.endsWith("졌어") || text.endsWith("죽였어") || text.endsWith("당했어");
        return hasRecentMarker && hasPastEventForm;
    }

    /**
     * A narrow fallback for obvious replies when phase 1 could only return a broad small-talk label.  Longer
     * Korean statements are intentionally left to the contextual classifier instead of being guessed from the
     * previous NPC punctuation.
     */
    private static boolean isExplicitAnswer(String text) {
        return text.matches("^(응|네|예|그래|맞아|맞습니다|그렇지|그렇습니다|아니|아니야|아니요|아닙니다|몰라|모르겠어|글쎄)[!?.~]*$");
    }
}
