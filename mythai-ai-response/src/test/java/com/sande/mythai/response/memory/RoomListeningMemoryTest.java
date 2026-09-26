package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext;
import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Actual receipt projection/journal replay tests; no Minecraft server or model requests. */
final class RoomListeningMemoryTest {
    private static final String A = "mythictrpg:athena", B = "mythictrpg:hermes", OUTSIDE = "mythictrpg:hades";
    private static final UUID WORLD = UUID.randomUUID(), ROOM = UUID.randomUUID(), PLAYER = UUID.randomUUID(), PEER = UUID.randomUUID();
    private static final UUID GENERATION = UUID.randomUUID(), TURN = UUID.randomUUID();
    private static final Set<UUID> AUDIENCE = Set.of(PLAYER, PEER);
    private static final Set<String> GODS = Set.of(A, B);
    private static int checks;

    static int run(Path root) throws Exception {
        checks = 0;
        var request = request(true, false);
        var event = event("NPC", A, "신전의 봉인에 관해 아테나가 말한 내용", GODS, true, true, RecordingScope.TEST_RECORDING);
        var owner = RoomListeningMemory.entry(event, request, context(A), 1, "NPC", event.text()).orElseThrow();
        var listener = RoomListeningMemory.entry(event, request, context(B), 1, "NPC", event.text()).orElseThrow();
        check(owner.key().god().equals(A) && owner.speakerGodId().equals(A), "speaker retains self-authored attribution");
        check(listener.key().god().equals(B) && listener.speakerGodId().equals(A), "listener remembers the actual speaker, not itself");
        check(owner.audience().equals(AUDIENCE) && listener.godAudience().equals(GODS), "exact player and God disclosure scopes retained");
        check(!owner.id().equals(listener.id()), "distinct owner keys cannot collide on one shared message");
        check(listener.equals(RoomListeningMemory.entry(event, request, context(B), 1, "NPC", event.text()).orElseThrow()),
                "duplicate publication creates a stable memory identity");
        check(RoomListeningMemory.entry(event, request, context(OUTSIDE), 1, "NPC", event.text()).isEmpty(), "outsider cannot acquire memory");
        check(RoomListeningMemory.entry(event, request, context(B), 1, "NPC", "unpublished draft").isEmpty(), "draft differs from certified speech");
        var legacy = new RoomDialogueEvent(event.messageId(), ROOM, 4, Optional.of(TURN), RoomType.PRIVATE,
                RecordingScope.TEST_RECORDING, "NPC", A, event.text(), GODS, event.participantNames(), event.deliveries(), event.occurredAtUtc());
        check(!RoomListeningMemory.matches(legacy, request, "NPC", event.text()), "legacy membership alone grants no hearing proof");
        var wrongTurn = new Request(ROOM, 4, UUID.randomUUID(), PLAYER, "player", request.godIds(), request.speakerGodId(),
                request.currentText(), List.of(), false, true, false, request.godStates());
        check(!RoomListeningMemory.matches(event, wrongTurn, "NPC", event.text()), "late response turn rejected");
        var oldRevision = new Request(ROOM, 3, TURN, PLAYER, "player", request.godIds(), request.speakerGodId(),
                request.currentText(), List.of(), false, true, false, request.godStates());
        check(!RoomListeningMemory.matches(event, oldRevision, "NPC", event.text()), "old room revision rejected");
        check(RoomListeningMemory.entry(event, request(false, false), context(B), 1, "NPC", event.text()).isEmpty(), "record-off request cannot store");
        var off = event("NPC", A, event.text(), GODS, true, true, RecordingScope.TEST_EPHEMERAL);
        check(RoomListeningMemory.entry(off, request, context(B), 1, "NPC", off.text()).isEmpty(), "record-off publication cannot store");
        var unreceived = event("NPC", A, event.text(), GODS, false, true, RecordingScope.TEST_RECORDING);
        check(RoomListeningMemory.entry(unreceived, request, context(B), 1, "NPC", unreceived.text()).isEmpty(), "partial HUD is not full-text receipt");
        var partial = event("NPC", A, event.text(), GODS, true, false, RecordingScope.TEST_RECORDING);
        var narrow = RoomListeningMemory.entry(partial, request, context(B), 1, "NPC", partial.text()).orElseThrow();
        check(narrow.audience().equals(Set.of(PLAYER)), "undelivered peer cannot become a disclosure recipient");
        var onlySpeaker = event("NPC", A, event.text(), Set.of(A), true, true, RecordingScope.TEST_RECORDING);
        check(RoomListeningMemory.entry(onlySpeaker, request, context(B), 1, "NPC", event.text()).isEmpty(), "God membership without individual hearing rejected");
        var wrongRoom = new ConversationMemoryContext(WORLD, UUID.randomUUID(), GENERATION, PLAYER, B, AUDIENCE, false);
        check(RoomListeningMemory.entry(event, request, wrongRoom, 1, "NPC", event.text()).isEmpty(), "another room context cannot authorize a write");
        var otherPlayer = new ConversationMemoryContext(WORLD, ROOM, GENERATION, PEER, B, AUDIENCE, false);
        check(RoomListeningMemory.entry(event, request, otherPlayer, 1, "NPC", event.text()).isEmpty(), "another player's bucket remains isolated");
        var oversized = new HashSet<>(AUDIENCE); while (oversized.size() < 17) oversized.add(UUID.randomUUID());
        check(RoomListeningMemory.entry(event, request, new ConversationMemoryContext(WORLD, ROOM, GENERATION, PLAYER, B, oversized, false),
                1, "NPC", event.text()).isEmpty(), "16-player journal limit is not silently expanded");
        var input = event("PLAYER", PLAYER.toString(), request.currentText(), GODS, false, false, RecordingScope.TEST_RECORDING);
        check(RoomListeningMemory.entry(input, request, context(B), 1, "PLAYER", input.text()).orElseThrow().audience().equals(Set.of(PLAYER)),
                "accepted input belongs to its author even with no successful player dispatch");
        check(RoomListeningMemory.entry(input, request(true, true), context(B), 2, "PLAYER", input.text()).isEmpty(),
                "secondary response cannot record the same player message again");
        String longText = "x".repeat(1199) + "🙂" + "suffix";
        var longEvent = event("NPC", A, longText, GODS, true, true, RecordingScope.TEST_RECORDING);
        check(RoomListeningMemory.entry(longEvent, request, context(B), 1, "NPC", longText).orElseThrow().text().length() == 1199,
                "bounded memory excerpt never splits a surrogate pair");

        Path directory = root.resolve("heard-npc");
        try (var journal = new MemoryJournal(directory)) {
            check(journal.append(listener).get(5, TimeUnit.SECONDS) == MemoryJournal.Result.STORED, "listener memory is durably stored");
            check(journal.append(listener).get(5, TimeUnit.SECONDS) == MemoryJournal.Result.DUPLICATE, "repeated receipt is persisted at most once");
            check(journal.append(narrow).get(5, TimeUnit.SECONDS) == MemoryJournal.Result.STORED, "partially delivered player audience stored narrowly");
            check(journal.readView(listener.key(), AUDIENCE, GODS).entries().equals(List.of(listener)), "full audience excludes partial-publication entry");
            check(journal.readView(listener.key(), Set.of(PLAYER), Set.of(B)).entries().size() == 2, "listener can recall in a narrower audience");
            check(journal.readView(listener.key(), Set.of(PLAYER), Set.of(B, OUTSIDE)).entries().isEmpty(), "new God cannot receive private heard memory");
            check(journal.searchConversation(listener.key(), AUDIENCE, "신전의 봉인", Set.of(), List.of(), UUID.randomUUID(),
                    System.currentTimeMillis(), 3, TimeUnit.SECONDS.toNanos(1), GODS).equals(List.of(listener)),
                    "ordinary recall finds exactly the heard speech authorized for the current full audience");
            var deduped = journal.searchConversation(listener.key(), Set.of(PLAYER), "신전의 봉인", Set.of(), List.of(), UUID.randomUUID(),
                    System.currentTimeMillis(), 3, TimeUnit.SECONDS.toNanos(1), Set.of(B));
            check(deduped.size() == 1 && deduped.getFirst().speakerGodId().equals(A) && deduped.getFirst().text().equals(event.text()),
                    "duplicate certified words consume one slot without losing original speaker attribution");
            var spoofed = new MemoryJournal.Entry(UUID.randomUUID(), listener.key(), listener.session(), listener.turn(), listener.source(),
                    listener.audience(), listener.occurredAt(), "changed", false, GODS, B);
            check(journal.supersede(listener.id(), spoofed, journal.view().revision()).get(5, TimeUnit.SECONDS) == MemoryJournal.Result.STALE,
                    "correction cannot silently change speech authorship");
            check(journal.pin(listener.id(), journal.view().revision(), true).get(5, TimeUnit.SECONDS) == MemoryJournal.Result.STORED,
                    "pin retains listener memory");
            check(journal.view().entries().stream().filter(e -> e.id().equals(listener.id())).findFirst().orElseThrow().speakerGodId().equals(A),
                    "pin preserves original speaker");
        }
        try (var journal = new MemoryJournal(directory)) {
            check(journal.awaitIdle(Duration.ofSeconds(5)) && journal.ready(), "listener metadata replays after restart");
            check(journal.readView(listener.key(), AUDIENCE, GODS).entries().getFirst().speakerGodId().equals(A), "replayed speaker does not turn into owner");
        }
        var legacyNpc = new Gson().toJsonTree(listener).getAsJsonObject(); legacyNpc.remove("speakerGodId");
        check(new Gson().fromJson(legacyNpc, MemoryJournal.Entry.class).speakerGodId().equals(B), "old NPC JSON defaults only to its proven owner");
        check(DialogueMemoryBridge.prompt(List.of(listener), List.of()).contains("\"speaker_god_id\":\"" + A + "\""),
                "legacy memory prompt attributes heard speech");
        var asSelf = new MemoryJournal.Entry(listener.id(), listener.key(), listener.session(), listener.turn(), listener.source(),
                listener.audience(), listener.occurredAt(), listener.text(), listener.important(), GODS, B);
        check(!DerivedMemory.fingerprint(listener).equals(DerivedMemory.fingerprint(asSelf)), "speaker identity participates in source fingerprint");
        receiptCache(request, event);
        guardedWithoutPersonalMemory();
        differentSpeakers(root.resolve("attributed-retrieval"));
        return checks;
    }

