package com.sande.mythictrpg.ai;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Result;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import java.util.*;

/**
 * Server-thread-only, non-authoritative continuity of the NPC's last delivered emotional interpretation.
 * No affinity, game action, shared God mood, numeric decay, permanent profile, or extra model request.
 */
final class RoomEmotionState {
    static final int MAX_HINT_CHARACTERS = 120;
    private static final int MAX_STATES = 1024;
    private static final Gson JSON = new Gson();
    private record Key(UUID room, long revision, String god, UUID player, boolean publicRoom,
            Set<String> gods, Set<UUID> audience) {
        static Key of(Request request) {
            return new Key(request.roomId(), request.revision(), request.speakerGodId().toString(), request.playerId(),
                    request.publicRoom(), request.godIds().stream().map(Object::toString).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    Set.copyOf(request.audiencePlayerIds()));
        }
    }
    private record State(String hint, UUID turnId, Map<UUID, String> publishedSpeech) {
        State { publishedSpeech = Map.copyOf(publishedSpeech); }
    }
    private static final class Pending {
        final Request request;
        final Result result;
        final String hint;
        final Map<UUID, RoomDialogueEvent> events = new LinkedHashMap<>();
        Pending(Request request, Result result, String hint) { this.request = request; this.result = result; this.hint = hint; }
    }
    private final LinkedHashMap<Key, State> states = new LinkedHashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Request> issued = new HashMap<>();

    static String policy() {
        return """
                [CURRENT_EMOTION_CONTINUITY]
                For a spoken reply, include currentEmotion as a short phrase (at most 120 characters) describing
                only this speaker's present emotional stance in this exchange. Use the persona, actual dialogue,
                relationship and circumstances; do not copy the player's emotion or a classification label.
                A reaction to another God is not automatically a feeling toward the current player.
                This is an optional qualitative interpretation, not an emotion score, fact, plan or game proposal.
                Do not add secret information or describe another person's inner state. Use an empty string when
                there is no grounded assessment, and for silence. Do not explain your reasoning in this field.
                NPC_SESSION_EMOTION, when supplied, is your own interpretation from a prior actually delivered
                reply in this exact room/player/audience scope. It is not a permanent trait or game-owned truth.
                Carry its continuity when appropriate; reassess it from the new exchange rather than copying it
                mechanically. Warmth, hurt, caution, anger or mixed feelings need not change merely because the
                player repeats, apologizes or makes a request. Do not impose a fixed reaction or numeric decay.
                A game-supplied E_UNASSESSED only means the game supplied no emotion assessment; it does not
                erase this scoped NPC interpretation or mean neutral, calm, indifferent or hostile.
                """;
    }

    static String normalize(String hint) {
        if (hint == null) return "";
        String value = hint.trim();
        return value.length() > MAX_HINT_CHARACTERS || value.codePoints().anyMatch(Character::isISOControl) ? "" : value;
    }

    /** A newly issued request supersedes only this room's pending generation, not another room's committed mood. */
    void begin(Request request) {
        issued.put(request.roomId(), request);
        pending.remove(request.roomId());
        states.keySet().removeIf(key -> key.room().equals(request.roomId()) && key.revision() != request.revision());
    }

    /** Staging is never a commit: repair drafts, rejected replies and unobserved model output establish no emotion. */
    void stage(Request request, Result result, String hint) {
        if (!request.equals(issued.get(request.roomId())) || !result.deliverableFor(request) || result.speech().isEmpty()) return;
        pending.put(request.roomId(), new Pending(request, result, normalize(hint)));
    }

    /** Called from the volatile dispatch callback even when durable recording is disabled. */
    void observed(RoomDialogueEvent event) {
        var candidate = pending.get(event.roomId());
        if (candidate == null || !matches(candidate.request, candidate.result, event)) return;
        if (candidate.events.size() < candidate.result.speech().size()) candidate.events.putIfAbsent(event.messageId(), event);
    }

