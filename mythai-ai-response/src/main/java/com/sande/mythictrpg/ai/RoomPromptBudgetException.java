package com.sande.mythictrpg.ai;

/** Contains sizing only, never private context text. Required data is not silently truncated. */
final class RoomPromptBudgetException extends IllegalArgumentException {
    RoomPromptBudgetException(int characters) {
        super("Required room context exceeds 12000 characters after optional selection: " + characters);
    }
}
