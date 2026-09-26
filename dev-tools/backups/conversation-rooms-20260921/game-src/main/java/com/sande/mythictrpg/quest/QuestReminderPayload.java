package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.interaction.api.InteractionPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Server-verified context supplied to the AI for one reminder interaction. */
public record QuestReminderPayload(ResourceLocation questId, ResourceLocation giverGodId,
        long ticksSinceRelevantAction, long unrelatedActivityTicks,
        int unrelatedActionCount, int previousReminderCount) implements InteractionPayload {
    public QuestReminderPayload {
        Objects.requireNonNull(questId, "questId");
        Objects.requireNonNull(giverGodId, "giverGodId");
        if (ticksSinceRelevantAction < 0L || unrelatedActivityTicks < 0L
                || unrelatedActionCount < 1 || previousReminderCount < 0) {
            throw new IllegalArgumentException("Invalid quest reminder counters");
        }
    }
}
