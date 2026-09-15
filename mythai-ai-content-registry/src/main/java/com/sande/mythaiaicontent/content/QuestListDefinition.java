package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** A reusable static quest list that may be referenced by any number of God profiles. */
public record QuestListDefinition(ResourceLocation questListId, ResourceLocation progressTrackId,
        String displayName, List<QuestDefinition> quests) {
    public QuestListDefinition {
        Objects.requireNonNull(questListId, "questListId");
        Objects.requireNonNull(progressTrackId, "progressTrackId");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Quest list displayName must not be blank");
        }
        displayName = displayName.trim();
        quests = quests == null ? List.of() : List.copyOf(quests);
        HashSet<ResourceLocation> ids = new HashSet<>();
        for (QuestDefinition quest : quests) {
            Objects.requireNonNull(quest, "quests contains null");
            if (!ids.add(quest.questId())) {
                throw new IllegalArgumentException("Duplicate questId in quest list " + questListId + ": "
                        + quest.questId());
            }
        }
    }
}
