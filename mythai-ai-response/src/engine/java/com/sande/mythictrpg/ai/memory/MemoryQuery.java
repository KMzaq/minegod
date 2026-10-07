package com.sande.mythictrpg.ai.memory;

/** A bounded, player-specific lookup request. */
public record MemoryQuery(String npcId, String playerId, String currentText, int maximumResults) {
    public MemoryQuery {
        npcId = requireText(npcId, "npcId");
        playerId = requireText(playerId, "playerId");
        currentText = currentText == null ? "" : currentText.trim();
        if (maximumResults < 1 || maximumResults > 12) {
            throw new IllegalArgumentException("maximumResults must be between 1 and 12");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
