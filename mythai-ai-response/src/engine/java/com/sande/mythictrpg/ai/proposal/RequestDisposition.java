package com.sande.mythictrpg.ai.proposal;

/** Candidate narrative posture for a player request. It is not permission to execute a game action. */
public enum RequestDisposition {
    ACCEPT,
    REJECT,
    NEGOTIATE,
    ASK_FOR_VOUCH,
    OFFER_QUEST,
    ASK_FOR_PROOF,
    DEFER
}
