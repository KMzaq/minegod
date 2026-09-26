package com.sande.mythictrpg.relation;

import com.sande.mythictrpg.ai.room.ConversationRoomSnapshot;
import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Actual projection logic, without starting a server or sending any model request. */
public final class GodRelationRoomContextTest {
    private static final ResourceLocation A = id("speaker");
    private static final ResourceLocation B = id("target");
    private static final ResourceLocation OUTSIDE = id("outside");
    private static final ResourceLocation SECRET_CAUSE = id("secret_cause_do_not_disclose");
    private static int checks;

    public static void main(String[] args) {
        directionalProjectionAndNoCauseLeak();
        scopeIsCheckedBeforeLookup();
        publicAndPrivateAudienceLeases();
        relationshipRevisionsInvalidateResults();
        missingAndUnavailableAreDifferent();
        boundsAndImmutability();
        malformedViewCannotExpandParticipants();
        System.out.println("GodRelationRoomContextTest: " + checks + " assertions passed");
    }

    private static void directionalProjectionAndNoCauseLeak() {
        var view = populated();
        var room = room(RoomType.PRIVATE, Set.of(UUID.randomUUID()), List.of(A, B));
        var first = GodRelationRoomContext.capture(view, room, A, room.playerIds());
        check(first.attitudes().size() == 1, "only one other participant projected");
        var attitude = first.attitudes().getFirst();
        check(attitude.targetGodId().equals(B), "correct outgoing target");
        check(attitude.score() == -120 && attitude.tags().equals(Set.of(GodRelationTag.FEARFUL)), "source attitude preserved");
        check(view.lookups.equals(List.of(new GodRelationKey(A, B))), "does not inspect reverse or outside relations");
        check(first.promptText().contains(A + " -> " + B), "prompt retains direction");
        check(!first.promptText().contains("INDIFFERENT"), "reverse state not disclosed in source prompt");
        check(!first.toString().contains(SECRET_CAUSE.toString()), "even metadata has no cause ID");
        check(!first.promptText().contains(OUTSIDE.toString()), "outside participant omitted");
        check(!first.promptText().contains("revision="), "internal revision absent from prompt");
        var second = GodRelationRoomContext.capture(view, room, B, room.playerIds());
        check(second.attitudes().getFirst().tags().equals(Set.of(GodRelationTag.INDIFFERENT)), "reverse is independent");
        check(second.promptText().contains(B + " -> " + A), "reverse prompt direction preserved");
        check(!second.promptText().contains("FEARFUL"), "one God cannot read another God's internal attitude");
    }

    private static void scopeIsCheckedBeforeLookup() {
        var view = populated();
        UUID player = UUID.randomUUID(), stranger = UUID.randomUUID();
        var room = room(RoomType.PRIVATE, Set.of(player), List.of(A, B));
        rejects(() -> GodRelationRoomContext.capture(view, room, OUTSIDE, Set.of(player)), "outside speaker rejected");
        rejects(() -> GodRelationRoomContext.capture(view, room, A, Set.of(player, stranger)), "private outsider rejected");
        rejects(() -> GodRelationRoomContext.capture(view, room, A, Set.of()), "missing private audience rejected");
        check(view.lookups.isEmpty(), "invalid scope is filtered before relation lookup");
        var sharedPrivate = room(RoomType.PRIVATE, Set.of(player, stranger), List.of(A, B));
        rejects(() -> GodRelationRoomContext.capture(view, sharedPrivate, A, Set.of(player)), "private query cannot omit co-listener");
        var publicRoom = room(RoomType.PUBLIC_MOBILE, Set.of(player), List.of(A, B));
        rejects(() -> GodRelationRoomContext.capture(view, publicRoom, A, Set.of(stranger)), "public query cannot omit participant");
        check(view.lookups.isEmpty(), "all invalid audiences rejected before lookup");
    }

