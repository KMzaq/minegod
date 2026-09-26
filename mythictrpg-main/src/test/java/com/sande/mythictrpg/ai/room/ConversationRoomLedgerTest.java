package com.sande.mythictrpg.ai.room;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Room authority regression checks. Pure Java; no world, Minecraft bootstrap or model requests. */
public final class ConversationRoomLedgerTest {
    private static int checks;
    private static final String FIRST_GOD = "mythictrpg:demeter";
    private static final String SECOND_GOD = "mythictrpg:fortuna";

    public static void main(String[] args) {
        immutableSnapshots();
        publicOwnershipAndRegions();
        privateInvitationsAndSelection();
        revisionsAndTurnLeases();
        publicMerges();
        mobileAndFixedSplits();
        splitGodDepartures();
        independentRoomLeases();
        recordingScopes();
        boundsAndCodes();
        perPlayerRoomCapacity();
        System.out.println("ConversationRoomLedgerTest: " + checks + " assertions passed");
    }

    private static void immutableSnapshots() {
        UUID player = UUID.randomUUID();
        var players = new LinkedHashSet<>(Set.of(player));
        var gods = new LinkedHashSet<>(List.of(FIRST_GOD, SECOND_GOD));
        var snapshot = new ConversationRoomSnapshot(UUID.randomUUID(), "A", RoomType.PRIVATE, 1,
                players, gods, "", RecordingScope.STANDARD);
        players.clear();
        gods.clear();
        check(snapshot.playerIds().equals(Set.of(player)), "player snapshot isolated from input");
        check(new ArrayList<>(snapshot.godIds()).equals(List.of(FIRST_GOD, SECOND_GOD)), "God order is stable");
        rejectsMutation(() -> snapshot.playerIds().clear(), "player snapshot unmodifiable");
        rejectsMutation(() -> snapshot.godIds().clear(), "God snapshot unmodifiable");
        rejects(() -> new ConversationRoomSnapshot(UUID.randomUUID(), "a", RoomType.PRIVATE, 1,
                Set.of(player), Set.of(FIRST_GOD), "", RecordingScope.STANDARD), "malformed code rejected");
        rejects(() -> new ConversationRoomSnapshot(UUID.randomUUID(), "A", RoomType.PRIVATE, 0,
                Set.of(player), Set.of(FIRST_GOD), "", RecordingScope.STANDARD), "zero revision rejected");
        rejects(() -> new ConversationRoomSnapshot(UUID.randomUUID(), "A", RoomType.PUBLIC_FIXED, 1,
                Set.of(player), Set.of(FIRST_GOD), "", RecordingScope.STANDARD), "fixed requires region");
        rejects(() -> new ConversationRoomSnapshot(UUID.randomUUID(), "A", RoomType.PRIVATE, 1,
                Set.of(player), Set.of(FIRST_GOD), "region", RecordingScope.STANDARD), "private cannot claim region");
    }

