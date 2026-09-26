package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** Optional per-quest policy for AI-authored reminders after unrelated play. */
public record QuestReminderPolicy(long unrelatedActivityTicks, long cooldownTicks,
        ResourceLocation signalId, List<QuestRelevantAction> relevantActions) {
    public static final long MIN_INTERVAL_TICKS = 20L;
    public static final long MAX_INTERVAL_TICKS = 51_840_000L; // 30 real-time days at 20 TPS

    public QuestReminderPolicy {
        Objects.requireNonNull(signalId, "signalId");
        relevantActions = List.copyOf(Objects.requireNonNull(relevantActions, "relevantActions"));
        requireInterval(unrelatedActivityTicks, "unrelatedActivityTicks");
        requireInterval(cooldownTicks, "cooldownTicks");
        if (relevantActions.isEmpty()) {
            throw new IllegalArgumentException("Quest reminder requires at least one relevant action");
        }
    }

    public boolean isRelevant(GameplayObservation<?> observation) {
        return relevantActions.stream().anyMatch(action -> action.matches(observation));
    }

    private static void requireInterval(long value, String field) {
        if (value < MIN_INTERVAL_TICKS || value > MAX_INTERVAL_TICKS) {
            throw new IllegalArgumentException(field + " must be between " + MIN_INTERVAL_TICKS
                    + " and " + MAX_INTERVAL_TICKS + " ticks");
        }
    }
}
