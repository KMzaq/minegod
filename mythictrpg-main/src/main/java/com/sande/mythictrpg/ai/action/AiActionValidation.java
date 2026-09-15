package com.sande.mythictrpg.ai.action;

/** Read-only validator outcome. */
public record AiActionValidation(boolean accepted, String reason) {
    public AiActionValidation {
        reason = reason == null ? "" : reason.trim();
        if (!accepted && reason.isBlank()) {
            throw new IllegalArgumentException("Rejected AI actions require a reason");
        }
    }

    public static AiActionValidation accept() {
        return new AiActionValidation(true, "");
    }

    public static AiActionValidation reject(String reason) {
        return new AiActionValidation(false, reason);
    }
}
