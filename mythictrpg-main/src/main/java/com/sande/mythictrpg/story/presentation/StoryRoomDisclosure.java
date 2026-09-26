package com.sande.mythictrpg.story.presentation;

import java.util.List;

/** Conservative legacy fallback when a fact level has no explicit audience-disclosure permission. */
public final class StoryRoomDisclosure {
    private StoryRoomDisclosure() { }
    public static int allowedLevel(int speakerKnownLevel, boolean publicRoom, int publicLevel,
            List<Integer> playerDisclosedLevels, List<Integer> otherGodKnownLevels) {
        if (speakerKnownLevel < 1 || playerDisclosedLevels.isEmpty()) return 0;
        int level = publicRoom ? Math.min(speakerKnownLevel, publicLevel) : speakerKnownLevel;
        for (Integer disclosed : playerDisclosedLevels) level = Math.min(level, disclosed == null ? 0 : disclosed);
        for (Integer known : otherGodKnownLevels) level = Math.min(level, known == null ? 0 : known);
        return Math.max(0, level);
    }
}
