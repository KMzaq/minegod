package com.sande.mythictrpg.quest.reward;

import net.minecraft.resources.ResourceLocation;
import java.util.Objects;

/** Permanent, personal entitlement committed with the existing reward receipt. */
public record ProgressionRewardEntry(Kind kind, ResourceLocation definitionId) implements RewardEntry {
    public enum Kind {
        LEARNED("learn_skill"), ACCESSORY("accessory"), SPECIAL("special_skill");
        private final String type;
        Kind(String type) { this.type=type; }
        public String type() { return type; }
    }
    public ProgressionRewardEntry { Objects.requireNonNull(kind); Objects.requireNonNull(definitionId); }
    @Override public String description() { return kind.type()+": "+definitionId; }
}