    private static void differentSpeakers(Path directory) throws Exception {
        var key = new MemoryJournal.Key(WORLD, B, PLAYER);
        String text = "신전의 봉인을 지켜보고 있었다";
        long now = System.currentTimeMillis();
        var a = new MemoryJournal.Entry(UUID.randomUUID(), key, GENERATION, 1, MemoryJournal.Source.NPC_UTTERANCE,
                AUDIENCE, now - 30, text, false, GODS, A);
        var b = new MemoryJournal.Entry(UUID.randomUUID(), key, GENERATION, 2, MemoryJournal.Source.NPC_UTTERANCE,
                AUDIENCE, now - 20, text, false, GODS, B);
        var repeat = new MemoryJournal.Entry(UUID.randomUUID(), key, GENERATION, 3, MemoryJournal.Source.NPC_UTTERANCE,
                AUDIENCE, now - 10, text, false, GODS, A);
        try (var journal = new MemoryJournal(directory)) {
            for (var entry : List.of(a, b, repeat)) check(journal.append(entry).get(5, TimeUnit.SECONDS) == MemoryJournal.Result.STORED,
                    "different-speaker/repetition fixture persisted");
            var legacy = journal.searchConversation(key, AUDIENCE, "신전의 봉인", Set.of(), List.of(), UUID.randomUUID(), now,
                    3, TimeUnit.SECONDS.toNanos(1), GODS);
            check(legacy.size() == 2 && legacy.stream().map(MemoryJournal.Entry::speakerGodId).collect(java.util.stream.Collectors.toSet()).equals(GODS),
                    "legacy retrieval preserves two speakers while collapsing one speaker's repetition");
            var query = RecallQuery.plan(new RecallQuery.Scope(key, UUID.randomUUID(), AUDIENCE), "신전의 봉인", 1, now, null);
            var result = RecallSearch.search(journal.readView(key, AUDIENCE, GODS), query, RecallSettings.OFF, Set.of(), List.of(), now,
                    TimeUnit.SECONDS.toNanos(1));
            check(result.selected().size() == 2 && result.selected().stream().map(MemoryJournal.Entry::speakerGodId)
                    .collect(java.util.stream.Collectors.toSet()).equals(GODS), "v2 retrieval also keeps actual speaker identity in deduplication");
            String packed = MemoryRecallPolicy.pack(result, List.of()).prompt();
            check(packed.contains("\"speaker_god_id\":\"" + A + "\"") && packed.contains("\"speaker_god_id\":\"" + B + "\""),
                    "both speakers survive bounded v2 prompt packing without becoming the memory owner");
        }
    }

