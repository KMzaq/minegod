package com.sande.mythictrpg.gameplay.promotion;

import com.google.gson.JsonObject;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationType;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.gameplay.observation.MatureCropHarvestPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public final class MatureCropHarvestPromotionAdapter
        implements GameplayPromotionAdapter<MatureCropHarvestPayload, MatureCropHarvestPromotionMatcher> {
    public static final MatureCropHarvestPromotionAdapter INSTANCE =
            new MatureCropHarvestPromotionAdapter();

    private MatureCropHarvestPromotionAdapter() {
    }

    @Override
    public GameplayObservationType<MatureCropHarvestPayload> observationType() {
        return GameplayObservationTypes.MATURE_CROP_HARVESTED;
    }

    @Override
    public Class<MatureCropHarvestPromotionMatcher> matcherType() {
        return MatureCropHarvestPromotionMatcher.class;
    }

    @Override
    public MatureCropHarvestPromotionMatcher parseMatcher(JsonObject json) {
        return GameplayPromotionSchema.parseMatureCropHarvestMatcher(json);
    }

    @Override
    public PromotionIndexTarget compileTarget(ResourceLocation promotionId,
            MatureCropHarvestPromotionMatcher matcher) {
        return matcher.cropBlockId()
                .<PromotionIndexTarget>map(id -> PromotionIndexTarget.exact(PromotionLookupKey.id(id)))
                .orElseGet(PromotionIndexTarget::wildcard);
    }

    @Override
    public PromotionLookupKey runtimeKey(MatureCropHarvestPayload payload) {
        return PromotionLookupKey.id(payload.cropBlockId());
    }

    @Override
    public boolean matches(MatureCropHarvestPayload payload, MatureCropHarvestPromotionMatcher matcher) {
        return matcher.cropBlockId().map(payload.cropBlockId()::equals).orElse(true);
    }

    @Override
    public Optional<GameplayActionEvidence> createEvidence(MatureCropHarvestPayload payload,
            MatureCropHarvestPromotionMatcher matcher) {
        return matches(payload, matcher)
                ? Optional.of(new MatureCropHarvestEvidence(payload.cropBlockId()))
                : Optional.empty();
    }
}
