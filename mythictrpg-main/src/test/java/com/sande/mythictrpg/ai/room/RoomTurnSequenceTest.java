package com.sande.mythictrpg.ai.room;

import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Exercises actual room/lease/result/dispatch contracts; no synthetic replacement for the engine. */
public final class RoomTurnSequenceTest {
    private static final ResourceLocation PRIMARY = ResourceLocation.parse("mythictrpg:demeter");
    private static final ResourceLocation SECONDARY = ResourceLocation.parse("mythictrpg:fortuna");
    private static final ResourceLocation OUTSIDE = ResourceLocation.parse("sequence_test:outside");
    private static int checks;

    public static void main(String[] args) {
        boundedSecondaryEligibility();
        primaryAndSecondaryResultAuthority();
        bodyAndBundleLimits();
        completePlayerDelivery();
        newInputSupersedesPendingSecondary();
        participantChangesInvalidatePendingSecondary();
        independentRoomsDoNotCancelEachOther();
        ownContextAndImmutableRequests();
        certifiedListenersExcludeUnavailableGods();
        secondaryRecallUsesPermittedNpcSpeechOnly();
        System.out.println("RoomTurnSequenceTest: " + checks + " assertions passed");
    }

    private static void secondaryRecallUsesPermittedNpcSpeechOnly() {
        check(RoomTurnPolicy.latestPermittedNpcText(List.of()).isEmpty(), "no permitted history produces no recall query");
        var player = new RoomConversationEngine.HistoryLine("PLAYER", UUID.randomUUID().toString(), "player", "UNHEARD_PLAYER_SECRET");
        var system = new RoomConversationEngine.HistoryLine("SYSTEM", "system", "system", "system notice");
        check(RoomTurnPolicy.latestPermittedNpcText(List.of(player, system)).isEmpty(), "player/system lines never become secondary recall query");
        var old = new RoomConversationEngine.HistoryLine("NPC", PRIMARY.toString(), "primary", "earlier allowed speech");
        var latest = new RoomConversationEngine.HistoryLine("NPC", SECONDARY.toString(), "secondary", "  newest allowed speech 🌾\n");
        check(RoomTurnPolicy.latestPermittedNpcText(List.of(old, player, latest, system)).equals(latest.text()),
                "latest permitted NPC speech is used verbatim without player input");
        check(RoomTurnPolicy.latestPermittedNpcText(List.of(old, player)).equals(old.text()), "later player line cannot overwrite NPC recall source");
        var blank = new RoomConversationEngine.HistoryLine("NPC", PRIMARY.toString(), "primary", " \n\t");
        check(RoomTurnPolicy.latestPermittedNpcText(List.of(old, blank)).isEmpty(), "blank latest NPC does not substitute an older utterance");
        var boundary = new RoomConversationEngine.HistoryLine("NPC", PRIMARY.toString(), "primary", "가".repeat(4000));
        check(RoomTurnPolicy.latestPermittedNpcText(List.of(boundary)).equals(boundary.text()), "4000-character NPC query remains intact");
        var oversized = new RoomConversationEngine.HistoryLine("NPC", PRIMARY.toString(), "primary", "가".repeat(4001));
        check(RoomTurnPolicy.latestPermittedNpcText(List.of(old, oversized)).isEmpty(), "oversized latest NPC is not truncated or replaced");
        var emoji = new RoomConversationEngine.HistoryLine("NPC", PRIMARY.toString(), "primary", "😀".repeat(2000));
        check(RoomTurnPolicy.latestPermittedNpcText(List.of(emoji)).equals(emoji.text()), "UTF-16 body boundary preserves complete supplementary characters");
    }
    private static void certifiedListenersExcludeUnavailableGods() {
        var room = snapshot(RoomType.PRIVATE, Set.of(UUID.randomUUID()), List.of(PRIMARY, SECONDARY));
        check(RoomTurnPolicy.heardGods(room, id -> id.equals(PRIMARY.toString())).equals(Set.of(PRIMARY.toString())),
                "sealed/absent member is not certified as listener");
        check(RoomTurnPolicy.heardGods(room, id -> false).isEmpty(), "no available gods produce no hearing grant");
        check(RoomTurnPolicy.heardGods(room, id -> true).equals(room.godIds()), "available virtual participants hear publication");
        var event = event(room.playerIds().iterator().next(), "test");
        check(event.heardGodIds().isEmpty(), "legacy publication constructor grants no God hearing");
        rejects(() -> event.withHeardGods(Set.of(OUTSIDE.toString())), "outside God cannot receive hearing proof");
    }