    private static void guardedWithoutPersonalMemory() throws Exception {
        var room = UUID.randomUUID();
        var offRequest = new Request(room, 4, TURN, PLAYER, "player", List.of(ResourceLocation.parse(A), ResourceLocation.parse(B)),
                ResourceLocation.parse(A), "story", List.of(), true, false, false,
                List.of(new GodState(ResourceLocation.parse(A), "R_NEUTRAL", "E_NEUTRAL", "", null)));
        String text = "STORY_RESULT_WITH_RECORDING_AND_MEMORY_OFF";
        var current = new java.util.concurrent.atomic.AtomicBoolean(true);
        var reference = ExperienceHistory.Reference.guarded(current::get);
        var noMemory = DialogueMemoryBridge.inherit(DialogueMemoryBridge.EMPTY, List.of(reference));
        check(noMemory.context() == null && noMemory.journal() == null && noMemory.hasGuardedEvidence(),
                "story guard exists independently of a personal-memory context or journal");
        check(DialogueMemoryBridge.roomTurnCurrent(null, noMemory), "live inherited story evidence passes with memory OFF");
        check(DialogueMemoryBridge.recordRoomEvidence(offRequest, null, text, noMemory.evidence()),
                "validated delivered line retains ephemeral provenance with recording and memory OFF");
        var history = List.of(new HistoryLine("NPC", A, "Athena", text, room));
        var inherited = DialogueMemoryBridge.roomHistoryReferences(room, history);
        check(inherited.equals(List.of(reference)), "later no-memory turns can inherit previously delivered story provenance");
        var repeated = DialogueMemoryBridge.inherit(DialogueMemoryBridge.EMPTY, inherited);
        check(DialogueMemoryBridge.roomTurnCurrent(null, repeated), "no-context repeated line carries the live original guard");
        current.set(false);
        check(!DialogueMemoryBridge.roomTurnCurrent(null, noMemory), "story revocation invalidates pending generation with memory OFF");
        check(!DialogueMemoryBridge.roomTurnCurrent(null, repeated), "revocation also invalidates no-context retransmission");
        check(DialogueMemoryBridge.excludedRoomHistory(room, List.of(text)).equals(Set.of(text)),
                "revoked story line is pruned even though it was never stored in the personal journal");
        check(DialogueMemoryBridge.recordRoomEvidence(offRequest, null, "OFF_ALREADY_DISPATCHED_STORY", noMemory.evidence())
                && DialogueMemoryBridge.excludedRoomHistory(room, List.of("OFF_ALREADY_DISPATCHED_STORY"))
                    .contains("OFF_ALREADY_DISPATCHED_STORY"), "revocation after OFF dispatch still attaches an immediately blocking guard");
        check(!DialogueMemoryBridge.recordRoomEvidence(offRequest, null, "NO_EVIDENCE", List.of()), "unguarded drafts are not put into guarded history");
        var onRequest = request(true, false);
        var dispatched = event("NPC", A, "ALREADY_DISPATCHED_STORY", GODS, true, true, RecordingScope.TEST_RECORDING);
        check(DialogueMemoryBridge.recordRoomEvidence(onRequest, dispatched, dispatched.text(), noMemory.evidence()),
                "exact dispatch receipt allows expired provenance to be attached after synchronous story-state change");
        check(DialogueMemoryBridge.excludedRoomHistory(ROOM, List.of(dispatched.text())).contains(dispatched.text()),
                "already-dispatched stale story text cannot be laundered into unguarded subsequent history");
        check(!DialogueMemoryBridge.recordRoomEvidence(onRequest, dispatched, "UNSENT_DRAFT", noMemory.evidence()),
                "expired source does not authorize a different unpublished draft");
        check(!DialogueMemoryBridge.recordRoomEvidence(onRequest, null, dispatched.text(), noMemory.evidence()),
                "recording-on provenance cannot be installed without a matching receipt");
        var staleTurn = new Request(ROOM, 4, UUID.randomUUID(), PLAYER, "player", onRequest.godIds(), onRequest.speakerGodId(),
                "story", List.of(), false, true, false, onRequest.godStates());
        check(!DialogueMemoryBridge.recordRoomEvidence(staleTurn, dispatched, dispatched.text(), noMemory.evidence()),
                "a different generation turn cannot adopt the dispatched line's expired provenance");
        var otherSpeaker = event("NPC", B, dispatched.text(), GODS, true, true, RecordingScope.TEST_RECORDING);
        check(!DialogueMemoryBridge.recordRoomEvidence(onRequest, otherSpeaker, otherSpeaker.text(), noMemory.evidence()),
                "another speaker's dispatch cannot authorize this speaker's provenance");
        var excess = new ArrayList<ExperienceHistory.Reference>();
        for (int i = 0; i <= ExperienceHistory.INHERITED_LIMIT; i++) excess.add(ExperienceHistory.Reference.guarded(() -> true));
        check(!DialogueMemoryBridge.roomTurnCurrent(null, DialogueMemoryBridge.inherit(DialogueMemoryBridge.EMPTY, excess)),
                "no-memory path still enforces inherited-evidence budget");
        DialogueMemoryBridge.invalidateRoom(room);
        check(DialogueMemoryBridge.excludedRoomHistory(room, List.of(text)).equals(Set.of(text)),
                "invalidating an OFF room cannot erase provenance of history copied into another room");
        var field = DialogueMemoryBridge.class.getDeclaredField("ROOM_HISTORY"); field.setAccessible(true);
        ((Map<?, ?>) field.get(null)).remove(room);
        ((Map<?, ?>) field.get(null)).remove(ROOM);
    }

