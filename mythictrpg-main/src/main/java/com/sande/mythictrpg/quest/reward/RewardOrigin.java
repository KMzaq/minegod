package com.sande.mythictrpg.quest.reward;

/** Authoritative acquisition channel, independent of a quest's completion condition or reward God. */
public enum RewardOrigin {
    QUEST, RAID_DIRECT, NPC_DIRECT, STORY_DIRECT, LEGACY_UNSPECIFIED;

    public boolean countsForNonRaidBlessingRoute() { return this != RAID_DIRECT; }
}
