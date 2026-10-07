package com.sande.mythictrpg.ai.api;

import com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Immutable room boundary. Every reply remains a proposal until the game revalidates its lease. */
public interface RoomConversationEngine {
    CompletableFuture<Result> respond(Request request);
    CompletableFuture<SplitResult> chooseSplit(SplitRequest request);
    default void invalidate(UUID roomId) { }
    /** Called only after game validation and actual delivery; commits optional NPC memory. */
    default void delivered(Request request, Result result) { }
    /** Attach only internal provenance before delivery. May reject a stale generated publication. */
    default RoomDialogueEvent preparePublication(RoomDialogueEvent event) { return event; }
    /** Volatile delivery provenance only, also when durable recording is disabled. */
    default void dialogueObserved(RoomDialogueEvent event) { }
    /** Game-side logical speech after fan-out, also for initial Encounter and non-LLM player turns.
     * Recording consumers enqueue immutable data only; they must not execute proposals or block the game thread.
     */
    default void dialoguePublished(RoomDialogueEvent event) { }
    /** Optional historical-source preparation; never grants room identity or broad archive access. */
    default CompletableFuture<Boolean> prepareRecordedEvidence(Request request, List<RoomEvidenceReference> evidence) {
        return CompletableFuture.completedFuture(evidence.isEmpty());
    }
    default boolean recordedEvidenceCurrent(Request request, List<RoomEvidenceReference> evidence) { return evidence.isEmpty(); }
    default void stop() { }

