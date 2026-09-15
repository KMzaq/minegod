package com.sande.mythictrpg.gameplay.sampling;

import java.util.List;

public record SamplingResult(
        int playersConsidered,
        int sourceReads,
        int baselinesCreated,
        int resets,
        int unavailableReads,
        List<ThresholdCrossing> crossings
) {
    private static final SamplingResult EMPTY = new SamplingResult(0, 0, 0, 0, 0, List.of());

    public SamplingResult {
        crossings = List.copyOf(crossings);
    }

    public static SamplingResult empty() {
        return EMPTY;
    }
}
