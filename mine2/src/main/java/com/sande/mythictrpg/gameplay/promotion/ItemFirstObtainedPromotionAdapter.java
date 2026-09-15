package com.sande.mythictrpg.gameplay.promotion;

import com.google.gson.JsonObject;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationType;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.gameplay.observation.ItemFirstObtainedPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public final class ItemFirstObtainedPromotionAdapter
        implements GameplayPromotionAdapter<ItemFirstObtainedPayload, ItemFirstObtainedPromotionMatcher> {
    public static final ItemFirstObtainedPromotionAdapter INSTANCE =
            new ItemFirstObtainedPromotionAdapter();

    private ItemFirstObtainedPromotionAdapter() {
    }

    @Override
    public GameplayObservationType<ItemFirstObtainedPayload> observationType() {
        return GameplayObservationTypes.ITEM_FIRST_OBTAINED;
    }

    @Override
    public Class<ItemFirstObtainedPromotionMatcher> matcherType() {
        return ItemFirstObtainedPromotionMatcher.class;
    }

    @Override
    public ItemFirstObtainedPromotionMatcher parseMatcher(JsonObject json) {
        return GameplayPromotionSchema.parseItemFirstObtainedMatcher(json);
    }

    @Override
    public PromotionIndexTarget compileTarget(ResourceLocation promotionId,
            ItemFirstObtainedPromotionMatcher matcher) {
        return matcher.itemId()
                .<PromotionIndexTarget>map(id -> PromotionIndexTarget.exact(PromotionLookupKey.id(id)))
                .orElseGet(PromotionIndexTarget::wildcard);
    }

    @Override
    public PromotionLookupKey runtimeKey(ItemFirstObtainedPayload payload) {
        return PromotionLookupKey.id(payload.itemId());
    }

    @Override
    public boolean matches(ItemFirstObtainedPayload payload, ItemFirstObtainedPromotionMatcher matcher) {
        return matcher.itemId().map(payload.itemId()::equals).orElse(true);
    }

    @Override
    public Optional<GameplayActionEvidence> createEvidence(ItemFirstObtainedPayload payload,
            ItemFirstObtainedPromotionMatcher matcher) {
        return matches(payload, matcher)
                ? Optional.of(new ItemFirstObtainedEvidence(payload.itemId()))
                : Optional.empty();
    }
}
