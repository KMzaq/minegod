package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.dialogue.api.GodDialogueRequest;
import com.sande.mythictrpg.dialogue.server.DialoguePresentationService;
import com.sande.mythictrpg.dialogue.server.DialogueSendStatus;
import com.sande.mythictrpg.ai.reaction.ReactionGuidelineRepository;
import com.sande.mythictrpg.ai.agent.NpcAgentRepository;
import com.sande.mythictrpg.ai.voice.JsonVoiceStyleRepository;
import com.sande.mythictrpg.ai.example.JsonDialogueExampleRepository;
import com.sande.mythictrpg.ai.example.JsonTagDialogueGuidanceRepository;
import com.sande.mythictrpg.ai.context.ConversationTurnContextSnapshot;
import com.sande.mythictrpg.ai.integration.mythictrpg.MythicTrpgConversationSnapshotProvider;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import com.sande.mythictrpg.ai.intent.ConversationIntentRouter;
import com.sande.mythictrpg.ai.tone.SocialAuthorityContextProvider;
import com.sande.mythictrpg.ai.knowledge.JsonKnowledgeRepository;
import com.sande.mythictrpg.ai.memory.NpcMemoryEngine;
import com.sande.mythictrpg.ai.memory.MemoryType;
import com.sande.mythictrpg.ai.memory.NpcMemory;
import com.sande.mythictrpg.ai.proposal.ProposalDecodeResult;
import com.sande.mythictrpg.ai.proposal.QuestRewardContextProvider;
import com.sande.mythictrpg.ai.proposal.RequestJudgmentProposal;
import com.sande.mythictrpg.ai.proposal.StructuredProposalDecoder;
import com.sande.mythictrpg.ai.proposal.VouchRequestProposal;
import com.sande.mythictrpg.ai.proposal.VouchResolutionProposal;
import com.sande.mythictrpg.ai.tag.CharacterStyleTagMapper;
import com.sande.mythictrpg.ai.tag.CharacterTagRegistry;
import com.sande.mythictrpg.ai.tag.NpcCharacterTagRepository;
import com.sande.mythictrpg.ai.vouch.PendingVouchInteraction;
import com.sande.mythictrpg.ai.vouch.VouchStance;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Conversation facade used by Minecraft events. It owns sessions and LLM orchestration,
 * but deliberately has no direct access to gameplay mutation APIs.
 */
public final class GodAiDialogueService {
    public static final GodAiDialogueService INSTANCE = new GodAiDialogueService();

    private final ConversationSessionManager sessions = new ConversationSessionManager();
    private final AiContextBuilder contextBuilder = new AiContextBuilder();
    private final LocalLlmClient ollama = new LocalOllamaClient();
    private final ConversationIntentRouter intentRouter = new ConversationIntentRouter();
    private final ConversationLogService logs = new ConversationLogService();
    private final StructuredProposalDecoder structuredProposalDecoder = new StructuredProposalDecoder();
    private volatile QuestRewardContextProvider questRewardContextProvider = QuestRewardContextProvider.none();
    private volatile SocialAuthorityContextProvider socialAuthorityContextProvider = SocialAuthorityContextProvider.none();

    private GodAiDialogueService() {
    }

    /** Compatibility entry point for a one-player, one-god conversation. */
    public StartResult start(ServerPlayer player, ResourceLocation godId) {
        StartConversationResult result = startConversation(List.of(player), List.of(godId));
        if (result.status() == StartConversationStatus.STARTED) {
            player.sendSystemMessage(Component.literal("[AI 대화] " + godId + "와(과)의 대화를 시작했습니다. "
                    + "일반 채팅으로 말하고, !로 시작하면 공개 채팅입니다.").withStyle(ChatFormatting.LIGHT_PURPLE));
            return StartResult.STARTED;
        }
        return switch (result.status()) {
            case PERSONA_MISSING -> StartResult.PERSONA_MISSING;
            case NPC_BUSY -> StartResult.NPC_BUSY;
            default -> StartResult.UNKNOWN_GOD;
        };
    }

    /**
     * Foundation API for multi-player/multi-god callers. Every supplied player starts ACTIVE.
     * Other systems may later add LISTENER participants through {@link #setParticipantState}.
     */
    public StartConversationResult startConversation(Collection<ServerPlayer> players,
            Collection<ResourceLocation> godIds) {
        return startConversation(players, godIds, null);
    }

