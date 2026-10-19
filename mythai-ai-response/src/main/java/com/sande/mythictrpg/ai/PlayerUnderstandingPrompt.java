package com.sande.mythictrpg.ai;

/** Interpretation guidance over the ALREADY scoped scene only; never reads the player's private profile. */
final class PlayerUnderstandingPrompt {
    private PlayerUnderstandingPrompt() { }
    // Live evaluation v1-v3 did not meet semantic quality gates. Never silently enable the candidate.
    static String productionPolicy(boolean secondary) { return ""; }

    /** Experimental candidate only; used by opt-in evaluation, not the production dialogue path. */
    static String policy(boolean secondary) {
        return """

                [PLAYER_UNDERSTANDING]
                Be an attentive conversational partner: connect the CURRENT_PLAYER_MESSAGE with what this
                player actually said in the supplied exchange. When asked to guess their purpose, offer the
                concrete most plausible reading supported by those words, tentatively. Ordinary inference
                from heard words is allowed; lack of mind-reading does not require refusing to make a guess.
                A character's values shape their view of that concrete situation, not an unrelated lecture.
                Relevant permitted memories may shape a response without being recited. Distinguish
                observation, self-report and hearsay. Outcomes are not choices: raid deaths/DPS/rosters alone establish
                neither courage, skill nor cooperation; absence of records is not disinterest.
                On a correction or changed plan, update the interpretation and move the conversation forward;
                don't reassert it using the same old evidence. Uncertainty need not end the conversation:
                if the concrete circumstances are missing, ask about the relevant action or situation.
                Keep your own values and ability to disagree; don't invent praise or flatten everyone into reassurance.
                Mention a tendency only when relevant and not already repeated in delivered conversation;
                don't interrupt combat to assess personality. Explain a permitted concrete basis if asked.
                Self-inspection notes are private, not your knowledge. Desire is not consent, and inference
                changes no rewards, affinity, difficulty or story facts. Existing validation and confirmation still apply.
                """ + (secondary
                ? "Do not echo the primary speaker's assessment. Speak only if your own evidence and viewpoint add something relevant; silence is valid.\n"
                : "If a plausible goal is relevant, phrase uncertainty naturally rather than claiming to read the player's mind.\n");
    }
}
