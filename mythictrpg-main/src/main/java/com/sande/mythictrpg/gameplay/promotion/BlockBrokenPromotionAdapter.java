package com.sande.mythictrpg.gameplay.promotion;

import com.google.gson.JsonObject;
import com.sande.mythictrpg.gameplay.observation.BlockBrokenPayload;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationType;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationTypes;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public final class BlockBrokenPromotionAdapter
        implements GameplayPromotionAdapter<BlockBrokenPayload, BlockBrokenPromotionMatcher> {
    public static final BlockBrokenPromotionAdapter INSTANCE = new BlockBrokenPromotionAdapter();

    private BlockBrokenPromotionAdapter() {
    }

    @Override
    public GameplayObservationType<BlockBrokenPayload> observationType() {
        return GameplayObservationTypes.BLOCK_BROKEN;
    }

    @Override
    public Class<BlockBrokenPromotionMatcher> matcherType() {
        return BlockBrokenPromotionMatcher.class;
    }

    @Override
    public BlockBrokenPromotionMatcher parseMatcher(JsonObject json) {
        return GameplayPromotionSchema.parseBlockBrokenMatcher(json);
    }

    @Override
    public PromotionIndexTarget compileTarget(ResourceLocation promotionId,
            BlockBrokenPromotionMatcher matcher) {
        return matcher.blockId()
                .<PromotionIndexTarget>map(id -> PromotionIndexTarget.exact(PromotionLookupKey.id(id)))
                .orElseGet(PromotionIndexTarget::wildcard);
    }

    @Override
    public PromotionLookupKey runtimeKey(BlockBrokenPayload payload) {
        return PromotionLookupKey.id(payload.blockId());
    }

    @Override
    public boolean matches(BlockBrokenPayload payload, BlockBrokenPromotionMatcher matcher) {
        return matcher.blockId().map(payload.blockId()::equals).orElse(true)
                && matcher.dimensionId().map(payload.dimensionId()::equals).orElse(true);
    }

    @Override
    public Optional<GameplayActionEvidence> createEvidence(BlockBrokenPayload payload,
            BlockBrokenPromotionMatcher matcher) {
        return matches(payload, matcher)
                ? Optional.of(new BlockBrokenEvidence(payload.blockId(), payload.dimensionId()))
                : Optional.empty();
    }
}
