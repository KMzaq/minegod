package com.sande.mythictrpg.gameplay.promotion;

import java.util.Objects;

public sealed interface PromotionIndexTarget
        permits PromotionIndexTarget.Exact, PromotionIndexTarget.Wildcard {
    static PromotionIndexTarget exact(PromotionLookupKey key) {
        return new Exact(key);
    }

    static PromotionIndexTarget wildcard() {
        return Wildcard.INSTANCE;
    }

    record Exact(PromotionLookupKey key) implements PromotionIndexTarget {
        public Exact {
            Objects.requireNonNull(key, "key");
        }
    }

    enum Wildcard implements PromotionIndexTarget {
        INSTANCE
    }
}