    private static void publicOwnershipAndRegions() {
        var ledger = new ConversationRoomLedger();
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        var fixed = ledger.create(RoomType.PUBLIC_FIXED, player, List.of(FIRST_GOD),
                "minecraft:overworld/plains/component-1", RecordingScope.STANDARD);
        rejects(() -> ledger.create(RoomType.PUBLIC_MOBILE, player, List.of(SECOND_GOD), "", RecordingScope.STANDARD),
                "one public room per player");
        rejects(() -> ledger.create(RoomType.PUBLIC_MOBILE, other, List.of(FIRST_GOD), "", RecordingScope.STANDARD),
                "one public room per God");
        rejects(() -> ledger.create(RoomType.PUBLIC_FIXED, other, List.of(SECOND_GOD), fixed.regionId(), RecordingScope.STANDARD),
                "same connected region is exclusive");
        var disconnected = ledger.create(RoomType.PUBLIC_FIXED, other, List.of(SECOND_GOD),
                "minecraft:overworld/plains/component-2", RecordingScope.STANDARD);
        check(ledger.activeRooms().size() == 2, "disconnected areas of same biome are distinct regions");
        rejects(() -> ledger.join(fixed.roomId(), fixed.revision(), other, ConversationRoomLedger.Admission.PUBLIC_RANGE),
                "cannot bypass public player ownership through join");
        var privateRoom = ledger.create(RoomType.PRIVATE, player, List.of(FIRST_GOD), "", RecordingScope.STANDARD);
        check(ledger.publicRoomForGod(FIRST_GOD).orElseThrow().roomId().equals(fixed.roomId()), "private does not steal public God");
        check(ledger.publicRoomForPlayer(player).orElseThrow().roomId().equals(fixed.roomId()), "private does not steal public player");
        check(!ledger.publicRoomForPlayer(UUID.randomUUID()).isPresent(), "global public viewer is not a participant");
        check(privateRoom.type() == RoomType.PRIVATE, "public and private rooms coexist");
        ledger.end(disconnected.roomId(), disconnected.revision());
        var added = ledger.addGod(fixed.roomId(), fixed.revision(), SECOND_GOD);
        check(added.godIds().size() == 2, "public God released on room end");
        var removed = ledger.removeGod(added.roomId(), added.revision(), SECOND_GOD).orElseThrow();
        check(removed.godIds().equals(Set.of(FIRST_GOD)), "explicit God departure");
        check(ledger.removeGod(removed.roomId(), removed.revision(), FIRST_GOD).isEmpty(), "last God ends room");
        check(ledger.find(privateRoom.roomId()).isPresent(), "ending public leaves private intact");
    }

    private static void privateInvitationsAndSelection() {
        var ledger = new ConversationRoomLedger();
        UUID owner = UUID.randomUUID(), guest = UUID.randomUUID(), outsider = UUID.randomUUID();
        var first = ledger.create(RoomType.PRIVATE, owner, List.of(FIRST_GOD), "", RecordingScope.STANDARD);
        var second = ledger.create(RoomType.PRIVATE, owner, List.of(FIRST_GOD), "", RecordingScope.STANDARD);
        check(ledger.selectedPrivateRoom(owner).orElseThrow().roomId().equals(second.roomId()), "new private selected");
        ledger.selectPrivate(owner, first.roomId());
        check(ledger.selectedPrivateRoom(owner).orElseThrow().roomId().equals(first.roomId()), "explicit private selection");
        rejects(() -> ledger.selectPrivate(guest, first.roomId()), "nonmember cannot select private");
        rejects(() -> ledger.join(first.roomId(), first.revision(), guest, ConversationRoomLedger.Admission.PUBLIC_RANGE),
                "public range cannot authorize private entry");
        rejects(() -> ledger.invite(first.roomId(), first.revision(), SECOND_GOD, guest), "outsider God cannot invite");
        var invite = ledger.invite(first.roomId(), first.revision(), FIRST_GOD, guest);
        check(ledger.invitationsFor(guest).equals(List.of(invite)), "invite is scoped to target player");
        check(ledger.invitationsFor(outsider).isEmpty(), "other player cannot discover invite capability");
        rejects(() -> ledger.acceptInvitation(invite, outsider), "wrong player cannot accept valid token");
        var forged = new ConversationRoomLedger.RoomInvitation(UUID.randomUUID(), first.roomId(), first.revision(), FIRST_GOD, guest);
        rejects(() -> ledger.acceptInvitation(forged, guest), "forged capability rejected");
        var tampered = new ConversationRoomLedger.RoomInvitation(invite.token(), first.roomId(), first.revision(), FIRST_GOD, outsider);
        rejects(() -> ledger.acceptInvitation(tampered, outsider), "target tampering rejected");
        var pending = ledger.beginTurn(first.roomId(), first.revision(), owner, FIRST_GOD);
        var joined = ledger.acceptInvitation(invite, guest);
        check(joined.playerIds().equals(Set.of(owner, guest)), "God-issued private admission succeeds");
        check(ledger.selectedPrivateRoom(guest).orElseThrow().roomId().equals(first.roomId()), "accepted private becomes selected");
        check(!ledger.isCurrent(pending), "private audience change invalidates pending turn");
        rejects(() -> ledger.acceptInvitation(invite, guest), "capability is single use");
        var expiring = ledger.invite(joined.roomId(), joined.revision(), FIRST_GOD, outsider);
        var changed = ledger.addGod(joined.roomId(), joined.revision(), SECOND_GOD);
        rejects(() -> ledger.acceptInvitation(expiring, outsider), "capability expires after room revision");
        check(ledger.invitationsFor(outsider).isEmpty(), "expired invitation removed");
        ledger.leave(changed.roomId(), changed.revision(), guest);
        check(ledger.selectedPrivateRoom(guest).isEmpty(), "leaving clears selected room");
        ledger.deselectPrivate(owner);
        check(ledger.selectedPrivateRoom(owner).isEmpty(), "deselect does not end membership");
        check(ledger.find(second.roomId()).isPresent(), "concurrent private room remains active");
        var current = ledger.find(first.roomId()).orElseThrow();
        ledger.selectPrivate(owner, current.roomId());
        ledger.end(current.roomId(), current.revision());
        check(ledger.selectedPrivateRoom(owner).isEmpty(), "ending selected private does not silently choose another");
    }

