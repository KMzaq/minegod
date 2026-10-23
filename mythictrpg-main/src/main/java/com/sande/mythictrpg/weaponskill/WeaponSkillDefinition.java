package com.sande.mythictrpg.weaponskill;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import java.util.Set;

/** Authored executable skill, separate from a weapon's combat-power contribution. */
public record WeaponSkillDefinition(ResourceLocation id, String name, int manaCost, int cooldownTicks, Action action) {
    public sealed interface Action permits AimedDamage, SelfHeal, SelfEffect, AreaDamage, PeriodicDamage, BladeWave, TimedParry, ShortDash { }
    public record BladeWave(double firstDamage,double subsequentDamage,double speed,double range,double radius,int maximumTargets,
                            int windupTicks,int recoveryTicks,int lifetimeTicks) implements Action {
        public BladeWave(double firstDamage,double subsequentDamage,double speed,double range,double radius,int maximumTargets,int windupTicks,int recoveryTicks) {
            this(firstDamage,subsequentDamage,speed,range,radius,maximumTargets,windupTicks,recoveryTicks,10);
        }
        public BladeWave {
            positive(firstDamage,2000);positive(subsequentDamage,2000);positive(speed,4);positive(range,32);positive(radius,1);
            if(maximumTargets<1||maximumTargets>16)throw new IllegalArgumentException("Blade target bounds");
            if(lifetimeTicks<1||lifetimeTicks>100)throw new IllegalArgumentException("Blade lifetime bounds");
            timing(windupTicks,recoveryTicks);
        }
    }
    public record TimedParry(int windupTicks,int activeTicks,int recoveryTicks,double halfAngleDegrees,double range) implements Action {
        public TimedParry { timing(windupTicks,recoveryTicks);if(activeTicks<1||activeTicks>40)throw new IllegalArgumentException("Parry timing");positive(halfAngleDegrees,90);positive(range,8); }
    }
    public record ShortDash(int windupTicks,int movementTicks,int evasionTicks,int recoveryTicks,double distance,double minimumDistance) implements Action {
        public ShortDash {
            timing(windupTicks,recoveryTicks);if(movementTicks<1||movementTicks>40||evasionTicks<0||evasionTicks>movementTicks)throw new IllegalArgumentException("Dash timing");
            positive(distance,8);positive(minimumDistance,distance);if(distance/movementTicks>1)throw new IllegalArgumentException("Dash step exceeds safe bound");
        }
    }
    public record AimedDamage(double amount, double range) implements Action {
        public AimedDamage { positive(amount, 2000); positive(range, 32); }
    }
    public record SelfHeal(double amount) implements Action {
        public SelfHeal { positive(amount, 2000); }
    }
    public record AreaDamage(double amount,double radius,int maximumTargets) implements Action {
        public AreaDamage { positive(amount,2000);positive(radius,16);if(maximumTargets<1||maximumTargets>64)throw new IllegalArgumentException("Area target limit"); }
    }
    public record PeriodicDamage(double amount,double range,int pulses,int intervalTicks) implements Action {
        public PeriodicDamage { positive(amount,2000);positive(range,32);if(pulses<1||pulses>32||intervalTicks<1||intervalTicks>1200)throw new IllegalArgumentException("Periodic damage bounds"); }
    }
    public record SelfEffect(ResourceLocation effectId, int level, int durationTicks) implements Action {
        public SelfEffect {
            java.util.Objects.requireNonNull(effectId);
            int cap = effectId.toString().equals("minecraft:resistance") ? 4 : 17;
            if (level < 1 || level > cap || durationTicks < 1 || durationTicks > 72000)
                throw new IllegalArgumentException("Invalid skill effect level/duration");
        }
    }
    public WeaponSkillDefinition {
        java.util.Objects.requireNonNull(id); java.util.Objects.requireNonNull(action);
        if (name == null || name.isBlank() || name.length() > 128 || manaCost < 0 || manaCost > ManaAccount.AUTHORING_MAX_MANA
                || cooldownTicks < 0 || cooldownTicks > 72000) throw new IllegalArgumentException("Invalid skill costs/name");
    }
    public static WeaponSkillDefinition parse(ResourceLocation id, JsonObject json) {
        fields(json, "schemaVersion", "name", "manaCost", "cooldownTicks", "action");
        if (integer(json, "schemaVersion") != 1) throw new IllegalArgumentException("Unknown weapon skill schema");
        return new WeaponSkillDefinition(id,string(json,"name"),integer(json,"manaCost"),integer(json,"cooldownTicks"),parseAction(json.getAsJsonObject("action")));
    }
    public static Action parseAction(JsonObject value) {
        return switch (string(value, "type")) {
            case "AIMED_DAMAGE" -> {
                fields(value, "type", "amount", "range");
                yield new AimedDamage(number(value, "amount"), number(value, "range"));
            }
            case "SELF_HEAL" -> {
                fields(value, "type", "amount"); yield new SelfHeal(number(value, "amount"));
            }
            case "SELF_EFFECT" -> {
                fields(value, "type", "effectId", "level", "durationTicks");
                String effect = string(value, "effectId");
                if (!effect.contains(":")) throw new IllegalArgumentException("Explicit effect namespace required");
                yield new SelfEffect(ResourceLocation.parse(effect), integer(value, "level"), integer(value, "durationTicks"));
            }
            case "AREA_DAMAGE" -> {
                fields(value,"type","amount","radius","maximumTargets");
                yield new AreaDamage(number(value,"amount"),number(value,"radius"),integer(value,"maximumTargets"));
            }
            case "PERIODIC_DAMAGE" -> {
                fields(value,"type","amount","range","pulses","intervalTicks");
                yield new PeriodicDamage(number(value,"amount"),number(value,"range"),integer(value,"pulses"),integer(value,"intervalTicks"));
            }
            case "BLADE_WAVE" -> {
                if(value.has("lifetimeTicks"))fields(value,"type","firstDamage","subsequentDamage","speed","range","radius","maximumTargets","windupTicks","recoveryTicks","lifetimeTicks");
                else fields(value,"type","firstDamage","subsequentDamage","speed","range","radius","maximumTargets","windupTicks","recoveryTicks");
                yield new BladeWave(number(value,"firstDamage"),number(value,"subsequentDamage"),number(value,"speed"),number(value,"range"),number(value,"radius"),integer(value,"maximumTargets"),integer(value,"windupTicks"),integer(value,"recoveryTicks"),value.has("lifetimeTicks")?integer(value,"lifetimeTicks"):10);
            }
            case "TIMED_PARRY" -> {
                fields(value,"type","windupTicks","activeTicks","recoveryTicks","halfAngleDegrees","range");
                yield new TimedParry(integer(value,"windupTicks"),integer(value,"activeTicks"),integer(value,"recoveryTicks"),number(value,"halfAngleDegrees"),number(value,"range"));
            }
            case "SHORT_DASH" -> {
                fields(value,"type","windupTicks","movementTicks","evasionTicks","recoveryTicks","distance","minimumDistance");
                yield new ShortDash(integer(value,"windupTicks"),integer(value,"movementTicks"),integer(value,"evasionTicks"),integer(value,"recoveryTicks"),number(value,"distance"),number(value,"minimumDistance"));
            }
            default -> throw new IllegalArgumentException("Unknown weapon skill action");
        };
    }
    private static void fields(JsonObject value, String... fields) {
        if (value == null || !value.keySet().equals(Set.of(fields))) throw new IllegalArgumentException("Missing/unknown skill fields");
    }
    private static String string(JsonObject value, String key) {
        var v = value == null ? null : value.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected skill string " + key);
        return v.getAsString();
    }
    private static double number(JsonObject value, String key) {
        var v = value.get(key);
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() || !Double.isFinite(v.getAsDouble()))
            throw new IllegalArgumentException("Expected finite skill number " + key);
        return v.getAsDouble();
    }
    private static int integer(JsonObject value, String key) { number(value,key); return value.get(key).getAsBigDecimal().intValueExact(); }
    private static void positive(double value, double maximum) {
        if (!Double.isFinite(value) || value <= 0 || value > maximum) throw new IllegalArgumentException("Skill amount/range out of bounds");
    }
    private static void timing(int windup,int recovery) {
        if(windup<1||windup>40||recovery<0||recovery>100)throw new IllegalArgumentException("Skill cast timing bounds");
    }
}
