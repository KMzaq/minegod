package com.sande.mythictrpg.condition.engine;

import com.sande.mythictrpg.condition.api.ConditionDependency;
import com.sande.mythictrpg.data.god.UnlockEvaluationReport;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Objects;
import java.util.UUID;

/** Small single-consumer bridge between persistent data mutations and progression evaluation. */
public final class ConditionChangeDispatcher {
    private static final Handler NO_OP = new Handler() {
    };
    private static volatile Handler handler = NO_OP;

    private ConditionChangeDispatcher() {
    }

    public static void registerHandler(Handler newHandler) {
        handler = Objects.requireNonNull(newHandler, "newHandler");
    }

    public static UnlockEvaluationReport publishPlayerDependencyChanged(
            MinecraftServer server, ConditionDependency dependency, UUID playerId) {
        return handler.onPlayerDependencyChanged(server, dependency, playerId);
    }

    public static UnlockEvaluationReport publishPlayerReady(MinecraftServer server, UUID playerId) {
        return handler.onPlayerReady(server, playerId);
    }

    public static UnlockEvaluationReport publishDefinitionsReloaded() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.overworld() == null) {
            return UnlockEvaluationReport.empty();
        }
        if (!server.isSameThread()) {
            server.execute(() -> handler.onDefinitionsReloaded(server));
            return UnlockEvaluationReport.empty();
        }
        return handler.onDefinitionsReloaded(server);
    }

    public interface Handler {
        default UnlockEvaluationReport onPlayerDependencyChanged(
                MinecraftServer server, ConditionDependency dependency, UUID playerId) {
            return UnlockEvaluationReport.empty();
        }

        default UnlockEvaluationReport onPlayerReady(MinecraftServer server, UUID playerId) {
            return UnlockEvaluationReport.empty();
        }

        default UnlockEvaluationReport onDefinitionsReloaded(MinecraftServer server) {
            return UnlockEvaluationReport.empty();
        }
    }
}
