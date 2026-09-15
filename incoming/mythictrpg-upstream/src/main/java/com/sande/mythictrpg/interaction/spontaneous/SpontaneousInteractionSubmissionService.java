package com.sande.mythictrpg.interaction.spontaneous;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.interaction.api.InteractionMode;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.content.InteractionContentPreparer;
import com.sande.mythictrpg.interaction.start.InteractionStartReasons;
import com.sande.mythictrpg.interaction.start.InteractionStartResult;
import com.sande.mythictrpg.interaction.start.InteractionStartStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class SpontaneousInteractionSubmissionService {
    public static final SpontaneousInteractionSubmissionService INSTANCE = productionCompatible(
            InteractionContentPreparerResolverRouter.INSTANCE,
            SpontaneousInteractionObserver.NO_OP);

    private final InteractionContentPreparerResolver resolver;
    private final RuntimeStateProvider runtimeStates;
    private final OnlinePlayerView onlinePlayers;
    private final SpontaneousInteractionExecutor executor;
    private final SpontaneousInteractionObserver observer;

    public SpontaneousInteractionSubmissionService(
            InteractionContentPreparerResolver resolver,
            RuntimeStateProvider runtimeStates,
            OnlinePlayerView onlinePlayers,
            SpontaneousInteractionExecutor executor,
            SpontaneousInteractionObserver observer) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.runtimeStates = Objects.requireNonNull(runtimeStates, "runtimeStates");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public static SpontaneousInteractionSubmissionService productionCompatible(
            InteractionContentPreparerResolver resolver,
            SpontaneousInteractionObserver observer) {
        return new SpontaneousInteractionSubmissionService(resolver,
                SpontaneousInteractionRuntimeState::get,
                (server, playerId) -> server.getPlayerList().getPlayer(playerId) != null,
                SpontaneousInteractionExecutor.ORCHESTRATOR, observer);
    }

    public SpontaneousSubmissionResult submit(MinecraftServer server,
            InteractionSignal<?> signal) {
        requireServerThread(server);
        Objects.requireNonNull(signal, "signal");
        if (signal.mode() != InteractionMode.SPONTANEOUS) {
            return SpontaneousSubmissionResult.REJECTED;
        }

        ContentPreparerResolution resolution;
        try {
            resolution = Objects.requireNonNull(resolver.resolve(signal),
                    "content preparer resolution");
        } catch (RuntimeException exception) {
            logSubmissionFailure(signal, "resolver failed", exception);
            return SpontaneousSubmissionResult.FAILED;
        }
        if (resolution.status() == ContentPreparerResolution.Status.UNAVAILABLE) {
            return SpontaneousSubmissionResult.UNAVAILABLE;
        }

        SpontaneousInteractionRuntimeState runtime;
        SpontaneousInteractionRuntimeState.AcquireResult acquired;
        try {
            runtime = Objects.requireNonNull(runtimeStates.state(server), "runtime state");
            acquired = runtime.tryAcquire(signal.initiatingPlayerId());
        } catch (RuntimeException exception) {
            logSubmissionFailure(signal, "runtime acquisition failed", exception);
            return SpontaneousSubmissionResult.FAILED;
        }
        if (acquired.status() != SpontaneousInteractionRuntimeState.AcquireStatus.ACQUIRED) {
            return SpontaneousSubmissionResult.REJECTED;
        }

        SpontaneousInteractionRuntimeState.Permit permit = acquired.permit();
        InteractionContentPreparer guarded = new GuardedInteractionContentPreparer(
                server, signal, resolution.preparer().orElseThrow(), runtime, permit, onlinePlayers);
        CompletionStage<InteractionStartResult> execution;
        try {
            execution = Objects.requireNonNull(executor.execute(server, signal, guarded),
                    "interaction execution stage");
            execution.whenComplete((result, failure) -> dispatch(server,
                    () -> complete(server, signal, runtime, permit, result, failure)));
        } catch (RuntimeException exception) {
            runtime.release(permit);
            logSubmissionFailure(signal, "executor submission failed", exception);
            return SpontaneousSubmissionResult.FAILED;
        }
        return SpontaneousSubmissionResult.ACCEPTED;
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SpontaneousInteractionRuntimeState.removePlayerIfPresent(
                    player.server, player.getUUID());
        }
    }

    public void onServerStopped(ServerStoppedEvent event) {
        SpontaneousInteractionRuntimeState.discard(event.getServer());
    }

    private void complete(MinecraftServer server, InteractionSignal<?> signal,
            SpontaneousInteractionRuntimeState runtime,
            SpontaneousInteractionRuntimeState.Permit permit,
            InteractionStartResult result, Throwable failure) {
        InteractionStartResult effective = result;
        if (failure != null || result == null) {
            logSubmissionFailure(signal, "executor completed exceptionally", failure);
            effective = InteractionStartResult.rejected(InteractionStartStatus.CONTENT_FAILED,
                    InteractionStartReasons.PREPARER_FAILED);
        }
        runtime.release(permit);
        MythicTrpg.LOGGER.debug("Spontaneous submission completed for signal {} and player {}: {}",
                signal.type().id(), signal.initiatingPlayerId(), effective.status());
        try {
            observer.onCompleted(signal, effective);
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.error("Spontaneous submission observer failed for signal {} and player {}",
                    signal.type().id(), signal.initiatingPlayerId(), exception);
        }
    }

    private static void dispatch(MinecraftServer server, Runnable action) {
        if (server.isSameThread()) {
            action.run();
        } else {
            server.execute(action);
        }
    }

    private static void logSubmissionFailure(InteractionSignal<?> signal, String detail,
            Throwable failure) {
        MythicTrpg.LOGGER.error("Spontaneous submission {} for signal {} and player {}",
                detail, signal.type().id(), signal.initiatingPlayerId(), failure);
    }

    private static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Spontaneous interaction submission must begin on the server thread");
        }
    }

    @FunctionalInterface
    public interface RuntimeStateProvider {
        SpontaneousInteractionRuntimeState state(MinecraftServer server);
    }

    @FunctionalInterface
    public interface OnlinePlayerView {
        boolean isOnline(MinecraftServer server, UUID playerId);
    }
}
