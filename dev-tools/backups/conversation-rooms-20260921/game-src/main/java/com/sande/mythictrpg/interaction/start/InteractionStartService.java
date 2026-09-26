package com.sande.mythictrpg.interaction.start;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.interaction.content.PreparedInteractionContentValidator;
import com.sande.mythictrpg.interaction.runtime.InteractionRuntimeState;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;

public final class InteractionStartService {
    public static final InteractionStartService INSTANCE = new InteractionStartService(
            PreparedInteractionContentValidator.INSTANCE,
            DialoguePresentationInteractionOutput.INSTANCE,
            InteractionStartStateProvider.LIVE,
            InteractionStartValidator.INSTANCE,
            EncounterCommitter.PERSISTENT,
            InteractionIdGenerator.RANDOM,
            com.sande.mythictrpg.ai.server.AiConversationRuntimeService.INSTANCE::onInteractionStarted);

    private final PreparedInteractionContentValidator contentValidator;
    private final InteractionDialogueOutput output;
    private final InteractionStartStateProvider stateProvider;
    private final InteractionStartValidator startValidator;
    private final EncounterCommitter encounters;
    private final InteractionIdGenerator idGenerator;
    private final InteractionStartedListener startedListener;

    public InteractionStartService(PreparedInteractionContentValidator contentValidator,
            InteractionDialogueOutput output, InteractionStartStateProvider stateProvider,
            InteractionStartValidator startValidator, EncounterCommitter encounters,
            InteractionIdGenerator idGenerator) {
        this(contentValidator, output, stateProvider, startValidator, encounters, idGenerator,
                InteractionStartedListener.NONE);
    }

    public InteractionStartService(PreparedInteractionContentValidator contentValidator,
            InteractionDialogueOutput output, InteractionStartStateProvider stateProvider,
            InteractionStartValidator startValidator, EncounterCommitter encounters,
            InteractionIdGenerator idGenerator, InteractionStartedListener startedListener) {
        this.contentValidator = contentValidator;
        this.output = output;
        this.stateProvider = stateProvider;
        this.startValidator = startValidator;
        this.encounters = encounters;
        this.idGenerator = idGenerator;
        this.startedListener = Objects.requireNonNull(startedListener, "startedListener");
    }

    public InteractionStartResult start(MinecraftServer server, InteractionRuntimeState runtime,
            InteractionStartRequest request) {
        requireServerThread(server);
        var contentResult = contentValidator.validate(request.plan(), request.content());
        if (contentResult.content().isEmpty()) {
            return InteractionStartResult.rejected(InteractionStartStatus.CONTENT_INVALID,
                    contentResult.rejectionReason().orElseThrow());
        }
        var content = contentResult.content().orElseThrow();
        var preflight = output.preflight(server, request.plan().audience(), content);
        if (preflight.status() != PresentationPreflightResult.Status.READY) {
            InteractionStartStatus status = preflight.status()
                    == PresentationPreflightResult.Status.AUDIENCE_UNAVAILABLE
                    ? InteractionStartStatus.AUDIENCE_UNAVAILABLE
                    : InteractionStartStatus.CONTENT_INVALID;
            return InteractionStartResult.rejected(status, preflight.reason().orElseThrow());
        }

        InteractionStartSnapshot snapshot = stateProvider.capture(server, request, runtime);
        StartValidationResult validation = startValidator.validate(request, snapshot);
        if (validation.status() != StartValidationResult.Status.ACCEPTED) {
            return InteractionStartResult.rejected(map(validation.status()), validation.reason().orElseThrow());
        }
        var reservation = runtime.tryReserve(request.plan().initiatingPlayerId());
        if (reservation.isEmpty()) {
            return InteractionStartResult.rejected(InteractionStartStatus.RUNTIME_BUSY,
                    InteractionStartReasons.RUNTIME_BUSY);
        }

        var held = reservation.orElseThrow();
        var interactionId = idGenerator.nextId();
        boolean committed = false;
        try {
            var encounterResult = encounters.commit(server, request.plan().initiatingPlayerId(),
                    content.manifestedGodIds());
            if (!encounterResult.committed()) {
                return InteractionStartResult.rejected(InteractionStartStatus.COMMIT_FAILED,
                        InteractionStartReasons.COMMIT_REJECTED);
            }
            committed = true;
            try {
                runtime.commit(held, request.plan().mode());
            } catch (RuntimeException exception) {
                MythicTrpg.LOGGER.error("Interaction {} failed after encounter commit at runtime stage",
                        interactionId, exception);
            }
            DeliverySummary delivery;
            try {
                delivery = output.deliver(server, interactionId, request.plan().audience(), content);
            } catch (RuntimeException exception) {
                int attempted = request.plan().audience().recipientPlayerIds().size()
                        * content.turns().size();
                delivery = DeliverySummary.of(attempted, 0);
                MythicTrpg.LOGGER.error("Interaction {} delivery threw after commit "
                        + "(audience={}, turns={})", interactionId,
                        request.plan().audience().recipientPlayerIds().size(), content.turns().size(), exception);
            }
            if (delivery.status() != DeliveryStatus.ALL_SENT) {
                MythicTrpg.LOGGER.error("Interaction {} delivery incomplete after commit: {} "
                        + "(sent={}/{}, audience={}, turns={})", interactionId, delivery.status(),
                        delivery.sent(), delivery.attempted(),
                        request.plan().audience().recipientPlayerIds().size(), content.turns().size());
            }
            try {
                startedListener.onStarted(server, interactionId, request.plan(), content);
            } catch (RuntimeException exception) {
                MythicTrpg.LOGGER.error("Interaction {} AI conversation hook failed after commit",
                        interactionId, exception);
            }
            return InteractionStartResult.started(interactionId, delivery);
        } catch (RuntimeException exception) {
            if (committed) {
                MythicTrpg.LOGGER.error("Interaction {} failed unexpectedly after encounter commit",
                        interactionId, exception);
                int attempted = request.plan().audience().recipientPlayerIds().size()
                        * content.turns().size();
                return InteractionStartResult.started(interactionId, DeliverySummary.of(attempted, 0));
            }
            MythicTrpg.LOGGER.error("Interaction commit failed before durable mutation", exception);
            return InteractionStartResult.rejected(InteractionStartStatus.COMMIT_FAILED,
                    InteractionStartReasons.COMMIT_REJECTED);
        } finally {
            runtime.release(held);
        }
    }

    private static InteractionStartStatus map(StartValidationResult.Status status) {
        return switch (status) {
            case STALE_PLAN -> InteractionStartStatus.STALE_PLAN;
            case AUDIENCE_UNAVAILABLE -> InteractionStartStatus.AUDIENCE_UNAVAILABLE;
            case LEGALITY_FAILED -> InteractionStartStatus.LEGALITY_FAILED;
            case RUNTIME_BUSY -> InteractionStartStatus.RUNTIME_BUSY;
            case ACCEPTED -> throw new IllegalArgumentException("ACCEPTED is not a rejection");
        };
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Interaction start may only commit on the server thread");
        }
    }
}
