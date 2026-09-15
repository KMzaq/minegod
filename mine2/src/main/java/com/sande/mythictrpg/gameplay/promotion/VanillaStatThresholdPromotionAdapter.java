package com.sande.mythictrpg.gameplay.promotion;

import com.google.gson.JsonObject;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationType;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.gameplay.observation.VanillaStatThresholdCrossedPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public final class VanillaStatThresholdPromotionAdapter
        implements GameplayPromotionAdapter<VanillaStatThresholdCrossedPayload,
        VanillaStatThresholdPromotionMatcher> {
    public static final VanillaStatThresholdPromotionAdapter INSTANCE =
            new VanillaStatThresholdPromotionAdapter();

    private VanillaStatThresholdPromotionAdapter() {
    }

    @Override
    public GameplayObservationType<VanillaStatThresholdCrossedPayload> observationType() {
        return GameplayObservationTypes.VANILLA_STAT_THRESHOLD_CROSSED;
    }

    @Override
    public Class<VanillaStatThresholdPromotionMatcher> matcherType() {
        return VanillaStatThresholdPromotionMatcher.class;
    }

    @Override
    public VanillaStatThresholdPromotionMatcher parseMatcher(JsonObject json) {
        return GameplayPromotionSchema.parseVanillaThresholdMatcher(json);
    }

    @Override
    public PromotionIndexTarget compileTarget(ResourceLocation promotionId,
            VanillaStatThresholdPromotionMatcher matcher) {
        return PromotionIndexTarget.exact(PromotionLookupKey.id(promotionId));
    }

    @Override
    public PromotionLookupKey runtimeKey(VanillaStatThresholdCrossedPayload payload) {
        return PromotionLookupKey.id(payload.watchId());
    }

    @Override
    public boolean matches(VanillaStatThresholdCrossedPayload payload,
            VanillaStatThresholdPromotionMatcher matcher) {
        return payload.vanillaStatisticKey().equals(matcher.vanillaStatisticKey())
                && payload.metricKey().equals(matcher.metricKey())
                && payload.threshold() == matcher.threshold();
    }

    @Override
    public Optional<GameplayActionEvidence> createEvidence(VanillaStatThresholdCrossedPayload payload,
            VanillaStatThresholdPromotionMatcher matcher) {
        if (!matches(payload, matcher)) {
            return Optional.empty();
        }
        return Optional.of(new VanillaStatMilestoneEvidence(
                payload.watchId(), payload.metricKey(), payload.vanillaStatisticKey(),
                payload.previousValue(), payload.currentValue(), payload.delta(), payload.threshold()));
    }
}
