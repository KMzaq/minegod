package com.sande.mythictrpg.ai.action;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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

    public enum Status {
        EXECUTED,
        PENDING_CONFIRMATION,
        REJECTED,
        FAILED
    }
}