    private static void boundedSecondaryEligibility() {
        UUID player = UUID.randomUUID(), peer = UUID.randomUUID();
        for (var type : RoomType.values()) {
            var room = snapshot(type, Set.of(player), List.of(PRIMARY, SECONDARY));
            check(RoomTurnPolicy.secondarySpeaker(room, PRIMARY.toString()).orElseThrow().equals(SECONDARY.toString()),
                    type + " permits one existing secondary");
            check(RoomTurnPolicy.secondarySpeaker(room, SECONDARY.toString()).orElseThrow().equals(PRIMARY.toString()),
                    type + " follows selected speaker, not an absolute primary identity");
            check(RoomTurnPolicy.secondarySpeaker(room, OUTSIDE.toString()).isEmpty(), "outside primary cannot select a reaction");
            check(RoomTurnPolicy.secondarySpeaker(snapshot(type, Set.of(player), List.of(PRIMARY)), PRIMARY.toString()).isEmpty(),
                    "single God cannot invent a secondary");
            check(RoomTurnPolicy.reactionSpeakers(snapshot(type, Set.of(player), List.of(PRIMARY, SECONDARY, OUTSIDE)),
                    PRIMARY.toString()).size()==2, "all other Gods get an optional reaction opportunity");
            check(RoomTurnPolicy.secondarySpeaker(snapshot(type, Set.of(player, peer), List.of(PRIMARY, SECONDARY)),
                    PRIMARY.toString()).isPresent(), "multi-player room supports reactions");
        }
        var gods = new ArrayList<ResourceLocation>(); gods.add(PRIMARY);
        for (int i = 1; i < 16; i++) gods.add(ResourceLocation.parse("sequence_test:god_" + i));
        var players=new java.util.LinkedHashSet<UUID>();for(int i=0;i<64;i++)players.add(UUID.randomUUID());
        var reactions=RoomTurnPolicy.reactionSpeakers(snapshot(RoomType.PRIVATE, players, gods),PRIMARY.toString());
        check(reactions.size()==15&&new java.util.HashSet<>(reactions).size()==15&&!reactions.contains(PRIMARY.toString()),
                "64 players and 16 Gods retain every existing candidate exactly once, with no autonomous recursion");
    }

