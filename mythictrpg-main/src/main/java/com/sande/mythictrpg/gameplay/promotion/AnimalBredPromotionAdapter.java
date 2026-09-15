package com.sande.mythictrpg.gameplay.promotion;

import com.google.gson.JsonObject;
import com.sande.mythictrpg.gameplay.observation.AnimalBredPayload;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationType;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public final class AnimalBredPromotionAdapter
        implements GameplayPromotionAdapter<AnimalBredPayload, AnimalBredPromotionMatcher> {
    public static final AnimalBredPromotionAdapter INSTANCE = new AnimalBredPromotionAdapter();

    private AnimalBredPromotionAdapter() {
    }

    @Override
    public GameplayObservationType<AnimalBredPayload> observationType() {
        return GameplayObservationTypes.ANIMAL_BRED;
    }

    @Override
    public Class<AnimalBredPromotionMatcher> matcherType() {
        return AnimalBredPromotionMatcher.class;
    }

    @Override
    public AnimalBredPromotionMatcher parseMatcher(JsonObject json) {
        return GameplayPromotionSchema.parseAnimalBredMatcher(json);
    }

    @Override
    public PromotionIndexTarget compileTarget(ResourceLocation promotionId,
            AnimalBredPromotionMatcher matcher) {
        return matcher.childEntityTypeId()
                .<PromotionIndexTarget>map(id -> PromotionIndexTarget.exact(PromotionLookupKey.id(id)))
                .orElseGet(PromotionIndexTarget::wildcard);
    }

    @Override
    public PromotionLookupKey runtimeKey(AnimalBredPayload payload) {
        return PromotionLookupKey.id(payload.childEntityTypeId());
    }

    @Override
    public boolean matches(AnimalBredPayload payload, AnimalBredPromotionMatcher matcher) {
        return matcher.childEntityTypeId().map(payload.childEntityTypeId()::equals).orElse(true)
                && matcher.parents().map(expected -> expected.equals(AnimalParentPair.of(
                        payload.parentAEntityTypeId(), payload.parentBEntityTypeId()))).orElse(true);
    }

    @Override
    public Optional<GameplayActionEvidence> createEvidence(AnimalBredPayload payload,
            AnimalBredPromotionMatcher matcher) {
        if (!matches(payload, matcher)) {
            return Optional.empty();
        }
        return Optional.of(new AnimalBredEvidence(AnimalParentPair.of(
                payload.parentAEntityTypeId(), payload.parentBEntityTypeId()),
                payload.childEntityTypeId()));
    }
}
