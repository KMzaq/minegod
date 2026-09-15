package com.sande.mythictrpg.ai.example;

/**
 * Stable v1 tags for the shared dialogue-example library. They deliberately are not raw character tags or
 * persisted relationship/emotion values.
 */
public enum DialogueExampleTag {
    P_GRUFF(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_GENTLE(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_STRICT(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_COLD(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_IMPERIOUS(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_AGGRESSIVE(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_CUNNING(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_ARROGANT(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_PLAYFUL(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_CALM(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_WISE(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_HONORABLE(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_FORMAL(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_MYSTERIOUS(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_SHORT(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_TALKATIVE(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_DRY_HUMOR(DialogueExampleTagCategory.PERSONALITY_SPEECH),
    P_INDIRECT_CARE(DialogueExampleTagCategory.PERSONALITY_SPEECH),

    R_STRANGER(DialogueExampleTagCategory.RELATIONSHIP),
    R_ACQUAINTANCE(DialogueExampleTagCategory.RELATIONSHIP),
    R_FRIENDLY(DialogueExampleTagCategory.RELATIONSHIP),
    R_CLOSE(DialogueExampleTagCategory.RELATIONSHIP),
    R_DISTRUST(DialogueExampleTagCategory.RELATIONSHIP),
    R_HOSTILE(DialogueExampleTagCategory.RELATIONSHIP),

    E_NEUTRAL(DialogueExampleTagCategory.EMOTION),
    E_HAPPY(DialogueExampleTagCategory.EMOTION),
    E_ANGRY(DialogueExampleTagCategory.EMOTION),
    E_ANNOYED(DialogueExampleTagCategory.EMOTION),
    E_CURIOUS(DialogueExampleTagCategory.EMOTION),
    E_SAD(DialogueExampleTagCategory.EMOTION),
    E_GRATEFUL(DialogueExampleTagCategory.EMOTION),

    S_CHAT(DialogueExampleTagCategory.SITUATION),
    S_ITEM_REQUEST(DialogueExampleTagCategory.SITUATION),
    S_POWER_REQUEST(DialogueExampleTagCategory.SITUATION),
    S_HELP_REQUEST(DialogueExampleTagCategory.SITUATION),
    S_INFORMATION_REQUEST(DialogueExampleTagCategory.SITUATION),
    S_QUEST_INQUIRY(DialogueExampleTagCategory.SITUATION),
    S_REWARD_NEGOTIATION(DialogueExampleTagCategory.SITUATION),
    S_GIFT_OFFER(DialogueExampleTagCategory.SITUATION),
    S_APOLOGY(DialogueExampleTagCategory.SITUATION),
    S_CONFLICT(DialogueExampleTagCategory.SITUATION),

    /** Legacy tags are retained only so old content files can still load; the intent classifier never emits them. */
    @Deprecated S_SMALLTALK(DialogueExampleTagCategory.SITUATION),
    @Deprecated S_SECRET_REQUEST(DialogueExampleTagCategory.SITUATION),
    @Deprecated S_QUEST_OFFER(DialogueExampleTagCategory.SITUATION),
    @Deprecated S_VOUCH(DialogueExampleTagCategory.SITUATION),

    C_ONE_TO_ONE(DialogueExampleTagCategory.CONVERSATION_CONTEXT),
    C_GROUP(DialogueExampleTagCategory.CONVERSATION_CONTEXT),
    C_TRUSTED_FRIEND_PRESENT(DialogueExampleTagCategory.CONVERSATION_CONTEXT),
    C_UNTRUSTED_LISTENER(DialogueExampleTagCategory.CONVERSATION_CONTEXT),
    C_MULTIPLE_GODS(DialogueExampleTagCategory.CONVERSATION_CONTEXT),
    C_PLAYER_VOUCHING(DialogueExampleTagCategory.CONVERSATION_CONTEXT),
    C_ARGUMENT(DialogueExampleTagCategory.CONVERSATION_CONTEXT),
    C_PRIVATE_TOPIC(DialogueExampleTagCategory.CONVERSATION_CONTEXT);

    private final DialogueExampleTagCategory category;

    DialogueExampleTag(DialogueExampleTagCategory category) {
        this.category = category;
    }

    public DialogueExampleTagCategory category() {
        return category;
    }
}
