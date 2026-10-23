package com.sande.mythictrpg.quest.reward.luck;

import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import java.util.Objects;

public record LuckyItemCandidate(String id, NpcRewardEntry item, int minCount, int maxCount,
        int quality, int weight, boolean quantityBonusAllowed) {
    public LuckyItemCandidate {
        Objects.requireNonNull(item);
        if (id == null || !id.matches("[a-z0-9_./-]{1,128}")) throw new IllegalArgumentException("Invalid lucky candidate ID");
        if (minCount < 1 || maxCount < minCount || maxCount > 64) throw new IllegalArgumentException("Lucky count range must be within 1..64");
        LuckModel.quality(quality);
        if (weight < 1 || weight > 1_000_000) throw new IllegalArgumentException("Lucky weight must be within 1..1000000");
    }
}
