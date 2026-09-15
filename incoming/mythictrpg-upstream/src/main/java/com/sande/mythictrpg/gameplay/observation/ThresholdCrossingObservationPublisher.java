package com.sande.mythictrpg.gameplay.observation;

import com.sande.mythictrpg.gameplay.sampling.ThresholdCrossing;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;

public final class ThresholdCrossingObservationPublisher {
    public static final ThresholdCrossingObservationPublisher INSTANCE =
            new ThresholdCrossingObservationPublisher(GameplayIngressService.INSTANCE);

    private final GameplayIngressService ingress;

    public ThresholdCrossingObservationPublisher(GameplayIngressService ingress) {
        this.ingress = Objects.requireNonNull(ingress, "ingress");
    }

    public void publish(MinecraftServer server, ThresholdCrossing crossing) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(crossing, "crossing");
        var payload = new VanillaStatThresholdCrossedPayload(crossing.watchId(), crossing.metricKey(),
                crossing.source().key(), crossing.previousValue(), crossing.currentValue(),
                crossing.delta(), crossing.threshold());
        ingress.accept(server, new GameplayObservation<>(GameplayObservationTypes.VANILLA_STAT_THRESHOLD_CROSSED,
                crossing.playerId(), server.overworld().getGameTime(), payload));
    }
}