    record HistoryLine(String role, String speakerId, String name, String text, UUID sourceRoomId, UUID messageId) {
        public HistoryLine(String role, String speakerId, String name, String text) {
            this(role, speakerId, name, text, null, null);
        }
        public HistoryLine(String role, String speakerId, String name, String text, UUID sourceRoomId) {
            this(role, speakerId, name, text, sourceRoomId, null);
        }
        public HistoryLine { Objects.requireNonNull(role); Objects.requireNonNull(speakerId); Objects.requireNonNull(name); Objects.requireNonNull(text); }
    }
    record GodState(ResourceLocation godId, String relationshipTier, String emotionTag, String gameContext,
                    ConversationMemoryContext memoryContext) {
        public GodState { Objects.requireNonNull(godId); Objects.requireNonNull(relationshipTier); Objects.requireNonNull(emotionTag); Objects.requireNonNull(gameContext); }
    }
    /** Bounded game-result projection, not permission to execute or proof of an ongoing world state. */
    record ActionOutcome(UUID proposalId, String actionType, ActionStatus status, String reason, Map<String, String> details) {
        public ActionOutcome {
            Objects.requireNonNull(proposalId); Objects.requireNonNull(actionType); Objects.requireNonNull(status);
            Objects.requireNonNull(reason); details = Map.copyOf(details);
            if (ResourceLocation.tryParse(actionType) == null || reason.length() > 320 || details.size() > 12
                    || status != ActionStatus.EXECUTED && !details.isEmpty()
                    || details.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getKey().length() > 64
                    || e.getValue().length() > 160))
                throw new IllegalArgumentException("Invalid bounded action outcome");
        }
    }
    enum ActionStatus { EXECUTED, PENDING_CONFIRMATION, CANCELLED, EXPIRED, REJECTED, FAILED }
    record Request(UUID roomId, long revision, UUID turnId, UUID playerId, String playerName,
                   List<ResourceLocation> godIds, ResourceLocation speakerGodId, String currentText,
                   List<HistoryLine> history, boolean readOnly, boolean recording, boolean publicRoom,
                   List<GodState> godStates, boolean secondary, Set<UUID> audiencePlayerIds,
                   List<ActionOutcome> actionOutcomes) {
        /** Existing callers have no supplied action-result evidence. */
        public Request(UUID roomId, long revision, UUID turnId, UUID playerId, String playerName,
                List<ResourceLocation> godIds, ResourceLocation speakerGodId, String currentText,
                List<HistoryLine> history, boolean readOnly, boolean recording, boolean publicRoom,
                List<GodState> godStates, boolean secondary, Set<UUID> audiencePlayerIds) {
            this(roomId, revision, turnId, playerId, playerName, godIds, speakerGodId, currentText,
                    history, readOnly, recording, publicRoom, godStates, secondary, audiencePlayerIds, List.of());
        }
        public Request(UUID roomId, long revision, UUID turnId, UUID playerId, String playerName,
                List<ResourceLocation> godIds, ResourceLocation speakerGodId, String currentText,
                List<HistoryLine> history, boolean readOnly, boolean recording, boolean publicRoom,
                List<GodState> godStates, boolean secondary) {
            this(roomId, revision, turnId, playerId, playerName, godIds, speakerGodId, currentText,
                    history, readOnly, recording, publicRoom, godStates, secondary,
                    godStates.size() == 1 && godStates.getFirst().memoryContext() != null
                        ? godStates.getFirst().memoryContext().audience() : Set.of(playerId));
        }
        /** Existing callers retain primary-only authority. */
        public Request(UUID roomId, long revision, UUID turnId, UUID playerId, String playerName,
                List<ResourceLocation> godIds, ResourceLocation speakerGodId, String currentText,
                List<HistoryLine> history, boolean readOnly, boolean recording, boolean publicRoom,
                List<GodState> godStates) {
            this(roomId, revision, turnId, playerId, playerName, godIds, speakerGodId, currentText,
                    history, readOnly, recording, publicRoom, godStates, false);
        }
        public Request {
            Objects.requireNonNull(roomId); Objects.requireNonNull(turnId); Objects.requireNonNull(playerId);
            Objects.requireNonNull(playerName); Objects.requireNonNull(speakerGodId); Objects.requireNonNull(currentText);
            godIds = List.copyOf(godIds); history = List.copyOf(history); godStates = List.copyOf(godStates);
            audiencePlayerIds = Set.copyOf(audiencePlayerIds);
            actionOutcomes = List.copyOf(actionOutcomes);
            if (revision < 0 || godIds.isEmpty() || godIds.size() > 16 || !godIds.contains(speakerGodId)
                    || new HashSet<>(godIds).size() != godIds.size() || history.size() > 128
                    || currentText.length() > 8192 || godStates.size() != 1
                    || godStates.stream().filter(s -> s.godId().equals(speakerGodId)).count() != 1
                    || !audiencePlayerIds.contains(playerId) || !publicRoom && audiencePlayerIds.size() > 64)
                throw new IllegalArgumentException("Invalid room turn scope");
            if (actionOutcomes.size() > 16 || (secondary || readOnly) && !actionOutcomes.isEmpty()
                    || actionOutcomes.stream().map(ActionOutcome::proposalId).distinct().count() != actionOutcomes.size())
                throw new IllegalArgumentException("Invalid room action-outcome scope");
            var memory = godStates.getFirst().memoryContext();
            if (memory != null && (!memory.godId().equals(speakerGodId.toString())
                    || !memory.playerId().equals(playerId) || !memory.interactionId().equals(roomId)))
                throw new IllegalArgumentException("Foreign room memory context");
        }
        public GodState speakerState() { return godStates.stream().filter(s -> s.godId().equals(speakerGodId)).findFirst().orElseThrow(); }
    }
    record Speech(ResourceLocation godId, String text) { public Speech { Objects.requireNonNull(godId); Objects.requireNonNull(text); } }
    record Control(String kind, String targetId, String reason) { }
    record Result(UUID roomId, long revision, UUID turnId, List<Speech> speech, String proposalsJson,
                  List<Control> controls, String failure, Optional<UUID> storyContextId, List<String> storyStatementAliases) {
        public Result(UUID roomId, long revision, UUID turnId, List<Speech> speech, String proposalsJson,
                List<Control> controls, String failure) {
            this(roomId, revision, turnId, speech, proposalsJson, controls, failure, Optional.empty(), List.of());
        }
        public Result {
            speech = List.copyOf(speech); controls = List.copyOf(controls);
            Objects.requireNonNull(proposalsJson); Objects.requireNonNull(failure); Objects.requireNonNull(storyContextId);
            storyStatementAliases = List.copyOf(storyStatementAliases);
            if (storyStatementAliases.size() > 8 || new HashSet<>(storyStatementAliases).size() != storyStatementAliases.size()
                    || !storyStatementAliases.isEmpty() && storyContextId.isEmpty()
                    || storyStatementAliases.stream().anyMatch(a -> a == null || a.isBlank() || a.length() > 128))
                throw new IllegalArgumentException("Invalid story disclosure selection");
        }
        public static Result failed(Request request, String reason) {
            return new Result(request.roomId(), request.revision(), request.turnId(), List.of(), "[]", List.of(), reason);
        }
        /** Reactions may propose only an explicitly authorized Story Hook, never arbitrary actions/controls. */
        public boolean deliverableFor(Request request) {
            return request.roomId().equals(roomId) && request.revision() == revision && request.turnId().equals(turnId)
                    && failure.isBlank() && (!speech.isEmpty() || request.secondary()) && speech.size() <= 8
                    && (!request.secondary() || controls.isEmpty() && reactionProposalsAllowed(request))
                    && speech.stream().allMatch(s -> s.godId().equals(request.speakerGodId()) && !s.text().isBlank()
                    && s.text().length() <= 4000);
        }
        private boolean reactionProposalsAllowed(Request request) {
            try {
                var proposals = com.google.gson.JsonParser.parseString(proposalsJson).getAsJsonArray();
                if (proposals.isEmpty()) return true;
                if (request.readOnly() || speech.isEmpty() || proposals.size() > 3) return false;
                for (var value : proposals) {
                    var p = value.getAsJsonObject();
                    if (!p.has("type") || !"story_event_hook".equals(p.get("type").getAsString())) return false;
                }
                return true;
            } catch (RuntimeException invalid) { return false; }
        }
    }
    record Candidate(String key, List<UUID> playerIds, String context) {
        public Candidate {
            Objects.requireNonNull(key); Objects.requireNonNull(context); playerIds = List.copyOf(playerIds);
            if (key.isBlank() || key.length() > 128 || context.length() > 12000 || playerIds.isEmpty())
                throw new IllegalArgumentException("Invalid split candidate");
        }
    }
    record SplitRequest(UUID roomId, long revision, ResourceLocation godId, List<Candidate> candidates,
                        String relationshipContext, List<HistoryLine> history, List<ResourceLocation> godIds) {
        public SplitRequest(UUID roomId, long revision, ResourceLocation godId, List<Candidate> candidates,
                String relationshipContext, List<HistoryLine> history) {
            this(roomId, revision, godId, candidates, relationshipContext, history, List.of(godId));
        }
        public SplitRequest {
            Objects.requireNonNull(roomId); Objects.requireNonNull(godId); Objects.requireNonNull(relationshipContext);
            candidates = List.copyOf(candidates); history = List.copyOf(history); godIds = List.copyOf(godIds);
            if (candidates.isEmpty() || candidates.size() > 64 || history.size() > 128
                    || godIds.isEmpty() || godIds.size() > 16 || !godIds.contains(godId)
                    || candidates.stream().map(Candidate::key).distinct().count() != candidates.size())
                throw new IllegalArgumentException("Invalid split scope");
        }
    }
    /** Empty candidateKey means the god chooses to leave the conversation. It never changes watch ownership. */
    record SplitResult(UUID roomId, long revision, ResourceLocation godId, String candidateKey, String reason, String failure) { }
}
