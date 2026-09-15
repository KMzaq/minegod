package com.sande.mythictrpg.interaction.api;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.ResourceLocation;

public final class InteractionSignalTypes {
    public static final ResourceLocation PLAYER_EXPLICIT_POLICY = id("player_explicit");
    public static final InteractionSignalType<EmptyInteractionPayload> TEST_SPONTANEOUS =
            new InteractionSignalType<>(id("test_spontaneous"), InteractionMode.SPONTANEOUS,
                    EmptyInteractionPayload.class);
    public static final InteractionSignalType<ExplicitGodCallPayload> EXPLICIT_GOD_CALL =
            new InteractionSignalType<>(id("explicit_god_call"), InteractionMode.EXPLICIT,
                    ExplicitGodCallPayload.class);

    private InteractionSignalTypes() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
