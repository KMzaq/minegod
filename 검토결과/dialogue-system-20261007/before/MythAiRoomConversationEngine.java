package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.sande.mythai.response.memory.DialogueMemoryBridge;
import com.sande.mythai.response.memory.RoomMemoryBridge;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.godavatar.activity.ActivityRoomExperience;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Room transport with advisory classification and persona-grounded generation. The game owns every visible effect. */
public final class MythAiRoomConversationEngine implements RoomConversationEngine {
    public static final MythAiRoomConversationEngine INSTANCE = new MythAiRoomConversationEngine();
    private static final Gson JSON = new Gson();
    private static final Set<String> CONTROLS = Set.of("conversation_leave", "conversation_end", "conversation_invite");
    private LocalLlmClient llm;
    private final AiTestContentRegistryBridge registry = new AiTestContentRegistryBridge();
    private final RoomResponseTokens tokens = new RoomResponseTokens();
    private final Map<UUID, Delivery> deliveries = new HashMap<>();
    private final Map<UUID, List<RoomDialogueEvent>> identityPublications = new HashMap<>();
    private final Map<UUID, StoryConversationContextBridge> stories = new HashMap<>();
    private final Map<UUID, Evidence> evidence = new HashMap<>();
    private final Map<String, UUID> splitTokens = new HashMap<>();
    private final Map<ActivityKey, String> activities = new HashMap<>();
    private final RoomEmotionState emotions = new RoomEmotionState();
    private final RoomDialogueLog logs = new RoomDialogueLog();
    private record ActivityKey(UUID room, long revision, ResourceLocation god) { }
    private record Delivery(Request request, Result result, DialogueMemoryBridge.Turn memory) { }
    private record Evidence(Request request, List<RoomEvidenceReference> references, Set<UUID> sources) { }
    private record Memories(DialogueMemoryBridge.Turn legacy, RoomMemoryBridge.Recall heard) { }
    private MythAiRoomConversationEngine() {
        RoomMemoryBridge.installEvidenceValidator((server,request,ref) -> {
            if (ActivityRoomExperience.KIND.equals(ref.kind()))
                return ActivityRoomExperience.current(server,request,ref);
            if (RoomKnowledgeContext.EVIDENCE_KIND.equals(ref.kind()))
                return RoomKnowledgeContext.validEvidence(ref,request.publicRoom(),request.godIds(),request.audiencePlayerIds());
            if (RoomQuestKnowledge.EVIDENCE_KIND.equals(ref.kind()))
                return RoomQuestKnowledge.validEvidence(ref,request.publicRoom(),request.godIds(),request.audiencePlayerIds());
            return com.sande.mythictrpg.story.presentation.StoryRoomConversationService.INSTANCE.evidenceCurrent(server,request,ref);
        });
    }
    private LocalLlmClient client() { if (llm == null) llm = new LocalOllamaClient(); return llm; }
    @Override public CompletableFuture<Boolean> prepareRecordedEvidence(Request request, List<RoomEvidenceReference> references) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return CompletableFuture.completedFuture(false);
        var result = new CompletableFuture<Boolean>();
        RoomMemoryBridge.prepareEvidence(server, request, references)
                .whenComplete((ready, failure) -> server.execute(() -> result.complete(failure == null && Boolean.TRUE.equals(ready)
                        && recordedEvidenceCurrent(request, references))));
        return result;
    }
    @Override public boolean recordedEvidenceCurrent(Request request, List<RoomEvidenceReference> references) {
        var server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isSameThread() && ConversationRooms.INSTANCE.memoryReadCurrent(server, request)
                && RoomMemoryBridge.evidenceCurrent(server, request, references, Set.of());
    }
    String visitEmotion(com.sande.mythictrpg.godavatar.visit.GodVisitPlanner.Request request) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("Visit emotion snapshot requires game thread");
        return emotions.visitHint(request);
    }

    @Override public CompletableFuture<Result> respond(Request request) {
        var result = new CompletableFuture<Result>();
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return CompletableFuture.completedFuture(Result.failed(request, "NO_GAME_THREAD"));
        if (com.sande.mythictrpg.recording.server.RecordingRuntime.retrievalForegroundBlocked(server))
            return CompletableFuture.completedFuture(Result.failed(request,
                    com.sande.mythictrpg.recording.server.RecordingRuntime.retrievalState(server)));
        var player = server.getPlayerList().getPlayer(request.playerId());
        if (player == null || !ConversationRooms.INSTANCE.isCurrent(request.roomId(), request.revision()))
            return CompletableFuture.completedFuture(Result.failed(request, "STALE_ROOM"));
        var token = tokens.issue(request.roomId(), request.revision(), request.turnId());
        emotions.begin(request);
        deliveries.remove(request.roomId());
        identityPublications.remove(request.roomId());
        evidence.remove(request.roomId());
        try {
            var story = StoryConversationContextBridge.capture(player, request);
            stories.put(request.roomId(), story);
            var candidates = request.secondary() ? List.<AiQuestContentBridge.QuestCandidate>of()
                    : AiQuestContentBridge.candidatesFor(request,player);
            // Durable refs are scoped to each turn. Warm them before pruning so a cold cache does not
            // erase otherwise permitted history; failed preparation still goes through strict current checks.
            RoomMemoryBridge.prepareHistoryEvidence(server, request).whenComplete((historyReady, historyFailure) -> server.execute(() -> {
                if (!current(player, token)) { result.complete(Result.failed(request, "STALE_ROOM")); return; }
                try {
                    var safe = enrichContext(pruneHistory(request,player), story, candidates);
                    DialogueMemoryBridge.beginRoomAsync(player, safe, token.sequence())
                            .thenCombine(RoomMemoryBridge.recall(player,safe),Memories::new).whenComplete((prepared, failure) -> server.execute(() -> {
                        if (!current(player, token)) { result.complete(Result.failed(request, "STALE_ROOM")); return; }
                        var memory = prepared == null ? null : prepared.legacy();
                        // Story disclosure dependencies are portable references below, not session-bound closures.
                        if (failure != null || memory == null || !DialogueMemoryBridge.roomTurnCurrent(player, memory)) {
                            result.complete(Result.failed(request, "MEMORY_UNAVAILABLE_OR_STALE")); return;
                        }
                        com.sande.mythai.response.memory.RecordedRetrievalShadow.compare(server, safe, prepared.heard(),
                                memory.observations().ids(), memory.rumors().stream()
                                .map(com.sande.mythictrpg.rumor.RumorLedger.HeardRumor::rootId).collect(java.util.stream.Collectors.toUnmodifiableSet()));
                        if (!request.secondary() && !memory.rumors().isEmpty()) try {
                            // Selected game-owned roots belong to this exact room turn; review remains asynchronous/game-owned.
                            com.sande.mythictrpg.rumor.SocialRuntime.roomRecoveryTopics(player,safe,
                                    memory.rumors().stream().map(com.sande.mythictrpg.rumor.RumorLedger.HeardRumor::rootId).toList());
                        } catch (RuntimeException unavailable) {
                            com.sande.mythictrpg.MythicTrpg.LOGGER.warn("Room social recovery selection unavailable; dialogue continues",unavailable);
                        }
                        try {
                            var content = RoomKnowledgeContext.load(safe);
                            var activityExperience = ActivityRoomExperience.capture(server, safe);
                            var refs = new ArrayList<RoomEvidenceReference>(story.evidenceReferences());
                            activityExperience.ifPresent(snapshot -> refs.addAll(snapshot.references()));
                            refs.add(RoomKnowledgeContext.evidence(safe,content));
                            var staticRelations = new LinkedHashMap<String,List<String>>();
                            for (var target : safe.godIds()) if (!target.equals(safe.speakerGodId())) {
                                var tags = RoomKnowledgeContext.directionalRelationTags(safe,target);
                                staticRelations.put(safe.speakerGodId()+" -> "+target,tags);
                                var pairContent = new AiTestContentRegistryBridge.ContentSnapshot(content.profile(),content.lore(),content.examples(),
                                        content.relationshipGuidance(),tags,content.generation());
                                refs.add(RoomKnowledgeContext.evidence(safe.speakerGodId(),safe.speakerState().relationshipTier(),
                                        pairContent,List.of(safe.speakerGodId(),target)));
                            }
                            candidates.stream().flatMap(candidate -> candidate.evidenceReferences().stream()).forEach(refs::add);
                            refs.addAll(RoomMemoryBridge.legacyEvidence(player,memory));
                            refs.addAll(com.sande.mythictrpg.ai.experiencecontract.ExperienceRoomEvidence.references(
                                    server,safe,memory.experience(),memory.observations().ids()));
                            var sources = new LinkedHashSet<UUID>(prepared.heard().sourceMessageIds());
                            safe.history().stream().map(HistoryLine::messageId).filter(Objects::nonNull).forEach(sources::add);
                            evidence.put(request.roomId(),new Evidence(safe,List.copyOf(refs),Set.copyOf(sources)));
                            if (!currentEvidence(player,request.roomId())) { result.complete(Result.failed(request,"EVIDENCE_REVOKED"));return; }
                            var promptRequest = safe;
                            if (activityExperience.isPresent()) promptRequest = appendOwnContext(promptRequest,
                                    NpcActivityPrompt.experience(safe, activityExperience.orElseThrow().view()));
                            var emotionContext = emotions.context(safe);
                            if (!emotionContext.isBlank()) promptRequest = appendOwnContext(promptRequest, emotionContext);
                            if (!staticRelations.isEmpty()) promptRequest = appendOwnContext(promptRequest,
                                    "\n[STATIC_DIRECTIONAL_GOD_RELATIONS]\nAuthored background from this speaker to each target. "
                                    +"Do not swap directions, attribute one target's tags to another, or invent historical causes. "
                                    +"CURRENT_GOD_ATTITUDES separately describes the current game-owned state.\n"+JSON.toJson(staticRelations));
                            if (request.secondary()) {
                                react(player, request, promptRequest, token, content, memory, prepared.heard().promptVariants(), result);
                                return;
                            }
                            var activityKey = new ActivityKey(request.roomId(), request.revision(), request.speakerGodId());
                            var prompt = new AiTestDialogueAdapter.RoomPrompt(promptRequest, memory, Map.of(request.speakerGodId(), content),
                                    activities.getOrDefault(activityKey, ""), prepared.heard().promptVariants());
                            activities.put(activityKey, prompt.activity());
                            classify(player, request, token, content, prompt, memory, result);
                        } catch (RuntimeException invalid) { result.complete(promptFailure(player, request, invalid)); }
                    }));
                } catch (RuntimeException unavailable) { result.complete(Result.failed(request, "CONTENT_OR_MEMORY_UNAVAILABLE")); }
            }));
        } catch (RuntimeException failure) { result.complete(Result.failed(request, "CONTENT_OR_MEMORY_UNAVAILABLE")); }
        return result;
    }

    private void react(ServerPlayer player, Request request, Request safe, RoomResponseTokens.Token token,
            AiTestContentRegistryBridge.ContentSnapshot content, DialogueMemoryBridge.Turn memory, List<String> heardMemory,
            CompletableFuture<Result> result) {
        if (safe.currentText().isBlank()) {
            result.complete(new Result(request.roomId(), request.revision(), request.turnId(), List.of(), "[]", List.of(), ""));
            return;
        }
        var messages = RoomReactionPrompt.messages(safe, content, memory, MinecraftCommonKnowledge.select(safe), heardMemory);
        log(player, request, "SECONDARY_INPUT", JSON.toJson(messages));
        client().submit(UUID.randomUUID(), messages, AiDialogueConfig.INSTANCE.settings()).completion()
                .whenComplete((generated, failure) -> player.server.execute(() -> afterEvidenceRefresh(player, request, token, result, () -> {
                    if (!current(player, token) || !DialogueMemoryBridge.roomTurnCurrent(player, memory)) {
                        result.complete(Result.failed(request, "STALE_ROOM_OR_MEMORY")); return;
                    }
                    try {
                        if (failure != null || generated == null || generated.value() == null
                                || RoomKnowledgeContext.load(request).generation() != content.generation()) {
                            result.complete(Result.failed(request, "SECONDARY_UNAVAILABLE")); return;
                        }
                        var speech = RoomReactionPrompt.speech(request, generated.value());
                        var proposals = request.readOnly() ? List.<AiDialogueModels.Proposal>of()
                                : normalizeProposals(player,request,generated.value().proposals());
                        var reply = reply(request,speech,proposals,List.of(),generated.value().proposals());
                        log(player, request, "SECONDARY_RESULT", JSON.toJson(generated.value()));
                        deliveries.put(request.roomId(), new Delivery(request, reply, memory));
                        emotions.stage(request, reply, generated.value().currentEmotion());
                        result.complete(reply);
                    } catch (RuntimeException invalid) { result.complete(Result.failed(request, "SECONDARY_REJECTED")); }
                })));
    }

    private void classify(ServerPlayer player, Request request, RoomResponseTokens.Token token,
            AiTestContentRegistryBridge.ContentSnapshot content, AiTestDialogueAdapter.RoomPrompt prompt,
            DialogueMemoryBridge.Turn memory, CompletableFuture<Result> result) {
        try {
            var fast = prompt.fastIntent();
            if (fast != null) {
                log(player, request, "CLASSIFICATION_POLICY", "clear social turn; existing heuristic route");
                generate(player, request, token, content, prompt, memory, prompt.generation(fast), false, result); return;
            }
            log(player, request, "CLASSIFICATION_INPUT", JSON.toJson(prompt.classificationMessages()));
            client().submitIntent(UUID.randomUUID(), prompt.classificationMessages(), AiDialogueConfig.INSTANCE.settings())
                    .completion().whenComplete((classified, failure) -> player.server.execute(() -> afterEvidenceRefresh(player, request, token, result, () -> {
                        if (!current(player, token)) { result.complete(Result.failed(request, "STALE_ROOM")); return; }
                        var intent = failure == null && classified != null && classified.value() != null
                                ? classified.value() : ConversationIntent.heuristicFallback();
                        log(player, request, "CLASSIFICATION_RESULT", JSON.toJson(intent));
                        try { generate(player, request, token, content, prompt, memory, prompt.generation(intent), false, result); }
                        catch (RuntimeException invalid) { result.complete(promptFailure(player, request, invalid)); }
                    })));
        } catch (RuntimeException unavailable) {
            try { generate(player, request, token, content, prompt, memory,
                    prompt.generation(ConversationIntent.heuristicFallback()), false, result); }
            catch (RuntimeException invalid) { result.complete(promptFailure(player, request, invalid)); }
        }
    }

    private Result promptFailure(ServerPlayer player, Request request, RuntimeException failure) {
        String reason = failure instanceof RoomPromptBudgetException ? "PROMPT_BUDGET_REQUIRED_CONTEXT" : "PROMPT_REJECTED";
        // Only the dedicated exception carries a safe size diagnostic; never log arbitrary exception messages/context.
        log(player, request, "PROMPT_REJECTED", reason + (failure instanceof RoomPromptBudgetException ? "; " + failure.getMessage() : ""));
        return Result.failed(request, reason);
    }

    private void generate(ServerPlayer player, Request request, RoomResponseTokens.Token token,
            AiTestContentRegistryBridge.ContentSnapshot content, AiTestDialogueAdapter.RoomPrompt prompt,
            DialogueMemoryBridge.Turn memory, List<AiDialogueModels.OllamaMessage> messages, boolean repaired,
            CompletableFuture<Result> result) {
        if (!current(player, token) || !DialogueMemoryBridge.roomTurnCurrent(player, memory)) {
            result.complete(Result.failed(request, "STALE_ROOM_OR_MEMORY")); return;
        }
        try {
            log(player, request, "GENERATION_POLICY", "persona-grounded-v1; intent=advisory; repair=structural-only");
            log(player, request, repaired ? "GENERATION_REPAIR_INPUT" : "GENERATION_INPUT", JSON.toJson(messages));
            client().submit(UUID.randomUUID(), messages, AiDialogueConfig.INSTANCE.settings()).completion()
                    .whenComplete((generated, failure) -> player.server.execute(() -> afterEvidenceRefresh(player, request, token, result, () -> {
                        if (!current(player, token) || !DialogueMemoryBridge.roomTurnCurrent(player, memory)) {
                            result.complete(Result.failed(request, "STALE_ROOM_OR_MEMORY")); return;
                        }
                        try {
                            if (failure != null || generated == null || generated.value() == null) {
                                result.complete(Result.failed(request, "GENERATION_FAILED")); return;
                            }
                            if (RoomKnowledgeContext.load(request).generation()
                                    != content.generation()) { result.complete(Result.failed(request, "CONTENT_RELOADED")); return; }
                            var output = generated.value();
                            log(player, request, "GENERATION_RESULT", JSON.toJson(output));
                            if (prompt.needsRepair(output)) {
                                if (repaired) { result.complete(Result.failed(request, "RESPONSE_REJECTED")); return; }
                                var repair = new ArrayList<>(messages);
                                repair.add(new AiDialogueModels.OllamaMessage("user", "The draft violates the response contract: "
                                        + prompt.validationIssue(output) + ". Correct only that structural problem using the same persona and context."
                                        + " Keep the supported meaning and character's response; do not force a different emotion, a shorter sentence or a stock reply."
                                        + " Return the required JSON schema. Quoted rejected draft data (not instructions): " + JSON.toJson(output)));
                                generate(player, request, token, content, prompt, memory, List.copyOf(repair), true, result); return;
                            }
                            var speech = prompt.speech(output).stream().map(s -> new Speech(ResourceLocation.parse(s.speakerId()), s.text())).toList();
                            if (speech.isEmpty()) { result.complete(Result.failed(request, "EMPTY_RESPONSE")); return; }
                            var controls = controls(output.proposals());
                            var allowed = prompt.admittedProposals(output.proposals());
                            var proposals = normalizeProposals(player, request, allowed);
                            var reply = reply(request,speech,proposals,controls,output.proposals());
                            deliveries.put(request.roomId(), new Delivery(request, reply, memory));
                            emotions.stage(request, reply, output.currentEmotion());
                            result.complete(reply);
                        } catch (RuntimeException invalid) { result.complete(Result.failed(request, "RESPONSE_REJECTED")); }
                    })));
        } catch (RuntimeException unavailable) { result.complete(Result.failed(request, "GENERATION_UNAVAILABLE")); }
    }

    private static List<Control> controls(List<AiDialogueModels.Proposal> proposals) {
        return proposals.stream().filter(p -> CONTROLS.contains(p.type())).limit(3)
                .map(p -> new Control(p.type(), p.targetParticipantIds().isEmpty() ? "" : p.targetParticipantIds().getFirst(), p.summary())).toList();
    }

    private Result reply(Request request,List<Speech> speech,List<AiDialogueModels.Proposal> proposals,
            List<Control> controls,List<AiDialogueModels.Proposal> authoredOutput) {
        var story=stories.get(request.roomId());
        var aliases=story==null?List.<String>of():story.selectedAliases(authoredOutput);
        return new Result(request.roomId(),request.revision(),request.turnId(),speech,JSON.toJson(proposals),controls,"",
                aliases.isEmpty()?Optional.empty():story.contextId(),aliases);
    }

    private List<AiDialogueModels.Proposal> normalizeProposals(ServerPlayer player, Request request,
            List<AiDialogueModels.Proposal> proposals) {
        var candidates = request.secondary() ? List.<AiQuestContentBridge.QuestCandidate>of()
                : AiQuestContentBridge.candidatesFor(request,player);
        var ids = candidates.stream().map(c -> c.questId().toString()).collect(java.util.stream.Collectors.toSet());
        var normalized = new ArrayList<AiDialogueModels.Proposal>();
        for (var proposal : proposals.stream().limit(8).toList()) {
            if (CONTROLS.contains(proposal.type()) || "story_disclose".equals(proposal.type())) continue;
            if ("story_event_hook".equals(proposal.type())) {
                var story = stories.get(request.roomId());
                var supported = story == null ? null : story.normalize(player, request, proposal);
                if (supported != null) normalized.add(supported);
                continue;
            }
            if (request.secondary() || request.readOnly()) continue;
            if ("quest_offer".equals(proposal.type())) {
                String quest = proposal.parameters().getOrDefault("quest_id", "");
                if (ids.contains(quest)) normalized.add(proposal);
                continue;
            }
            var supported = AiActionCapabilityBridge.normalize(proposal, request.speakerGodId(), request.currentText(), request.godIds());
            if (supported != null) normalized.add(supported);
        }
        return List.copyOf(normalized);
    }

    @Override public void delivered(Request request, Result result) {
        var delivery = deliveries.get(request.roomId());
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread() || delivery == null || !delivery.request().equals(request)
                || !delivery.result().equals(result) || !ConversationRooms.INSTANCE.isCurrent(request.roomId(), request.revision())) return;
        var player = server.getPlayerList().getPlayer(request.playerId());
        if (player == null) return;
        deliveries.remove(request.roomId());
        emotions.delivered(request, result);
        // Modern publications already carry portable source dependencies and exact hearing receipts.
        // Do not duplicate them into the session-closure journal used by the legacy transport.
        // Identification can synchronously advance Story state. Register the whole delivered
        // bundle's provenance first, then permit those events to invalidate its evidence.
        var published = identityPublications.remove(request.roomId());
        if (published != null) for (var event : published) commitPublishedIdentity(server, event);
    }

    private boolean current(ServerPlayer player, RoomResponseTokens.Token token) {
        return scopeCurrent(player, token) && currentEvidence(player,token.room());
    }

    private boolean scopeCurrent(ServerPlayer player, RoomResponseTokens.Token token) {
        return player.server.getPlayerList().getPlayer(player.getUUID()) == player && tokens.current(token)
                && ConversationRooms.INSTANCE.isCurrent(token.room(), token.revision())
                && stories.containsKey(token.room()) && stories.get(token.room()).current(player);
    }

    /** A model can legitimately outlive a short read lease. Revalidate original proof, never extend it blindly. */
    private void afterEvidenceRefresh(ServerPlayer player, Request request, RoomResponseTokens.Token token,
            CompletableFuture<Result> result, Runnable apply) {
        if (result.isDone()) return;
        var expected = evidence.get(token.room());
        com.sande.mythai.response.memory.RoomEvidenceRefresh.ensure(
                // An ordinary recording-OFF reply is still valid. Storage re-prepare below separately
                // requires actual memoryReadCurrent; do not impose that read grant on the fast path.
                () -> !result.isDone() && scopeCurrent(player, token) && evidence.get(token.room()) == expected,
                () -> currentEvidence(player, token.room()),
                () -> expected == null ? CompletableFuture.completedFuture(false)
                        : RoomMemoryBridge.preparePublicationEvidence(player.server, expected.request(), expected.references(), expected.sources()),
                player.server::execute).whenComplete((ready, failure) -> {
                    try { player.server.execute(() -> {
                    if (result.isDone()) return;
                    if (failure != null || !Boolean.TRUE.equals(ready) || evidence.get(token.room()) != expected
                            || !current(player, token)) {
                        result.complete(Result.failed(request, "STALE_ROOM_OR_EVIDENCE")); return;
                    }
                    try { apply.run(); }
                    catch (RuntimeException invalid) { result.complete(Result.failed(request, "RESPONSE_REJECTED")); }
                    }); } catch (RuntimeException stopped) { result.complete(Result.failed(request, "EVIDENCE_DISPATCH_UNAVAILABLE")); }
                });
    }

    private boolean currentEvidence(ServerPlayer player,UUID room) {
        var proof=evidence.get(room);
        return proof==null || RoomMemoryBridge.evidenceCurrent(player.server,proof.request(),proof.references(),proof.sources());
    }

    @Override public RoomDialogueEvent preparePublication(RoomDialogueEvent event) {
        var delivery=deliveries.get(event.roomId());
        if(delivery==null||!publicationMatches(delivery.request(),delivery.result(),event))return event;
        var server=ServerLifecycleHooks.getCurrentServer();
        var proof=evidence.get(event.roomId());
        if(server==null||!server.isSameThread()||proof==null
                ||!RoomMemoryBridge.evidenceCurrent(server,proof.request(),proof.references(),proof.sources()))
            throw new IllegalStateException("Publication evidence revoked");
        var refs=new LinkedHashSet<RoomEvidenceReference>(event.evidenceRefs());refs.addAll(proof.references());
        var sources=new LinkedHashSet<UUID>(event.sourceMessageIds());sources.addAll(proof.sources());
        return event.withEvidence(List.copyOf(refs),sources);
    }

    @Override public void dialogueObserved(RoomDialogueEvent event) {
        var server=ServerLifecycleHooks.getCurrentServer();
        if(server!=null&&server.isSameThread()) {
            RoomMemoryBridge.observed(server,event);
            if(ConversationRooms.INSTANCE.isCurrent(event.roomId(),event.revision()))emotions.observed(event);
        }
    }

    private void log(ServerPlayer player, Request request, String kind, String text) {
        if (!request.recording()) return;
        logs.append(player.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("mythictrpg-ai-room-logs"),
                request.roomId(), request.playerId(), request.turnId(), true, kind, text).exceptionally(failure -> {
                    com.sande.mythictrpg.MythicTrpg.LOGGER.warn("Room dialogue log write failed for {}", request.roomId(), failure); return false;
                });
    }

    @Override public void dialoguePublished(RoomDialogueEvent event) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread() || !event.recordingScope().recordingAllowed()
                || !ConversationRooms.INSTANCE.isCurrent(event.roomId(), event.revision())) return;
        // The game already froze actual recipients; never rediscover an audience after dispatch.
        DialogueMemoryBridge.roomPublished(event);
        RoomMemoryBridge.published(server,event);
        var delivery = deliveries.get(event.roomId());
        if (delivery != null && publicationMatches(delivery.request(), delivery.result(), event)) {
            var batch = identityPublications.computeIfAbsent(event.roomId(), ignored -> new ArrayList<>());
            if (batch.size() < 8 && batch.stream().noneMatch(previous -> previous.messageId().equals(event.messageId()))) batch.add(event);
        } else if (event.turnId().isEmpty()) {
            // Initial authored encounter speech has no generated-memory provenance to register.
            commitPublishedIdentity(server, event);
        }
        logs.published(server.getServerDirectory(), event).exceptionally(failure -> {
            com.sande.mythictrpg.MythicTrpg.LOGGER.warn("Room transcript write failed for message {} in {}",
                    event.messageId(), event.roomId(), failure); return false;
        });
    }

    static boolean publicationMatches(Request request, Result result, RoomDialogueEvent event) {
        return "NPC".equals(event.role()) && request.roomId().equals(event.roomId())
                && request.revision() == event.revision() && event.turnId().filter(request.turnId()::equals).isPresent()
                && request.speakerGodId().toString().equals(event.speakerId())
                && result.speech().stream().anyMatch(s -> s.godId().equals(request.speakerGodId()) && s.text().equals(event.text()));
    }

    private void commitPublishedIdentity(MinecraftServer server, RoomDialogueEvent event) {
        if ("NPC".equals(event.role()) && !event.recordingScope().isTest()
                && com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                    != com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.RUMOR_TEST) {
            try {
                var god = ResourceLocation.parse(event.speakerId());
                var content = registry.load(god, "R_NEUTRAL", event.godIds().stream().map(ResourceLocation::parse).toList());
                for (var id : event.deliveries().keySet()) {
                    var recipient = server.getPlayerList().getPlayer(id);
                    if (recipient != null && com.sande.mythictrpg.ai.room.RoomTurnPolicy.fullyDispatched(event, id))
                        SpokenIdentityReveal.commitIfNameWasSpoken(recipient, god, content.profile().displayName(), event.text());
                }
            } catch (RuntimeException unavailable) { /* Optional identity detection must not prevent transcript recording. */ }
        }
    }

    private static Request pruneHistory(Request request,ServerPlayer player) {
        var permittedSources = RoomMemoryBridge.currentSources(player.server,request,request.history().stream()
                .map(HistoryLine::messageId).filter(Objects::nonNull).toList());
        return pruneHistory(request,permittedSources);
    }
    /** Pure projection after the game-thread evidence batch; never obtains permissions by itself. */
    static Request pruneHistory(Request request,Set<UUID> permittedSources) {
        var visible = RoomHistorySources.filter(request.roomId(), request.history(),
                (source, text) -> !DialogueMemoryBridge.excludedRoomHistory(source, List.of(text)).contains(text))
                .stream().filter(line -> line.messageId()==null || permittedSources.contains(line.messageId())).toList();
        String input = request.secondary() ? com.sande.mythictrpg.ai.room.RoomTurnPolicy.latestPermittedNpcText(visible) : request.currentText();
        if (visible.equals(request.history()) && input.equals(request.currentText())) return request;
        return new Request(request.roomId(), request.revision(), request.turnId(), request.playerId(), request.playerName(),
                request.godIds(), request.speakerGodId(), input, visible, request.readOnly(),
                request.recording(), request.publicRoom(), request.godStates(), request.secondary(),request.audiencePlayerIds());
    }

    /** Static quest retrieval uses an explicit current room audience; game validators still decide execution. */
    private static Request enrichContext(Request request, StoryConversationContextBridge story,
            List<AiQuestContentBridge.QuestCandidate> candidates) {
        var addition = new StringBuilder(story.prompt());
        if (!candidates.isEmpty()) {
            addition.append("\n[AUTHOR_DEFINED_QUEST_CANDIDATES]\n");
            for (var candidate : candidates) addition.append("- ").append(candidate.promptSummary()).append("\n");
            addition.append("Use only these exact quest IDs. A candidate is not accepted or completed. Ask for selection and required consent.\n");
        }
        if (gameplayCapabilitiesVisible(request)) AiActionCapabilityBridge.appendPrompt(addition, request.speakerGodId(), request.godIds());
        var states = request.godStates().stream().map(s -> s.godId().equals(request.speakerGodId())
                ? new GodState(s.godId(), s.relationshipTier(), s.emotionTag(), s.gameContext() + addition, s.memoryContext()) : s).toList();
        return new Request(request.roomId(), request.revision(), request.turnId(), request.playerId(), request.playerName(),
                request.godIds(), request.speakerGodId(), request.currentText(), request.history(), request.readOnly(), request.recording(),
                request.publicRoom(), states, request.secondary(),request.audiencePlayerIds());
    }

    static boolean gameplayCapabilitiesVisible(Request request) { return !request.secondary() && !request.readOnly(); }
    private static Request appendOwnContext(Request request,String addition) {
        var own=request.speakerState();
        var states=List.of(new GodState(own.godId(),own.relationshipTier(),own.emotionTag(),
                own.gameContext()+addition,own.memoryContext()));
        return new Request(request.roomId(),request.revision(),request.turnId(),request.playerId(),request.playerName(),
                request.godIds(),request.speakerGodId(),request.currentText(),request.history(),request.readOnly(),request.recording(),
                request.publicRoom(),states,request.secondary(),request.audiencePlayerIds());
    }

    @Override public CompletableFuture<SplitResult> chooseSplit(SplitRequest request) {
        var result = new CompletableFuture<SplitResult>();
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread() || !ConversationRooms.INSTANCE.isCurrent(request.roomId(), request.revision()))
            return CompletableFuture.completedFuture(splitFailed(request, "STALE_ROOM"));
        String key = request.roomId() + "/" + request.godId();
        UUID token = UUID.randomUUID(); splitTokens.put(key, token);
        try {
            var audience=server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            var owner=request.candidates().getFirst().playerIds().getFirst();
            // Scope carrier only: the split prompt uses no single-player tier/guideline. Its actual
            // social input is SYSTEM_SPLIT_CONTEXT with all players and no favoured current speaker.
            var scope=new Request(request.roomId(),request.revision(),token,owner,"split decision",request.godIds(),
                    request.godId(),"",request.history(),true,false,true,
                    List.of(new GodState(request.godId(),"R_NEUTRAL","E_UNASSESSED",request.relationshipContext(),null)),false,audience);
            var content = RoomKnowledgeContext.load(scope);
            var godRelations = new LinkedHashMap<String, List<String>>();
            for (var other : request.godIds()) if (!other.equals(request.godId()))
                godRelations.put(request.godId() + " -> " + other,
                        RoomKnowledgeContext.directionalRelations(request.godId(),"R_NEUTRAL",true,request.godIds(),audience,
                                List.of(request.godId(),other)));
            String system = DivineSocialPrompt.policy()
                    + "\nThis is a group split decision, not a speech turn. There is no current player to prefer. "
                    + "Read each player's own supplied relationship and social evidence; first candidate order grants no preference. "
                    + "You decide where one existing God continues a Minecraft RPG conversation after its player group splits. "
                    + "Use the supplied persona, directional relationships and recent conversation. Choose exactly one supplied candidate key,"
                    + " or leave when the God would leave. This moves conversation membership only; it never changes divine watch, quests,"
                    + " location or ownership. Return JSON only: {\"speech\":[],\"proposals\":[{\"type\":\"conversation_split\","
                    + "\"title\":\"\",\"summary\":\"short reason\",\"targetParticipantIds\":[],\"parameters\":{\"candidateKey\":\"candidate or empty for leave\"}}]}";
            var permittedSources=RoomMemoryBridge.currentSources(server,scope,request.history().stream()
                    .map(HistoryLine::messageId).filter(Objects::nonNull).toList());
            var safeHistory = RoomHistorySources.filter(request.roomId(), request.history(),
                    (source, text) -> !DialogueMemoryBridge.excludedRoomHistory(source, List.of(text)).contains(text))
                    .stream().filter(line->line.messageId()==null||permittedSources.contains(line.messageId())).toList();
            var sources=safeHistory.stream().map(HistoryLine::messageId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            var portableEvidence=List.of(RoomKnowledgeContext.evidence(scope,content));
            var historyEvidence = DialogueMemoryBridge.roomHistoryReferences(request.roomId(), safeHistory);
            if (historyEvidence.size() > com.sande.mythai.response.memory.ExperienceHistory.INHERITED_LIMIT) {
                splitTokens.remove(key); return CompletableFuture.completedFuture(splitFailed(request, "SPLIT_EVIDENCE_BUDGET"));
            }
            var safeRequest = new SplitRequest(request.roomId(), request.revision(), request.godId(), request.candidates(),
                    request.relationshipContext(), safeHistory, request.godIds());
            String user = splitInput(safeRequest, content.profile(), godRelations);
            if (user.length() > 32000) { splitTokens.remove(key); return CompletableFuture.completedFuture(splitFailed(request, "SPLIT_CONTEXT_TOO_LARGE")); }
            client().submit(UUID.randomUUID(), List.of(new AiDialogueModels.OllamaMessage("system", system),
                    new AiDialogueModels.OllamaMessage("user", user)), AiDialogueConfig.INSTANCE.settings()).completion()
                    .whenComplete((response, failure) -> server.execute(() -> {
                        if (!token.equals(splitTokens.get(key)) || !ConversationRooms.INSTANCE.isCurrent(request.roomId(), request.revision())) {
                            result.complete(splitFailed(request, "STALE_ROOM")); return;
                        }
                        splitTokens.remove(key);
                        if (historyEvidence.stream().anyMatch(ref -> !ref.current())
                                ||!RoomMemoryBridge.evidenceCurrent(server,scope,portableEvidence,sources)) {
                            result.complete(splitFailed(request, "SPLIT_EVIDENCE_REVOKED")); return;
                        }
                        if (failure != null || response == null || response.value() == null) { result.complete(splitFailed(request, "SPLIT_GENERATION_FAILED")); return; }
                        try {
                            if (RoomKnowledgeContext.load(scope).generation() != content.generation())
                                result.complete(splitFailed(request, "CONTENT_RELOADED"));
                            else result.complete(parseSplit(request, response.value()));
                        }
                        catch (RuntimeException invalid) { result.complete(splitFailed(request, "INVALID_SPLIT_CHOICE")); }
                    }));
        } catch (RuntimeException unavailable) { splitTokens.remove(key); result.complete(splitFailed(request, "SPLIT_UNAVAILABLE")); }
        return result;
    }

    static SplitResult parseSplit(SplitRequest request, AiDialogueModels.StructuredAiResult response) {
        if (!response.speech().isEmpty() || response.proposals().size() != 1) throw new IllegalArgumentException("Expected one decision");
        var proposal = response.proposals().getFirst();
        if (!"conversation_split".equals(proposal.type()) || !proposal.parameters().containsKey("candidateKey"))
            throw new IllegalArgumentException("Missing split decision");
        String chosen = proposal.parameters().get("candidateKey");
        if (!chosen.isEmpty() && request.candidates().stream().noneMatch(c -> c.key().equals(chosen)))
            throw new IllegalArgumentException("Unknown group");
        return new SplitResult(request.roomId(), request.revision(), request.godId(), chosen,
                proposal.summary().substring(0, Math.min(500, proposal.summary().length())), "");
    }
    static String splitInput(SplitRequest request, AiTestContentRegistryBridge.Profile persona, Map<String, List<String>> godRelations) {
        var history = new ArrayList<>(RoomHistorySources.filter(request.roomId(), request.history(),
                (source, text) -> !DialogueMemoryBridge.excludedRoomHistory(source, List.of(text)).contains(text)));
        while (true) {
            String input = JSON.toJson(Map.of("god", request.godId().toString(), "persona", persona,
                    "relationships", request.relationshipContext(), "presentGods", request.godIds().stream().map(ResourceLocation::toString).toList(),
                    "directionalGodRelations", godRelations, "candidates", request.candidates(), "recentConversation", history));
            if (input.length() <= 32000 || history.isEmpty()) return input;
            history.removeFirst(); // Never trim decision candidates, identity, persona, relationships or authority boundaries.
        }
    }
    private static SplitResult splitFailed(SplitRequest request, String failure) {
        return new SplitResult(request.roomId(), request.revision(), request.godId(), "", "", failure);
    }

    @Override public void invalidate(UUID roomId) {
        emotions.invalidate(roomId);
        tokens.invalidate(roomId); deliveries.remove(roomId); identityPublications.remove(roomId); stories.remove(roomId);evidence.remove(roomId);
        activities.keySet().removeIf(key -> key.room().equals(roomId));
        splitTokens.keySet().removeIf(key -> key.startsWith(roomId + "/"));
        DialogueMemoryBridge.invalidateRoom(roomId);
    }
    @Override public void stop() {
        emotions.clear();
        tokens.clear(); deliveries.clear(); identityPublications.clear(); stories.clear(); evidence.clear();splitTokens.clear(); activities.clear();
        RoomMemoryBridge.close();
        if (llm != null) llm.close(); llm = null; logs.close();
    }
}
