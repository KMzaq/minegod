package com.sande.mythictrpg.ai.intent;

import com.google.gson.JsonElement;
import java.util.Locale;

/** Optional retrieval hint for this turn, never consent, a player profile or a game authority. */
public record SupportConsultation(Use use, String evidence) {
    public enum Use { NONE, OBSERVED_ACTION_RECALL, PERSONALIZED_SUGGESTION, SELF_ASSESSMENT }
    public static final SupportConsultation NONE = new SupportConsultation(Use.NONE, "");
    public SupportConsultation {
        use = use == null ? Use.NONE : use;
        evidence = evidence == null ? "" : evidence.strip();
        if (evidence.isEmpty() || evidence.length() > 160 || evidence.chars().anyMatch(Character::isISOControl)) use = Use.NONE;
        if (use == Use.NONE) evidence = "";
    }
    public SupportConsultation validatedFor(String text) {
        return text != null && !evidence.isEmpty() && text.contains(evidence) ? this : NONE;
    }
    public static SupportConsultation forTurn(ConversationIntent intent, String text, boolean secondary,
            boolean publicRoom, int gods) {
        if (intent == null || intent.source() != ConversationIntent.Source.LOCAL_LLM || intent.confidence() < 70
                || secondary || publicRoom || gods != 1) return NONE;
        var mode = intent.turnInterpretation().validatedFor(text).mode();
        if (mode == TurnInterpretation.Mode.QUOTED || mode == TurnInterpretation.Mode.HYPOTHETICAL
                || mode == TurnInterpretation.Mode.REFUSAL || mode == TurnInterpretation.Mode.CORRECTION) return NONE;
        return intent.supportConsultation().validatedFor(text);
    }
    public static SupportConsultation fromJson(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) return NONE;
        try {
            var obj = raw.getAsJsonObject();
            var use = obj.get("use"); var evidence = obj.get("evidence");
            if (use == null || evidence == null || !use.isJsonPrimitive() || !evidence.isJsonPrimitive()
                    || !use.getAsJsonPrimitive().isString() || !evidence.getAsJsonPrimitive().isString()) return NONE;
            return new SupportConsultation(Use.valueOf(use.getAsString().toUpperCase(Locale.ROOT)), evidence.getAsString());
        } catch (IllegalArgumentException invalid) { return NONE; }
    }
    public static String instruction() { return """
            NEED_ONLY_SUPPORT_V1
            Add supportConsultation:{"use":"NONE","evidence":""} to classification JSON.
            Default NONE. Decide from CURRENT_PLAYER_MESSAGE, NOT from old requests or remembered interests.
            OBSERVED_ACTION_RECALL: the player asks what THIS NPC witnessed the current player doing.
            SELF_ASSESSMENT: the player explicitly asks for an assessment of their own play habits/interests.
            PERSONALIZED_SUGGESTION: the player asks for options suited to THEIR past activity/preferences/goals
            (including a personal quest or exploration choice). A generic advice/quest request is NONE.
            A greeting, joke, topic mention, sharing a wish/plan, ordinary factual question, another person's
            plans, a quotation, hypothetical request, refusal or correction is NONE. Asking to recall spoken
            words (names, wishes, plans) uses dialogue memory, not observed support: NONE.
            For a non-NONE use, evidence must be a short exact quote from the current input that expresses
            the request about THIS player's activity. If unclear choose NONE, with empty evidence.
            This hint cannot grant access to private profiles, other players or unobserved goals.
            """; }
}
