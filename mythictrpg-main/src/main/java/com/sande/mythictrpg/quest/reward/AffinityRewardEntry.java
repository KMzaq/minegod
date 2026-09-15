package com.sande.mythictrpg.quest.reward;

/** Positive quest affinity reward. AI relationship actions retain their separate +/-50 rule. */
public record AffinityRewardEntry(int amount) implements RewardEntry {
    public AffinityRewardEntry {
        if (amount < 1 || amount > 200) {
            throw new IllegalArgumentException("Quest affinity reward must be between 1 and 200");
        }
    }

    @Override
    public String description() {
        return "호감도 +" + amount;
    }
}
