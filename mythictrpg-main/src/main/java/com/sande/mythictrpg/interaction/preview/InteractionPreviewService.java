package com.sande.mythictrpg.interaction.preview;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.interaction.api.ExplicitGodCallPayload;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.api.InteractionSignalTypes;
import com.sande.mythictrpg.interaction.director.InteractionDecision;
import com.sande.mythictrpg.interaction.orchestration.InteractionOrchestrator;
import com.sande.mythictrpg.interaction.policy.CooldownView;
import com.sande.mythictrpg.interaction.policy.InteractionRuntimeView;
import com.sande.mythictrpg.interaction.runtime.InteractionRuntimeState;
import com.sande.mythictrpg.interaction.spontaneous.ContentPreparerResolution;
import com.sande.mythictrpg.interaction.spontaneous.InteractionContentPreparerResolver;
import com.sande.mythictrpg.interaction.spontaneous.InteractionContentPreparerResolverRouter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public final class InteractionPreviewService {
    static final ResourceLocation INVALID_SIGNAL = id("invalid_preview_signal");
    static final ResourceLocation PLAYER_OFFLINE = id("preview_player_offline");
    static final ResourceLocation PROVIDER_UNAVAILABLE = id("preview_provider_unavailable");
    static final ResourceLocation PROVIDER_FAILED = id("preview_provider_failed");

    public static final InteractionPreviewService INSTANCE = new InteractionPreviewService(
            InteractionOrchestrator.INSTANCE::plan,
            (server, playerId) -> server.getPlayerList().getPlayer(playerId) != null,
            InteractionRuntimeState::readOnlyViewsIfPresent,
            InteractionContentPreparerResolverRouter.INSTANCE);

    private final Planner planner;
    private final OnlinePlayerLookup playerLookup;
    private final Function<MinecraftServer, InteractionRuntimeState.ReadOnlyViews> runtimeViews;
    private final InteractionContentPreparerResolver resolver;

    InteractionPreviewService(Planner planner, OnlinePlayerLookup playerLookup,
            Function<MinecraftServer, InteractionRuntimeState.ReadOnlyViews> runtimeViews,
            InteractionContentPreparerResolver resolver) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.runtimeViews = Objects.requireNonNull(runtimeViews, "runtimeViews");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public InteractionPreviewResult preview(MinecraftServer server, InteractionSignal<?> signal) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(signal, "signal");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Interaction previews require the server thread");
        }

        ResourceLocation signalTypeId = signal.type().id();
        if (!signal.type().equals(InteractionSignalTypes.EXPLICIT_GOD_CALL)
                || !(signal.payload() instanceof ExplicitGodCallPayload)) {
            return InteractionPreviewResult.rejected(
                    InteractionPreviewStatus.INVALID_REQUEST, signalTypeId, INVALID_SIGNAL);
        }
        if (!playerLookup.isOnline(server, signal.initiatingPlayerId())) {
            return InteractionPreviewResult.rejected(
                    InteractionPreviewStatus.PLAYER_OFFLINE, signalTypeId, PLAYER_OFFLINE);
        }

        InteractionRuntimeState.ReadOnlyViews views = Objects.requireNonNull(
                runtimeViews.apply(server), "read-only runtime views");
        InteractionDecision decision = Objects.requireNonNull(planner.plan(
                server, signal, views.cooldowns(), views.runtime()), "interaction decision");
        if (decision.status() == InteractionDecision.Status.NO_START) {
            return InteractionPreviewResult.rejected(InteractionPreviewStatus.NO_PLAN,
                    signalTypeId, decision.reason().orElseThrow());
        }

        ResourceLocation primaryGodId = decision.plan().orElseThrow()
                .participants().primaryGodId();
        try {
            ContentPreparerResolution resolution = Objects.requireNonNull(
                    resolver.resolve(signal), "content preparer resolution");
            if (resolution.status() == ContentPreparerResolution.Status.UNAVAILABLE) {
                return InteractionPreviewResult.provider(
                        InteractionPreviewStatus.PROVIDER_UNAVAILABLE, signalTypeId,
                        primaryGodId, Optional.of(PROVIDER_UNAVAILABLE));
            }
            return InteractionPreviewResult.provider(InteractionPreviewStatus.PROVIDER_AVAILABLE,
                    signalTypeId, primaryGodId, Optional.empty());
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.warn("Interaction preview provider resolution failed for {}: {}",
                    signalTypeId, exception.getClass().getSimpleName());
            return InteractionPreviewResult.provider(InteractionPreviewStatus.PROVIDER_FAILED,
                    signalTypeId, primaryGodId, Optional.of(PROVIDER_FAILED));
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    @FunctionalInterface
    interface Planner {
        InteractionDecision plan(MinecraftServer server, InteractionSignal<?> signal,
                CooldownView cooldowns, InteractionRuntimeView runtime);
    }

    @FunctionalInterface
    interface OnlinePlayerLookup {
        boolean isOnline(MinecraftServer server, UUID playerId);
    }
}