    private static void primaryAndSecondaryResultAuthority() {
        var room = snapshot(RoomType.PRIVATE, Set.of(UUID.randomUUID()), List.of(PRIMARY, SECONDARY));
        UUID turn = UUID.randomUUID();
        var first = request(room, turn, PRIMARY, false, false, ownStates(room, PRIMARY));
        var second = request(room, turn, SECONDARY, true, true, ownStates(room, SECONDARY));
        check(reply(first, List.of(new RoomConversationEngine.Speech(PRIMARY, "첫 답변")), "[]", List.of()).deliverableFor(first),
                "selected primary speech accepted");
        check(reply(first, List.of(), "[]", List.of()).deliverableFor(first) == false, "primary cannot silently succeed");
        check(reply(second, List.of(), "[]", List.of()).deliverableFor(second), "secondary can decline to speak");
        check(reply(second, List.of(new RoomConversationEngine.Speech(SECONDARY, "짧은 반응")), "[]", List.of()).deliverableFor(second),
                "selected secondary speech accepted");
        check(!reply(second, List.of(new RoomConversationEngine.Speech(PRIMARY, "도용")), "[]", List.of()).deliverableFor(second),
                "secondary cannot impersonate primary");
        check(!reply(first, List.of(new RoomConversationEngine.Speech(PRIMARY, "정상"), new RoomConversationEngine.Speech(SECONDARY, "끼어듦")),
                "[]", List.of()).deliverableFor(first), "mixed-speaker bundle rejected as a whole");
        check(!reply(second, List.of(), "[{\"type\":\"quest_offer\"}]", List.of()).deliverableFor(second), "silent secondary cannot offer a quest");
        check(!reply(second, List.of(new RoomConversationEngine.Speech(SECONDARY, "관계 변경")), "[{\"type\":\"god_relation_transition\"}]",
                List.of()).deliverableFor(second), "spoken secondary cannot mutate relations");
        check(!reply(second, List.of(), "[]", List.of(new RoomConversationEngine.Control("conversation_invite", "peer", ""))).deliverableFor(second),
                "secondary cannot invite");
        check(!reply(second, List.of(), "[]", List.of(new RoomConversationEngine.Control("conversation_end", "", ""))).deliverableFor(second),
                "secondary cannot end room attendance");
        check(reply(second, List.of(), " \n[]\t ", List.of()).deliverableFor(second), "empty proposal array permits harmless surrounding whitespace");
        check(!new RoomConversationEngine.Result(UUID.randomUUID(), room.revision(), turn, List.of(), "[]", List.of(), "").deliverableFor(second),
                "wrong room rejected even for silence");
        check(!new RoomConversationEngine.Result(room.roomId(), room.revision() + 1, turn, List.of(), "[]", List.of(), "").deliverableFor(second),
                "wrong revision rejected");
        check(!new RoomConversationEngine.Result(room.roomId(), room.revision(), UUID.randomUUID(), List.of(), "[]", List.of(), "").deliverableFor(second),
                "wrong turn rejected");
        check(!RoomConversationEngine.Result.failed(second, "timeout").deliverableFor(second), "secondary timeout is not successful silence");
        var hookRequest=request(room,turn,SECONDARY,false,true,ownStates(room,SECONDARY));
        check(reply(hookRequest,List.of(new RoomConversationEngine.Speech(SECONDARY,"허용된 후속 사건 제안")),
                "[{\"type\":\"story_event_hook\"}]",List.of()).deliverableFor(hookRequest),"live secondary can carry Story Hook for game validation");
        check(!reply(second,List.of(new RoomConversationEngine.Speech(SECONDARY,"읽기전용")),
                "[{\"type\":\"story_event_hook\"}]",List.of()).deliverableFor(second),"test/read-only reaction cannot carry hook");
        var three = snapshot(RoomType.PRIVATE, room.playerIds(), List.of(PRIMARY, SECONDARY, OUTSIDE));
        check(request(three,turn,SECONDARY,true,true,ownStates(three,SECONDARY)).godIds().size()==3,"reaction accepts existing multi-God room");
        var legacy = new RoomConversationEngine.Request(room.roomId(), room.revision(), turn, first.playerId(), "player", first.godIds(),
                PRIMARY, "말", List.of(), false, true, false, ownStates(room, PRIMARY));
        check(!legacy.secondary(), "old constructor retains primary authority semantics");
    }

    private static void bodyAndBundleLimits() {
        var room = snapshot(RoomType.PRIVATE, Set.of(UUID.randomUUID()), List.of(PRIMARY, SECONDARY));
        for (boolean secondary : List.of(false, true)) {
            var god = secondary ? SECONDARY : PRIMARY;
            var request = request(room, UUID.randomUUID(), god, secondary, secondary, ownStates(room, god));
            check(reply(request, List.of(new RoomConversationEngine.Speech(god, "가".repeat(4000))), "[]", List.of()).deliverableFor(request),
                    "4000-character body accepted without truncation");
            check(!reply(request, List.of(new RoomConversationEngine.Speech(god, "가".repeat(4001))), "[]", List.of()).deliverableFor(request),
                    "oversized body rejected at game boundary");
            check(!reply(request, List.of(new RoomConversationEngine.Speech(god, " \n\t")), "[]", List.of()).deliverableFor(request),
                    "blank body rejected");
            var eight = new ArrayList<RoomConversationEngine.Speech>();
            for (int i = 0; i < 8; i++) eight.add(new RoomConversationEngine.Speech(god, "대사 " + i));
            check(reply(request, eight, "[]", List.of()).deliverableFor(request), "bounded eight-part bundle accepted");
            eight.add(new RoomConversationEngine.Speech(god, "추가"));
            check(!reply(request, eight, "[]", List.of()).deliverableFor(request), "nine-part bundle rejected");
        }
    }

