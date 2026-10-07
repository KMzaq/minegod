package com.sande.mythictrpg.ai.proposal;

/** The narrative decision proposed while negotiating a reward; the game still decides whether anything is granted. */
public enum RewardNegotiationDecision {
    OFFER,
    ACCEPT,
    REJECT,
    NEGOTIATE,
    DOWNGRADE,
    OFFER_ALTERNATIVE,
    ASK_FOR_MORE
}
