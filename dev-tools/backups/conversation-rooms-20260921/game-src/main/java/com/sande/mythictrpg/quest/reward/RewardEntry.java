package com.sande.mythictrpg.quest.reward;

/** One immutable, server-executable reward operation. */
public sealed interface RewardEntry permits NpcRewardEntry, AffinityRewardEntry,
        BlessingRewardEntry, TitleRewardEntry, CurrencyRewardEntry, UnlockShopProductRewardEntry, WatchRewardEntry {
    String description();
}
