package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import com.sande.mythictrpg.interaction.spontaneous.SpontaneousInteractionSubmissionService;
import com.sande.mythictrpg.interaction.spontaneous.SpontaneousSubmissionResult;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;

public final class GameplaySpontaneousInteractionSink implements GameplaySignalSink {
    private static final GameplaySpontaneousInteractionSink PRODUCTION =
            new GameplaySpontaneousInteractionSink(
                    SpontaneousInteractionSubmissionService.INSTANCE::submit);

    private final SubmissionBoundary submission;

    public GameplaySpontaneousInteractionSink(
            SpontaneousInteractionSubmissionService submissionService) {
        this(Objects.requireNonNull(submissionService, "submissionService")::submit);
    }

    GameplaySpontaneousInteractionSink(SubmissionBoundary submission) {
        this.submission = Objects.requireNonNull(submission, "submission");
    }

    public static void configureProduction() {
        GameplaySignalSinkRouter.INSTANCE.configureProductionSink(PRODUCTION);
    }

    @Override
    public GameplaySignalResult accept(MinecraftServer server,
            InteractionSignal<GameplayActionPayload> signal) {
        try {
            SpontaneousSubmissionResult result = Objects.requireNonNull(
                    submission.submit(Objects.requireNonNull(server, "server"),
                            Objects.requireNonNull(signal, "signal")),
                    "spontaneous submission result");
            return switch (result) {
                case ACCEPTED -> GameplaySignalResult.ACCEPTED;
                case UNAVAILABLE -> GameplaySignalResult.UNAVAILABLE;
                case REJECTED -> GameplaySignalResult.REJECTED;
                case FAILED -> GameplaySignalResult.FAILED;
            };
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.error("Gameplay spontaneous interaction submission failed",
                    exception);
            return GameplaySignalResult.FAILED;
        }
    }

    @FunctionalInterface
    interface SubmissionBoundary {
        SpontaneousSubmissionResult submit(MinecraftServer server,
                InteractionSignal<?> signal);
    }
}
