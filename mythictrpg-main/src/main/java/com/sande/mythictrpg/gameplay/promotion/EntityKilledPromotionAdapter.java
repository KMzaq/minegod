package com.sande.mythictrpg.gameplay.promotion;

import com.google.gson.JsonObject;
import com.sande.mythictrpg.gameplay.observation.EntityKilledPayload;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationType;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public final class EntityKilledPromotionAdapter
        implements GameplayPromotionAdapter<EntityKilledPayload, EntityKilledPromotionMatcher> {
    public static final EntityKilledPromotionAdapter INSTANCE = new EntityKilledPromotionAdapter();

    private EntityKilledPromotionAdapter() {
    }

    @Override
    public GameplayObservationType<EntityKilledPayload> observationType() {
        return GameplayObservationTypes.ENTITY_KILLED;
    }

    @Override
    public Class<EntityKilledPromotionMatcher> matcherType() {
        return EntityKilledPromotionMatcher.class;
    }

    @Override
    public EntityKilledPromotionMatcher parseMatcher(JsonObject json) {
        return GameplayPromotionSchema.parseEntityKilledMatcher(json);
    }

    @Override
    public PromotionIndexTarget compileTarget(ResourceLocation promotionId,
            EntityKilledPromotionMatcher matcher) {
        return matcher.entityTypeId()
                .<PromotionIndexTarget>map(id -> PromotionIndexTarget.exact(PromotionLookupKey.id(id)))
                .orElseGet(PromotionIndexTarget::wildcard);
    }

    @Override
    public PromotionLookupKey runtimeKey(EntityKilledPayload payload) {
        return PromotionLookupKey.id(payload.killedEntityTypeId());
    }

    @Override
    public boolean matches(EntityKilledPayload payload, EntityKilledPromotionMatcher matcher) {
        return matcher.entityTypeId().map(payload.killedEntityTypeId()::equals).orElse(true)
                && matcher.dimensionId().map(payload.dimensionId()::equals).orElse(true);
    }

    @Override
    public Optional<GameplayActionEvidence> createEvidence(EntityKilledPayload payload,
            EntityKilledPromotionMatcher matcher) {
        return matches(payload, matcher)
                ? Optional.of(new EntityKilledEvidence(
                        payload.killedEntityTypeId(), payload.dimensionId()))
                : Optional.empty();
    }
}
