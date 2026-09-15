package com.sande.mythictrpg.gameplay.promotion;

import com.google.gson.JsonObject;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationType;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import com.sande.mythictrpg.gameplay.observation.PlayerDiedPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public final class PlayerDiedPromotionAdapter
        implements GameplayPromotionAdapter<PlayerDiedPayload, PlayerDiedPromotionMatcher> {
    public static final PlayerDiedPromotionAdapter INSTANCE = new PlayerDiedPromotionAdapter();

    private PlayerDiedPromotionAdapter() {
    }

    @Override
    public GameplayObservationType<PlayerDiedPayload> observationType() {
        return GameplayObservationTypes.PLAYER_DIED;
    }

    @Override
    public Class<PlayerDiedPromotionMatcher> matcherType() {
        return PlayerDiedPromotionMatcher.class;
    }

    @Override
    public PlayerDiedPromotionMatcher parseMatcher(JsonObject json) {
        return GameplayPromotionSchema.parsePlayerDiedMatcher(json);
    }

    @Override
    public PromotionIndexTarget compileTarget(ResourceLocation promotionId,
            PlayerDiedPromotionMatcher matcher) {
        return switch (matcher.damageTypeCriterion()) {
            case DamageTypeCriterion.Exact exact ->
                    PromotionIndexTarget.exact(PromotionLookupKey.id(exact.id()));
            case DamageTypeCriterion.Missing ignored ->
                    PromotionIndexTarget.exact(PromotionLookupKey.missing());
            case DamageTypeCriterion.Any ignored -> PromotionIndexTarget.wildcard();
        };
    }

    @Override
    public PromotionLookupKey runtimeKey(PlayerDiedPayload payload) {
        return payload.damageTypeId()
                .<PromotionLookupKey>map(PromotionLookupKey::id)
                .orElseGet(PromotionLookupKey::missing);
    }

    @Override
    public boolean matches(PlayerDiedPayload payload, PlayerDiedPromotionMatcher matcher) {
        return switch (matcher.damageTypeCriterion()) {
            case DamageTypeCriterion.Exact exact -> payload.damageTypeId().map(exact.id()::equals).orElse(false);
            case DamageTypeCriterion.Missing ignored -> payload.damageTypeId().isEmpty();
            case DamageTypeCriterion.Any ignored -> true;
        };
    }

    @Override
    public Optional<GameplayActionEvidence> createEvidence(PlayerDiedPayload payload,
            PlayerDiedPromotionMatcher matcher) {
        return matches(payload, matcher)
                ? Optional.of(new PlayerDiedEvidence(payload.damageTypeId()))
                : Optional.empty();
    }
}