    private static void completePlayerDelivery() {
        UUID player = UUID.randomUUID(), peer = UUID.randomUUID();
        String text = "가".repeat(400);
        int pages = RoomHudText.pages(text).size();
        check(pages > 1, "test uses a multi-page utterance");
        var event = event(player, text);
        check(!RoomTurnPolicy.fullyDispatched(event, player), "no receipt cannot start secondary");
        check(RoomTurnPolicy.fullyDispatched(event.withDeliveries(Map.of(player, new RoomDialogueEvent.Delivery("player", true, 0))), player),
                "full chat body suffices even without HUD");
        check(RoomTurnPolicy.fullyDispatched(event.withDeliveries(Map.of(player, new RoomDialogueEvent.Delivery("player", false, pages))), player),
                "complete HUD suffices when chat hidden");
        check(!RoomTurnPolicy.fullyDispatched(event.withDeliveries(Map.of(player, new RoomDialogueEvent.Delivery("player", false, pages - 1))), player),
                "partial HUD cannot authorize reaction");
        check(!RoomTurnPolicy.fullyDispatched(event.withDeliveries(Map.of(player, new RoomDialogueEvent.Delivery("player", false, pages + 1))), player),
                "inconsistent HUD page count is not complete");
        check(!RoomTurnPolicy.fullyDispatched(event.withDeliveries(Map.of(player, new RoomDialogueEvent.Delivery("player", true, pages))), peer),
                "someone else's receipt cannot authorize reaction");
        var published = RoomDialoguePublisher.publish(event, Set.of(player), id -> new RoomDialogueEvent.Delivery("player", false, pages),
                ignored -> { }, ignored -> { });
        check(RoomTurnPolicy.fullyDispatched(published, player), "actual publisher receipt supports complete dispatch check");
        var failed = RoomDialoguePublisher.publish(event, Set.of(player), id -> null, ignored -> { }, ignored -> { });
        check(!RoomTurnPolicy.fullyDispatched(failed, player), "actual failed publisher never fabricates receipt");
    }

    private static void newInputSupersedesPendingSecondary() {
        var ledger = new ConversationRoomLedger(); UUID player = UUID.randomUUID();
        var room = ledger.create(RoomType.PRIVATE, player, List.of(PRIMARY.toString(), SECONDARY.toString()), "", RecordingScope.STANDARD);
        var chain = ledger.beginTurn(room.roomId(), room.revision(), player, PRIMARY.toString());
        check(ledger.latestTurnSequence(room.roomId())==chain.sequence(),"accepted input records a durable in-memory sequence");
        var primaryRequest = request(room, chain.turnId(), PRIMARY, false, false, ownStates(room, PRIMARY));
        var primaryResult = reply(primaryRequest, List.of(new RoomConversationEngine.Speech(PRIMARY, "완료")), "[]", List.of());
        check(ledger.isCurrent(chain) && primaryResult.deliverableFor(primaryRequest), "primary result current before secondary starts");
        var secondaryRequest = request(room, chain.turnId(), SECONDARY, true, true, ownStates(room, SECONDARY));
        var delayedSecondary = reply(secondaryRequest, List.of(new RoomConversationEngine.Speech(SECONDARY, "늦은 반응")), "[]", List.of());
        check(ledger.isCurrent(chain) && delayedSecondary.deliverableFor(secondaryRequest), "same logical turn remains live across selected speaker change");
        var replacement = ledger.beginTurn(room.roomId(), room.revision(), player, SECONDARY.toString());
        check(ledger.latestTurnSequence(room.roomId())==replacement.sequence()&&replacement.sequence()>chain.sequence(),
                "new input invalidates a completed social result without relying on UI selection");
        check(!ledger.isCurrent(chain), "new player input invalidates old primary-secondary chain");
        check(delayedSecondary.deliverableFor(secondaryRequest), "shape validity alone does not imply current lease");
        check(!ledger.finishTurn(chain), "late old completion cannot finish newer turn");
        check(ledger.isCurrent(replacement), "new pending turn survives old callback cleanup");
        check(ledger.finishTurn(replacement), "current chain completes exactly once");
        check(ledger.latestTurnSequence(room.roomId())==replacement.sequence(),"finished turn remains latest for post-delivery review");
        check(!ledger.finishTurn(replacement) && !ledger.isCurrent(replacement), "duplicate completion is inert");
    }

