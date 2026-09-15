package com.sande.mythictrpg.data.god;

import com.sande.mythictrpg.condition.api.GodDefinitionView;
import com.sande.mythictrpg.condition.api.WorldStateView;
import com.sande.mythictrpg.data.world.MythicWorldState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;

public final class GodAccessService {
    private final GodDefinitionRepository definitions;

    public GodAccessService(GodDefinitionRepository definitions) {
        this.definitions = definitions;
    }

    public boolean isEffectivelyUnlocked(MinecraftServer server, ResourceLocation godId) {
        MythicWorldState state = MythicWorldState.get(server);
        return effectiveUnlockStatus(godId, worldView(state)).orElse(false);
    }

    public boolean isEffectivelyUnlocked(ResourceLocation godId, WorldStateView world) {
        return effectiveUnlockStatus(godId, world).orElse(false);
    }

    public Optional<Boolean> effectiveUnlockStatus(ResourceLocation godId, WorldStateView world) {
        if (!world.isReady()) {
            return Optional.empty();
        }
        return definitions.find(godId)
                .map(definition -> definition.unlockPolicy() == UnlockPolicy.NOT_REQUIRED
                        || world.isGodUnlocked(godId));
    }

    public static Optional<Boolean> effectiveUnlockStatus(
            ResourceLocation godId, WorldStateView world, GodDefinitionView gods) {
        if (!world.isReady() || !gods.isReady()) {
            return Optional.empty();
        }
        return gods.find(godId).map(definition -> definition.unlockPolicy() == UnlockPolicy.NOT_REQUIRED
                || world.isGodUnlocked(godId));
    }

    private static WorldStateView worldView(MythicWorldState state) {
        return new WorldStateView() {
            @Override
            public boolean isReady() {
                return !state.isRejected();
            }

            @Override
            public int dataVersion() {
                return state.dataVersion();
            }

            @Override
            public java.util.Set<ResourceLocation> unlockedGods() {
                return state.unlockedGods();
            }
        };
    }
}
