package com.sande.mythictrpg.shop;

import java.util.Locale;

public enum ShopType {
    BUY,
    SELL;

    public static ShopType parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Shop type must be buy or sell");
        }
    }
}
