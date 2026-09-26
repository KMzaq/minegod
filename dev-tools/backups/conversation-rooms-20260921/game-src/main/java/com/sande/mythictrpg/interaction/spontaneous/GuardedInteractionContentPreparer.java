package com.sande.mythictrpg.interaction.spontaneous;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.content.ContentPreparationRequest;
import com.sande.mythictrpg.interaction.content.InteractionContentPreparer;
import com.sande.mythictrpg.interaction.content.PreparationResult;
import com.sande.mythictrpg.interaction.start.InteractionStartReasons;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class GuardedInteractionContentPreparer implements InteractionContentPreparer {
    private final MinecraftServer server;
    private final InteractionSignal<?> signal;
    private final InteractionContentPreparer delegate;
    private final SpontaneousInteractionRuntimeState runtime;
    private final SpontaneousInteractionRuntimeState.Permit permit;
    private final SpontaneousInteractionSubmissionService.OnlinePlayerView onlinePlayers;

    GuardedInteractionContentPreparer(MinecraftServer server, InteractionSignal<?> signal,
            InteractionContentPreparer delegate, SpontaneousInteractionRuntimeState runtime,
            SpontaneousInteractionRuntimeState.Permit permit,
            SpontaneousInteractionSubmissionService.OnlinePlayerView onlinePlayers) {
        this.server = Objects.requireNonNull(server, "server");
        this.signal = Objects.requireNonNull(signal, "signal");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.permit = Objects.requireNonNull(permit, "permit");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
    }

    @Override
    public CompletionStage<PreparationResult> prepare(ContentPreparationRequest request) {
        Objects.requireNonNull(request, "request");
        CompletionStage<PreparationResult> preparation;
        try {
            preparation = Objects.requireNonNull(delegate.prepare(request),
                    "content preparer stage");
        } catch (RuntimeException exception) {
            logProviderFailure("failed synchronously", exception);
            return CompletableFuture.completedFuture(
                    PreparationResult.failed(InteractionStartReasons.PREPARER_FAILED));
        }

        CompletableFuture<PreparationResult> guarded = new CompletableFuture<>();
        preparation.whenComplete((prepared, failure) -> dispatch(() -> {
            if (!runtime.isActive(permit)
                    || !onlinePlayers.isOnline(server, signal.initiatingPlayerId())) {
                guarded.complete(PreparationResult.noContent(
                        SpontaneousSubmissionReasons.STALE_SUBMISSION));
                return;
            }
            if (failure != null || prepared == null) {
                logProviderFailure("completed exceptionally", failure);
                guarded.complete(PreparationResult.failed(
                        InteractionStartReasons.PREPARER_FAILED));
                return;
            }
            guarded.complete(prepared);
        }));
        return guarded;
    }

    private void dispatch(Runnable action) {
        if (server.isSameThread()) {
            action.run();
        } else {
            server.execute(action);
        }
    }

    private void logProviderFailure(String detail, Throwable failure) {
        MythicTrpg.LOGGER.error("Spontaneous content provider {} for signal {} and player {}",
                detail, signal.type().id(), signal.initiatingPlayerId(), failure);
    }
}