    private static void participantChangesInvalidatePendingSecondary() {
        UUID player = UUID.randomUUID(), peer = UUID.randomUUID();
        var ledger = new ConversationRoomLedger();
        var room = ledger.create(RoomType.PUBLIC_MOBILE, player, List.of(PRIMARY.toString(), SECONDARY.toString()), "", RecordingScope.STANDARD);
        var chain = ledger.beginTurn(room.roomId(), room.revision(), player, PRIMARY.toString());
        var joined = ledger.join(room.roomId(), room.revision(), peer, ConversationRoomLedger.Admission.PUBLIC_RANGE);
        check(joined.revision() > room.revision() && !ledger.isCurrent(chain), "join changes revision and cancels old secondary");
        check(RoomTurnPolicy.secondarySpeaker(joined, PRIMARY.toString()).isPresent(), "new audience permits only newly captured reaction turns");
        var next = ledger.beginTurn(joined.roomId(), joined.revision(), player, PRIMARY.toString());
        check(!ledger.finishTurn(chain) && ledger.isCurrent(next), "old revision cleanup cannot finish new audience turn");
        var withoutSecondary = ledger.removeGod(joined.roomId(), joined.revision(), SECONDARY.toString()).orElseThrow();
        check(!ledger.isCurrent(next), "secondary leaving invalidates pending chain");
        check(RoomTurnPolicy.secondarySpeaker(withoutSecondary, PRIMARY.toString()).isEmpty(), "removed God cannot be reintroduced");
        var last = ledger.beginTurn(withoutSecondary.roomId(), withoutSecondary.revision(), player, PRIMARY.toString());
        ledger.end(withoutSecondary.roomId(), withoutSecondary.revision());
        check(!ledger.isCurrent(last) && !ledger.finishTurn(last), "closed room ignores delayed completion");
    }

    private static void independentRoomsDoNotCancelEachOther() {
        UUID player = UUID.randomUUID(); var ledger = new ConversationRoomLedger();
        var first = ledger.create(RoomType.PRIVATE, player, List.of(PRIMARY.toString(), SECONDARY.toString()), "", RecordingScope.STANDARD);
        var second = ledger.create(RoomType.PRIVATE, player, List.of(PRIMARY.toString(), SECONDARY.toString()), "", RecordingScope.STANDARD);
        var a = ledger.beginTurn(first.roomId(), first.revision(), player, PRIMARY.toString());
        var b = ledger.beginTurn(second.roomId(), second.revision(), player, PRIMARY.toString());
        check(ledger.isCurrent(a) && ledger.isCurrent(b), "same Gods and player retain independent room chains");
        ledger.end(first.roomId(), first.revision());
        check(!ledger.isCurrent(a) && ledger.isCurrent(b), "closing one room leaves other secondary live");
        check(!ledger.finishTurn(a) && ledger.isCurrent(b), "late closed-room cleanup cannot cancel another room");
    }

