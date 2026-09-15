package com.sande.mythictrpg.ai.vouch;

import net.minecraft.resources.ResourceLocation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * AI-owned conversation state for one NPC asking a present player to vouch for another player.
 * It deliberately contains no relationship score, reward, or game-mutation handle.
 */
public record PendingVouchInteraction(ResourceLocation npcId, UUID sponsorPlayerId, UUID beneficiaryPlayerId,
        String originalRequestText, String reason, Instant createdAt, Instant expiresAt) {
    public PendingVouchInteraction {
        npcId = Objects.requireNonNull(npcId, "npcId");
        sponsorPlayerId = Objects.requireNonNull(sponsorPlayerId, "sponsorPlayerId");
        beneficiaryPlayerId = Objects.requireNonNull(beneficiaryPlayerId, "beneficiaryPlayerId");
        if (sponsorPlayerId.equals(beneficiaryPlayerId)) {
            throw new IllegalArgumentException("A player cannot vouch for themselves");
        }
        originalRequestText = boundedText(originalRequestText, "originalRequestText", 600);
        reason = boundedText(reason, "reason", 240);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    public boolean expiredAt(Instant now) {
        return !expiresAt.isAfter(Objects.requireNonNull(now, "now"));
    }

    /** A concise, prompt-safe state summary; the original player text remains in the conversation history as well. */
    public String description() {
        return "vouch_request: npc=" + npcId + ", sponsor=" + sponsorPlayerId + ", beneficiary="
                + beneficiaryPlayerId + ", reason=" + reason + ", originalRequest=" + originalRequestText;
    }

    private static String boundedText(String value, String name, int maximumCodePoints) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.codePointCount(0, normalized.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(name + " is too long");
        }
        for (int index = 0; index < normalized.length();) {
            int codePoint = normalized.codePointAt(index);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException(name + " contains a control character");
            }
            index += Character.charCount(codePoint);
        }
        return normalized;
    }
}