    private static void publicAndPrivateAudienceLeases() {
        var view = populated();
        UUID player = UUID.randomUUID(), observer = UUID.randomUUID();
        var room = room(RoomType.PUBLIC_MOBILE, Set.of(player), List.of(A, B));
        var snapshot = GodRelationRoomContext.capture(view, room, A, Set.of(player, observer));
        check(snapshot.audiencePlayerIds().size() == 2 && snapshot.participantPlayerIds().size() == 1,
                "public observer is audience but not participant");
        check(GodRelationRoomContext.isCurrent(view, snapshot, room, Set.of(player, observer)), "unchanged public scope current");
        check(!GodRelationRoomContext.isCurrent(view, snapshot, room, Set.of(player)), "public audience removal expires response");
        check(!GodRelationRoomContext.isCurrent(view, snapshot, room, Set.of(player, observer, UUID.randomUUID())),
                "new public observer expires response");
        var sameIdsNewRevision = replace(room, room.roomId(), room.revision() + 1, room.type(), room.playerIds());
        check(!GodRelationRoomContext.isCurrent(view, snapshot, sameIdsNewRevision, Set.of(player, observer)), "room revision expires response");
        var otherRoom = replace(room, UUID.randomUUID(), room.revision(), room.type(), room.playerIds());
        check(!GodRelationRoomContext.isCurrent(view, snapshot, otherRoom, Set.of(player, observer)), "room identity expires response");
        var privateRoom = replace(room, room.roomId(), room.revision(), RoomType.PRIVATE, room.playerIds());
        check(!GodRelationRoomContext.isCurrent(view, snapshot, privateRoom, Set.of(player)), "room type change expires response");
        var privateSnapshot = GodRelationRoomContext.capture(view, privateRoom, A, Set.of(player));
        var invited = replace(privateRoom, privateRoom.roomId(), privateRoom.revision() + 1, RoomType.PRIVATE, Set.of(player, observer));
        check(!GodRelationRoomContext.isCurrent(view, privateSnapshot, invited, Set.of(player, observer)), "new private participant expires response");
        check(!GodRelationRoomContext.isCurrent(view, snapshot, null, Set.of(player, observer)), "closed room expires response");
    }

    private static void relationshipRevisionsInvalidateResults() {
        var view = populated();
        var room = room(RoomType.PRIVATE, Set.of(UUID.randomUUID()), List.of(A, B));
        var snapshot = GodRelationRoomContext.capture(view, room, A, room.playerIds());
        check(GodRelationRoomContext.isCurrent(view, snapshot, room, room.playerIds()), "unchanged relation current");
        view.put(A, B, -120, Set.of(GodRelationTag.FEARFUL), 2);
        check(!GodRelationRoomContext.isCurrent(view, snapshot, room, room.playerIds()), "same attitude newer revision expires response");
        view.put(A, B, -120, Set.of(GodRelationTag.RESENTFUL), 1);
        check(!GodRelationRoomContext.isCurrent(view, snapshot, room, room.playerIds()), "changed tags detected even with same revision");
        view.put(A, B, 120, Set.of(GodRelationTag.FEARFUL), 1);
        check(!GodRelationRoomContext.isCurrent(view, snapshot, room, room.playerIds()), "changed score detected");
        view.put(A, B, -120, Set.of(GodRelationTag.FEARFUL), 1);
        view.put(B, A, -20, Set.of(GodRelationTag.HOSTILE), 7);
        check(GodRelationRoomContext.isCurrent(view, snapshot, room, room.playerIds()), "unread reverse relation does not invalidate source snapshot");
        view.ready = false;
        check(!GodRelationRoomContext.isCurrent(view, snapshot, room, room.playerIds()), "unavailable store expires populated response");
    }

    private static void missingAndUnavailableAreDifferent() {
        var view = new View();
        var room = room(RoomType.PRIVATE, Set.of(UUID.randomUUID()), List.of(A, B));
        var missing = GodRelationRoomContext.capture(view, room, A, room.playerIds());
        check(missing.available(), "empty ready store is available");
        check(missing.attitudes().size() == 1 && !missing.attitudes().getFirst().recorded(), "missing direction retains neutral default marker");
        check(missing.promptText().isEmpty(), "unrecorded neutral does not invent social history");
        view.put(A, B, 0, Set.of(GodRelationTag.WATCHFUL), 1);
        check(!GodRelationRoomContext.isCurrent(view, missing, room, room.playerIds()), "new relation invalidates previously neutral snapshot");
        view.ready = false;
        view.lookups.clear();
        var unavailable = GodRelationRoomContext.capture(view, room, A, room.playerIds());
        check(!unavailable.available() && unavailable.attitudes().isEmpty(), "unavailable is not neutral");
        check(unavailable.promptText().isEmpty() && view.lookups.isEmpty(), "unavailable store not queried or exposed");
        view.ready = true;
        check(!GodRelationRoomContext.isCurrent(view, unavailable, room, room.playerIds()), "restored store requires fresh response snapshot");
    }

