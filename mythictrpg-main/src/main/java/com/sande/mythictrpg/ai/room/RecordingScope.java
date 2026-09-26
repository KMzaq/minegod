package com.sande.mythictrpg.ai.room;

/** An immutable recording boundary, separate from the room's visibility. */
public enum RecordingScope {
    STANDARD,
    TEST_RECORDING,
    TEST_EPHEMERAL;

    /** STANDARD remains subject to the existing global recording policy. */
    public boolean recordingAllowed() {
        return this != TEST_EPHEMERAL;
    }

    public boolean isTest() {
        return this != STANDARD;
    }
}
