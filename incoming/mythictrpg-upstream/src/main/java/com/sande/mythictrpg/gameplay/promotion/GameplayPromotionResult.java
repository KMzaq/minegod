package com.sande.mythictrpg.gameplay.promotion;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record GameplayPromotionResult(
        Status status,
        Optional<ResourceLocation> promotionRuleId,
        Optional<GameplaySignalResult> signalResult
) {
    public GameplayPromotionResult {
        Objects.requireNonNull(status, "status");
        promotionRuleId = Objects.requireNonNull(promotionRuleId, "promotionRuleId");
        signalResult = Objects.requireNonNull(signalResult, "signalResult");
        if (status.isSinkResult() != signalResult.isPresent()) {
            throw new IllegalArgumentException("Sink statuses must contain exactly one signal result");
        }
        if (status.requiresRuleId() != promotionRuleId.isPresent()) {
            throw new IllegalArgumentException("Invalid promotion rule ID for status " + status);
        }
        if (signalResult.isPresent() && status.signalResult() != signalResult.orElseThrow()) {
            throw new IllegalArgumentException("Status and signal result do not agree");
        }
    }

    public static GameplayPromotionResult withoutRule(Status status) {
        return new GameplayPromotionResult(status, Optional.empty(), Optional.empty());
    }

    public static GameplayPromotionResult forRule(Status status, ResourceLocation ruleId) {
        return new GameplayPromotionResult(status, Optional.of(ruleId), Optional.empty());
    }

    public static GameplayPromotionResult fromSink(ResourceLocation ruleId,
            GameplaySignalResult result) {
        return new GameplayPromotionResult(Status.fromSignalResult(result), Optional.of(ruleId),
                Optional.of(result));
    }

    public enum Status {
        NO_MATCH,
        PLAYER_OFFLINE,
        ACTIVITY_UNAVAILABLE,
        PLAYER_IDLE,
        ALL_CANDIDATES_COOLDOWN,
        SIGNAL_CREATION_FAILED,
        CAPACITY_REJECTED,
        SINK_ACCEPTED(GameplaySignalResult.ACCEPTED),
        SINK_UNAVAILABLE(GameplaySignalResult.UNAVAILABLE),
        SINK_REJECTED(GameplaySignalResult.REJECTED),
        SINK_FAILED(GameplaySignalResult.FAILED);

        private final GameplaySignalResult signalResult;

        Status() {
            this.signalResult = null;
        }

        Status(GameplaySignalResult signalResult) {
            this.signalResult = signalResult;
        }

        boolean isSinkResult() {
            return signalResult != null;
        }

        boolean requiresRuleId() {
            return this == SIGNAL_CREATION_FAILED || this == CAPACITY_REJECTED || isSinkResult();
        }

        GameplaySignalResult signalResult() {
            return signalResult;
        }

        static Status fromSignalResult(GameplaySignalResult result) {
            Objects.requireNonNull(result, "result");
            return switch (result) {
                case ACCEPTED -> SINK_ACCEPTED;
                case UNAVAILABLE -> SINK_UNAVAILABLE;
                case REJECTED -> SINK_REJECTED;
                case FAILED -> SINK_FAILED;
            };
        }
    }
}
