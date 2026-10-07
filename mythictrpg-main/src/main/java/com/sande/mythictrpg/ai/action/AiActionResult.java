package com.sande.mythictrpg.ai.action;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded feedback returned to the AI integration layer. */
public record AiActionResult(UUID proposalId, ResourceLocation actionType, Status status,
        String reason, Map<String, String> details) {
    public AiActionResult {
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(status, "status");
        reason = reason == null ? "" : reason.trim();
        details = details == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(details));
    }

    public boolean succeeded() {
        return status == Status.EXECUTED;
    }

    /** Plain values for JSON projection. No model title/summary or unrestricted executor payload is copied. */
    public Map<String, String> dialogueDetails() {
        if (status != Status.EXECUTED) return Map.of();
        var safe = new LinkedHashMap<String, String>();
        details.entrySet().stream().filter(entry -> FEEDBACK_DETAILS.contains(entry.getKey()) && entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey()).limit(12)
                .forEach(entry -> safe.put(entry.getKey(), boundedPlain(entry.getValue(), 160)));
        return Map.copyOf(safe);
    }

    public String dialogueReason() { return boundedPlain(reason, 320); }

    private static String boundedPlain(String text, int maximum) {
        String safe = text.replaceAll("[\\p{Cntrl}]", " ");
        int end = Math.min(safe.length(), maximum);
        if (end < safe.length() && end > 0 && Character.isHighSurrogate(safe.charAt(end - 1))) end--;
        return safe.substring(0, end);
    }

    /** Game facts only: never echo the model's title/summary or arbitrary executor payloads into context. */
    public String feedbackLine() {
        StringBuilder line = new StringBuilder("Game validator: proposal=").append(proposalId)
                .append(" action=").append(actionType).append(" status=").append(status)
                .append(" reason=\"").append(quoted(reason, 320)).append('"');
        if (status == Status.EXECUTED) details.entrySet().stream()
                .filter(entry -> FEEDBACK_DETAILS.contains(entry.getKey()) && entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey()).limit(12)
                .forEach(entry -> line.append(' ').append(entry.getKey()).append("=\"")
                        .append(quoted(entry.getValue(), 160)).append('"'));
        return line.toString();
    }

    private static final Set<String> FEEDBACK_DETAILS = Set.of(
            "template_id", "item_id", "count", "effect_id", "duration_ticks", "amplifier",
            "damage_mode", "damage_type", "requested_damage", "health_before", "health_after", "allow_death",
            "quest_id", "quest_status", "reward_table_id", "tier", "status", "combat_started", "attempt_id",
            "affinity_before", "affinity_after", "affinity_delta", "event_id", "event_kind");

    private static String quoted(String text, int maximum) {
        String bounded = text.length() > maximum ? text.substring(0, maximum) + "…" : text;
        return bounded.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\p{Cntrl}]", " ");
    }

    public enum Status {
        EXECUTED,
        PENDING_CONFIRMATION,
        CANCELLED,
        EXPIRED,
        REJECTED,
        FAILED
    }
}
