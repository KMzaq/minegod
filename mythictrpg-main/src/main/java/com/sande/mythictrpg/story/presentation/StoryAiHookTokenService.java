package com.sande.mythictrpg.story.presentation;

import com.sande.mythictrpg.ai.action.AiActionProposal;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.runtime.StoryHookService;
import com.sande.mythictrpg.story.runtime.StoryTeamResolver;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** One-use, session-bound aliases. Raw Story Hook IDs are never sent to a model. */
public final class StoryAiHookTokenService {
    public static final StoryAiHookTokenService INSTANCE = new StoryAiHookTokenService();
    private static final long TTL_TICKS = 1_200L;
    private final Map<UUID, Token> tokens = new HashMap<>();
    private final Map<UUID, Map<String, UUID>> aliasesBySnapshot = new HashMap<>();
    private StoryAiHookTokenService() {}

    public String issue(ServerPlayer player, UUID snapshotId, ResourceLocation hookId,
            ResourceLocation speakerActorId, ResourceLocation speakerGodId, UUID sessionId, int index) {
        requireThread(player);
        cleanup(player.server.overworld().getGameTime());
        UUID opaque = UUID.randomUUID();
        String alias = "hook_" + index;
        var hook = StoryDefinitionManager.INSTANCE.hook(hookId).orElseThrow();
        StoryScopeKey targetScope = switch (hook.targetScope()) {
            case SERVER -> StoryScopeKey.server();
            case PLAYER -> StoryScopeKey.player(player.getUUID());
            case TEAM -> StoryScopeKey.team(StoryTeamResolver.resolve(player).stableTeamId());
        };
        var current = StoryRuntimeState.get(player.server).latestEvent(hook.targetEventId(), targetScope);
        String expectedInstance = current.map(value -> value.instanceId()).orElse("");
        long expectedRevision = current.map(value -> value.revision()).orElse(0L);
        Token token = new Token(opaque, player.getUUID(), sessionId, hookId, speakerActorId, speakerGodId,
                player.server.overworld().getGameTime() + TTL_TICKS, targetScope, expectedInstance,
                expectedRevision);
        tokens.put(opaque, token);
        aliasesBySnapshot.computeIfAbsent(snapshotId, ignored -> new HashMap<>()).put(alias, opaque);
        return alias;
    }

    public Optional<UUID> resolveAlias(ServerPlayer player, UUID snapshotId, String alias,
            ResourceLocation actingGodId) {
        requireThread(player);
        UUID opaque = aliasesBySnapshot.getOrDefault(snapshotId, Map.of()).get(alias);
        if (opaque == null) return Optional.empty();
        Token token = tokens.get(opaque);
        return token != null && token.matches(player, actingGodId) ? Optional.of(opaque) : Optional.empty();
    }

    public Validation validate(ServerPlayer player, AiActionProposal proposal) {
        requireThread(player);
        UUID opaque;
        try { opaque = UUID.fromString(proposal.parameters().getOrDefault("token", "")); }
        catch (IllegalArgumentException exception) { return Validation.reject("Invalid Story Hook token"); }
        Token token = tokens.get(opaque);
        if (token == null || token.expiresAt <= player.server.overworld().getGameTime())
            return Validation.reject("Story Hook token is missing or expired");
        if (!token.playerId.equals(player.getUUID()) || !token.sessionId.equals(proposal.sessionId())
                || !token.speakerGodId.equals(proposal.actingGodId()))
            return Validation.reject("Story Hook token does not belong to this player, session, and speaker");
        var hook = StoryDefinitionManager.INSTANCE.hook(token.hookId).orElse(null);
        if (hook == null) return Validation.reject("Story Hook definition is no longer available");
        var current = StoryRuntimeState.get(player.server).latestEvent(hook.targetEventId(), token.targetScope);
        String currentInstance = current.map(value -> value.instanceId()).orElse("");
        long currentRevision = current.map(value -> value.revision()).orElse(0L);
        if (!token.expectedInstanceId.equals(currentInstance) || token.expectedRevision != currentRevision)
            return Validation.reject("Story Hook source revision changed after it was offered");
        var preview = StoryHookService.INSTANCE.preview(player, token.hookId, token.speakerActorId);
        return preview.accepted() ? new Validation(true, "", Optional.of(token))
                : Validation.reject(preview.reason());
    }

    public StoryHookService.HookAcceptResult consume(ServerPlayer player, AiActionProposal proposal) {
        Validation validation = validate(player, proposal);
        if (!validation.accepted()) return new StoryHookService.HookAcceptResult(false,
                validation.reason(), java.util.List.of());
        Token token = validation.token.orElseThrow();
        tokens.remove(token.opaque);
        aliasesBySnapshot.values().forEach(map -> map.values().remove(token.opaque));
        return StoryHookService.INSTANCE.accept(player, token.hookId, token.speakerActorId, true);
    }

    public void discardPlayer(UUID playerId) {
        tokens.values().removeIf(value -> value.playerId.equals(playerId));
        aliasesBySnapshot.values().forEach(map -> map.entrySet().removeIf(entry -> !tokens.containsKey(entry.getValue())));
    }

    private void cleanup(long now) { tokens.values().removeIf(value -> value.expiresAt <= now); }
    private static void requireThread(ServerPlayer player) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Story AI tokens require server thread");
    }
    private record Token(UUID opaque, UUID playerId, UUID sessionId, ResourceLocation hookId,
            ResourceLocation speakerActorId, ResourceLocation speakerGodId, long expiresAt,
            StoryScopeKey targetScope, String expectedInstanceId, long expectedRevision) {
        boolean matches(ServerPlayer player, ResourceLocation godId) {
            return playerId.equals(player.getUUID()) && speakerGodId.equals(godId) && expiresAt > player.server.overworld().getGameTime();
        }
    }
    public record Validation(boolean accepted, String reason, Optional<Token> token) {
        static Validation reject(String reason) { return new Validation(false, reason, Optional.empty()); }
    }
}
