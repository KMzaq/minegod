package com.sande.mythictrpg.story.presentation;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.action.AiActionGateway;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.server.ConversationRooms;
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Optional AI orchestration. Canonical state is already committed before this service runs. */
public final class StoryPresentationService {
    public static final StoryPresentationService INSTANCE = new StoryPresentationService();
    private final Map<UUID, UUID> activeRequests = new ConcurrentHashMap<>();
    private StoryPresentationService() {}
    public void clear() { activeRequests.clear(); StoryRoomConversationService.INSTANCE.clear(); }

    public void dispatch(MinecraftServer server, PresentationOpportunity opportunity) {
        if (!server.isSameThread()) throw new IllegalStateException("Story presentation requires server thread");
        StoryRuntimeState state = StoryRuntimeState.get(server);
        PresentationOpportunity current = state.presentations().get(opportunity.opportunityId());
        if (current == null || (current.status() != PresentationStatus.PENDING
                && current.status() != PresentationStatus.GENERATING)) return;
        if (state.eventInstance(current.eventInstanceId()).filter(value -> value.status()
                == com.sande.mythictrpg.story.state.StoryRuntimeModels.EventStatus.RESOLVED).isEmpty()) return;
        if (activeRequests.containsKey(current.opportunityId())) return;
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
        var roomContext = StoryRoomConversationService.INSTANCE.forOpportunity(player, current);
        if (roomContext.isPresent()) {
            dispatchRoom(player, current, roomContext.orElseThrow(), definition);
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
        // Disclosure is re-evaluated after every asynchronous generation, not merely at submission.
        var refreshed = StoryPresentationSnapshotService.INSTANCE.forOpportunity(player, opportunity);
        if (refreshed.snapshot().isEmpty() || !sameDisclosure(snapshot, refreshed.snapshot().orElseThrow())
                || !StoryAiPresentationRouter.INSTANCE.provider().map(provider -> provider.current(snapshot)).orElse(false)) {
            deliverFallback(server, opportunity, null);
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
        deliverCanonicalAndCommit(player, refreshed);
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
        if (definition == null) return;
        if (player == null) {
            state.updatePresentationStatus(opportunity.opportunityId(), PresentationStatus.PENDING);
            return;
        }
        var roomContext = StoryRoomConversationService.INSTANCE.forOpportunity(player, opportunity);
        if (roomContext.isPresent()) {
            deliverRoom(player, opportunity, roomContext.orElseThrow(), fallbackLines(definition), Optional.empty(), "FALLBACK");
            return;
        }
        // The definition may have changed while an AI request was in flight. In particular, a new
        // flavor-only definition must never commit transfers captured by an older fact-bearing one.
        StoryPresentationSnapshotService.BuildResult built = null;
        if (!definition.factRequests().isEmpty()) {
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

    private void dispatchRoom(ServerPlayer player, PresentationOpportunity opportunity,
            StoryRoomConversationService.Context context, PresentationDefinition definition) {
        var snapshot = context.snapshot();
        if (definition.generationPolicy() == GenerationPolicy.SCRIPTED_ONLY || StoryAiPresentationRouter.INSTANCE.provider().isEmpty()) {
            deliverRoom(player, opportunity, context, fallbackLines(definition), Optional.empty(), "FALLBACK");
            return;
        }
        activeRequests.put(opportunity.opportunityId(), snapshot.requestId());
        StoryRuntimeState.get(player.server).updatePresentationStatus(opportunity.opportunityId(), PresentationStatus.GENERATING);
        var server = player.server;
        try {
            StoryAiPresentationRouter.INSTANCE.provider().orElseThrow().generate(snapshot).whenComplete((response, failure) ->
                    server.execute(() -> completeRoom(server, opportunity, context, response, failure)));
        } catch (RuntimeException failure) { completeRoom(server, opportunity, context, null, failure); }
    }

    private void completeRoom(MinecraftServer server, PresentationOpportunity opportunity,
            StoryRoomConversationService.Context context, Response response, Throwable failure) {
        var state = StoryRuntimeState.get(server);
        var current = state.presentations().get(opportunity.opportunityId());
        if (current == null || current.status() != PresentationStatus.GENERATING
                || !activeRequests.remove(opportunity.opportunityId(), context.snapshot().requestId())) return;
        var player = server.getPlayerList().getPlayer(current.audiencePlayerId());
        if (player == null) { state.updatePresentationStatus(current.opportunityId(), PresentationStatus.PENDING); return; }
        if (failure != null || response == null || response.speech().size() > context.snapshot().maximumSpeechLines()
                || !StoryRoomConversationService.INSTANCE.current(player, context)
                || !StoryAiPresentationRouter.INSTANCE.provider().map(provider -> provider.current(context.snapshot())).orElse(false)) {
            deliverFallback(server, current, null); return;
        }
        deliverRoom(player, current, context, response.speech(), response.proposedHookAlias(), "AI");
    }

    private static List<String> fallbackLines(PresentationDefinition definition) {
        return definition.fallbackTranslationKeys().stream().map(key -> Component.translatable(key).getString()).toList();
    }

    private void deliverRoom(ServerPlayer player, PresentationOpportunity opportunity,
            StoryRoomConversationService.Context context, List<String> lines, Optional<String> hookAlias, String mode) {
        var service = StoryRoomConversationService.INSTANCE;
        var state = StoryRuntimeState.get(player.server);
        var snapshot = context.snapshot();
        if (!service.beginPresentationDelivery(player, context)) {
            state.updatePresentationStatus(opportunity.opportunityId(), PresentationStatus.PENDING); return;
        }
        state.updatePresentationStatus(opportunity.opportunityId(), mode.equals("AI")
                ? PresentationStatus.DELIVERING_AI : PresentationStatus.DELIVERING_FALLBACK);
        var canonicalReceipts = new ArrayList<RoomDialogueEvent>();
        Set<UUID> completeAudience = new LinkedHashSet<>(context.audience());
        try {
            var renderingEvidence = new ArrayList<>(context.evidenceReferences());
            if (mode.equals("AI")) renderingEvidence.addAll(StoryAiPresentationRouter.INSTANCE.provider().orElseThrow().evidence(snapshot));
            var canonical = service.prepareDisclosures(player, snapshot.requestId(), context.roomId(), context.roomRevision(),
                    snapshot.speakerGodId(), snapshot.allowedStatements().stream().map(value -> value.alias()).toList());
            if (canonical.size() != snapshot.allowedStatements().size()) completeAudience.clear();
            for (var line : canonical) {
                var receipt = ConversationRooms.INSTANCE.publishStoryPresentation(player, context.roomId(), context.roomRevision(),
                        snapshot.speakerGodId(), line.text(), List.of(line.evidence()), snapshot.requestId());
                receipt.ifPresent(canonicalReceipts::add);
                completeAudience.retainAll(receipt.map(RoomDialogueEvent::fullTextReceiverIds).orElse(Set.of()));
            }
            for (String line : lines) {
                var receipt = ConversationRooms.INSTANCE.publishStoryPresentation(player, context.roomId(), context.roomRevision(),
                        snapshot.speakerGodId(), line, renderingEvidence, snapshot.requestId());
                completeAudience.retainAll(receipt.map(RoomDialogueEvent::fullTextReceiverIds).orElse(Set.of()));
            }
            service.commitDisclosures(player, snapshot.requestId(), canonicalReceipts);
            if (completeAudience.contains(player.getUUID())) {
                hookAlias.flatMap(alias -> StoryAiHookTokenService.INSTANCE.resolveRoomAlias(player, snapshot.requestId(), alias,
                        snapshot.speakerGodId(), context.roomId(), context.roomRevision())).ifPresent(token -> {
                    var offer = snapshot.hookOffers().stream().filter(value -> value.alias().equals(hookAlias.orElseThrow())).findFirst().orElseThrow();
                    AiActionGateway.submitRoom(player, context.roomId(), context.roomRevision(), snapshot.speakerGodId(), "story_event_hook",
                            offer.title(), offer.summary(), Map.of("token", token.toString()), false);
                });
            }
            // A TEAM/all-online EMIT creates one opportunity per player. One room delivery satisfies
            // its actual full-text recipients without broadcasting the same presentation again.
            for (var candidate : state.presentations().values().stream().toList()) {
                if (candidate.eventInstanceId().equals(opportunity.eventInstanceId()) && candidate.presentationId().equals(opportunity.presentationId())
                        && completeAudience.contains(candidate.audiencePlayerId()) && candidate.status() != PresentationStatus.DELIVERED) {
                    activeRequests.remove(candidate.opportunityId()); markDelivered(player.server, candidate.opportunityId(), mode + "_ROOM");
                }
            }
            if (!completeAudience.contains(player.getUUID())) state.updatePresentationStatus(opportunity.opportunityId(), PresentationStatus.PENDING);
        } catch (RuntimeException failure) {
            MythicTrpg.LOGGER.warn("Story room presentation {} interrupted; retaining opportunity", opportunity.opportunityId(), failure);
            service.commitDisclosures(player, snapshot.requestId(), canonicalReceipts);
            state.updatePresentationStatus(opportunity.opportunityId(), PresentationStatus.PENDING);
        } finally { service.endPresentationDelivery(snapshot.requestId()); }
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

    static boolean sameDisclosure(Snapshot before, Snapshot current) {
        return before.audiencePlayerId().equals(current.audiencePlayerId())
                && before.speakerGodId().equals(current.speakerGodId())
                && before.kind() == current.kind() && before.generationPolicy() == current.generationPolicy()
                && before.maximumSpeechLines() == current.maximumSpeechLines()
                && before.allowedStatements().equals(current.allowedStatements())
                && before.performanceDirectives().equals(current.performanceDirectives())
                && before.demeanorContext().equals(current.demeanorContext())
                && before.audience().equals(current.audience())
                && before.hookOffers().equals(current.hookOffers());
    }

    private static void markDelivered(MinecraftServer server, UUID opportunityId, String mode) {
        StoryRuntimeState state = StoryRuntimeState.get(server);
        state.updatePresentationStatus(opportunityId, PresentationStatus.DELIVERED);
        state.addAudit(server.overworld().getGameTime(), "PRESENTATION_DELIVERED", opportunityId + ":" + mode);
    }
}
