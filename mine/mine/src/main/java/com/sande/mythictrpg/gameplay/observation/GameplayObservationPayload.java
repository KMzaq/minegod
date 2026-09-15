package com.sande.mythictrpg.gameplay.observation;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public interface GameplayObservationPayload {
    Optional<ResourceLocation> subjectId();
}
