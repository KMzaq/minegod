package com.sande.mythaiaicontent.content;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Static quest plus the reusable list and 0..100 progress track it belongs to. */
public record QuestCandidateDefinition(ResourceLocation questListId, ResourceLocation progressTrackId,
        QuestDefinition quest) {
    public QuestCandidateDefinition {
        Objects.requireNonNull(questListId, "questListId");
        Objects.requireNonNull(progressTrackId, "progressTrackId");
        Objects.requireNonNull(quest, "quest");
    }

    public String promptSummary() {
        return "questListId=" + questListId + ", progressTrackId=" + progressTrackId + ", "
                + quest.promptSummary();
    }
}
