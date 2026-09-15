package com.sande.mythictrpg.interaction.content;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

public record ValidatedInteractionContent(List<PreparedDialogueTurn> turns,
        Set<ResourceLocation> manifestedGodIds) {
    public ValidatedInteractionContent {
        turns = List.copyOf(turns);
        manifestedGodIds = Set.copyOf(manifestedGodIds);
    }
}
