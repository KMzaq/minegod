package com.sande.mythictrpg.gameplay.promotion;

import java.util.Objects;

public record PlayerDiedPromotionMatcher(DamageTypeCriterion damageTypeCriterion)
        implements PromotionMatcher {
    public PlayerDiedPromotionMatcher {
        Objects.requireNonNull(damageTypeCriterion, "damageTypeCriterion");
    }
}