    private static void boundsAndImmutability() {
        var view = new View();
        var gods = new ArrayList<ResourceLocation>();
        gods.add(A);
        for (int i = 1; i < 16; i++) { var target = id("god_" + i); gods.add(target); view.put(A, target, i, Set.of(), i); }
        var room = room(RoomType.PRIVATE, Set.of(UUID.randomUUID()), gods);
        var snapshot = GodRelationRoomContext.capture(view, room, A, room.playerIds());
        check(snapshot.attitudes().size() == 15, "all 16 participants supported without all-pairs expansion");
        check(view.lookups.size() == 15, "only 15 outgoing reads at maximum size");
        check(snapshot.promptText().length() < 4096, "maximum attitude prompt remains bounded");
        rejectsMutation(() -> snapshot.attitudes().clear(), "attitude list immutable");
        rejectsMutation(() -> snapshot.participantGodIds().clear(), "participant gods immutable");
        rejectsMutation(() -> snapshot.audiencePlayerIds().clear(), "audience immutable");
        rejectsMutation(() -> snapshot.attitudes().getFirst().tags().add(GodRelationTag.FEARFUL), "tag set immutable");
        var alone = room(RoomType.PRIVATE, room.playerIds(), List.of(A));
        check(GodRelationRoomContext.capture(view, alone, A, alone.playerIds()).promptText().isEmpty(), "single God has no invented target");
        gods.add(id("seventeenth"));
        rejects(() -> room(RoomType.PRIVATE, room.playerIds(), gods), "room contract rejects over 16 Gods");
        rejects(() -> new GodRelationRoomContext.Attitude(B, 1001, Set.of(), 1, true), "score bounds preserved");
        rejects(() -> new GodRelationRoomContext.Attitude(B, 1, Set.of(), 0, false), "unrecorded attitude cannot hold fake score");
    }

    private static void malformedViewCannotExpandParticipants() {
        var room = room(RoomType.PRIVATE, Set.of(UUID.randomUUID()), List.of(A, B));
        var view = new View();
        view.values.put(new GodRelationKey(A, B), relation(A, OUTSIDE, 99, Set.of(GodRelationTag.WATCHFUL), 1));
        rejects(() -> GodRelationRoomContext.capture(view, room, A, room.playerIds()), "mismatched game view cannot add a target");
    }

    private static View populated() {
        var view = new View();
        view.put(A, B, -120, Set.of(GodRelationTag.FEARFUL), 1);
        view.put(B, A, 0, Set.of(GodRelationTag.INDIFFERENT), 1);
        view.put(A, OUTSIDE, -500, Set.of(GodRelationTag.HOSTILE), 1);
        return view;
    }

    private static ConversationRoomSnapshot room(RoomType type, Set<UUID> players, List<ResourceLocation> gods) {
        return new ConversationRoomSnapshot(UUID.randomUUID(), "A", type, 1, players,
                gods.stream().map(ResourceLocation::toString).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                type == RoomType.PUBLIC_FIXED ? "test-region" : "", RecordingScope.STANDARD);
    }

    private static ConversationRoomSnapshot replace(ConversationRoomSnapshot room, UUID id, long revision,
            RoomType type, Set<UUID> players) {
        return new ConversationRoomSnapshot(id, room.code(), type, revision, players, room.godIds(),
                type == RoomType.PUBLIC_FIXED ? "test-region" : "", room.recordingScope());
    }

    private static GodRelationSnapshot relation(ResourceLocation source, ResourceLocation target, int score,
            Set<GodRelationTag> tags, long revision) {
        return new GodRelationSnapshot(new GodRelationKey(source, target), score, tags, revision, 20,
                SECRET_CAUSE, List.of(new GodRelationHistoryEntry(revision, 20, SECRET_CAUSE, 0, score, tags, Set.of())));
    }

    private static final class View implements GodRelationView {
        private boolean ready = true;
        private final Map<GodRelationKey, GodRelationSnapshot> values = new LinkedHashMap<>();
        private final List<GodRelationKey> lookups = new ArrayList<>();
        @Override public boolean isReady() { return ready; }
        @Override public Optional<GodRelationSnapshot> find(ResourceLocation source, ResourceLocation target) {
            var key = new GodRelationKey(source, target); lookups.add(key); return Optional.ofNullable(values.get(key));
        }
        private void put(ResourceLocation source, ResourceLocation target, int score, Set<GodRelationTag> tags, long revision) {
            values.put(new GodRelationKey(source, target), relation(source, target, score, tags, revision));
        }
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("relation_test", path); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void rejects(Runnable call, String message) {
        try { call.run(); } catch (IllegalArgumentException expected) { check(true, message); return; }
        throw new AssertionError(message);
    }
    private static void rejectsMutation(Runnable call, String message) {
        try { call.run(); } catch (UnsupportedOperationException expected) { check(true, message); return; }
        throw new AssertionError(message);
    }
}
