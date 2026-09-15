package com.sande.mythictrpg.story.presentation;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.action.AiActionGateway;
import com.sande.mythictrpg.dialogue.api.DialoguePriority;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.server.DialoguePresentationService;
import com.sande.mythictrpg.dialogue.server.DialogueSendStatus;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import com.sande.mythictrpg.story.presentation.StoryAiPresentationContracts.Response;
import com.sande.mythictrpg.story.presentation.StoryAiPresentationContracts.Snapshot;
import com.sande.mythictrpg.story.runtime.StoryDisclosureService;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.PresentationOpportunity;
import com.sande.mythictrpg.story.state.StoryRuntimeModels.PresentationStatus;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Optional AI orchestration. Canonical state is already committed before this service runs. */
public final class StoryPresentationService {
    public static final StoryPresentationService INSTANCE = new StoryPresentationService();
    private final Map<UUID, UUID> activeRequests = new ConcurrentHashMap<>();
    private StoryPresentationService() {}

    public void dispatch(MinecraftServer server, PresentationOpportunity opportunity) {
        if (!server.isSameThread()) throw new IllegalStateException("Story presentation requires server thread");
        StoryRuntimeState state = StoryRuntimeState.get(server);
        PresentationOpportunity current = state.presentations().get(opportunity.opportunityId());
        if (current == null || (current.status() != PresentationStatus.PENDING
                && current.status() != PresentationStatus.GENERATING)) return;
        PresentationDefinition definition = StoryDefinitionManager.INSTANCE
                .presentation(current.presentationId()).orElse(null);
        if (definition == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(current.audiencePlayerId());
        if (player == null) {
            if (definition.offlineDeliveryPolicy() == OfflineDeliveryPolicy.DROP_FLAVOR
                    && definition.canonicalDeliveryPolicy() == CanonicalDeliveryPolicy.FLAVOR_ONLY) {
                state.updatePresentationStatus(current.opportunityId(), PresentationStatus.CANCELLED);
            }
            return;
        }

        if (definition.generationPolicy() == GenerationPolicy.SCRIPTED_ONLY
                || StoryAiPresentationRouter.INSTANCE.provider().isEmpty()) {
            deliverFallback(server, current, null);
            return;
        }
        var built = StoryPresentationSnapshotService.INSTANCE.forOpportunity(player, current);
        if (built.snapshot().isEmpty()) {
            state.updatePresentationStatus(current.opportunityId(), PresentationStatus.BLOCKED_DISCLOSURE);
            state.addAudit(server.overworld().getGameTime(), "PRESENTATION_BLOCKED",
                    current.opportunityId() + ":" + built.reason());
            return;
        }
        Snapshot snapshot = built.snapshot().orElseThrow();
        activeRequests.put(current.opportunityId(), snapshot.requestId());
        state.updatePresentationStatus(current.opportunityId(), PresentationStatus.GENERATING);
        try {
            StoryAiPresentationRouter.INSTANCE.provider().orElseThrow().generate(snapshot)
                    .whenComplete((response, failure) -> server.execute(() -> complete(server,
                            current.opportunityId(), snapshot, built, response, failure)));
        } catch (RuntimeException exception) {
            complete(server, current.opportunityId(), snapshot, built, null, exception);
        }
    }

    private void complete(MinecraftServer server, UUID opportunityId, Snapshot snapshot,
            StoryPresentationSnapshotService.BuildResult built, Response response, Throwable failure) {
        if (!server.isSameThread()) throw new IllegalStateException("Story presentation requires server thread");
        StoryRuntimeState state = StoryRuntimeState.get(server);
        PresentationOpportunity opportunity = state.presentations().get(opportunityId);
        if (opportunity == null || opportunity.status() != PresentationStatus.GENERATING
                || !snapshot.requestId().equals(activeRequests.get(opportunityId))) return;
        activeRequests.remove(opportunityId);
        if (failure != null || response == null || response.speech().size() > snapshot.maximumSpeechLines()) {
            MythicTrpg.LOGGER.warn("Story AI presentation {} failed; using authored fallback", opportunityId,
                    failure);
            deliverFallback(server, opportunity, built);
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(opportunity.audiencePlayerId());
        if (player == null) {
            state.updatePresentationStatus(opportunityId, PresentationStatus.PENDING);
            return;
        }
        state.updatePresentationStatus(opportunityId, PresentationStatus.DELIVERING_AI);
        boolean sent = true;
        for (String line : response.speech()) {
            sent &= DialoguePresentationService.INSTANCE.sendTo(player, new GodDialogueRequest(
                    snapshot.speakerGodId(), Component.literal(line), DialoguePriority.NORMAL,
                    com.sande.mythictrpg.dialogue.api.DialogueDisplayOptions.defaults()))
                    .status() == DialogueSendStatus.SENT;
        }
        if (!sent) {
            deliverFallback(server, opportunity, built);
            return;
        }
        deliverCanonicalAndCommit(player, built);
        response.proposedHookAlias().flatMap(alias -> StoryAiHookTokenService.INSTANCE.resolveAlias(
                player, snapshot.requestId(), alias, snapshot.speakerGodId())).ifPresent(token ->
                AiActionGateway.submit(player, snapshot.speakerGodId(), "story_event_hook",
                        "사건 제안", "이 제안을 수락하면 등록된 사건이 시작됩니다.",
                        Map.of("token", token.toString())));
        markDelivered(server, opportunityId, "AI");
    }

    public void deliverFallback(MinecraftServer server, PresentationOpportunity opportunity,
            StoryPresentationSnapshotService.BuildResult previouslyBuilt) {
        StoryRuntimeState state = StoryRuntimeState.get(server);
        PresentationDefinition definition = StoryDefinitionManager.INSTANCE
                .presentation(opportunity.presentationId()).orElse(null);
        ServerPlayer player = server.getPlayerList().getPlayer(opportunity.audiencePlayerId());
        if (definition == null || player == null) return;
        var built = previouslyBuilt;
        if (!definition.factRequests().isEmpty() && built == null) {
            built = StoryPresentationSnapshotService.INSTANCE.forOpportunity(player, opportunity);
            if (built.snapshot().isEmpty()) {
                state.updatePresentationStatus(opportunity.opportunityId(), PresentationStatus.BLOCKED_DISCLOSURE);
                return;
            }
        }
        state.updatePresentationStatus(opportunity.opportunityId(), PresentationStatus.DELIVERING_FALLBACK);
        ResourceLocation godId = definition.speakerActorId().flatMap(StoryDefinitionManager.INSTANCE::actor)
                .flatMap(ActorDefinition::godId).orElse(null);
        for (String key : definition.fallbackTranslationKeys()) {
            boolean dialogueDelivered = godId != null && DialoguePresentationService.INSTANCE.sendTo(player,
                    new GodDialogueRequest(godId, Component.translatable(key), DialoguePriority.NORMAL,
                            com.sande.mythictrpg.dialogue.api.DialogueDisplayOptions.defaults()))
                    .status() == DialogueSendStatus.SENT;
            if (!dialogueDelivered) player.sendSystemMessage(Component.translatable(key));
        }
        if (built != null) deliverCanonicalAndCommit(player, built);
        markDelivered(server, opportunity.opportunityId(), "FALLBACK");
    }

    private static void deliverCanonicalAndCommit(ServerPlayer player,
            StoryPresentationSnapshotService.BuildResult built) {
        for (var statement : built.snapshot().orElseThrow().allowedStatements()) {
            if (statement.canonical()) player.sendSystemMessage(Component.literal(statement.text()));
        }
        for (var transfer : built.transfers()) {
            StoryDisclosureService.INSTANCE.commitTransfer(player.server, player.getUUID(), transfer.factId(),
                    transfer.level(), transfer.disclosurePolicyId(), transfer.sourceInstanceId());
        }
    }

    private static void markDelivered(MinecraftServer server, UUID opportunityId, String mode) {
        StoryRuntimeState state = StoryRuntimeState.get(server);
        state.updatePresentationStatus(opportunityId, PresentationStatus.DELIVERED);
        state.addAudit(server.overworld().getGameTime(), "PRESENTATION_DELIVERED", opportunityId + ":" + mode);
    }
}
