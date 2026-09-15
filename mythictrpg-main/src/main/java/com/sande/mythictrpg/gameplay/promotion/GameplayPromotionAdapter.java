package com.sande.mythictrpg.gameplay.promotion;

import com.google.gson.JsonObject;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationPayload;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationType;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public interface GameplayPromotionAdapter<P extends GameplayObservationPayload, M extends PromotionMatcher> {
    GameplayObservationType<P> observationType();

    Class<M> matcherType();

    M parseMatcher(JsonObject json);

    PromotionIndexTarget compileTarget(ResourceLocation promotionId, M matcher);

    PromotionLookupKey runtimeKey(P payload);

    boolean matches(P payload, M matcher);

    Optional<GameplayActionEvidence> createEvidence(P payload, M matcher);

    default PromotionIndexTarget compileTargetUntyped(ResourceLocation promotionId, PromotionMatcher matcher) {
        if (!matcherType().isInstance(matcher)) {
            throw new IllegalArgumentException("Matcher type " + matcher.getClass().getName()
                    + " is not valid for observation " + observationType().id());
        }
        return compileTarget(promotionId, matcherType().cast(matcher));
    }

    default PromotionLookupKey runtimeKeyUntyped(GameplayObservationPayload payload) {
        observationType().validatePayload(payload);
        return runtimeKey(observationType().payloadType().cast(payload));
    }

    default boolean matchesUntyped(GameplayObservationPayload payload, PromotionMatcher matcher) {
        observationType().validatePayload(payload);
        if (!matcherType().isInstance(matcher)) {
            return false;
        }
        return matches(observationType().payloadType().cast(payload), matcherType().cast(matcher));
    }

    default Optional<GameplayActionEvidence> createEvidenceUntyped(
            GameplayObservationPayload payload, PromotionMatcher matcher) {
        observationType().validatePayload(payload);
        if (!matcherType().isInstance(matcher)) {
            return Optional.empty();
        }
        return createEvidence(observationType().payloadType().cast(payload), matcherType().cast(matcher));
    }
}
