package com.sande.mythictrpg.rumor;

import java.util.*;

/** Stored with the original evidence in ONE game-owned snapshot; never an AI-created witness. */
public record CourierProof(UUID sourceId, long sourceRevision, String ruleId, String ruleFingerprint,
        String eventType, CourierSettings.Source source, long recordedAt, long gameTick,
        String dimension, int x, int y, int z, String excerptHash) {
    public CourierProof {
        Objects.requireNonNull(sourceId);Objects.requireNonNull(source);
        CourierSettings.identifier(ruleId);CourierSettings.identifier(eventType);CourierSettings.identifier(dimension);
        if(sourceRevision<1||recordedAt<0||gameTick<0||Math.abs((long)x)>30000000||Math.abs((long)z)>30000000||y< -2048||y>2048
                ||ruleFingerprint==null||!ruleFingerprint.matches("[a-f0-9]{64}")||excerptHash==null||!excerptHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("invalid witness proof");
    }
}
