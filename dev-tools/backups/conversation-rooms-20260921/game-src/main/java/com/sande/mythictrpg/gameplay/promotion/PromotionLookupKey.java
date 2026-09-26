package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public sealed interface PromotionLookupKey
        permits PromotionLookupKey.Id, PromotionLookupKey.Missing {
    static PromotionLookupKey id(ResourceLocation value) {
        return new Id(value);
    }

    static PromotionLookupKey missing() {
        return Missing.INSTANCE;
    }

    record Id(ResourceLocation value) implements PromotionLookupKey {
        public Id {
            Objects.requireNonNull(value, "value");
        }
    }

    enum Missing implements PromotionLookupKey {
        INSTANCE
    }
}
