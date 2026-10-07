package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.recording.api.MemoryReadSession;
import com.sande.mythictrpg.recording.api.RecordingRecords.ActorKind;
import com.sande.mythictrpg.recording.api.RecordingRecords.ActorRef;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reuses the existing player-only recall plan; filters can narrow, never grant source knowledge. */
final class RecordedRecallQuery {
    record Prepared(MemoryReadSession.Query query, boolean semanticEligible) { }
    private RecordedRecallQuery() { }

    static Optional<Prepared> prepare(Request request, Optional<RecallQuery> carried) {
        Objects.requireNonNull(request); Objects.requireNonNull(carried);
        String input = request.currentText().trim();
        if (carried.isPresent() && !matches(request, carried.orElseThrow(), input)) return Optional.empty();
        String original = carried.map(RecallQuery::text).orElse(input);
        String text = RecallSourceScope.lexicalQuery(original);
        var source = RecallSourceScope.resolve(original);
        var selection = switch (source.role()) {
            case PLAYER -> exact(Set.of(new ActorRef(ActorKind.PLAYER, request.playerId().toString())));
            case THIS_GOD -> exact(Set.of(new ActorRef(ActorKind.GOD, request.speakerGodId().toString())));
            case OTHER_GOD -> new MemoryReadSession.ActorSelection(Optional.of(ActorKind.GOD), Set.of(),
                    Set.of(new ActorRef(ActorKind.GOD, request.speakerGodId().toString())));
            case ANY_GOD -> new MemoryReadSession.ActorSelection(Optional.of(ActorKind.GOD), Set.of(), Set.of());
            case IDENTIFIED_GOD -> exact(source.speakerGodIds().stream().map(id -> new ActorRef(ActorKind.GOD, id))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            case UNSPECIFIED -> MemoryReadSession.ActorSelection.ANY;
        };
        // The existing temporal matcher describes dates mentioned IN an utterance. Those are not
        // occurrence-time filters: a promise about tomorrow may have been spoken much earlier.
        var query = new MemoryReadSession.Query(text, Optional.empty(), Optional.empty(), selection);
        return Optional.of(new Prepared(query, carried.filter(RecallQuery::explicit).isPresent()
                && !text.isBlank() && text.length() <= 1600));
    }

    private static MemoryReadSession.ActorSelection exact(Set<ActorRef> actors) {
        return new MemoryReadSession.ActorSelection(Optional.empty(), actors, Set.of());
    }

    private static boolean matches(Request request, RecallQuery plan, String input) {
        if (plan.scope() == null || plan.scope().key() == null) return false;
        var memory = request.speakerState().memoryContext();
        if (memory != null && !memory.worldId().equals(plan.scope().key().world())) return false;
        UUID expected = UUID.nameUUIDFromBytes((request.roomId() + ":" + request.revision()).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(plan.scope().generation()) || !request.playerId().equals(plan.scope().key().player())
                || !request.speakerGodId().toString().equals(plan.scope().key().god())
                || !request.audiencePlayerIds().equals(plan.scope().audience())) return false;
        if (!plan.followUp()) return input.equals(plan.text());
        return plan.explicit() && RecallQuery.bareFollowUp(input) && plan.focus() != null
                && plan.scope().equals(plan.focus().scope()) && plan.text().equals(plan.focus().question())
                && plan.askedAt() == plan.focus().askedAt();
    }
}