    private static void revisionsAndTurnLeases() {
        var ledger = new ConversationRoomLedger();
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        var room = mobile(ledger, player, FIRST_GOD);
        var first = ledger.beginTurn(room.roomId(), room.revision(), player, FIRST_GOD);
        var second = ledger.beginTurn(room.roomId(), room.revision(), player, FIRST_GOD);
        check(!ledger.isCurrent(first) && ledger.isCurrent(second), "new input supersedes old turn at same revision");
        check(!ledger.finishTurn(first), "stale completion cannot finish current turn");
        check(ledger.finishTurn(second), "matching completion accepted");
        check(!ledger.finishTurn(second), "duplicate completion rejected");
        rejects(() -> ledger.beginTurn(room.roomId(), room.revision(), other, FIRST_GOD), "nonparticipant player rejected");
        rejects(() -> ledger.beginTurn(room.roomId(), room.revision(), player, SECOND_GOD), "nonparticipant God rejected");
        var pending = ledger.beginTurn(room.roomId(), room.revision(), player, FIRST_GOD);
        var joined = ledger.join(room.roomId(), room.revision(), other, ConversationRoomLedger.Admission.PUBLIC_RANGE);
        check(joined.revision() == room.revision() + 1, "admission increments revision");
        check(!ledger.isCurrent(pending), "admission invalidates pending turn");
        rejects(() -> ledger.leave(room.roomId(), room.revision(), player), "stale leave rejected");
        rejects(() -> ledger.end(room.roomId(), room.revision()), "stale end rejected");
        rejects(() -> ledger.addGod(room.roomId(), room.revision(), SECOND_GOD), "stale God addition rejected");
        var afterLeave = ledger.leave(joined.roomId(), joined.revision(), other).orElseThrow();
        var ending = ledger.beginTurn(afterLeave.roomId(), afterLeave.revision(), player, FIRST_GOD);
        check(ledger.leave(afterLeave.roomId(), afterLeave.revision(), player).isEmpty(), "last player closes room");
        var replacement = mobile(ledger, player, FIRST_GOD);
        check(replacement.code().equals(room.code()), "closed code may be reused");
        check(!replacement.roomId().equals(room.roomId()), "reused code never reuses room identity");
        check(!ledger.isCurrent(ending), "reused display code cannot revive old turn");
    }

