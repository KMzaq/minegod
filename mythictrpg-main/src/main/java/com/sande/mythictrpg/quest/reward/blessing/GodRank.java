package com.sande.mythictrpg.quest.reward.blessing;

import java.util.Locale;

/** Authored deity rank; independent of reward-table tiers and equipment grades. */
public enum GodRank {
    LOWEST("최하급"), LOW("하급"), MIDDLE("중급"), HIGH("상급"),
    HIGHEST("최상급"), TRANSCENDENT("초월급");

    private final String displayName;
    GodRank(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }
    public String serializedName() { return name().toLowerCase(Locale.ROOT); }
    public static GodRank parse(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
