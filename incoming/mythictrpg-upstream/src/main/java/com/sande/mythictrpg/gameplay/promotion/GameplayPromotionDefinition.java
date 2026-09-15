package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Comparator;
import java.util.Objects;

public record GameplayPromotionDefinition(
        ResourceLocation id,
        ResourceLocation observationTypeId,
        ResourceLocation signalId,
        int priority,
        long attemptCooldownTicks,
        PromotionMatcher matcher
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MIN_PRIORITY = -1_000_000;
    public static final int MAX_PRIORITY = 1_000_000;
    public static final long DEFAULT_ATTEMPT_COOLDOWN_TICKS = 1_200;
    public static final long MIN_ATTEMPT_COOLDOWN_TICKS = 20;
    public static final long MAX_ATTEMPT_COOLDOWN_TICKS = 72_000;
    public static final Comparator<GameplayPromotionDefinition> ORDERING =
            Comparator.comparingInt(GameplayPromotionDefinition::priority).reversed()
                    .thenComparing(GameplayPromotionDefinition::id);

    public GameplayPromotionDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(observationTypeId, "observationTypeId");
        Objects.requireNonNull(signalId, "signalId");
        Objects.requireNonNull(matcher, "matcher");
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            throw new IllegalArgumentException("Priority must be between " + MIN_PRIORITY
                    + " and " + MAX_PRIORITY);
        }
        if (attemptCooldownTicks < MIN_ATTEMPT_COOLDOWN_TICKS
                || attemptCooldownTicks > MAX_ATTEMPT_COOLDOWN_TICKS) {
            throw new IllegalArgumentException("Attempt cooldown must be between "
                    + MIN_ATTEMPT_COOLDOWN_TICKS + " and " + MAX_ATTEMPT_COOLDOWN_TICKS + " ticks");
        }
    }
}
