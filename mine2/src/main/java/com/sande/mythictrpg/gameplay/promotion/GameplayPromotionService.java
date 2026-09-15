package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.gameplay.activity.PlayerActivitySnapshot;
import com.sande.mythictrpg.gameplay.activity.PlayerActivityService;
import com.sande.mythictrpg.gameplay.activity.PlayerActivityState;
import com.sande.mythictrpg.gameplay.activity.PlayerActivityView;
import com.sande.mythictrpg.gameplay.observation.GameplayObservation;
import com.sande.mythictrpg.interaction.api.InteractionSignal;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class GameplayPromotionService {
    public static final GameplayPromotionService INSTANCE = new GameplayPromotionService(
            GameplayPromotionManager.INSTANCE::snapshot,
            (server, playerId) -> server.getPlayerList().getPlayer(playerId) != null,
            PlayerActivityService.INSTANCE::view,
            GameplayPromotionRuntimeState::get,
            GameplayPromotionSignalFactory::create,
            GameplaySignalSink.UNAVAILABLE);

    private final Supplier<GameplayPromotionSnapshot> snapshotProvider;
    private final OnlinePlayerView onlinePlayers;
    private final ActivityViewProvider activityViews;
    private final RuntimeStateProvider runtimeStates;
    private final SignalFactory signalFactory;
    private final GameplaySignalSink signalSink;

    GameplayPromotionService(Supplier<GameplayPromotionSnapshot> snapshotProvider,
            OnlinePlayerView onlinePlayers,
            ActivityViewProvider activityViews,
            RuntimeStateProvider runtimeStates,
            SignalFactory signalFactory,
            GameplaySignalSink signalSink) {
        this.snapshotProvider = Objects.requireNonNull(snapshotProvider, "snapshotProvider");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        this.activityViews = Objects.requireNonNull(activityViews, "activityViews");
        this.runtimeStates = Objects.requireNonNull(runtimeStates, "runtimeStates");
        this.signalFactory = Objects.requireNonNull(signalFactory, "signalFactory");
        this.signalSink = Objects.requireNonNull(signalSink, "signalSink");
    }

    public GameplayPromotionResult promote(MinecraftServer server,
            GameplayObservation<?> observation) {
        requireServerThread(server);
        Objects.requireNonNull(observation, "observation");
        GameplayPromotionSnapshot snapshot = Objects.requireNonNull(
                snapshotProvider.get(), "snapshot provider result");
        UUID playerId = observation.initiatingPlayerId();
        if (!onlinePlayers.isOnline(server, playerId)) {
            return GameplayPromotionResult.withoutRule(
                    GameplayPromotionResult.Status.PLAYER_OFFLINE);
        }

        Optional<PlayerActivitySnapshot> activity;
        try {
            PlayerActivityView view = Objects.requireNonNull(
                    activityViews.view(server), "activity view");
            activity = Objects.requireNonNull(view.find(playerId), "activity lookup result");
        } catch (RuntimeException exception) {
            return GameplayPromotionResult.withoutRule(
                    GameplayPromotionResult.Status.ACTIVITY_UNAVAILABLE);
        }
        if (activity.isEmpty()) {
            return GameplayPromotionResult.withoutRule(
                    GameplayPromotionResult.Status.ACTIVITY_UNAVAILABLE);
        }
        if (activity.orElseThrow().state() != PlayerActivityState.ACTIVE) {
            return GameplayPromotionResult.withoutRule(
                    GameplayPromotionResult.Status.PLAYER_IDLE);
        }

        List<GameplayPromotionDefinition> candidates = snapshot.index().match(observation);
        if (candidates.isEmpty()) {
            return GameplayPromotionResult.withoutRule(GameplayPromotionResult.Status.NO_MATCH);
        }

        GameplayPromotionRuntimeState runtime = Objects.requireNonNull(
                runtimeStates.state(server), "runtime state");
        runtime.alignGeneration(snapshot.generation());
        GameplayPromotionDefinition selected = candidates.stream()
                .filter(candidate -> runtime.isEligible(playerId, candidate.id()))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            return GameplayPromotionResult.withoutRule(
                    GameplayPromotionResult.Status.ALL_CANDIDATES_COOLDOWN);
        }

        Optional<InteractionSignal<GameplayActionPayload>> signal;
        try {
            signal = Objects.requireNonNull(
                    signalFactory.create(snapshot, selected, observation),
                    "signal factory result");
        } catch (RuntimeException exception) {
            signal = Optional.empty();
        }
        if (signal.isEmpty()) {
            return GameplayPromotionResult.forRule(
                    GameplayPromotionResult.Status.SIGNAL_CREATION_FAILED, selected.id());
        }

        AttemptReservationResult reservation = runtime.reserve(
                playerId, selected.id(), selected.attemptCooldownTicks());
        if (reservation == AttemptReservationResult.COOLDOWN) {
            return GameplayPromotionResult.withoutRule(
                    GameplayPromotionResult.Status.ALL_CANDIDATES_COOLDOWN);
        }
        if (reservation == AttemptReservationResult.CAPACITY_REJECTED) {
            return GameplayPromotionResult.forRule(
                    GameplayPromotionResult.Status.CAPACITY_REJECTED, selected.id());
        }

        GameplaySignalResult signalResult;
        try {
            signalResult = Objects.requireNonNull(
                    signalSink.accept(server, signal.orElseThrow()), "signal sink result");
        } catch (RuntimeException exception) {
            signalResult = GameplaySignalResult.FAILED;
        }
        return GameplayPromotionResult.fromSink(selected.id(), signalResult);
    }

    private static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Gameplay promotion must run on the server thread");
        }
    }

    @FunctionalInterface
    interface OnlinePlayerView {
        boolean isOnline(MinecraftServer server, UUID playerId);
    }

    @FunctionalInterface
    interface ActivityViewProvider {
        PlayerActivityView view(MinecraftServer server);
    }

    @FunctionalInterface
    interface RuntimeStateProvider {
        GameplayPromotionRuntimeState state(MinecraftServer server);
    }

    @FunctionalInterface
    interface SignalFactory {
        Optional<InteractionSignal<GameplayActionPayload>> create(
                GameplayPromotionSnapshot snapshot,
                GameplayPromotionDefinition definition,
                GameplayObservation<?> observation);
    }
}
