package com.sande.mythictrpg.ai.action;

import java.util.LinkedHashMap;
import java.util.Map;

/** Result returned by a game-owned executor after it attempts an authoritative commit. */
public record AiActionExecution(boolean executed, String reason, Map<String, String> details) {
    public AiActionExecution {
        reason = reason == null ? "" : reason.trim();
        details = details == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(details));
        if (!executed && reason.isBlank()) {
            throw new IllegalArgumentException("Rejected AI action execution requires a reason");
        }
    }

    public static AiActionExecution executed(Map<String, String> details) {
        return new AiActionExecution(true, "", details);
    }

    public static AiActionExecution rejected(String reason) {
        return new AiActionExecution(false, reason, Map.of());
    }
}
