package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.sande.mythai.response.memory.DialogueMemoryBridge;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Room transport around the existing two-stage prompt engine. The game owns every visible effect. */
public final class MythAiRoomConversationEngine implements RoomConversationEngine {
    public static final MythAiRoomConversationEngine INSTANCE = new MythAiRoomConversationEngine();
    private static final Gson JSON = new Gson();
    private static final Set<String> CONTROLS = Set.of("conversation_leave", "conversation_end", "conversation_invite");
    private LocalLlmClient llm;
    private final AiTestContentRegistryBridge registry = new AiTestContentRegistryBridge();
    private final RoomResponseTokens tokens = new RoomResponseTokens();
    private final Map<UUID, Delivery> deliveries = new HashMap<>();
    private final Map<String, UUID> splitTokens = new HashMap<>();
    private final Map<ActivityKey, String> activities = new HashMap<>();
    private final RoomDialogueLog logs = new RoomDialogueLog();
    private record ActivityKey(UUID room, long revision, ResourceLocation god) { }
    private record Delivery(Request request, Result result, DialogueMemoryBridge.Turn memory) { }
    private MythAiRoomConversationEngine() { }
    private LocalLlmClient client() { if (llm == null) llm = new LocalOllamaClient(); return llm; }

    @Override public CompletableFuture<Result> respond(Request request) {
        var result = new CompletableFuture<Result>();
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return CompletableFuture.completedFuture(Result.failed(request, "NO_GAME_THREAD"));
        var player = server.getPlayerList().getPlayer(request.playerId());
        if (player == null || !ConversationRooms.INSTANCE.isCurrent(request.roomId(), request.revision()))
            return CompletableFuture.completedFuture(Result.failed(request, "STALE_ROOM"));
        var token = tokens.issue(request.roomId(), request.revision(), request.turnId());
        deliveries.remove(request.roomId());
        log(player, request, "PLAYER", request.playerName() + ": " + request.currentText());
        try {
            var state = request.speakerState();
            var content = registry.load(request.speakerGodId(), state.relationshipTier(), request.godIds());
            var safe = enrichContext(pruneHistory(request), player);
            DialogueMemoryBridge.beginRoomAsync(player, safe, token.sequence()).whenComplete((memory, failure) -> server.execute(() -> {
                if (!current(player, token)) { result.complete(Result.failed(request, "STALE_ROOM")); return; }
                if (failure != null || memory == null || !DialogueMemoryBridge.roomTurnCurrent(player, memory)) {
                    result.complete(Result.failed(request, "MEMORY_UNAVAILABLE_OR_STALE")); return;
                }
                try {
                    var activityKey = new ActivityKey(request.roomId(), request.revision(), request.speakerGodId());
                    var prompt = new AiTestDialogueAdapter.RoomPrompt(safe, memory, Map.of(request.speakerGodId(), content),
                            activities.getOrDefault(activityKey, ""));
                    activities.put(activityKey, prompt.activity());
                    classify(player, request, token, content, prompt, memory, result);
                } catch (RuntimeException invalid) { result.complete(Result.failed(request, "PROMPT_REJECTED")); }
            }));
        } catch (RuntimeException failure) { result.complete(Result.failed(request, "CONTENT_OR_MEMORY_UNAVAILABLE")); }
        return result;
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
                    .completion().whenComplete((classified, failure) -> player.server.execute(() -> {
                        if (!current(player, token)) { result.complete(Result.failed(request, "STALE_ROOM")); return; }
                        var intent = failure == null && classified != null && classified.value() != null
                                ? classified.value() : ConversationIntent.heuristicFallback();
                        log(player, request, "CLASSIFICATION_RESULT", JSON.toJson(intent));
                        try { generate(player, request, token, content, prompt, memory, prompt.generation(intent), false, result); }
                        catch (RuntimeException invalid) { result.complete(Result.failed(request, "PROMPT_REJECTED")); }
                    }));
        } catch (RuntimeException unavailable) {
            try { generate(player, request, token, content, prompt, memory,
                    prompt.generation(ConversationIntent.heuristicFallback()), false, result); }
            catch (RuntimeException invalid) { result.complete(Result.failed(request, "PROMPT_REJECTED")); }
        }
    }

    private void generate(ServerPlayer player, Request request, RoomResponseTokens.Token token,
            AiTestContentRegistryBridge.ContentSnapshot content, AiTestDialogueAdapter.RoomPrompt prompt,
            DialogueMemoryBridge.Turn memory, List<AiDialogueModels.OllamaMessage> messages, boolean repaired,
            CompletableFuture<Result> result) {
        if (!current(player, token) || !DialogueMemoryBridge.roomTurnCurrent(player, memory)) {
            result.complete(Result.failed(request, "STALE_ROOM_OR_MEMORY")); return;
        }
        try {
            log(player, request, repaired ? "GENERATION_REPAIR_INPUT" : "GENERATION_INPUT", JSON.toJson(messages));
            client().submit(UUID.randomUUID(), messages, AiDialogueConfig.INSTANCE.settings()).completion()
                    .whenComplete((generated, failure) -> player.server.execute(() -> {
                        if (!current(player, token) || !DialogueMemoryBridge.roomTurnCurrent(player, memory)) {
                            result.complete(Result.failed(request, "STALE_ROOM_OR_MEMORY")); return;
                        }
                        try {
                            if (failure != null || generated == null || generated.value() == null) {
                                result.complete(Result.failed(request, "GENERATION_FAILED")); return;
                            }
                            if (registry.load(request.speakerGodId(), request.speakerState().relationshipTier(), request.godIds()).generation()
                                    != content.generation()) { result.complete(Result.failed(request, "CONTENT_RELOADED")); return; }
                            var output = generated.value();
                            log(player, request, "GENERATION_RESULT", JSON.toJson(output));
                            if (prompt.needsRepair(output)) {
                                if (repaired) { result.complete(Result.failed(request, "RESPONSE_REJECTED")); return; }
                                var repair = new ArrayList<>(messages);
                                repair.add(new AiDialogueModels.OllamaMessage("user", "Revise this rejected draft using the same supplied persona and room context."
                                        + " Respond naturally to the player's immediate feeling. Do not invent real-world actions or repeat a generic remedy."
                                        + " Return the same JSON schema. Rejected draft data: " + JSON.toJson(output)));
                                generate(player, request, token, content, prompt, memory, List.copyOf(repair), true, result); return;
                            }
                            var speech = prompt.speech(output).stream().map(s -> new Speech(ResourceLocation.parse(s.speakerId()), s.text())).toList();
                            if (speech.isEmpty()) { result.complete(Result.failed(request, "EMPTY_RESPONSE")); return; }
                            var controls = controls(output.proposals());
                            var proposals = prompt.gameplayProposalsAllowed()
                                    ? normalizeProposals(player, request, output.proposals()) : List.<AiDialogueModels.Proposal>of();
                            var reply = new Result(request.roomId(), request.revision(), request.turnId(), speech, JSON.toJson(proposals), controls, "");
                            deliveries.put(request.roomId(), new Delivery(request, reply, memory));
                            result.complete(reply);
                        } catch (RuntimeException invalid) { result.complete(Result.failed(request, "RESPONSE_REJECTED")); }
                    }));
        } catch (RuntimeException unavailable) { result.complete(Result.failed(request, "GENERATION_UNAVAILABLE")); }
    }

    private static List<Control> controls(List<AiDialogueModels.Proposal> proposals) {
        return proposals.stream().filter(p -> CONTROLS.contains(p.type())).limit(3)
                .map(p -> new Control(p.type(), p.targetParticipantIds().isEmpty() ? "" : p.targetParticipantIds().getFirst(), p.summary())).toList();
    }

    private static List<AiDialogueModels.Proposal> normalizeProposals(ServerPlayer player, Request request,
            List<AiDialogueModels.Proposal> proposals) {
        var room = ConversationRooms.INSTANCE.memberships(player).stream().filter(r -> r.roomId().equals(request.roomId())
                && r.revision() == request.revision()).findFirst().orElseThrow();
        var candidates = AiQuestContentBridge.candidatesFor(request.speakerGodId(), player, room.playerIds());
        var ids = candidates.stream().map(c -> c.questId().toString()).collect(java.util.stream.Collectors.toSet());
        var normalized = new ArrayList<AiDialogueModels.Proposal>();
        for (var proposal : proposals.stream().limit(8).toList()) {
            if (CONTROLS.contains(proposal.type())) continue;
            if ("quest_offer".equals(proposal.type())) {
                String quest = proposal.parameters().getOrDefault("quest_id", "");
                if (ids.contains(quest)) normalized.add(proposal);
                continue;
            }
            var supported = AiActionCapabilityBridge.normalize(proposal, request.speakerGodId(), request.currentText());
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
        for (var speech : result.speech()) {
            DialogueMemoryBridge.roomDelivered(player, request, delivery.memory(), speech.text());
            log(player, request, "DELIVERED", speech.godId() + ": " + speech.text());
            try {
                var content = registry.load(speech.godId(), request.speakerState().relationshipTier(), request.godIds());
                var room = ConversationRooms.INSTANCE.memberships(player).stream().filter(r -> r.roomId().equals(request.roomId())
                        && r.revision() == request.revision()).findFirst().orElseThrow();
                var recipients = request.publicRoom() ? server.getPlayerList().getPlayers()
                        : room.playerIds().stream().map(server.getPlayerList()::getPlayer).filter(Objects::nonNull).toList();
                if (!request.readOnly()) for (var recipient : recipients)
                    SpokenIdentityReveal.commitIfNameWasSpoken(recipient, speech.godId(), content.profile().displayName(), speech.text());
            } catch (RuntimeException unavailable) { /* Committed dialogue remains valid when optional content unloads. */ }
        }
    }

    private boolean current(ServerPlayer player, RoomResponseTokens.Token token) {
        return player.server.getPlayerList().getPlayer(player.getUUID()) == player && tokens.current(token)
                && ConversationRooms.INSTANCE.isCurrent(token.room(), token.revision());
    }

    private void log(ServerPlayer player, Request request, String kind, String text) {
        if (!request.recording()) return;
        if ("PLAYER".equals(kind) || "DELIVERED".equals(kind)) {
            var room = ConversationRooms.INSTANCE.memberships(player).stream().filter(r -> r.roomId().equals(request.roomId())
                    && r.revision() == request.revision()).findFirst().orElse(null);
            if (room != null) for (var id : room.playerIds()) {
                var participant = player.server.getPlayerList().getPlayer(id);
                if (participant == null) continue;
                logs.transcript(player.server.getServerDirectory(), request.roomId(), id, participant.getGameProfile().getName(),
                        request.godIds().stream().map(ResourceLocation::getPath).collect(java.util.stream.Collectors.joining("_")),
                        request.turnId(), true, kind, text).exceptionally(failure -> {
                            com.sande.mythictrpg.MythicTrpg.LOGGER.warn("Room transcript write failed for {}", request.roomId(), failure); return false;
                        });
            }
        }
        logs.append(player.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("mythictrpg-ai-room-logs"),
                request.roomId(), request.playerId(), request.turnId(), true, kind, text).exceptionally(failure -> {
                    com.sande.mythictrpg.MythicTrpg.LOGGER.warn("Room dialogue log write failed for {}", request.roomId(), failure); return false;
                });
    }

    private static Request pruneHistory(Request request) {
        var excluded = DialogueMemoryBridge.excludedRoomHistory(request.roomId(), request.history().stream().map(HistoryLine::text).toList());
        if (excluded.isEmpty()) return request;
        return new Request(request.roomId(), request.revision(), request.turnId(), request.playerId(), request.playerName(),
                request.godIds(), request.speakerGodId(), request.currentText(), request.history().stream()
                .filter(line -> !"NPC".equals(line.role()) || !excluded.contains(line.text())).toList(), request.readOnly(),
                request.recording(), request.publicRoom(), request.godStates());
    }

    /** Static quest retrieval uses an explicit current room audience; game validators still decide execution. */
    private static Request enrichContext(Request request, ServerPlayer player) {
        var room = ConversationRooms.INSTANCE.memberships(player).stream().filter(r -> r.roomId().equals(request.roomId())
                && r.revision() == request.revision()).findFirst().orElseThrow();
        var candidates = AiQuestContentBridge.candidatesFor(request.speakerGodId(), player, room.playerIds());
        var addition = new StringBuilder();
        if (!candidates.isEmpty()) {
            addition.append("\n[AUTHOR_DEFINED_QUEST_CANDIDATES]\n");
            for (var candidate : candidates) addition.append("- ").append(candidate.promptSummary()).append("\n");
            addition.append("Use only these exact quest IDs. A candidate is not accepted or completed. Ask for selection and required consent.\n");
        }
        AiActionCapabilityBridge.appendPrompt(addition, request.speakerGodId());
        var states = request.godStates().stream().map(s -> s.godId().equals(request.speakerGodId())
                ? new GodState(s.godId(), s.relationshipTier(), s.emotionTag(), s.gameContext() + addition, s.memoryContext()) : s).toList();
        return new Request(request.roomId(), request.revision(), request.turnId(), request.playerId(), request.playerName(),
                request.godIds(), request.speakerGodId(), request.currentText(), request.history(), request.readOnly(), request.recording(),
                request.publicRoom(), states);
    }

    @Override public CompletableFuture<SplitResult> chooseSplit(SplitRequest request) {
        var result = new CompletableFuture<SplitResult>();
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread() || !ConversationRooms.INSTANCE.isCurrent(request.roomId(), request.revision()))
            return CompletableFuture.completedFuture(splitFailed(request, "STALE_ROOM"));
        String key = request.roomId() + "/" + request.godId();
        UUID token = UUID.randomUUID(); splitTokens.put(key, token);
        try {
            var content = registry.load(request.godId(), "R_NEUTRAL", request.godIds());
            var godRelations = new LinkedHashMap<String, List<String>>();
            for (var other : request.godIds()) if (!other.equals(request.godId()))
                godRelations.put(request.godId() + " -> " + other,
                        registry.load(request.godId(), "R_NEUTRAL", List.of(request.godId(), other)).socialRelationTags());
            String system = "You decide where one existing God continues a Minecraft RPG conversation after its player group splits. "
                    + "Use the supplied persona, directional relationships and recent conversation. Choose exactly one supplied candidate key,"
                    + " or leave when the God would leave. This moves conversation membership only; it never changes divine watch, quests,"
                    + " location or ownership. Return JSON only: {\"speech\":[],\"proposals\":[{\"type\":\"conversation_split\","
                    + "\"title\":\"\",\"summary\":\"short reason\",\"targetParticipantIds\":[],\"parameters\":{\"candidateKey\":\"candidate or empty for leave\"}}]}";
            String user = splitInput(request, content.profile(), godRelations);
            if (user.length() > 32000) { splitTokens.remove(key); return CompletableFuture.completedFuture(splitFailed(request, "SPLIT_CONTEXT_TOO_LARGE")); }
            client().submit(UUID.randomUUID(), List.of(new AiDialogueModels.OllamaMessage("system", system),
                    new AiDialogueModels.OllamaMessage("user", user)), AiDialogueConfig.INSTANCE.settings()).completion()
                    .whenComplete((response, failure) -> server.execute(() -> {
                        if (!token.equals(splitTokens.get(key)) || !ConversationRooms.INSTANCE.isCurrent(request.roomId(), request.revision())) {
                            result.complete(splitFailed(request, "STALE_ROOM")); return;
                        }
                        splitTokens.remove(key);
                        if (failure != null || response == null || response.value() == null) { result.complete(splitFailed(request, "SPLIT_GENERATION_FAILED")); return; }
                        try {
                            if (registry.load(request.godId(), "R_NEUTRAL", request.godIds()).generation() != content.generation())
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
        var history = new ArrayList<>(request.history());
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
        tokens.invalidate(roomId); deliveries.remove(roomId);
        activities.keySet().removeIf(key -> key.room().equals(roomId));
        splitTokens.keySet().removeIf(key -> key.startsWith(roomId + "/"));
        DialogueMemoryBridge.invalidateRoom(roomId);
    }
    @Override public void stop() {
        tokens.clear(); deliveries.clear(); splitTokens.clear(); activities.clear();
        if (llm != null) llm.close(); llm = null; logs.close();
    }
}
