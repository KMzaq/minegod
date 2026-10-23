package com.sande.mythictrpg.weaponskill;

import com.sande.mythictrpg.skills.ManaTuning;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed-point mana with a durable regeneration remainder. Balance values live in ManaTuning. */
public final class ManaAccount {
    /** Version-one storage bounds, not editable balance defaults. Preserve when changing ManaTuning. */
    public static final int MAX_MANA = 200;
    public static final int MAX_UNITS = MAX_MANA * 10;
    public static final int AUTHORING_MAX_MANA = 1_000_000;
    static final long SCALE = 1_000_000L;
    /** Storage-format denominator: 20 ticks/second * 100 percent. Never tune this value. */
    private static final int REGEN_DENOMINATOR = 2000;
    static final int MAX_COOLDOWNS = 1024;
    private long microUnits;
    private int regenRemainder;
    private long lastUseTick;
    private long lastRegenTick;
    private final Map<String, Long> cooldownEnds;

    ManaAccount() { this(cap(ManaTuning.BASE_MAX_MANA), 0, -1, -1, Map.of()); }

    ManaAccount(int units, long lastUseTick, long lastRegenTick, Map<String, Long> cooldownEnds) {
        if (units < 0 || units > MAX_UNITS || lastUseTick < -1 || lastRegenTick < -1
                || cooldownEnds.size() > MAX_COOLDOWNS) throw new IllegalArgumentException("Invalid mana account");
        cooldownEnds.forEach((id, end) -> {
            if (!validId(id) || end == null || end < 0) throw new IllegalArgumentException("Invalid cooldown");
        });
        this.microUnits = units * (SCALE / 10);
        this.lastUseTick = lastUseTick;
        this.lastRegenTick = lastRegenTick;
        this.cooldownEnds = new LinkedHashMap<>(cooldownEnds);
    }

    ManaAccount(long microUnits, int regenRemainder, long lastUseTick, long lastRegenTick, Map<String,Long> ends) {
        this(0,lastUseTick,lastRegenTick,ends);
        if (microUnits < 0 || microUnits > AUTHORING_MAX_MANA*SCALE || regenRemainder < 0 || regenRemainder >= REGEN_DENOMINATOR)
            throw new IllegalArgumentException("Invalid dynamic mana");
        this.microUnits = microUnits; this.regenRemainder = regenRemainder;
    }
    public double mana() { return microUnits / (double)SCALE; }
    public long remainingTicks(String skillId, long gameTick) {
        if (gameTick < 0) return Long.MAX_VALUE;
        return Math.max(0, cooldownEnds.getOrDefault(skillId, 0L) - gameTick);
    }

    WeaponSkillState.Result canUse(String skillId, int manaCost, int cooldownTicks, long gameTick) {
        if (!validId(skillId) || manaCost < 0 || manaCost > AUTHORING_MAX_MANA || cooldownTicks < 0
                || gameTick < 0 || gameTick > Long.MAX_VALUE - cooldownTicks || gameTick < lastUseTick)
            return WeaponSkillState.Result.INVALID;
        if (gameTick == lastUseTick) return WeaponSkillState.Result.DUPLICATE;
        if (remainingTicks(skillId, gameTick) > 0) return WeaponSkillState.Result.COOLDOWN;
        if (microUnits < manaCost * SCALE) return WeaponSkillState.Result.NO_MANA;
        if (cooldownTicks > 0 && cooldownEnds.entrySet().stream()
                .filter(entry -> entry.getValue() > gameTick && !entry.getKey().equals(skillId)).count() >= MAX_COOLDOWNS)
            return WeaponSkillState.Result.UNAVAILABLE;
        return WeaponSkillState.Result.READY;
    }

    WeaponSkillState.Result spend(String skillId, int manaCost, int cooldownTicks, long gameTick) {
        var result = canUse(skillId, manaCost, cooldownTicks, gameTick);
        if (result != WeaponSkillState.Result.READY) return result;
        cooldownEnds.entrySet().removeIf(entry -> entry.getValue() <= gameTick);
        microUnits -= manaCost * SCALE;
        lastUseTick = gameTick;
        if (cooldownTicks > 0) cooldownEnds.put(skillId, gameTick + cooldownTicks);
        return WeaponSkillState.Result.SUCCESS;
    }

    boolean regenerate(long gameTick) { return regenerate(gameTick,ManaTuning.BASE_MAX_MANA); }
    boolean regenerate(long gameTick, double maximum) {
        long cap = cap(maximum);
        boolean clamped = clamp(maximum);
        if (gameTick < 0 || gameTick <= lastRegenTick || gameTick < lastUseTick || microUnits >= cap) return clamped;
        long numerator = ManaTuning.regenerationNumerator(cap,ManaTuning.REGEN_PERCENT_PER_SECOND) + regenRemainder;
        microUnits = Math.min(cap,microUnits + numerator / REGEN_DENOMINATOR);
        regenRemainder = microUnits == cap ? 0 : (int)(numerator % REGEN_DENOMINATOR);
        lastRegenTick = gameTick;
        return true;
    }
    boolean clamp(double maximum) {
        long cap = cap(maximum);
        if (microUnits <= cap) return false;
        microUnits = cap; regenRemainder = 0; return true;
    }
    double refund(double amount,double maximum) {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid refund");
        long cap = cap(maximum), before = microUnits;
        microUnits = Math.min(cap,microUnits + Math.round(Math.min(amount,AUTHORING_MAX_MANA)*SCALE));
        if(microUnits==cap)regenRemainder=0;
        return (microUnits-before)/(double)SCALE;
    }
    long reduceCooldown(String id,long reduction,long tick) {
        if (reduction<=0 || tick<0) return 0;
        long remaining=remainingTicks(id,tick), actual=Math.min(remaining,reduction);
        if(actual>0) cooldownEnds.put(id,tick+remaining-actual);
        return actual;
    }
    static long cap(double maximum) {
        if(!Double.isFinite(maximum)||maximum<1||maximum>AUTHORING_MAX_MANA) throw new IllegalArgumentException("Invalid maximum mana");
        return Math.round(maximum*SCALE);
    }
    int units() { return (int)(microUnits/(SCALE/10)); }
    long microUnits() { return microUnits; }
    int regenRemainder() { return regenRemainder; }
    long lastUseTick() { return lastUseTick; }
    long lastRegenTick() { return lastRegenTick; }
    Map<String, Long> cooldownEnds() { return Map.copyOf(cooldownEnds); }
    static boolean validId(String id) {
        return id != null && id.length() <= 256 && id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+");
    }
}
