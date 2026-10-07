package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.GodState;
import com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext;
import com.sande.mythictrpg.recording.api.MemoryReadSession;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import net.minecraft.resources.ResourceLocation;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** No models/DB: query intent is metadata, and reusing focus never creates read authority. */
public final class RecordedRecallQueryTest {
    private static final UUID ROOM = UUID.randomUUID(), PLAYER = UUID.randomUUID(), OTHER = UUID.randomUUID(), WORLD = UUID.randomUUID();
    private static final ResourceLocation GOD = ResourceLocation.parse("test:athena"), OTHER_GOD = ResourceLocation.parse("test:hermes");
    private static final ActorRef A = new ActorRef(ActorKind.PLAYER, PLAYER.toString()), B = new ActorRef(ActorKind.PLAYER, OTHER.toString()),
            SELF = new ActorRef(ActorKind.GOD, GOD.toString()), ANOTHER = new ActorRef(ActorKind.GOD, OTHER_GOD.toString());
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static Request request(String input) {
        return new Request(ROOM, 2, UUID.randomUUID(), PLAYER, "Fixture", List.of(GOD, OTHER_GOD), GOD, input,
                List.of(), false, true, false, List.of(new GodState(GOD, "R_NEUTRAL", "E_NEUTRAL", "", null)), false, Set.of(PLAYER, OTHER));
    }
    private static RecallQuery.Scope scope(Request request) {
        return new RecallQuery.Scope(new MemoryJournal.Key(WORLD, request.speakerGodId().toString(), request.playerId()),
                UUID.nameUUIDFromBytes((request.roomId() + ":" + request.revision()).getBytes(StandardCharsets.UTF_8)), request.audiencePlayerIds());
    }
    private static RecordedRecallQuery.Prepared planned(String input) {
        var request = request(input);
        return RecordedRecallQuery.prepare(request, Optional.of(RecallQuery.plan(scope(request), input, 1, 1000, null))).orElseThrow();
    }
    public static void main(String[] args) {
        selection("내가 뭐라고 말했지?", Set.of(A));
        selection("네가 뭐라고 말했지?", Set.of(SELF));
        selection("다른 신이 뭐라고 말했지?", Set.of(ANOTHER));
        selection("신이 뭐라고 말했지?", Set.of(SELF, ANOTHER));
        selection("test:hermes가 뭐라고 말했지?", Set.of(ANOTHER));
        selection("무슨 약속이었더라?", Set.of(A, B, SELF, ANOTHER));
        selection("내가 네가 뭐라고 말했지?", Set.of(A, B, SELF, ANOTHER));
        var explicit = planned("test:hermes가 뭐라고 말했지?");
        check(!explicit.query().text().contains("test:hermes"), "ID is actor metadata rather than a lexical topic or embedding token");
        check(explicit.semanticEligible(), "validated explicit plan can attempt semantic lane");
        check(!planned("안녕하세요").semanticEligible(), "ordinary chat does not create query embeddings");
        check(planned("내가 내일 어디 간다고 말했지?").query().fromInclusive().isEmpty(), "mentioned future date is not archive occurrence lower bound");
        check(planned("내가 어제 어디 간다고 말했지?").query().untilExclusive().isEmpty(), "mentioned past date is not archive occurrence upper bound");
        var initial = request("내가 내일 어디 간다고 말했지?");
        var first = RecallQuery.plan(scope(initial), initial.currentText(), 10, 1000, null);
        var next = request("다시 알려줘");
        var follow = RecallQuery.plan(scope(next), next.currentText(), 11, 2000, first.focus());
        var carried = RecordedRecallQuery.prepare(next, Optional.of(follow)).orElseThrow();
        check(follow.followUp() && carried.semanticEligible() && carried.query().text().equals(first.text()), "bare follow-up uses original player question");
        check(carried.query().actorSelection().matches(A) && !carried.query().actorSelection().matches(B), "focus preserves requester attribution");
        var shiftedDate = new RecallQuery(follow.text(), true, true, follow.askedAt() + 1, follow.focus(), follow.scope());
        check(RecordedRecallQuery.prepare(next, Optional.of(shiftedDate)).isEmpty(), "follow-up cannot silently change original date basis");
        var wrongWorld = new Request(next.roomId(), next.revision(), next.turnId(), next.playerId(), next.playerName(), next.godIds(),
                next.speakerGodId(), next.currentText(), next.history(), next.readOnly(), next.recording(), next.publicRoom(),
                List.of(new GodState(GOD, "R_NEUTRAL", "E_NEUTRAL", "", new ConversationMemoryContext(UUID.randomUUID(), ROOM,
                        UUID.randomUUID(), PLAYER, GOD.toString(), next.audiencePlayerIds(), false))), false, next.audiencePlayerIds());
        check(RecordedRecallQuery.prepare(wrongWorld, Optional.of(follow)).isEmpty(), "game memory world and carried plan world must agree");
        var expired = RecallQuery.plan(scope(next), next.currentText(), 14, 2000, first.focus());
        check(!RecordedRecallQuery.prepare(next, Optional.of(expired)).orElseThrow().semanticEligible(), "existing three-turn expiry remains authoritative for focus planning");
        var aged = RecallQuery.plan(scope(next), next.currentText(), 11, 301001, first.focus());
        check(!RecordedRecallQuery.prepare(next, Optional.of(aged)).orElseThrow().semanticEligible(), "existing five-minute expiry remains");
        var change = request("아무튼 다른 얘기 하자");
        var changed = RecallQuery.plan(scope(change), change.currentText(), 11, 2000, first.focus());
        check(!RecordedRecallQuery.prepare(change, Optional.of(changed)).orElseThrow().semanticEligible(), "topic change clears semantic recall intent");
        check(RecordedRecallQuery.prepare(change, Optional.of(follow)).isEmpty(), "old focus is not reused for unrelated input");
        var absent = RecordedRecallQuery.prepare(initial, Optional.empty()).orElseThrow();
        check(!absent.semanticEligible() && absent.query().actorSelection().matches(A) && !absent.query().actorSelection().matches(B), "missing plan permits only narrowed raw comparison, not second semantic planning");
        for (int mismatch = 0; mismatch < 4; mismatch++) {
            var expected = scope(initial);
            var wrong = new RecallQuery.Scope(new MemoryJournal.Key(WORLD, mismatch == 0 ? OTHER_GOD.toString() : GOD.toString(),
                    mismatch == 1 ? OTHER : PLAYER), mismatch == 2 ? UUID.randomUUID() : expected.generation(),
                    mismatch == 3 ? Set.of(PLAYER) : expected.audience());
            var foreign = RecallQuery.plan(wrong, initial.currentText(), 1, 1000, null);
            check(RecordedRecallQuery.prepare(initial, Optional.of(foreign)).isEmpty(), "God/player/room-generation/audience plan mismatch fails closed");
        }
        var reader = new Session();
        RecordedMemoryShadow.compare(() -> Optional.of(reader), next, Runnable::run, Set.of(), Optional.of(follow));
        check(reader.queries.size() == 1 && reader.queries.getFirst().equals(carried.query()), "raw SHADOW receives exact existing focus and selector");
        RecordedMemoryShadow.compare(() -> { throw new AssertionError("mismatched focus must not even open a reader"); },
                change, Runnable::run, Set.of(), Optional.of(follow));
        check(reader.queries.size() == 1, "no stale-plan fallback to broader query");
        System.out.println("RecordedRecallQueryTest: " + checks + " checks passed; no LLM or server");
    }
    private static void selection(String input, Set<ActorRef> expected) {
        var query = planned(input).query();
        for (var actor : List.of(A, B, SELF, ANOTHER)) check(query.actorSelection().matches(actor) == expected.contains(actor), "exact source intent: " + input);
    }
    private static final class Session implements MemoryReadSession {
        final List<Query> queries = new ArrayList<>();
        final Set<Page> issued = Collections.newSetFromMap(new IdentityHashMap<>());
        @Override public CompletableFuture<Page> query(Query query, Optional<Cursor> cursor, Budget budget) {
            queries.add(query); var page = new Page(Status.PARTIAL, List.of(), Optional.empty()); issued.add(page);
            return CompletableFuture.completedFuture(page);
        }
        @Override public boolean current(Page page) { return issued.contains(page); }
    }
}
