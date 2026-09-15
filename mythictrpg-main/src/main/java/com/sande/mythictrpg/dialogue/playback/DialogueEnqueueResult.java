package com.sande.mythictrpg.dialogue.playback;

public enum DialogueEnqueueResult {
    STARTED,
    QUEUED,
    INTERRUPTED,
    DUPLICATE,
    DROPPED_OVERFLOW,
    QUEUED_AFTER_EVICTION
}
