package com.sande.mythictrpg.accessory;

import java.util.Objects;

/** Values are authored per accessory. No production reward or balance values are assigned here. */
public record AccessoryEffect(Kind kind, double amount, int durationTicks, long cooldownMillis) {
    public enum Kind {
        MAX_MANA, LINK_KNOT, CLEANSE_CC, EMERGENCY_HEAL, MISS_REFUND, LUCK,
        DAMAGE_FLAT, SPEED_FLAT, EXTRA_JUMPS, DAMAGE_REDUCTION_FLAT,
        HEALTH_REGEN_PER_SECOND, GOLD_BONUS_FRACTION
    }
    public AccessoryEffect {
        Objects.requireNonNull(kind);
        if (!Double.isFinite(amount) || amount <= 0 || durationTicks < 0 || durationTicks > 72000
                || cooldownMillis < 0 || cooldownMillis > 31_536_000_000L) throw new IllegalArgumentException("Invalid accessory effect value");
        double cap = switch(kind) {
            case MAX_MANA -> 499900;
            case LINK_KNOT, MISS_REFUND, EMERGENCY_HEAL, GOLD_BONUS_FRACTION -> 1;
            case LUCK -> 17;
            case SPEED_FLAT -> 1;
            case EXTRA_JUMPS -> 8;
            default -> 2000;
        };
        if (amount > cap || kind == Kind.EXTRA_JUMPS && amount != Math.rint(amount))
            throw new IllegalArgumentException("Accessory effect amount out of bounds");
        if(kind==Kind.CLEANSE_CC&&amount!=1)throw new IllegalArgumentException("One cleansing entitlement supplies one charge");
        if (kind == Kind.EMERGENCY_HEAL && (durationTicks < 1 || cooldownMillis < 1)
                || kind == Kind.CLEANSE_CC && cooldownMillis < 1) throw new IllegalArgumentException("Triggered accessory needs duration/cooldown");
        if (kind != Kind.EMERGENCY_HEAL && durationTicks != 0) throw new IllegalArgumentException("Only emergency healing has duration");
        if (kind != Kind.EMERGENCY_HEAL && kind != Kind.CLEANSE_CC && cooldownMillis != 0)
            throw new IllegalArgumentException("Unexpected accessory cooldown");
    }
}
