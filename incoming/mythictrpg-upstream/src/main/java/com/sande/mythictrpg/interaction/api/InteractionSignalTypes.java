package com.sande.mythictrpg.interaction.api;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;

public final class InteractionSignalTypes {
    public static final ResourceLocation PLAYER_EXPLICIT_POLICY = id("player_explicit");
    public static final InteractionSignalType<EmptyInteractionPayload> TEST_SPONTANEOUS =
            new InteractionSignalType<>(id("test_spontaneous"), InteractionMode.SPONTANEOUS,
                    EmptyInteractionPayload.class);
    public static final InteractionSignalType<ExplicitGodCallPayload> EXPLICIT_GOD_CALL =
            new InteractionSignalType<>(id("explicit_god_call"), InteractionMode.EXPLICIT,
                    ExplicitGodCallPayload.class);
    private static final Map<ResourceLocation, InteractionSignalType<?>> KNOWN_TYPES = Map.of(
            TEST_SPONTANEOUS.id(), TEST_SPONTANEOUS,
            EXPLICIT_GOD_CALL.id(), EXPLICIT_GOD_CALL);

    private InteractionSignalTypes() {
    }

    public static Map<ResourceLocation, InteractionSignalType<?>> knownTypes() {
        return KNOWN_TYPES;
    }

    public static Optional<InteractionSignalType<?>> find(ResourceLocation id) {
        return Optional.ofNullable(KNOWN_TYPES.get(id));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
