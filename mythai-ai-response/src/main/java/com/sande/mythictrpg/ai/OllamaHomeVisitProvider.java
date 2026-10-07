package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.sande.mythictrpg.godavatar.visit.GodVisitPlanner;
import com.sande.mythai.response.memory.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.util.*;
import java.util.concurrent.*;

/** Dedicated optional, foreground-yielding inference; no Minecraft access from the worker. */
public final class OllamaHomeVisitProvider implements GodVisitPlanner.Provider {
    public static final OllamaHomeVisitProvider INSTANCE = new OllamaHomeVisitProvider();
    private ThreadPoolExecutor worker;
    private record Persona(String text, long generation) { }
    private static Persona persona(GodVisitPlanner.Request request) {
        var server = ServerLifecycleHooks.getCurrentServer();
        var god = ResourceLocation.parse(request.godId());
        var tier = com.sande.mythictrpg.ai.social.AffinityTierPolicy.load(server.getServerDirectory()
                .resolve(com.sande.mythictrpg.ai.social.AffinityTierPolicy.CONFIG_PATH)).tier(request.affinity());
        var content = new AiTestContentRegistryBridge().load(god, tier, List.of(god));
        var profile = content.profile();
        String text = new Gson().toJson(Map.of("identity", profile.identity(), "description", profile.description(),
                "personality", profile.personality(), "values", profile.values(), "restrictions", profile.restrictions(),
                "relationshipGuidance", content.relationshipGuidance()));
        if (text.length() > 10000) throw new IllegalArgumentException("Visit persona budget");
        return new Persona(text, content.generation());
    }
    @Override public CompletableFuture<GodVisitPlanner.Decision> choose(GodVisitPlanner.Request request) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return CompletableFuture.failedFuture(new IllegalStateException("Visit snapshot thread"));
        var persona = persona(request);
        String emotion = MythAiRoomConversationEngine.INSTANCE.visitEmotion(request);
        var settings = AiDialogueConfig.INSTANCE.settings();
        var result = new CompletableFuture<GodVisitPlanner.Decision>();
        if (worker == null || worker.isShutdown()) worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), runnable -> { var t = new Thread(runnable, "mythai-home-visit"); t.setDaemon(true); return t; });
        try {
            var task = worker.submit(() -> {
                try (var lease = ModelAdmission.followup()) {
                    if (lease == null || result.isCancelled()) throw new IllegalStateException("Visit deferred: model busy");
                    var choice = OllamaVisitSelection.local().choose(request, persona.text(), emotion, settings.ollamaChatUrl(), settings.ollamaModel());
                    server.execute(() -> {
                        if (result.isCancelled()) return;
                        try {
                            if (ServerLifecycleHooks.getCurrentServer() != server || !persona.equals(persona(request)))
                                throw new IllegalStateException("Visit persona/server changed");
                            result.complete(choice);
                        } catch (RuntimeException stale) { result.completeExceptionally(stale); }
                    });
                } catch (Exception unavailable) { result.completeExceptionally(unavailable); }
                finally { Thread.interrupted(); }
            });
            result.whenComplete((value, failure) -> { if (result.isCancelled()) task.cancel(true); });
        } catch (RejectedExecutionException full) { result.completeExceptionally(full); }
        return result;
    }
    public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        if (INSTANCE.worker != null) { INSTANCE.worker.shutdownNow(); INSTANCE.worker = null; }
    }
    private OllamaHomeVisitProvider() { }
}
