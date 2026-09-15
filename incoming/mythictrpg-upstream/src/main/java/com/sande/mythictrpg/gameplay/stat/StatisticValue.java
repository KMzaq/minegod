package com.sande.mythictrpg.gameplay.stat;

public record StatisticValue(StatisticReadStatus status, int value) {
    public static StatisticValue available(int value) {
        return new StatisticValue(StatisticReadStatus.AVAILABLE, value);
    }

    public static StatisticValue unavailable() {
        return new StatisticValue(StatisticReadStatus.UNAVAILABLE, 0);
    }

    public boolean isAvailable() {
        return status == StatisticReadStatus.AVAILABLE;
    }
}
