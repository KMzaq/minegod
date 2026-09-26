package com.sande.mythictrpg.interaction.spontaneous;

/** Immediate submission result. ACCEPTED does not mean that an interaction started. */
public enum SpontaneousSubmissionResult {
    ACCEPTED,
    UNAVAILABLE,
    REJECTED,
    FAILED
}