    private static void publicMerges() {
        var ledger = new ConversationRoomLedger();
        UUID firstPlayer = UUID.randomUUID(), secondPlayer = UUID.randomUUID();
        var first = mobile(ledger, firstPlayer, FIRST_GOD);
        var second = mobile(ledger, secondPlayer, SECOND_GOD);
        var firstTurn = ledger.beginTurn(first.roomId(), first.revision(), firstPlayer, FIRST_GOD);
        var secondTurn = ledger.beginTurn(second.roomId(), second.revision(), secondPlayer, SECOND_GOD);
        rejects(() -> ledger.merge(first.roomId(), first.revision() + 1, second.roomId(), second.revision()), "stale merge rejected");
        check(ledger.activeRooms().size() == 2 && ledger.isCurrent(firstTurn), "rejected merge has no side effects");
        var merged = ledger.merge(first.roomId(), first.revision(), second.roomId(), second.revision());
        check(merged.type() == RoomType.PUBLIC_MOBILE && merged.playerIds().equals(Set.of(firstPlayer, secondPlayer)), "mobile merge unions players");
        check(new ArrayList<>(merged.godIds()).equals(List.of(FIRST_GOD, SECOND_GOD)), "mobile merge preserves God order");
        check(!Set.of(first.roomId(), second.roomId()).contains(merged.roomId()), "mobile merge gets fresh history identity");
        check(!ledger.isCurrent(firstTurn) && !ledger.isCurrent(secondTurn), "merge invalidates both pending turns");
        check(ledger.find(first.roomId()).isEmpty() && ledger.find(second.roomId()).isEmpty(), "source rooms ended");
        UUID fixedPlayer = UUID.randomUUID();
        var fixed = ledger.create(RoomType.PUBLIC_FIXED, fixedPlayer, List.of("mythictrpg:third"), "dimension/region", RecordingScope.STANDARD);
        var fixedTurn = ledger.beginTurn(fixed.roomId(), fixed.revision(), fixedPlayer, "mythictrpg:third");
        var combined = ledger.merge(merged.roomId(), merged.revision(), fixed.roomId(), fixed.revision());
        check(combined.roomId().equals(fixed.roomId()) && combined.code().equals(fixed.code()), "fixed room identity wins");
        check(combined.type() == RoomType.PUBLIC_FIXED && combined.regionId().equals(fixed.regionId()), "fixed region survives merge");
        check(combined.revision() == fixed.revision() + 1 && !ledger.isCurrent(fixedTurn), "fixed audience revision invalidates pending turn");
        check(combined.playerIds().size() == 3 && combined.godIds().size() == 3, "fixed merge retains all participants");
        var secondFixed = ledger.create(RoomType.PUBLIC_FIXED, UUID.randomUUID(), List.of("mythictrpg:fourth"), "dimension/other", RecordingScope.STANDARD);
        rejects(() -> ledger.merge(combined.roomId(), combined.revision(), secondFixed.roomId(), secondFixed.revision()), "fixed rooms do not merge");
        var privateRoom = ledger.create(RoomType.PRIVATE, firstPlayer, List.of(FIRST_GOD), "", RecordingScope.STANDARD);
        rejects(() -> ledger.merge(combined.roomId(), combined.revision(), privateRoom.roomId(), privateRoom.revision()), "private never merges into public");
    }

