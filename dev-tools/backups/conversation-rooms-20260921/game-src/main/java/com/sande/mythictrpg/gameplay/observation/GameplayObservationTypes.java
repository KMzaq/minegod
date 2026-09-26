package com.sande.mythictrpg.gameplay.observation;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.ResourceLocation;

public final class GameplayObservationTypes {
    public static final GameplayObservationType<BlockBrokenPayload> BLOCK_BROKEN =
            new GameplayObservationType<>(id("block_broken"), BlockBrokenPayload.class);
    public static final GameplayObservationType<BlockBrokenPayload> ELIGIBLE_BLOCK_MINED =
            new GameplayObservationType<>(id("eligible_block_mined"), BlockBrokenPayload.class);
    public static final GameplayObservationType<AnimalBredPayload> ANIMAL_BRED =
            new GameplayObservationType<>(id("animal_bred"), AnimalBredPayload.class);
    public static final GameplayObservationType<EntityKilledPayload> ENTITY_KILLED =
            new GameplayObservationType<>(id("entity_killed"), EntityKilledPayload.class);
    public static final GameplayObservationType<PlayerDiedPayload> PLAYER_DIED =
            new GameplayObservationType<>(id("player_died"), PlayerDiedPayload.class);
    public static final GameplayObservationType<ItemFirstObtainedPayload> ITEM_FIRST_OBTAINED =
            new GameplayObservationType<>(id("item_first_obtained"), ItemFirstObtainedPayload.class);
    public static final GameplayObservationType<MatureCropHarvestPayload> MATURE_CROP_HARVESTED =
            new GameplayObservationType<>(id("mature_crop_harvested"), MatureCropHarvestPayload.class);
    public static final GameplayObservationType<VanillaStatThresholdCrossedPayload> VANILLA_STAT_THRESHOLD_CROSSED =
            new GameplayObservationType<>(id("vanilla_stat_threshold_crossed"),
                    VanillaStatThresholdCrossedPayload.class);
    public static final GameplayObservationType<AnimalFedPayload> ANIMAL_FED =
            new GameplayObservationType<>(id("animal_fed"), AnimalFedPayload.class);

    private GameplayObservationTypes() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
