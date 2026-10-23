package com.sande.mythictrpg.quest.reward;

import com.sande.mythictrpg.quest.reward.blessing.ResolvedBlessing;
import java.util.Objects;

/** Immutable acquisition result; never reselected on login, retries or resource reload. */
public record ResolvedBlessingRewardEntry(ResolvedBlessing blessing) implements RewardEntry {
    public ResolvedBlessingRewardEntry { Objects.requireNonNull(blessing, "blessing"); }
    @Override public String description() {
        return "가호 " + blessing.definitionId() + blessing.effectId()
                .map(id -> " / " + id + " " + blessing.level() + "단계")
                .orElse(" / 가호 소유") + " / 가호 고유 전투력 " + blessing.intrinsicScore();
    }
}
