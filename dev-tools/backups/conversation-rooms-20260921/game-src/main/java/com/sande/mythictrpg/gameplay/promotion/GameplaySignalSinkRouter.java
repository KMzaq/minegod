package com.sande.mythictrpg.gameplay.promotion;

import com.sande.mythictrpg.interaction.api.InteractionSignal;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;

public final class GameplaySignalSinkRouter implements GameplaySignalSink {
    static final GameplaySignalSinkRouter INSTANCE = new GameplaySignalSinkRouter();

    private GameplaySignalSink productionSink = GameplaySignalSink.UNAVAILABLE;
    private GameplaySignalSink testOverride;
    private boolean productionSinkConfigured;

    private GameplaySignalSinkRouter() {
    }

    static GameplaySignalSinkRouter forTesting() {
        return new GameplaySignalSinkRouter();
    }

    public void configureProductionSink(GameplaySignalSink sink) {
        Objects.requireNonNull(sink, "sink");
        if (!productionSinkConfigured) {
            productionSink = sink;
            productionSinkConfigured = true;
            return;
        }
        if (productionSink != sink) {
            throw new IllegalStateException("Gameplay signal production sink is already configured");
        }
    }

    public void setSinkForTesting(GameplaySignalSink sink) {
        testOverride = Objects.requireNonNull(sink, "sink");
    }

    public void clearSinkOverrideForTesting() {
        testOverride = null;
    }

    public void resetForTesting() {
        clearSinkOverrideForTesting();
    }

    @Override
    public GameplaySignalResult accept(MinecraftServer server,
            InteractionSignal<GameplayActionPayload> signal) {
        GameplaySignalSink effectiveSink = testOverride != null ? testOverride : productionSink;
        return Objects.requireNonNull(effectiveSink.accept(server, signal), "signal sink result");
    }
}
