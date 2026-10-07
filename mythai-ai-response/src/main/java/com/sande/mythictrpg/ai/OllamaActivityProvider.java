package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.sande.mythai.response.memory.ModelAdmission;
import com.sande.mythai.response.memory.OllamaActivitySelection;
import com.sande.mythictrpg.godavatar.activity.GodActivityPlanner;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.util.*;
import java.util.concurrent.*;

/** Autonomous-only inference. Chat requests remain in the existing room generation call. */
public final class OllamaActivityProvider implements GodActivityPlanner.Provider {
    public static final OllamaActivityProvider INSTANCE = new OllamaActivityProvider();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private ThreadPoolExecutor worker;
    private record Personas(String text, List<Long> generations) { }

    /** Only static persona here. Own activity evidence comes from the game Request, never a private-room cache. */
    private static Personas personas(GodActivityPlanner.Request request) {
        var ids = new ArrayList<ResourceLocation>(); ids.add(ResourceLocation.parse(request.godId()));
        request.peers().forEach(peer -> ids.add(ResourceLocation.parse(peer.godId())));
        var actors = new ArrayList<Map<String,Object>>();
        var generations = new ArrayList<Long>();
        var registry = new AiTestContentRegistryBridge();
        for (var actor : ids) {
            // The bridge requires a tier to read a profile, but no player-tier guidance is exported.
            var content = registry.load(actor, "R_NEUTRAL", List.of(actor));
            var profile = Objects.requireNonNull(content.profile(), "Activity actor profile unavailable");
            var data = new LinkedHashMap<String,Object>();
            data.put("godId", actor.toString());
            data.put("identity", profile.identity());
            data.put("displayName", profile.displayName());
            data.put("description", profile.description());
            data.put("personality", profile.personality());
            data.put("values", profile.values());
            data.put("speechStyles", profile.speechStyles());
            data.put("dialogueGuidelines", profile.dialogueGuidelines());
            data.put("restrictions", profile.restrictions());
            data.put("characterTags", profile.characterTags());
            generations.add(content.generation());
            var relations = new LinkedHashMap<String,Object>();
            for (var other : ids) if (!other.equals(actor)) {
                var pair = registry.load(actor, "R_NEUTRAL", List.of(actor, other));
                relations.put(other.toString(), pair.socialRelationTags());
                generations.add(pair.generation());
            }
            data.put("authoredDirectionalTagsNotDynamicRelationship", relations);
            actors.add(data);
        }
        String text = new Gson().toJson(actors);
        if (text.length() > 20000) throw new IllegalArgumentException("Activity persona budget");
        return new Personas(text, List.copyOf(generations));
    }

    @Override public CompletableFuture<GodActivityPlanner.Decision> choose(GodActivityPlanner.Request request) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return CompletableFuture.failedFuture(new IllegalStateException("Activity snapshot thread"));
        final Personas snapshot;
        try { OllamaActivitySelection.validate(request); snapshot = personas(request); }
        catch (RuntimeException unavailable) { return CompletableFuture.failedFuture(unavailable); }
        var settings = AiDialogueConfig.INSTANCE.settings();
        var result = new CompletableFuture<GodActivityPlanner.Decision>();
        pending.add(result);
        result.whenComplete((value, failure) -> pending.remove(result));
        if (worker == null || worker.isShutdown()) worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), runnable -> { var thread = new Thread(runnable, "mythai-npc-activity"); thread.setDaemon(true); return thread; });
        try {
            var task = worker.submit(() -> {
                try (var admission = ModelAdmission.followup()) {
                    if (admission == null || result.isDone()) throw new IllegalStateException("Activity deferred: model busy");
                    var decision = OllamaActivitySelection.local().choose(request, snapshot.text(), DivineSocialPrompt.policy(),
                            settings.ollamaChatUrl(), settings.ollamaModel());
                    if (result.isDone() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Activity preempted");
                    server.execute(() -> {
                        if (result.isDone()) return;
                        try {
                            var now = AiDialogueConfig.INSTANCE.settings();
                            if (ServerLifecycleHooks.getCurrentServer() != server || !snapshot.equals(personas(request))
                                    || !settings.ollamaChatUrl().equals(now.ollamaChatUrl()) || !settings.ollamaModel().equals(now.ollamaModel()))
                                throw new IllegalStateException("Activity persona/server/model changed");
                            // No state is committed here: the game rechecks actor/activity and experience revisions,
                            // offered token, source event IDs and the unchanged live audience before accepting affect.
                            result.complete(decision);
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
        INSTANCE.pending.forEach(future -> future.cancel(true));
        if (INSTANCE.worker != null) { INSTANCE.worker.shutdownNow(); INSTANCE.worker = null; }
    }
    private OllamaActivityProvider() { }
}