    private static void receiptCache(Request request, RoomDialogueEvent event) throws Exception {
        var take = DialogueMemoryBridge.class.getDeclaredMethod("takeRoomReceipt", Request.class, String.class, String.class); take.setAccessible(true);
        DialogueMemoryBridge.roomPublished(event);
        check(take.invoke(null, request, "NPC", "unpublished") == null, "receipt cache does not authorize a different draft");
        check(event.equals(take.invoke(null, request, "NPC", event.text())), "exact delivered speech consumes its receipt");
        check(take.invoke(null, request, "NPC", event.text()) == null, "one queued receipt cannot authorize a second commit");
        DialogueMemoryBridge.roomPublished(event); DialogueMemoryBridge.invalidateRoom(ROOM);
        check(take.invoke(null, request, "NPC", event.text()) == null, "room invalidation removes unconsumed receipts");
    }
    private static ConversationMemoryContext context(String god) { return new ConversationMemoryContext(WORLD, ROOM, GENERATION, PLAYER, god, AUDIENCE, false); }
    private static Request request(boolean recording, boolean secondary) {
        var a = ResourceLocation.parse(A); var b = ResourceLocation.parse(B);
        return new Request(ROOM, 4, TURN, PLAYER, "player", List.of(a, b), a, "신전의 봉인은 어땠어?", List.of(), secondary, recording, false,
                List.of(new GodState(a, "R_NEUTRAL", "E_NEUTRAL", "", context(A))), secondary);
    }
    private static RoomDialogueEvent event(String role, String speaker, String text, Set<String> heard,
            boolean playerChat, boolean peerChat, RecordingScope scope) {
        return new RoomDialogueEvent(UUID.randomUUID(), ROOM, 4, Optional.of(TURN), RoomType.PRIVATE, scope, role, speaker, text,
                GODS, Map.of(PLAYER, "player", PEER, "peer"), Map.of(PLAYER, new RoomDialogueEvent.Delivery("player", playerChat, 1),
                PEER, new RoomDialogueEvent.Delivery("peer", peerChat, 1)), System.currentTimeMillis(), heard);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