    /**
     * Integration entry point for an already-approved Interaction/Encounter. The caller owns participant selection
     * and the interaction ID; AI only attaches a conversation session to that reference.
     */
    public StartConversationResult startConversation(Collection<ServerPlayer> players,
            Collection<ResourceLocation> godIds, UUID interactionId) {
        if (players == null || players.isEmpty() || godIds == null || godIds.isEmpty()) {
            return StartConversationResult.rejected(StartConversationStatus.INVALID_PARTICIPANTS);
        }
        for (ResourceLocation godId : godIds) {
            if (GodDefinitionManager.INSTANCE.find(godId).isEmpty()) {
                return StartConversationResult.rejected(StartConversationStatus.UNKNOWN_GOD);
            }
            if (!hasPersona(godId)) {
                return StartConversationResult.rejected(StartConversationStatus.PERSONA_MISSING);
            }
            boolean available = NpcAgentRepository.INSTANCE.find(godId)
                    .map(agent -> agent.conversationPolicy().canJoinSessionCount(sessions.findByDivine(godId).size()))
                    .orElseGet(() -> sessions.findByDivine(godId).isEmpty());
            if (!available) {
                return StartConversationResult.rejected(StartConversationStatus.NPC_BUSY);
            }
        }
        for (ServerPlayer player : players) {
            detachForNewSession(player);
        }
        ServerPlayer anchor = players.iterator().next();
        List<AiDialogueModels.Participant> participants = new ArrayList<>();
        for (ServerPlayer player : players) {
            participants.add(ConversationSessionManager.player(player.getGameProfile().getName(), player.getUUID(),
                    AiDialogueModels.ParticipantState.ACTIVE));
        }
        for (ResourceLocation godId : godIds) {
            String displayName = GodDefinitionManager.INSTANCE.find(godId).orElseThrow().displayName().getString();
            participants.add(ConversationSessionManager.divine(godId, displayName));
        }
        AiDialogueModels.LocationSnapshot location = new AiDialogueModels.LocationSnapshot(
                anchor.level().dimension().location().toString(), (int) Math.floor(anchor.getX()),
                (int) Math.floor(anchor.getY()), (int) Math.floor(anchor.getZ()));
        ConversationSessionManager.Session session = sessions.create(participants, location,
                AiDialogueConfig.INSTANCE.settings().transcriptMessages() * 2, interactionId);
        logs.open(session.snapshot(AiDialogueConfig.INSTANCE.settings().transcriptMessages()));
        return StartConversationResult.started(session.id());
    }