    private static void ownContextAndImmutableRequests() {
        var room = snapshot(RoomType.PRIVATE, Set.of(UUID.randomUUID()), List.of(PRIMARY, SECONDARY));
        UUID turn = UUID.randomUUID();
        var primary = request(room, turn, PRIMARY, false, false, ownStates(room, PRIMARY));
        var secondary = request(room, turn, SECONDARY, true, true, ownStates(room, SECONDARY));
        check(primary.godStates().size() == 1 && primary.speakerState().gameContext().contains("OWN_" + PRIMARY), "primary has only own context");
        check(secondary.godStates().size() == 1 && secondary.speakerState().gameContext().contains("OWN_" + SECONDARY), "secondary has separately rebuilt own context");
        check(!secondary.toString().contains("OWN_" + PRIMARY), "primary private context is absent from secondary DTO");
        check(secondary.speakerState().memoryContext().godId().equals(SECONDARY.toString()), "memory owner follows secondary speaker");
        check(primary.turnId().equals(secondary.turnId()), "one player turn ID covers both selected-speaker requests");
        rejects(() -> request(room, turn, SECONDARY, true, true, ownStates(room, PRIMARY)), "wrong owner cannot supply selected state");
        var duplicate = new ArrayList<>(ownStates(room, SECONDARY)); duplicate.addAll(ownStates(room, SECONDARY));
        rejects(() -> request(room, turn, SECONDARY, true, true, duplicate), "duplicate speaker state rejected");
        var extra = new ArrayList<>(ownStates(room, SECONDARY)); extra.addAll(ownStates(room, PRIMARY));
        rejects(() -> request(room, turn, SECONDARY, true, true, extra), "another God's private context cannot enter request");
        var wrongMemory = List.of(new RoomConversationEngine.GodState(SECONDARY, "R_NEUTRAL", "E_NEUTRAL", "OWN_SECONDARY",
                primary.speakerState().memoryContext()));
        rejects(() -> request(room, turn, SECONDARY, true, true, wrongMemory), "mismatched memory owner rejected");
        var mutableStates = new ArrayList<>(ownStates(room, SECONDARY));
        var copied = request(room, turn, SECONDARY, true, true, mutableStates); mutableStates.clear();
        check(copied.godStates().size() == 1, "request copies state input");
        rejectsMutation(() -> copied.godStates().clear(), "state list immutable");
        rejectsMutation(() -> copied.godIds().clear(), "participant list immutable");
        rejectsMutation(() -> copied.history().clear(), "history immutable");
    }

    private static ConversationRoomSnapshot snapshot(RoomType type, Set<UUID> players, List<ResourceLocation> gods) {
        return new ConversationRoomSnapshot(UUID.randomUUID(), "A", type, 1, players,
                gods.stream().map(ResourceLocation::toString).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)),
                type == RoomType.PUBLIC_FIXED ? "sequence-test-region" : "", RecordingScope.STANDARD);
    }
    private static List<RoomConversationEngine.GodState> ownStates(ConversationRoomSnapshot room, ResourceLocation speaker) {
        var context = new ConversationMemoryContext(UUID.randomUUID(), room.roomId(), UUID.randomUUID(), room.playerIds().iterator().next(),
                speaker.toString(), room.playerIds(), false);
        return List.of(new RoomConversationEngine.GodState(speaker, "R_NEUTRAL", "E_NEUTRAL", "OWN_" + speaker, context));
    }
    private static RoomConversationEngine.Request request(ConversationRoomSnapshot room, UUID turn, ResourceLocation speaker,
            boolean readOnly, boolean secondary, List<RoomConversationEngine.GodState> states) {
        return new RoomConversationEngine.Request(room.roomId(), room.revision(), turn, room.playerIds().iterator().next(), "player",
                room.godIds().stream().map(ResourceLocation::parse).toList(), speaker, "플레이어 입력", List.of(),
                readOnly, true, room.type().isPublic(), states, secondary);
    }
    private static RoomConversationEngine.Result reply(RoomConversationEngine.Request request, List<RoomConversationEngine.Speech> speech,
            String proposals, List<RoomConversationEngine.Control> controls) {
        return new RoomConversationEngine.Result(request.roomId(), request.revision(), request.turnId(), speech, proposals, controls, "");
    }
    private static RoomDialogueEvent event(UUID player, String text) {
        return new RoomDialogueEvent(UUID.randomUUID(), UUID.randomUUID(), 1, Optional.of(UUID.randomUUID()), RoomType.PRIVATE,
                RecordingScope.STANDARD, "NPC", PRIMARY.toString(), text, Set.of(PRIMARY.toString(), SECONDARY.toString()),
                Map.of(player, "player"), Map.of(), 1);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void rejects(Runnable call, String message) {
        try { call.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static void rejectsMutation(Runnable call, String message) {
        try { call.run(); } catch (UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError(message);
    }
}
