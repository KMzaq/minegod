package com.sande.mythictrpg.story.runtime;

import com.sande.mythictrpg.condition.api.ConditionResult;
import com.sande.mythictrpg.condition.engine.ConditionContexts;
import com.sande.mythictrpg.condition.engine.ConditionEngine;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitions.HookDefinition;
import com.sande.mythictrpg.story.definition.StoryDefinitions.ScopeType;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.HookAcceptanceRecord;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Confirmation-agnostic server boundary used by UI today and opaque AI tokens in 04 later. */
public final class StoryHookService {
    public static final StoryHookService INSTANCE = new StoryHookService();
    private StoryHookService() {}

    public HookAcceptResult accept(ServerPlayer player, ResourceLocation hookId,
            ResourceLocation speakerActorId, boolean playerConfirmed) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Story Hook must run on server thread");
        HookAcceptResult preview = preview(player, hookId, speakerActorId);
        if (!preview.accepted()) return preview;
        HookDefinition hook = StoryDefinitionManager.INSTANCE.hook(hookId).orElse(null);
        if (hook.confirmationRequired() && !playerConfirmed)
            return HookAcceptResult.rejected("Explicit player confirmation is required");

        StoryScopeKey scope = scope(player, hook.targetScope());
        StoryRuntimeState state = StoryRuntimeState.get(player.server);
        long now = player.server.overworld().getGameTime();

        var result = StoryEventService.INSTANCE.triggerHook(player.server, hook, player);
        if (result.status() != StoryEventService.Status.STARTED)
            return HookAcceptResult.rejected(result.reason());
        String key = hook.id() + "|" + scope.type() + ":" + scope.key();
        state.recordHookAcceptance(key, hook.id(), scope.type() + ":" + scope.key(), now);
        return new HookAcceptResult(true, "Story Hook accepted", result.startedInstanceIds());
    }

    /** Side-effect-free check used before exposing an opaque alias and again at confirmation time. */
    public HookAcceptResult preview(ServerPlayer player, ResourceLocation hookId,
            ResourceLocation speakerActorId) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Story Hook must run on server thread");
        HookDefinition hook = StoryDefinitionManager.INSTANCE.hook(hookId).orElse(null);
        if (hook == null) return HookAcceptResult.rejected("Unknown Story Hook");
        if (!hook.allowedSpeakerActorIds().contains(speakerActorId))
            return HookAcceptResult.rejected("Speaker is not allowed to offer this Story Hook");

        StoryScopeKey scope = scope(player, hook.targetScope());
        if (ConditionEngine.INSTANCE.evaluate(hook.availabilityConditions(),
                ConditionContexts.forStory(player.server, java.util.Optional.of(player.getUUID()), scope))
                != ConditionResult.MATCH) {
            return HookAcceptResult.rejected("Story Hook availability conditions no longer match");
        }
        StoryRuntimeState state = StoryRuntimeState.get(player.server);
        String key = hook.id() + "|" + scope.type() + ":" + scope.key();
        HookAcceptanceRecord previous = state.hookAcceptance(key).orElse(null);
        long now = player.server.overworld().getGameTime();
        if (previous != null && previous.count() >= hook.maximumAcceptances())
            return HookAcceptResult.rejected("Story Hook acceptance limit reached");
        if (previous != null && now - previous.lastAcceptedGameTime() < hook.cooldownTicks())
            return HookAcceptResult.rejected("Story Hook is still on cooldown");
        return new HookAcceptResult(true, "Story Hook is available", java.util.List.of());
    }

    private static StoryScopeKey scope(ServerPlayer player, ScopeType type) {
        return switch (type) {
            case SERVER -> StoryScopeKey.server();
            case PLAYER -> StoryScopeKey.player(player.getUUID());
            case TEAM -> StoryScopeKey.team(StoryTeamResolver.resolve(player).stableTeamId());
        };
    }

    public record HookAcceptResult(boolean accepted, String reason, java.util.List<String> instanceIds) {
        public static HookAcceptResult rejected(String reason) {
            return new HookAcceptResult(false, reason, java.util.List.of());
        }
    }
}