    private static void mobileAndFixedSplits() {
        var ledger = new ConversationRoomLedger();
        UUID firstPlayer = UUID.randomUUID(), secondPlayer = UUID.randomUUID(), thirdPlayer = UUID.randomUUID();
        var seed = ledger.create(RoomType.PUBLIC_MOBILE, firstPlayer, List.of(FIRST_GOD, SECOND_GOD), "", RecordingScope.STANDARD);
        var two = ledger.join(seed.roomId(), seed.revision(), secondPlayer, ConversationRoomLedger.Admission.PUBLIC_RANGE);
        var room = ledger.join(two.roomId(), two.revision(), thirdPlayer, ConversationRoomLedger.Admission.PUBLIC_RANGE);
        var turn = ledger.beginTurn(room.roomId(), room.revision(), firstPlayer, FIRST_GOD);
        var components = List.of(Set.of(firstPlayer), Set.of(secondPlayer), Set.of(thirdPlayer));
        rejects(() -> ledger.split(room.roomId(), room.revision(), List.of(Set.of(firstPlayer)), Map.of(FIRST_GOD, 0, SECOND_GOD, 0)), "split cannot omit players");
        rejects(() -> ledger.split(room.roomId(), room.revision(), List.of(Set.of(firstPlayer, secondPlayer), Set.of(secondPlayer, thirdPlayer)), Map.of(FIRST_GOD, 0, SECOND_GOD, 1)), "split cannot duplicate players");
        rejects(() -> ledger.split(room.roomId(), room.revision(), List.of(Set.of(firstPlayer, secondPlayer, thirdPlayer, UUID.randomUUID())), Map.of(FIRST_GOD, 0, SECOND_GOD, 0)), "split cannot add players");
        rejects(() -> ledger.split(room.roomId(), room.revision(), components, Map.of(FIRST_GOD, 0)), "split cannot drop God");
        rejects(() -> ledger.split(room.roomId(), room.revision(), components, Map.of(FIRST_GOD, 0, SECOND_GOD, 3)), "split invalid destination rejected");
        rejects(() -> ledger.split(room.roomId(), room.revision(), components, Map.of(FIRST_GOD, 0, SECOND_GOD, 1, "mythictrpg:outsider", 2)), "split cannot invent God");
        check(ledger.isCurrent(turn), "rejected split leaves pending authority intact");
        var children = ledger.split(room.roomId(), room.revision(), components, Map.of(FIRST_GOD, 0, SECOND_GOD, 1));
        check(children.size() == 2, "godless group yields no room");
        check(ledger.publicRoomForPlayer(thirdPlayer).isEmpty(), "godless players leave conversation");
        check(children.stream().noneMatch(child -> child.roomId().equals(room.roomId())), "split mobile histories have fresh identities");
        check(children.stream().allMatch(child -> child.recordingScope() == room.recordingScope()), "split keeps recording policy");
        check(!ledger.isCurrent(turn) && ledger.find(room.roomId()).isEmpty(), "split ends source and pending turn");
        check(ledger.publicRoomForGod(FIRST_GOD).orElseThrow().playerIds().equals(Set.of(firstPlayer)), "first God assigned only to selected component");
        check(ledger.publicRoomForGod(SECOND_GOD).orElseThrow().playerIds().equals(Set.of(secondPlayer)), "second God assigned only to selected component");

        var fixedLedger = new ConversationRoomLedger();
        var fixedSeed = fixedLedger.create(RoomType.PUBLIC_FIXED, firstPlayer, List.of(FIRST_GOD, SECOND_GOD), "dimension/fixed", RecordingScope.STANDARD);
        var fixed = fixedLedger.join(fixedSeed.roomId(), fixedSeed.revision(), secondPlayer, ConversationRoomLedger.Admission.PUBLIC_RANGE);
        var fixedChildren = fixedLedger.split(fixed.roomId(), fixed.revision(), List.of(Set.of(firstPlayer), Set.of(secondPlayer)), Map.of(FIRST_GOD, 0, SECOND_GOD, 1), 0);
        var retained = fixedChildren.get(0);
        check(retained.roomId().equals(fixed.roomId()) && retained.type() == RoomType.PUBLIC_FIXED, "inside group keeps fixed identity");
        check(retained.regionId().equals(fixed.regionId()) && retained.revision() > fixed.revision(), "retained fixed region revises");
        check(fixedChildren.get(1).type() == RoomType.PUBLIC_MOBILE && fixedChildren.get(1).regionId().isEmpty(), "outside group becomes mobile");
        check(!fixedChildren.get(0).code().equals(fixedChildren.get(1).code()), "split codes remain distinct");
        rejects(() -> fixedLedger.create(RoomType.PUBLIC_FIXED, thirdPlayer, List.of("mythictrpg:third"), fixed.regionId(), RecordingScope.STANDARD), "retained region remains exclusive");
        var departing = fixedLedger.split(retained.roomId(), retained.revision(), List.of(retained.playerIds()), Map.of(FIRST_GOD, 0));
        check(departing.get(0).type() == RoomType.PUBLIC_MOBILE, "all participants can depart fixed region");
        check(fixedLedger.create(RoomType.PUBLIC_FIXED, thirdPlayer, List.of("mythictrpg:third"), fixed.regionId(), RecordingScope.STANDARD).type() == RoomType.PUBLIC_FIXED, "empty origin region released");
    }

