package com.sande.mythictrpg.ai.reaction;

/** A fact derived from supplied game/conversation data, never an LLM-invented world fact. */
public enum SituationSignal {
    FIRST_MEETING,
    TIME_GREETING,
    LONG_TIME_REUNION,
    REPEATED_CONVERSATION,
    WEATHER_CHANGE,
    LOW_HEALTH,
    POST_COMBAT,
    QUEST_REQUEST,
    QUEST_INCOMPLETE,
    QUEST_COMPLETED,
    PREFERRED_GIFT,
    DISLIKED_GIFT,
    REPEATED_GIFT,
    COMPLIMENT,
    INSULT,
    APOLOGY,
    ASK_ABOUT_OTHER_GOD,
    UNKNOWN_INFORMATION
}
