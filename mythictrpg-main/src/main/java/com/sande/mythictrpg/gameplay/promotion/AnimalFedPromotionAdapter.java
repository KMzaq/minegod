package com.sande.mythictrpg.gameplay.promotion;

import com.google.gson.JsonObject;
import com.sande.mythictrpg.gameplay.observation.AnimalFeedEntry;
import com.sande.mythictrpg.gameplay.observation.AnimalFedPayload;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationType;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

public final class AnimalFedPromotionAdapter
        implements GameplayPromotionAdapter<AnimalFedPayload, AnimalFedPromotionMatcher> {
    public static final AnimalFedPromotionAdapter INSTANCE = new AnimalFedPromotionAdapter();

    private AnimalFedPromotionAdapter() {
    }

    @Override
    public GameplayObservationType<AnimalFedPayload> observationType() {
        return GameplayObservationTypes.ANIMAL_FED;
    }

    @Override
    public Class<AnimalFedPromotionMatcher> matcherType() {
        return AnimalFedPromotionMatcher.class;
    }

    @Override
    public AnimalFedPromotionMatcher parseMatcher(JsonObject json) {
        return GameplayPromotionSchema.parseAnimalFedMatcher(json);
    }

    @Override
    public PromotionIndexTarget compileTarget(ResourceLocation promotionId,
            AnimalFedPromotionMatcher matcher) {
        return matcher.entityTypeId()
                .<PromotionIndexTarget>map(id -> PromotionIndexTarget.exact(PromotionLookupKey.id(id)))
                .orElseGet(PromotionIndexTarget::wildcard);
    }

    @Override
    public PromotionLookupKey runtimeKey(AnimalFedPayload payload) {
        return PromotionLookupKey.id(payload.entityTypeId());
    }

    @Override
    public boolean matches(AnimalFedPayload payload, AnimalFedPromotionMatcher matcher) {
        return !matchingEntries(payload, matcher).isEmpty();
    }

    @Override
    public Optional<GameplayActionEvidence> createEvidence(AnimalFedPayload payload,
            AnimalFedPromotionMatcher matcher) {
        List<AnimalFeedEntry> matching = matchingEntries(payload, matcher);
        return matching.isEmpty()
                ? Optional.empty()
                : Optional.of(new AnimalFeedingEvidence(payload.entityTypeId(), matching));
    }

    private static List<AnimalFeedEntry> matchingEntries(AnimalFedPayload payload,
            AnimalFedPromotionMatcher matcher) {
        if (!matcher.entityTypeId().map(payload.entityTypeId()::equals).orElse(true)) {
            return List.of();
        }
        return payload.entries().stream()
                .filter(entry -> matcher.foodItemId().map(entry.foodItemId()::equals).orElse(true))
                .filter(entry -> matcher.outcome().map(entry.outcome()::equals).orElse(true))
                .toList();
    }
}