    private static void recordingScopes() {
        for (RecordingScope firstScope : RecordingScope.values()) {
            for (RecordingScope secondScope : RecordingScope.values()) {
                var ledger = new ConversationRoomLedger();
                UUID player = UUID.randomUUID(), other = UUID.randomUUID();
                var first = ledger.create(RoomType.PUBLIC_MOBILE, player, List.of(FIRST_GOD), "", firstScope);
                var second = ledger.create(RoomType.PUBLIC_MOBILE, other, List.of(SECOND_GOD), "", secondScope);
                if (firstScope == secondScope) {
                    check(ledger.merge(first.roomId(), first.revision(), second.roomId(), second.revision()).recordingScope() == firstScope,
                            "same recording scope may merge");
                } else {
                    var lease = ledger.beginTurn(first.roomId(), first.revision(), player, FIRST_GOD);
                    rejects(() -> ledger.merge(first.roomId(), first.revision(), second.roomId(), second.revision()), "cross-scope merge rejected");
                    check(ledger.activeRooms().size() == 2 && ledger.isCurrent(lease), "cross-scope rejection is atomic");
                }
            }
        }
        check(!RecordingScope.TEST_EPHEMERAL.recordingAllowed(), "test off never promotes to recorded scope");
        check(RecordingScope.TEST_RECORDING.recordingAllowed(), "test on allows recording under external policy");
    }

    private static void splitGodDepartures() {
        var ledger = new ConversationRoomLedger();
        UUID firstPlayer = UUID.randomUUID(), secondPlayer = UUID.randomUUID();
        var seed = ledger.create(RoomType.PUBLIC_MOBILE, firstPlayer, List.of(FIRST_GOD, SECOND_GOD), "", RecordingScope.STANDARD);
        var room = ledger.join(seed.roomId(), seed.revision(), secondPlayer, ConversationRoomLedger.Admission.PUBLIC_RANGE);
        var groups = List.of(Set.of(firstPlayer), Set.of(secondPlayer));
        rejects(() -> ledger.split(room.roomId(), room.revision(), groups, Map.of(FIRST_GOD, -2, SECOND_GOD, 1)),
                "only -1 may explicitly leave");
        var children = ledger.split(room.roomId(), room.revision(), groups, Map.of(FIRST_GOD, -1, SECOND_GOD, 1));
        check(children.size() == 1 && children.getFirst().godIds().equals(Set.of(SECOND_GOD)), "one God can explicitly leave during split");
        check(ledger.publicRoomForGod(FIRST_GOD).isEmpty(), "departing God releases public ownership");
        check(ledger.publicRoomForPlayer(firstPlayer).isEmpty(), "departing God's godless group closes");
        check(children.getFirst().playerIds().equals(Set.of(secondPlayer)), "remaining God keeps only selected group");
        var finalRoom = children.getFirst();
        var pending = ledger.beginTurn(finalRoom.roomId(), finalRoom.revision(), secondPlayer, SECOND_GOD);
        var none = ledger.split(finalRoom.roomId(), finalRoom.revision(), List.of(finalRoom.playerIds()), Map.of(SECOND_GOD, -1));
        check(none.isEmpty() && ledger.activeRooms().isEmpty(), "all Gods leaving closes source with no child room");
        check(!ledger.isCurrent(pending), "all-God departure invalidates pending turn");
        check(ledger.publicRoomForPlayer(secondPlayer).isEmpty(), "all-God departure releases remaining player");
        var fixed = ledger.create(RoomType.PUBLIC_FIXED, firstPlayer, List.of(FIRST_GOD), "dimension/departed", RecordingScope.STANDARD);
        check(ledger.split(fixed.roomId(), fixed.revision(), List.of(fixed.playerIds()), Map.of(FIRST_GOD, -1), 0).isEmpty(),
                "even retained fixed component closes if every God leaves");
        var reopened = ledger.create(RoomType.PUBLIC_FIXED, secondPlayer, List.of(FIRST_GOD), fixed.regionId(), RecordingScope.STANDARD);
        check(!reopened.roomId().equals(fixed.roomId()), "departed fixed region can reopen under fresh identity");
    }

