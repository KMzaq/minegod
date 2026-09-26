package com.sande.mythictrpg.ai.room;

/** Location and audience rules are enforced by the game, never inferred by the model. */
public enum RoomType {
    PUBLIC_FIXED,
    PUBLIC_MOBILE,
    PRIVATE;

    public boolean isPublic() {
        return this != PRIVATE;
    }
}
