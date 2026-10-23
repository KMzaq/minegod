package com.sande.mythictrpg.quest.reward.luck;

import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import java.util.Objects;

/** Fully resolved item/components/count and luck level, frozen in the receipt before granting. */
public record LuckyRewardSelection(NpcRewardEntry reward, int level, String candidateId) {
    public LuckyRewardSelection {
        Objects.requireNonNull(reward);
        if (level < 0 || level > 17) throw new IllegalArgumentException("Resolved luck level must be 0..17");
        if (candidateId == null || !candidateId.matches("[a-z0-9_./-]{1,128}")) throw new IllegalArgumentException("Invalid selected lucky candidate ID");
    }
}