    private static void independentRoomLeases() {
        var ledger = new ConversationRoomLedger();
        UUID player = UUID.randomUUID();
        var publicRoom = mobile(ledger, player, FIRST_GOD);
        var privateA = ledger.create(RoomType.PRIVATE, player, List.of(FIRST_GOD), "", RecordingScope.TEST_EPHEMERAL);
        var privateB = ledger.create(RoomType.PRIVATE, player, List.of(FIRST_GOD), "", RecordingScope.TEST_RECORDING);
        var publicTurn = ledger.beginTurn(publicRoom.roomId(), publicRoom.revision(), player, FIRST_GOD);
        var turnA = ledger.beginTurn(privateA.roomId(), privateA.revision(), player, FIRST_GOD);
        var turnB = ledger.beginTurn(privateB.roomId(), privateB.revision(), player, FIRST_GOD);
        check(ledger.isCurrent(publicTurn) && ledger.isCurrent(turnA) && ledger.isCurrent(turnB),
                "same player and God can have isolated public and private pending turns");
        var crossRoom = new ConversationRoomLedger.TurnLease(privateB.roomId(), turnA.revision(), turnA.turnId(), player, FIRST_GOD);
        check(!ledger.isCurrent(crossRoom) && !ledger.finishTurn(crossRoom), "room identity cannot borrow another room turn capability");
        ledger.selectPrivate(player, privateA.roomId());
        check(ledger.isCurrent(turnA) && ledger.isCurrent(turnB), "selection does not rewrite other private context authority");
        ledger.addGod(privateA.roomId(), privateA.revision(), SECOND_GOD);
        check(!ledger.isCurrent(turnA) && ledger.isCurrent(turnB) && ledger.isCurrent(publicTurn), "private revision invalidates only its own pending turn");
        ledger.end(privateB.roomId(), privateB.revision());
        check(!ledger.isCurrent(turnB) && ledger.isCurrent(publicTurn), "private close cannot cancel public turn");
        check(ledger.find(privateA.roomId()).orElseThrow().recordingScope() == RecordingScope.TEST_EPHEMERAL,
                "another room recording policy never promotes private off");
    }

    private static void boundsAndCodes() {
        check(ConversationRoomLedger.codeFor(1).equals("A"), "code A");
        check(ConversationRoomLedger.codeFor(26).equals("Z"), "code Z");
        check(ConversationRoomLedger.codeFor(27).equals("AA"), "code AA");
        check(ConversationRoomLedger.codeFor(52).equals("AZ"), "code AZ");
        check(ConversationRoomLedger.codeFor(53).equals("BA"), "code BA");
        var ledger = new ConversationRoomLedger();
        UUID player = UUID.randomUUID();
        var rooms = new ArrayList<ConversationRoomSnapshot>();
        for (int i = 0; i < ConversationRoomLedger.MAX_ROOMS; i++) {
            rooms.add(ledger.create(RoomType.PRIVATE, UUID.randomUUID(), List.of(FIRST_GOD), "", RecordingScope.STANDARD));
        }
        check(new LinkedHashSet<>(rooms.stream().map(ConversationRoomSnapshot::code).toList()).size() == rooms.size(), "all active codes unique");
        rejects(() -> ledger.create(RoomType.PRIVATE, player, List.of(FIRST_GOD), "", RecordingScope.STANDARD), "room count bounded");
        var closed = rooms.get(25);
        ledger.end(closed.roomId(), closed.revision());
        var replacement = ledger.create(RoomType.PRIVATE, player, List.of(FIRST_GOD), "", RecordingScope.STANDARD);
        check(replacement.code().equals("Z") && !replacement.roomId().equals(closed.roomId()), "lowest released code reused with fresh identity");
        check(ledger.findByCode(" z ").orElseThrow().roomId().equals(replacement.roomId()), "display code lookup canonicalized");
        var playerLedger = new ConversationRoomLedger();
        var full = mobile(playerLedger, player, FIRST_GOD);
        for (int i = 1; i < ConversationRoomSnapshot.MAX_PLAYERS; i++) {
            full = playerLedger.join(full.roomId(), full.revision(), UUID.randomUUID(), ConversationRoomLedger.Admission.PUBLIC_RANGE);
        }
        var fullRoom = full;
        rejects(() -> playerLedger.join(fullRoom.roomId(), fullRoom.revision(), UUID.randomUUID(), ConversationRoomLedger.Admission.PUBLIC_RANGE), "participant count bounded");
        check(playerLedger.find(fullRoom.roomId()).orElseThrow().equals(fullRoom), "failed over-capacity admission is atomic");
        var manyGods = java.util.stream.IntStream.range(0, ConversationRoomSnapshot.MAX_GODS + 1).mapToObj(i -> "test:god_" + i).toList();
        rejects(() -> new ConversationRoomLedger().create(RoomType.PRIVATE, player, manyGods, "", RecordingScope.STANDARD), "God count bounded");
        var invitationLedger = new ConversationRoomLedger();
        var privateRoom = invitationLedger.create(RoomType.PRIVATE, player, List.of(FIRST_GOD), "", RecordingScope.STANDARD);
        for (int i = 0; i < ConversationRoomLedger.MAX_INVITATIONS_PER_ROOM; i++) {
            invitationLedger.invite(privateRoom.roomId(), privateRoom.revision(), FIRST_GOD, UUID.randomUUID());
        }
        rejects(() -> invitationLedger.invite(privateRoom.roomId(), privateRoom.revision(), FIRST_GOD, UUID.randomUUID()), "pending invitations bounded");
    }

