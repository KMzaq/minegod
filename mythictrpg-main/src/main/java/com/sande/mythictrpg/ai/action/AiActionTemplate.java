package com.sande.mythictrpg.ai.action;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

sealed interface AiActionTemplate permits ItemRequestTemplate, RewardProposalTemplate,
        BlessingOfferTemplate, WorldInteractionTemplate, PlayerDamageTemplate {
    ResourceLocation id();

    ResourceLocation godId();

    ResourceLocation actionType();
}

record PlayerDamageTemplate(ResourceLocation id, ResourceLocation godId,
        DamageMode damageMode, ResourceLocation damageTypeId, double amount,
        boolean allowDeath, int maxUsesPerSession, int cooldownTicks) implements AiActionTemplate {
    PlayerDamageTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(godId, "godId");
        Objects.requireNonNull(damageMode, "damageMode");
        Objects.requireNonNull(damageTypeId, "damageTypeId");
        if (damageMode == DamageMode.LETHAL) {
            if (!allowDeath || amount != 0.0D) {
                throw new IllegalArgumentException("Lethal damage must allow death and omit an amount");
            }
        } else if (!Double.isFinite(amount) || amount <= 0.0D
                || (damageMode == DamageMode.FLAT && amount > 2_048.0D)
                || (damageMode != DamageMode.FLAT && amount > 1.0D)) {
            throw new IllegalArgumentException("Damage amount is outside the allowed range");
        }
        if (maxUsesPerSession < 1 || maxUsesPerSession > 16) {
            throw new IllegalArgumentException("Damage maxUsesPerSession must be between 1 and 16");
        }
        if (cooldownTicks < 0 || cooldownTicks > 72_000) {
            throw new IllegalArgumentException("Damage cooldownTicks must be between 0 and 72000");
        }
    }

    @Override
    public ResourceLocation actionType() {
        return AiActionTypes.PLAYER_DAMAGE;
    }

    enum DamageMode {
        FLAT,
        MAX_HEALTH_FRACTION,
        CURRENT_HEALTH_FRACTION,
        LETHAL
    }
}

record ItemRequestTemplate(ResourceLocation id, ResourceLocation godId,
        ResourceLocation itemId, int count) implements AiActionTemplate {
    ItemRequestTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(godId, "godId");
        Objects.requireNonNull(itemId, "itemId");
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("Item request count must be between 1 and 64");
        }
    }

    @Override
    public ResourceLocation actionType() {
        return AiActionTypes.ITEM_REQUEST;
    }
}

record RewardProposalTemplate(ResourceLocation id, ResourceLocation godId,
        ResourceLocation rewardTableId, int tier) implements AiActionTemplate {
    RewardProposalTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(godId, "godId");
        Objects.requireNonNull(rewardTableId, "rewardTableId");
        if (tier < 1 || tier > 100) {
            throw new IllegalArgumentException("Reward tier must be between 1 and 100");
        }
    }

    @Override
    public ResourceLocation actionType() {
        return AiActionTypes.REWARD_PROPOSAL;
    }
}

record BlessingOfferTemplate(ResourceLocation id, ResourceLocation godId,
        ResourceLocation effectId, int durationTicks, int amplifier) implements AiActionTemplate {
    BlessingOfferTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(godId, "godId");
        Objects.requireNonNull(effectId, "effectId");
        if (durationTicks < 20 || durationTicks > 72_000) {
            throw new IllegalArgumentException("Blessing duration must be between 20 and 72000 ticks");
        }
        if (amplifier < 0 || amplifier > 4) {
            throw new IllegalArgumentException("Blessing amplifier must be between 0 and 4");
        }
    }

    @Override
    public ResourceLocation actionType() {
        return AiActionTypes.BLESSING_OFFER;
    }
}

record WorldInteractionTemplate(ResourceLocation id, ResourceLocation godId,
        EventKind eventKind, ResourceLocation eventId, int count, double spread,
        double speed, float volume, float pitch) implements AiActionTemplate {
    WorldInteractionTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(godId, "godId");
        Objects.requireNonNull(eventKind, "eventKind");
        Objects.requireNonNull(eventId, "eventId");
        if (eventKind == EventKind.PARTICLE) {
            if (count < 1 || count > 200 || spread < 0.0D || spread > 8.0D
                    || speed < 0.0D || speed > 2.0D) {
                throw new IllegalArgumentException("Particle event values are outside the safe range");
            }
        } else if (volume < 0.0F || volume > 4.0F || pitch < 0.5F || pitch > 2.0F) {
            throw new IllegalArgumentException("Sound event values are outside the safe range");
        }
    }

    @Override
    public ResourceLocation actionType() {
        return AiActionTypes.WORLD_INTERACTION;
    }

    enum EventKind {
        SOUND,
        PARTICLE
    }
}
