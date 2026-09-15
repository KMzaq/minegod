package com.sande.mythictrpg.gameplay.sampling;

@FunctionalInterface
public interface WatchedMetricSnapshotProvider {
    WatchedMetricSnapshotProvider EMPTY = WatchedMetricSnapshot::empty;

    WatchedMetricSnapshot snapshot();
}
