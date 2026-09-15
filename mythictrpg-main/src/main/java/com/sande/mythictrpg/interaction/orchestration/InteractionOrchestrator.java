package com.sande.mythictrpg.interaction.orchestration;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.candidate.CandidateTraceMode;
import com.sande.mythictrpg.interaction.content.ContentPreparationRequest;
import com.sande.mythictrpg.interaction.content.InteractionContentPreparer;
import com.sande.mythictrpg.interaction.content.PreparationResult;
import com.sande.mythictrpg.interaction.context.InteractionContextFactory;
import com.sande.mythictrpg.interaction.director.GodInteractionDirector;
import com.sande.mythictrpg.interaction.director.InteractionDecision;
import com.sande.mythictrpg.interaction.policy.CooldownView;
import com.sande.mythictrpg.interaction.policy.InteractionRuntimeView;
import com.sande.mythictrpg.interaction.runtime.InteractionRuntimeState;
import com.sande.mythictrpg.interaction.start.InteractionStartReasons;
import com.sande.mythictrpg.interaction.start.InteractionStartRequest;
import com.sande.mythictrpg.interaction.start.InteractionStartResult;
import com.sande.mythictrpg.interaction.start.InteractionStartService;
import com.sande.mythictrpg.interaction.start.InteractionStartStatus;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class InteractionOrchestrator {
    public static final InteractionOrchestrator INSTANCE = new InteractionOrchestrator(
            GodInteractionDirector.INSTANCE, InteractionStartService.INSTANCE);

    private final GodInteractionDirector director;
    private final InteractionStartService startService;

    public InteractionOrchestrator(GodInteractionDirector director, InteractionStartService startService) {
        this.director = director;
        this.startService = startService;
    }

    public CompletionStage<InteractionStartResult> execute(MinecraftServer server,
            InteractionSignal<?> signal, InteractionContentPreparer preparer) {
        requireServerThread(server);
        InteractionRuntimeState runtime = InteractionRuntimeState.get(server);
        InteractionDecision decision = plan(server, signal, runtime, runtime);
        if (decision.status() == InteractionDecision.Status.NO_START) {
            return CompletableFuture.completedFuture(InteractionStartResult.rejected(
                    InteractionStartStatus.NO_PLAN, decision.reason().orElse(InteractionStartReasons.NO_PLAN)));
        }

        var plan = decision.plan().orElseThrow();
        CompletionStage<PreparationResult> preparation;
        try {
            preparation = preparer.prepare(new ContentPreparationRequest(signal, plan));
            if (preparation == null) {
                throw new IllegalStateException("Content preparer returned null");
            }
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.error("Interaction content preparation failed synchronously", exception);
            return CompletableFuture.completedFuture(InteractionStartResult.rejected(
                    InteractionStartStatus.CONTENT_FAILED, InteractionStartReasons.PREPARER_FAILED));
        }

        CompletableFuture<InteractionStartResult> result = new CompletableFuture<>();
        preparation.whenComplete((prepared, failure) -> {
            Runnable continuation = () -> completePrepared(
                    server, runtime, signal, plan, prepared, failure, result);
            if (server.isSameThread()) {
                continuation.run();
            } else {
                server.execute(continuation);
            }
        });
        return result;
    }

    public InteractionDecision plan(MinecraftServer server, InteractionSignal<?> signal,
            CooldownView cooldowns, InteractionRuntimeView runtime) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(cooldowns, "cooldowns");
        Objects.requireNonNull(runtime, "runtime");
        requireServerThread(server);
        var context = InteractionContextFactory.create(server, signal, Set.of(), cooldowns, runtime);
        return director.plan(context, CandidateTraceMode.NONE);
    }

    private void completePrepared(MinecraftServer server, InteractionRuntimeState runtime,
            InteractionSignal<?> signal, com.sande.mythictrpg.interaction.director.InteractionPlan plan,
            PreparationResult prepared, Throwable failure,
            CompletableFuture<InteractionStartResult> result) {
        try {
            if (failure != null || prepared == null) {
                MythicTrpg.LOGGER.error("Interaction content preparation completed exceptionally", failure);
                result.complete(InteractionStartResult.rejected(
                        InteractionStartStatus.CONTENT_FAILED, InteractionStartReasons.PREPARER_FAILED));
                return;
            }
            switch (prepared.status()) {
                case NO_CONTENT -> result.complete(InteractionStartResult.rejected(
                        InteractionStartStatus.NO_CONTENT, prepared.reason().orElseThrow()));
                case FAILED -> result.complete(InteractionStartResult.rejected(
                        InteractionStartStatus.CONTENT_FAILED, prepared.reason().orElseThrow()));
                case PREPARED -> result.complete(startService.start(server, runtime,
                        new InteractionStartRequest(signal, plan, prepared.content().orElseThrow())));
            }
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.error("Interaction preparation continuation failed", exception);
            result.complete(InteractionStartResult.rejected(
                    InteractionStartStatus.CONTENT_FAILED, InteractionStartReasons.PREPARER_FAILED));
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Interaction orchestration must begin on the server thread");
        }
    }
}