    public boolean setParticipantState(UUID sessionId, UUID playerId, AiDialogueModels.ParticipantState state) {
        ConversationSessionManager.StateChangeResult result;
        try {
            result = sessions.setPlayerState(sessionId, playerId, state);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        if (result.closed()) {
            logs.close(sessionId);
        }
        return result.existed();
    }

    /** Preferred state-update overload when the caller has the server and wants a cancelled turn to advance at once. */
    public boolean setParticipantState(MinecraftServer server, UUID sessionId, UUID playerId,
            AiDialogueModels.ParticipantState state) {
        boolean changed = setParticipantState(sessionId, playerId, state);
        if (changed && server != null) {
            dispatchNextQueuedTurn(server, sessionId);
        }
        return changed;
    }

    /** Adds a nearby player as a listener. Sending a message promotes that player to ACTIVE. */
    public boolean addListener(UUID sessionId, ServerPlayer player) {
        ConversationSessionManager.Session existing = sessions.find(sessionId).orElse(null);
        if (existing == null) {
            return false;
        }
        if (existing.player(player.getUUID()).map(participant -> participant.state()
                != AiDialogueModels.ParticipantState.OUTSIDE).orElse(false)) {
            return true;
        }
        boolean added = sessions.addOrUpdatePlayer(sessionId, ConversationSessionManager.player(
                player.getGameProfile().getName(), player.getUUID(), AiDialogueModels.ParticipantState.LISTENER));
        if (added) {
            AiDialogueModels.SessionSnapshot snapshot = existing.snapshot(
                    AiDialogueConfig.INSTANCE.settings().transcriptMessages());
            logs.addPlayer(snapshot, ConversationSessionManager.player(player.getGameProfile().getName(),
                    player.getUUID(), AiDialogueModels.ParticipantState.LISTENER));
            logs.append(sessionId, "SYSTEM", "Conversation", player.getGameProfile().getName()
                    + " joined the session as a listener.");
        }
        return added;
    }

    /** Adds or refreshes a divine participant after validating that its definition and persona are available. */
    public boolean addDivineParticipant(UUID sessionId, ResourceLocation godId) {
        ConversationSessionManager.Session existing = sessions.find(sessionId).orElse(null);
        if (existing == null) {
            return false;
        }
        var definition = GodDefinitionManager.INSTANCE.find(godId).orElse(null);
        if (definition == null || !hasPersona(godId)) {
            return false;
        }
        boolean alreadyPresent = existing.participants().stream().anyMatch(participant -> participant.kind()
                == AiDialogueModels.ParticipantKind.DIVINE && godId.equals(participant.godId()));
        if (!alreadyPresent) {
            boolean available = NpcAgentRepository.INSTANCE.find(godId).map(agent -> agent.conversationPolicy()
                    .canJoinSessionCount(sessions.findByDivine(godId).size()))
                    .orElseGet(() -> sessions.findByDivine(godId).isEmpty());
            if (!available) {
                return false;
            }
        }
        return sessions.addOrUpdateDivine(sessionId,
                ConversationSessionManager.divine(godId, definition.displayName().getString()));
    }

    public boolean leave(ServerPlayer player) {
        ConversationSessionManager.LeaveResult result = sessions.leave(player.getUUID());
        if (!result.existed()) {
            return false;
        }
        result.session().ifPresent(session -> {
            logs.append(session.id(), "SYSTEM", "Conversation", player.getGameProfile().getName() + " left the session.");
            if (result.closed()) {
                logs.close(session.id());
            } else {
                dispatchNextQueuedTurn(player.server, session.id());
            }
        });
        player.sendSystemMessage(Component.literal("[AI 대화] 대화를 종료했습니다.").withStyle(ChatFormatting.GRAY));
        return true;
    }

    public boolean isActive(ServerPlayer player) {
        return sessions.findByPlayer(player.getUUID()).flatMap(session -> session.player(player.getUUID()))
                .map(participant -> participant.state() != AiDialogueModels.ParticipantState.OUTSIDE).orElse(false);
    }

    /**
     * Installs the game-owned source of per-turn Quest/Reward constraints and validator feedback. The provider may
     * observe RPG state, but its returned DTO is immutable and cannot grant a quest or reward from the AI layer.
     */
    public void installQuestRewardContextProvider(QuestRewardContextProvider provider) {
        questRewardContextProvider = java.util.Objects.requireNonNull(provider, "provider");
    }

    /**
     * Installs the RPG-owned source of current player-vs-NPC authority (titles, contracts, encounter roles, etc.).
     * No authority is guessed from chat text; absent data remains UNKNOWN and is treated conservatively.
     */
    public void installSocialAuthorityContextProvider(SocialAuthorityContextProvider provider) {
        socialAuthorityContextProvider = java.util.Objects.requireNonNull(provider, "provider");
    }

    public Component status(ServerPlayer player) {
        Optional<ConversationSessionManager.Session> session = sessions.findByPlayer(player.getUUID());
        if (session.isEmpty()) {
            return Component.literal("활성 AI 대화가 없습니다.");
        }
        AiDialogueModels.SessionSnapshot snapshot = session.get().snapshot(AiDialogueConfig.INSTANCE.settings().transcriptMessages());
        long playerCount = snapshot.participants().stream().filter(participant -> participant.kind()
                == AiDialogueModels.ParticipantKind.PLAYER && participant.state() != AiDialogueModels.ParticipantState.OUTSIDE).count();
        long godCount = snapshot.participants().stream().filter(participant -> participant.kind()
                == AiDialogueModels.ParticipantKind.DIVINE).count();
        return Component.literal("AI 대화 " + snapshot.sessionId() + " / 플레이어 " + playerCount + " / 신 " + godCount
                + " / Ollama " + AiDialogueConfig.INSTANCE.settings().ollamaModel()
                + (snapshot.requestInFlight() ? " / 응답 생성 중" : " / 입력 대기 중")
                + (session.get().queuedTurnCount() > 0 ? " / 대기 " + session.get().queuedTurnCount() + "건" : ""));
    }

    public void handlePlayerText(ServerPlayer player, String rawText) {
        Optional<ConversationSessionManager.Session> found = sessions.findByPlayer(player.getUUID());
        if (found.isEmpty()) {
            return;
        }
        ConversationSessionManager.Session session = found.get();
        if (!session.promoteListener(player.getUUID())) {
            return;
        }
        AiDialogueConfig.Settings settings = AiDialogueConfig.INSTANCE.settings();
        String text = boundedText(rawText, settings.maxPromptCharacters());
        if (text.isEmpty()) {
            player.sendSystemMessage(Component.literal("[AI 대화] 빈 메시지는 보낼 수 없습니다.").withStyle(ChatFormatting.RED));
            return;
        }
        ConversationSessionManager.Session.TurnQueueResult queued;
        try {
            queued = session.enqueuePlayerTurn(player.getUUID(), participantId(player.getUUID()),
                    player.getGameProfile().getName(), text);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            player.sendSystemMessage(Component.literal("[AI 대화] 메시지를 대기열에 넣을 수 없습니다: "
                    + exception.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }
        if (queued.requestInFlight() || queued.queuedTurnCount() > 1) {
            player.sendSystemMessage(Component.literal("[AI 대화] 메시지를 대기열에 추가했습니다.")
                    .withStyle(ChatFormatting.YELLOW));
        }
        dispatchNextQueuedTurn(player.server, session.id());
    }

    public void onPlayerLoggedOut(ServerPlayer player) {
        ConversationSessionManager.LeaveResult result = sessions.leave(player.getUUID());
        result.session().ifPresent(session -> {
            if (result.closed()) {
                logs.close(session.id());
            } else {
                dispatchNextQueuedTurn(player.server, session.id());
            }
        });
    }

    public void reload() {
        AiDialogueConfig.INSTANCE.load();
        GodPersonaRepository.INSTANCE.load();
        CharacterTagRegistry.INSTANCE.load();
        NpcCharacterTagRepository.INSTANCE.load();
        CharacterStyleTagMapper.INSTANCE.load();
        ReactionGuidelineRepository.INSTANCE.load();
        JsonVoiceStyleRepository.INSTANCE.load();
        NpcAgentRepository.INSTANCE.load();
        JsonKnowledgeRepository.INSTANCE.load();
        JsonDialogueExampleRepository.INSTANCE.load();
        JsonTagDialogueGuidanceRepository.INSTANCE.load();
    }

    public void stop() {
        for (ConversationSessionManager.Session session : sessions.allSessions()) {
            logs.close(session.id());
            sessions.close(session.id());
        }
        logs.close();
        ollama.close();
    }

    /**
     * Serializes player turns inside one session while allowing the backend scheduler to run other sessions according
     * to its configured concurrency. All Minecraft/session mutation remains on the server thread.
     */
    private void dispatchNextQueuedTurn(MinecraftServer server, UUID sessionId) {
        ConversationSessionManager.Session session = sessions.find(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        ConversationSessionManager.Session.TurnStart turnStart = session.beginNextTurn().orElse(null);
        if (turnStart == null) {
            return;
        }
        ConversationSessionManager.Session.QueuedPlayerTurn queued = turnStart.queuedTurn();
        AiDialogueConfig.Settings settings = AiDialogueConfig.INSTANCE.settings();
        try {
            Set<String> listeners = session.visiblePlayerParticipantIds();
            session.append(new AiDialogueModels.ConversationTurn(UUID.randomUUID(), Instant.now(), queued.speakerId(),
                    queued.text(), List.copyOf(listeners)));
            logs.append(session.id(), "PLAYER", queued.displayName(), queued.text());
            AiDialogueModels.SessionSnapshot sessionSnapshot = session.snapshot(settings.transcriptMessages());
            var gameSnapshot = new MythicTrpgConversationSnapshotProvider(server, questRewardContextProvider,
                    socialAuthorityContextProvider)
                    .capture(sessionSnapshot, queued.speakerId());
            UUID requestId = UUID.randomUUID();
            ConversationTurnContextSnapshot turnSnapshot = new ConversationTurnContextSnapshot(session.id(),
                    turnStart.token().turnId(), turnStart.token().generation(), requestId, queued.queuedAt(), Instant.now(),
                    queued.speakerId(), queued.playerId(), sessionSnapshot, gameSnapshot, session.pendingVouch());
            ServerPlayer triggeringPlayer = server.getPlayerList().getPlayer(queued.playerId());
            if (triggeringPlayer != null) {
                recordRecentPlayerInput(session, triggeringPlayer, queued.text());
            }
            routeThenGenerate(server, sessionId, turnStart.token(), turnSnapshot, settings);
        } catch (Throwable failure) {
            if (session.completeTurn(turnStart.token())) {
                ServerPlayer triggeringPlayer = server.getPlayerList().getPlayer(queued.playerId());
                if (triggeringPlayer != null) {
                    triggeringPlayer.sendSystemMessage(Component.literal("[AI 대화 오류] " + failureMessage(failure))
                            .withStyle(ChatFormatting.RED));
                }
            }
            MythicTrpg.LOGGER.warn("Could not prepare queued AI dialogue turn {} for session {}", turnStart.token().turnId(),
                    sessionId, failure);
            dispatchNextQueuedTurn(server, sessionId);
        }
    }

    /**
     * Hybrid routing is attached to this immutable turn snapshot. No classifier result is kept on a session or NPC
     * singleton, so intent and retrieval context cannot cross sessions.
     */
    private void routeThenGenerate(MinecraftServer server, UUID sessionId,
            ConversationSessionManager.Session.TurnToken turn, ConversationTurnContextSnapshot turnSnapshot,
            AiDialogueConfig.Settings settings) {
        String currentText = turnSnapshot.session().history().isEmpty() ? ""
                : turnSnapshot.session().history().getLast().text();
        if (!settings.hybridIntentRoutingEnabled() || !intentRouter.shouldRoute(currentText)) {
            submitGeneration(server, sessionId, turn, turnSnapshot, settings,
                    intentRouter.heuristicIntent(currentText, turnSnapshot.session()));
            return;
        }
        java.util.Optional<ConversationIntent> fastIntent = intentRouter.tryFastIntent(currentText,
                turnSnapshot.session());
        if (fastIntent.isPresent()) {
            submitGeneration(server, sessionId, turn, turnSnapshot, settings, fastIntent.get());
            return;
        }
        UUID routingRequestId = UUID.randomUUID();
        try {
            ollama.submitIntent(routingRequestId, intentRouter.messages(turnSnapshot.session(),
                    turnSnapshot.triggeringParticipantId()), settings).completion().whenComplete((scheduled, failure) ->
                    server.execute(() -> {
                        ConversationIntent intent = scheduled == null || scheduled.value() == null
                                ? intentRouter.heuristicIntent(currentText, turnSnapshot.session())
                                : intentRouter.mergeStrongHeuristicTones(scheduled.value(), currentText,
                                        turnSnapshot.session());
                        if (failure != null) {
                            MythicTrpg.LOGGER.debug("Intent routing failed for session {} turn {}; using heuristic retrieval",
                                    sessionId, turn.turnId(), failure);
                            intent = intentRouter.heuristicIntent(currentText, turnSnapshot.session());
                        }
                        submitGeneration(server, sessionId, turn, turnSnapshot, settings, intent);
                    }));
        } catch (Throwable failure) {
            MythicTrpg.LOGGER.debug("Could not submit intent routing for session {} turn {}; using heuristic retrieval",
                    sessionId, turn.turnId(), failure);
            submitGeneration(server, sessionId, turn, turnSnapshot, settings,
                    intentRouter.heuristicIntent(currentText, turnSnapshot.session()));
        }
    }

    private void submitGeneration(MinecraftServer server, UUID sessionId,
            ConversationSessionManager.Session.TurnToken turn, ConversationTurnContextSnapshot turnSnapshot,
            AiDialogueConfig.Settings settings, ConversationIntent intent) {
        ConversationSessionManager.Session session = sessions.find(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        try {
            AiDialogueModels.ConversationContext context = contextBuilder.build(turnSnapshot, settings, intent);
            AiConversationDiagnostics.turnPrepared(turnSnapshot, context, settings);
            List<AiDialogueModels.OllamaMessage> messages = contextBuilder.messages(context, settings);
            ollama.submit(turnSnapshot.requestId(), messages, settings).completion().whenComplete((scheduled, failure) ->
                    server.execute(() -> completeResponse(server, sessionId, turn, turnSnapshot, context, scheduled,
                            failure, settings)));
        } catch (Throwable failure) {
            if (session.completeTurn(turn)) {
                ServerPlayer triggeringPlayer = server.getPlayerList().getPlayer(turnSnapshot.triggeringPlayerId());
                if (triggeringPlayer != null) {
                    triggeringPlayer.sendSystemMessage(Component.literal("[AI 대화 오류] " + failureMessage(failure))
                            .withStyle(ChatFormatting.RED));
                }
            }
            MythicTrpg.LOGGER.warn("Could not prepare routed AI dialogue turn {} for session {}", turn.turnId(),
                    sessionId, failure);
            dispatchNextQueuedTurn(server, sessionId);
        }
    }

    private void completeResponse(MinecraftServer server, UUID sessionId, ConversationSessionManager.Session.TurnToken turn,
            ConversationTurnContextSnapshot turnSnapshot, AiDialogueModels.ConversationContext context,
            LocalLlmRequestScheduler.ScheduledResult<AiDialogueModels.StructuredAiResult> scheduled,
            Throwable failure, AiDialogueConfig.Settings settings) {
        ConversationSessionManager.Session session = sessions.find(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        if (!session.completeTurn(turn)) {
            MythicTrpg.LOGGER.debug("Discarded stale AI response for session {} turn {} request {}", sessionId,
                    turn.turnId(), turnSnapshot.requestId());
            dispatchNextQueuedTurn(server, sessionId);
            return;
        }
        try {
            AiConversationDiagnostics.llmFinished(turnSnapshot, scheduled, failure, settings);
            ServerPlayer triggeringPlayer = server.getPlayerList().getPlayer(turnSnapshot.triggeringPlayerId());
            if (failure != null) {
                MythicTrpg.LOGGER.warn("Ollama dialogue request {} for session {} turn {} failed", turnSnapshot.requestId(),
                        sessionId, turn.turnId(), failure);
                if (triggeringPlayer != null) {
                    triggeringPlayer.sendSystemMessage(Component.literal("[AI 대화 오류] " + failureMessage(failure))
                            .withStyle(ChatFormatting.RED));
                }
                return;
            }
            AiDialogueModels.StructuredAiResult response = scheduled == null ? null : scheduled.value();
            if (response == null) {
                notifyEmpty(triggeringPlayer);
                return;
            }
            String topic = boundedText(response.currentTopic(), 160);
            if (!topic.isEmpty()) {
                session.setCurrentTopic(topic);
            }
            DialogueTurnDirective directive = DialogueTurnDirective.plan(turnSnapshot.session(),
                    turnSnapshot.triggeringParticipantId(), context.intent());
            List<ValidatedSpeech> speeches = validateSpeech(session, response.speech(), settings.maxResponseCharacters(),
                    Math.min(settings.maxNpcResponsesPerTurn(), directive.maximumNpcReplies())).stream()
                    .map(speech -> {
                        String text = directive.compactSpeech(speech.text());
                        if (directive.requiresFreshReply() && text.equals(normalize(latestDivineSpeech(session)))) {
                            text = directive.repeatedGreetingFallback();
                        }
                        return new ValidatedSpeech(speech.speakerId(), text, speech.audienceParticipantIds());
                    })
                    .toList();
            if (speeches.isEmpty()) {
                notifyEmpty(triggeringPlayer);
            }
            for (ValidatedSpeech speech : speeches) {
                session.append(new AiDialogueModels.ConversationTurn(UUID.randomUUID(), Instant.now(), speech.speakerId(),
                        speech.text(), List.copyOf(speech.audienceParticipantIds())));
                AiDialogueModels.Participant divine = session.participant(speech.speakerId()).orElseThrow();
                logs.append(session.id(), "DIVINE", divine.displayName(), speech.text());
                recordRecentDivineSpeech(session, divine, speech);
                for (String audienceId : speech.audienceParticipantIds()) {
                    AiDialogueModels.Participant recipient = session.participant(audienceId).orElse(null);
                    if (recipient == null || recipient.playerId() == null) {
                        continue;
                    }
                    ServerPlayer player = server.getPlayerList().getPlayer(recipient.playerId());
                    if (player == null) {
                        continue;
                    }
                    var result = DialoguePresentationService.INSTANCE.sendTo(player,
                            GodDialogueRequest.literal(divine.godId(), speech.text()));
                    if (result.status() != DialogueSendStatus.SENT) {
                        MythicTrpg.LOGGER.warn("Could not display AI dialogue {} to {}: {}", session.id(),
                                player.getGameProfile().getName(), result.status());
                    }
                }
            }
            AiDialogueModels.SessionSnapshot snapshot = session.snapshot(settings.transcriptMessages());
            for (AiDialogueModels.Proposal proposal : response.proposals()) {
                if (!directive.proposalsAllowed()) {
                    logs.appendRejectedProposal(session.id(), proposal,
                            "Turn directive permits no proposals for " + directive.mode());
                    continue;
                }
                if (!validProposal(proposal, context.allowedProposalTypes())) {
                    logs.appendRejectedProposal(session.id(), proposal, "Unknown proposal type or missing title/summary");
                    continue;
                }
                ProposalDecodeResult decoded = structuredProposalDecoder.decode(proposal, snapshot,
                        context.questRewardContext(), turnSnapshot.pendingVouchInteraction(),
                        turnSnapshot.triggeringPlayerId());
                AiConversationDiagnostics.proposalDecoded(turnSnapshot, proposal, decoded, settings);
                if (decoded.status() == ProposalDecodeResult.Status.REJECTED) {
                    MythicTrpg.LOGGER.warn("Rejected unsafe AI proposal {} for session {}: {}", proposal.type(),
                            session.id(), decoded.reason());
                    logs.appendRejectedProposal(session.id(), proposal, decoded.reason());
                    continue;
                }
                if (decoded.proposal().orElse(null) instanceof VouchRequestProposal vouchRequest) {
                    if (!beginPendingVouch(session, vouchRequest, turnSnapshot, settings)) {
                        logs.appendRejectedProposal(session.id(), proposal,
                                "A pending vouch already exists or the participants are no longer active");
                        continue;
                    }
                    AiProposalGateway.ProposalDecision decision = AiProposalGateway.ProposalDecision.accepted(
                            "Stored as AI-owned pending conversation state; no game state was changed");
                    AiConversationDiagnostics.proposalDecision(turnSnapshot, proposal, decision, settings);
                    logs.appendProposal(session.id(), proposal, decision);
                    continue;
                }
                if (decoded.proposal().orElse(null) instanceof VouchResolutionProposal vouchResolution) {
                    if (!session.resolvePendingVouch(vouchResolution)) {
                        logs.appendRejectedProposal(session.id(), proposal, "Pending vouch no longer matches this response");
                        continue;
                    }
                    recordVouchResolution(vouchResolution, turnSnapshot);
                    AiProposalGateway.ProposalDecision decision = AiProposalGateway.ProposalDecision.accepted(
                            vouchResolution.stance() == VouchStance.UNCLEAR
                                    ? "Sponsor reply is unclear; the pending vouch remains open"
                                    : "Recorded as AI-owned vouch conversation history; no game state was changed");
                    AiConversationDiagnostics.proposalDecision(turnSnapshot, proposal, decision, settings);
                    logs.appendProposal(session.id(), proposal, decision);
                    continue;
                }
                if (decoded.proposal().orElse(null) instanceof RequestJudgmentProposal) {
                    AiProposalGateway.ProposalDecision decision = AiProposalGateway.ProposalDecision.accepted(
                            "Recorded as AI-owned request judgment; no game state was changed");
                    AiConversationDiagnostics.proposalDecision(turnSnapshot, proposal, decision, settings);
                    logs.appendProposal(session.id(), proposal, decision);
                    continue;
                }
                try {
                    AiProposalGateway.ProposalDecision decision = AiProposalGateway.INSTANCE.submit(
                            new AiDialogueModels.ProposalEnvelope(session.id(), proposal, snapshot, context,
                                    decoded.proposal()));
                    AiConversationDiagnostics.proposalDecision(turnSnapshot, proposal, decision, settings);
                    logs.appendProposal(session.id(), proposal, decision);
                } catch (RuntimeException exception) {
                    MythicTrpg.LOGGER.error("Game proposal validator failed for {} in session {}; proposal was not applied.",
                            proposal.type(), session.id(), exception);
                    logs.appendRejectedProposal(session.id(), proposal, "Game proposal validator failed safely");
                }
            }
        } catch (Throwable unexpected) {
            MythicTrpg.LOGGER.error("Unexpected AI dialogue completion failure for session {}; turn discarded safely.",
                    sessionId, unexpected);
            ServerPlayer triggeringPlayer = server.getPlayerList().getPlayer(turnSnapshot.triggeringPlayerId());
            if (triggeringPlayer != null) {
                triggeringPlayer.sendSystemMessage(Component.literal("[AI 대화 오류] 응답을 안전하게 처리하지 못했습니다.")
                        .withStyle(ChatFormatting.RED));
            }
        } finally {
            dispatchNextQueuedTurn(server, sessionId);
        }
    }

    private static List<ValidatedSpeech> validateSpeech(ConversationSessionManager.Session session,
            List<AiDialogueModels.Speech> rawSpeech, int maximumCharacters, int maximumResponses) {
        if (rawSpeech == null || rawSpeech.isEmpty()) {
            return List.of();
        }
        Map<String, AiDialogueModels.Participant> divines = new LinkedHashMap<>();
        for (AiDialogueModels.Participant participant : session.participants()) {
            if (participant.kind() == AiDialogueModels.ParticipantKind.DIVINE) {
                divines.put(participant.participantId(), participant);
            }
        }
        Set<String> visibleAudience = session.visiblePlayerParticipantIds();
        List<ValidatedSpeech> accepted = new ArrayList<>();
        for (AiDialogueModels.Speech raw : rawSpeech) {
            if (accepted.size() >= maximumResponses) {
                break;
            }
            if (raw == null) {
                continue;
            }
            String speakerId = raw.speakerId();
            if (speakerId.isBlank() && divines.size() == 1) {
                speakerId = divines.keySet().iterator().next();
            }
            if (!divines.containsKey(speakerId)) {
                continue;
            }
            String text = boundedText(raw.text(), maximumCharacters);
            if (text.isEmpty()) {
                continue;
            }
            LinkedHashSet<String> audience = new LinkedHashSet<>();
            for (String requested : raw.audienceParticipantIds()) {
                if (visibleAudience.contains(requested)) {
                    audience.add(requested);
                }
            }
            if (audience.isEmpty()) {
                audience.addAll(visibleAudience);
            }
            accepted.add(new ValidatedSpeech(speakerId, text, Set.copyOf(audience)));
        }
        return List.copyOf(accepted);
    }

    private static String latestDivineSpeech(ConversationSessionManager.Session session) {
        List<AiDialogueModels.ConversationTurn> history = session.snapshot(Integer.MAX_VALUE).history();
        for (int index = history.size() - 1; index >= 0; index--) {
            AiDialogueModels.ConversationTurn turn = history.get(index);
            AiDialogueModels.Participant participant = session.participant(turn.speakerId()).orElse(null);
            if (participant != null && participant.kind() == AiDialogueModels.ParticipantKind.DIVINE) {
                return turn.text();
            }
        }
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static void recordRecentPlayerInput(ConversationSessionManager.Session session, ServerPlayer player,
            String text) {
        for (AiDialogueModels.Participant participant : session.participants()) {
            if (participant.kind() == AiDialogueModels.ParticipantKind.DIVINE && participant.godId() != null) {
                NpcMemoryEngine.INSTANCE.recordRecent(participant.godId().toString(), player.getUUID().toString(),
                        "Player said: " + text, List.of("conversation"));
            }
        }
    }

    private static void recordRecentDivineSpeech(ConversationSessionManager.Session session,
            AiDialogueModels.Participant divine, ValidatedSpeech speech) {
        if (divine.godId() == null) {
            return;
        }
        for (String audienceId : speech.audienceParticipantIds()) {
            AiDialogueModels.Participant audience = session.participant(audienceId).orElse(null);
            if (audience != null && audience.playerId() != null) {
                NpcMemoryEngine.INSTANCE.recordRecent(divine.godId().toString(), audience.playerId().toString(),
                        "NPC replied: " + speech.text(), List.of("conversation"));
            }
        }
    }

    private static boolean beginPendingVouch(ConversationSessionManager.Session session,
            VouchRequestProposal request, ConversationTurnContextSnapshot turnSnapshot, AiDialogueConfig.Settings settings) {
        String originalRequest = latestTextFrom(turnSnapshot.session(), turnSnapshot.triggeringParticipantId());
        Instant now = Instant.now();
        return session.beginPendingVouch(new PendingVouchInteraction(request.npcId(), request.sponsorPlayerId(),
                request.beneficiaryPlayerId(), originalRequest, request.reason(), now,
                now.plusSeconds(settings.vouchRequestTimeoutSeconds())));
    }

    /**
     * Vouch history is AI-owned narrative memory only. Relationship changes remain separate proposals sent through
     * the game validator, so a sponsor can never directly raise or lower an authoritative relationship value.
     */
    private static void recordVouchResolution(VouchResolutionProposal resolution,
            ConversationTurnContextSnapshot turnSnapshot) {
        if (resolution.stance() == VouchStance.UNCLEAR) {
            return;
        }
        String stance = resolution.stance().name().toLowerCase(java.util.Locale.ROOT);
        String summary = "Vouch " + stance + ": " + resolution.sponsorPlayerId() + " spoke for "
                + resolution.beneficiaryPlayerId() + ". " + resolution.reason();
        NpcMemoryEngine.INSTANCE.rememberLongTerm(new NpcMemory(UUID.randomUUID(), resolution.npcId().toString(),
                resolution.sponsorPlayerId().toString(), MemoryType.LONG_TERM, 0.55D, summary,
                List.of("vouch", "vouch_" + stance), Instant.now()));
        NpcMemoryEngine.INSTANCE.rememberLongTerm(new NpcMemory(UUID.randomUUID(), resolution.npcId().toString(),
                resolution.beneficiaryPlayerId().toString(), MemoryType.LONG_TERM, 0.55D, summary,
                List.of("vouch", "vouch_" + stance), Instant.now()));
    }

    private static String latestTextFrom(AiDialogueModels.SessionSnapshot snapshot, String participantId) {
        for (int index = snapshot.history().size() - 1; index >= 0; index--) {
            AiDialogueModels.ConversationTurn turn = snapshot.history().get(index);
            if (participantId.equals(turn.speakerId())) {
                return turn.text();
            }
        }
        return "The player made a request.";
    }

    private static boolean validProposal(AiDialogueModels.Proposal proposal, List<String> allowedTypes) {
        if (proposal == null || !allowedTypes.contains(proposal.type())
                || !safeProposalText(proposal.title(), 120) || !safeProposalText(proposal.summary(), 600)
                || proposal.targetParticipantIds().size() > 8 || proposal.parameters().size() > 16) {
            return false;
        }
        for (String target : proposal.targetParticipantIds()) {
            if (!safeProposalText(target, 120)) {
                return false;
            }
        }
        for (Map.Entry<String, String> parameter : proposal.parameters().entrySet()) {
            if (parameter.getKey() == null || !parameter.getKey().matches("[A-Za-z][A-Za-z0-9]*")
                    || !safeParameterValue(parameter.getValue(), 360)) {
                return false;
            }
        }
        return true;
    }

    private static boolean safeProposalText(String value, int maximumCodePoints) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maximumCodePoints) {
            return false;
        }
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if (Character.isISOControl(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean safeParameterValue(String value, int maximumCodePoints) {
        if (value == null || value.codePointCount(0, value.length()) > maximumCodePoints) {
            return false;
        }
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if (Character.isISOControl(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private void detachForNewSession(ServerPlayer player) {
        ConversationSessionManager.LeaveResult previous = sessions.leave(player.getUUID());
        previous.session().ifPresent(session -> {
            if (previous.closed()) {
                logs.close(session.id());
            } else {
                dispatchNextQueuedTurn(player.server, session.id());
            }
        });
    }

    private static String participantId(UUID playerId) {
        return "player:" + playerId;
    }

    private static boolean hasPersona(ResourceLocation godId) {
        ResourceLocation personaId = NpcAgentRepository.INSTANCE.find(godId).map(agent -> agent.personaId())
                .orElse(godId);
        return GodPersonaRepository.INSTANCE.find(personaId).isPresent();
    }

    private static void notifyEmpty(ServerPlayer player) {
        if (player != null) {
            player.sendSystemMessage(Component.literal("[AI 대화] AI가 응답을 반환하지 않았습니다.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    private static String boundedText(String raw, int maximumCodePoints) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.replace('\u0000', ' ').replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").trim();
        int codePoints = normalized.codePointCount(0, normalized.length());
        return codePoints <= maximumCodePoints ? normalized
                : normalized.substring(0, normalized.offsetByCodePoints(0, maximumCodePoints));
    }

    private static String failureMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public enum StartResult {
        STARTED,
        UNKNOWN_GOD,
        PERSONA_MISSING,
        NPC_BUSY
    }

    public enum StartConversationStatus {
        STARTED,
        UNKNOWN_GOD,
        PERSONA_MISSING,
        NPC_BUSY,
        INVALID_PARTICIPANTS
    }

    public record StartConversationResult(StartConversationStatus status, UUID sessionId) {
        public static StartConversationResult started(UUID sessionId) {
            return new StartConversationResult(StartConversationStatus.STARTED, sessionId);
        }

        public static StartConversationResult rejected(StartConversationStatus status) {
            return new StartConversationResult(status, null);
        }
    }

    private record ValidatedSpeech(String speakerId, String text, Set<String> audienceParticipantIds) {
    }
}