    private static ConversationRoomSnapshot mobile(ConversationRoomLedger ledger, UUID player, String god) {
        return ledger.create(RoomType.PUBLIC_MOBILE, player, List.of(god), "", RecordingScope.STANDARD);
    }

    private static void perPlayerRoomCapacity() {
        var ledger = new ConversationRoomLedger();
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        var privateRooms = new ArrayList<ConversationRoomSnapshot>();
        for (int i = 0; i < ConversationRoomLedger.MAX_ROOMS_PER_PLAYER; i++) {
            privateRooms.add(ledger.create(RoomType.PRIVATE, player, List.of(FIRST_GOD), "", RecordingScope.STANDARD));
        }
        rejects(() -> ledger.create(RoomType.PRIVATE, player, List.of(FIRST_GOD), "", RecordingScope.STANDARD),
                "sixty-fifth private room rejected");
        rejects(() -> mobile(ledger, player, SECOND_GOD), "public and private rooms share player UI capacity");
        var otherPublic = mobile(ledger, other, SECOND_GOD);
        rejects(() -> ledger.join(otherPublic.roomId(), otherPublic.revision(), player, ConversationRoomLedger.Admission.PUBLIC_RANGE),
                "public join also enforces player capacity");
        check(ledger.find(otherPublic.roomId()).orElseThrow().equals(otherPublic), "failed capacity join is atomic");
        var otherPrivate = ledger.create(RoomType.PRIVATE, other, List.of(FIRST_GOD), "", RecordingScope.STANDARD);
        var invite = ledger.invite(otherPrivate.roomId(), otherPrivate.revision(), FIRST_GOD, player);
        rejects(() -> ledger.acceptInvitation(invite, player), "invitation cannot bypass room capacity");
        check(ledger.invitationsFor(player).contains(invite), "failed capacity acceptance does not consume valid invitation");
        var first = privateRooms.getFirst();
        ledger.leave(first.roomId(), first.revision(), player);
        var publicRoom = ledger.join(otherPublic.roomId(), otherPublic.revision(), player, ConversationRoomLedger.Admission.PUBLIC_RANGE);
        var lease = ledger.beginTurn(publicRoom.roomId(), publicRoom.revision(), player, SECOND_GOD);
        check(ledger.join(publicRoom.roomId(), publicRoom.revision(), player, ConversationRoomLedger.Admission.PUBLIC_RANGE).equals(publicRoom),
                "existing member join is idempotent at capacity");
        check(ledger.isCurrent(lease), "idempotent at-capacity join does not invalidate pending turn");
        rejects(() -> ledger.acceptInvitation(invite, player), "public membership counts toward private invitation capacity");
        ledger.leave(publicRoom.roomId(), publicRoom.revision(), player);
        check(ledger.acceptInvitation(invite, player).playerIds().contains(player), "released capacity permits original invitation acceptance");
        check(ledger.activeRooms().stream().filter(room -> room.playerIds().contains(player)).count()
                == ConversationRoomLedger.MAX_ROOMS_PER_PLAYER, "all player memberships fit payload capacity");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static void rejects(Runnable operation, String label) {
        try { operation.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(label);
    }

    private static void rejectsMutation(Runnable operation, String label) {
        try { operation.run(); } catch (UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError(label);
    }
}