    static boolean matches(Request request, Result result, RoomDialogueEvent event) {
        var key = Key.of(request);
        return "NPC".equals(event.role()) && event.roomId().equals(key.room()) && event.revision() == key.revision()
                && event.turnId().filter(request.turnId()::equals).isPresent() && key.god().equals(event.speakerId())
                && event.roomType().isPublic() == request.publicRoom() && event.godIds().equals(key.gods())
                && event.heardGodIds().contains(key.god()) && event.participantNames().containsKey(key.player())
                && event.deliveries().keySet().equals(key.audience()) && event.fullTextReceiverIds().containsAll(key.audience())
                && result.speech().stream().anyMatch(speech -> speech.godId().equals(request.speakerGodId()) && speech.text().equals(event.text()));
    }

    /** Caller already validated the live room/turn. Require the exact staged result and every actual speech receipt. */
    boolean delivered(Request request, Result result) {
        var candidate = pending.get(request.roomId());
        if (candidate == null || !request.equals(issued.get(request.roomId()))
                || !candidate.request.equals(request) || !candidate.result.equals(result)) return false;
        pending.remove(request.roomId());
        var remaining = new ArrayList<>(candidate.events.values());
        for (var speech : result.speech()) {
            var event = remaining.stream().filter(value -> matches(request, result, value) && value.text().equals(speech.text())).findFirst();
            if (event.isEmpty()) return false;
            remaining.remove(event.orElseThrow());
        }
        if (candidate.events.size() != result.speech().size() || !remaining.isEmpty()) return false;
        var key = Key.of(request);
        states.remove(key);
        if (!candidate.hint.isBlank()) {
            var sources = new LinkedHashMap<UUID, String>();
            candidate.events.forEach((id, event) -> sources.put(id, event.text()));
            states.put(key, new State(candidate.hint, request.turnId(), sources));
            while (states.size() > MAX_STATES) states.remove(states.keySet().iterator().next());
        }
        return true;
    }

    /** Must receive history AFTER the normal room evidence/audience filter. Revoked or trimmed sources are not reused. */
    String context(Request request) {
        var key = Key.of(request);
        var state = states.get(key);
        if (state == null) return "";
        boolean permitted = state.publishedSpeech().entrySet().stream().allMatch(source -> request.history().stream()
                .anyMatch(line -> source.getKey().equals(line.messageId()) && request.roomId().equals(line.sourceRoomId())
                        && "NPC".equals(line.role()) && request.speakerGodId().toString().equals(line.speakerId())
                        && source.getValue().equals(line.text())));
        if (!permitted) { states.remove(key); return ""; }
        return "\n[NPC_SESSION_EMOTION]\n" + JSON.toJson(Map.of("assessment", "NPC_INTERPRETATION_NOT_GAME_TRUTH",
                "speakerGodId", key.god(), "playerContextId", key.player().toString(), "stance", state.hint(),
                "source", "ACTUALLY_DELIVERED_OWN_SPEECH", "scope", "CURRENT_ROOM_PLAYER_AND_AUDIENCE"));
    }

    void invalidate(UUID room) { issued.remove(room); pending.remove(room); states.keySet().removeIf(key -> key.room().equals(room)); }
    String visitHint(com.sande.mythictrpg.godavatar.visit.GodVisitPlanner.Request request) {
        if (request.dialogue().isEmpty()) return "UNASSESSED: no current conversation emotion was supplied";
        var dialogue = request.dialogue().orElseThrow();
        return states.entrySet().stream().filter(e -> e.getKey().room().equals(dialogue.roomId())
                && e.getKey().revision() == dialogue.revision() && e.getKey().god().equals(request.godId())
                && e.getKey().player().equals(request.playerId())
                && e.getValue().publishedSpeech().entrySet().stream().allMatch(source -> dialogue.history().stream()
                    .anyMatch(line -> source.getKey().equals(line.messageId()) && source.getValue().equals(line.text())
                            && line.role().equals("NPC") && line.speakerId().equals(request.godId()))))
                .map(e -> "NPC_INTERPRETATION_NOT_GAME_TRUTH: " + e.getValue().hint()).findFirst()
                .orElse("UNASSESSED: infer cautiously from supplied current dialogue, not another room");
    }
    void clear() { issued.clear(); pending.clear(); states.clear(); }
}
