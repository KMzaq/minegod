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
    default void stop() { }

    record HistoryLine(String role, String speakerId, String name, String text) {
        public HistoryLine { Objects.requireNonNull(role); Objects.requireNonNull(speakerId); Objects.requireNonNull(name); Objects.requireNonNull(text); }
    }
    record GodState(ResourceLocation godId, String relationshipTier, String emotionTag, String gameContext,
                    ConversationMemoryContext memoryContext) {
        public GodState { Objects.requireNonNull(godId); Objects.requireNonNull(relationshipTier); Objects.requireNonNull(emotionTag); Objects.requireNonNull(gameContext); }
    }
    record Request(UUID roomId, long revision, UUID turnId, UUID playerId, String playerName,
                   List<ResourceLocation> godIds, ResourceLocation speakerGodId, String currentText,
                   List<HistoryLine> history, boolean readOnly, boolean recording, boolean publicRoom,
                   List<GodState> godStates) {
        public Request {
            Objects.requireNonNull(roomId); Objects.requireNonNull(turnId); Objects.requireNonNull(playerId);
            Objects.requireNonNull(playerName); Objects.requireNonNull(speakerGodId); Objects.requireNonNull(currentText);
            godIds = List.copyOf(godIds); history = List.copyOf(history); godStates = List.copyOf(godStates);
            if (revision < 0 || godIds.isEmpty() || godIds.size() > 16 || !godIds.contains(speakerGodId)
                    || new HashSet<>(godIds).size() != godIds.size() || history.size() > 128
                    || currentText.length() > 8192 || godStates.stream().filter(s -> s.godId().equals(speakerGodId)).count() != 1)
                throw new IllegalArgumentException("Invalid room turn scope");
        }
        public GodState speakerState() { return godStates.stream().filter(s -> s.godId().equals(speakerGodId)).findFirst().orElseThrow(); }
    }
    record Speech(ResourceLocation godId, String text) { public Speech { Objects.requireNonNull(godId); Objects.requireNonNull(text); } }
    record Control(String kind, String targetId, String reason) { }
    record Result(UUID roomId, long revision, UUID turnId, List<Speech> speech, String proposalsJson,
                  List<Control> controls, String failure) {
        public Result { speech = List.copyOf(speech); controls = List.copyOf(controls); Objects.requireNonNull(proposalsJson); Objects.requireNonNull(failure); }
        public static Result failed(Request request, String reason) {
            return new Result(request.roomId(), request.revision(), request.turnId(), List.of(), "[]", List.of(), reason);
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
