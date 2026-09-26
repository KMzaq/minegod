package com.sande.mythictrpg.ai;

import com.sande.mythai.response.memory.DialogueMemoryBridge;
import com.sande.mythai.response.memory.ExperienceHistory;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real room prompt pruning, revocable provenance and TXT sink; no server, network or LLM. */
final class RoomRecordingIntegrationTest {
    private static int checks;
    static int run(Path root, AiTestContentRegistryBridge.Profile profile) throws Exception {
        checks = 0;
        var room = UUID.randomUUID(); var source = UUID.randomUUID(); var other = UUID.randomUUID();
        var player = UUID.randomUUID(); var peer = UUID.randomUUID(); var spectator = UUID.randomUUID();
        var god = ResourceLocation.parse("mythictrpg:fortuna");
        var playerLine = new HistoryLine("PLAYER", player.toString(), "player", "KEEP_PLAYER", source);
        var sourced = new HistoryLine("NPC", god.toString(), "god", "REVOKABLE_SOURCE", source);
        var neighbor = new HistoryLine("NPC", god.toString(), "god", "REVOKABLE_SOURCE", other);
        var legacy = new HistoryLine("NPC", god.toString(), "god", "LEGACY_CURRENT_ROOM");
        var history = List.of(playerLine, sourced, neighbor, legacy);
        var grouped = RoomHistorySources.npcSources(room, history);
        check(grouped.size() == 3 && grouped.get(source).equals(List.of(sourced.text())), "provenance remains keyed by original room after move");
        check(grouped.get(room).equals(List.of(legacy.text())), "legacy constructor falls back to current room");
        var filtered = RoomHistorySources.filter(room, history, (origin, text) -> !origin.equals(source));
        check(filtered.equals(List.of(playerLine, neighbor, legacy)), "equal NPC words in another room and player input remain isolated");
        var request = new Request(room, 4, UUID.randomUUID(), player, "player", List.of(god), god, "current", history,
                false, true, false, List.of(new GodState(god, "R_NEUTRAL", "E_NEUTRAL", "", null)));
        var result = new Result(room, 4, request.turnId(), List.of(new Speech(god, "reply")), "[]", List.of(), "");
        check(result.deliverableFor(request), "exact room/revision/turn/speaker accepted");
        check(!new Result(room, 4, UUID.randomUUID(), result.speech(), "[]", List.of(), "").deliverableFor(request), "late turn rejected");
        check(!new Result(room, 3, request.turnId(), result.speech(), "[]", List.of(), "").deliverableFor(request), "old revision rejected");
        check(!new Result(room, 4, request.turnId(), List.of(new Speech(ResourceLocation.parse("mythictrpg:demeter"), "reply")), "[]", List.of(), "")
                .deliverableFor(request), "unselected speaker cannot be delivered or remembered");
        check(!new Result(room, 4, request.turnId(), List.of(new Speech(god, " ")), "[]", List.of(), "").deliverableFor(request), "blank reply rejected");
        check(!Result.failed(request, "FAILED").deliverableFor(request), "failure cannot be recorded as NPC delivery");
        var field = DialogueMemoryBridge.class.getDeclaredField("ROOM_HISTORY"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var guards = (Map<UUID, ExperienceHistory>) field.get(null);
        var allowed = new AtomicBoolean(true); var evidence = new ExperienceHistory();
        evidence.record(sourced.text(), List.of(ExperienceHistory.Reference.guarded(allowed::get)));
        guards.put(source, evidence);
        try {
            check(MythAiRoomConversationEngine.pruneHistory(request,Set.of()).history().equals(history), "live original evidence remains usable after room move");
            var pendingEvidence = DialogueMemoryBridge.roomHistoryReferences(room, history);
            check(pendingEvidence.size() == 1 && pendingEvidence.stream().allMatch(ExperienceHistory.Reference::current),
                    "pending generation snapshots original-room evidence rather than destination lookup");
            allowed.set(false);
            check(pendingEvidence.stream().anyMatch(ref -> !ref.current()), "revocation during async split/generation invalidates captured evidence");
            var pruned = MythAiRoomConversationEngine.pruneHistory(request,Set.of());
            check(pruned.history().equals(List.of(playerLine, neighbor, legacy)), "actual generation pruning revokes source evidence without changing neighbor");
            check(pruned.roomId().equals(room) && pruned.turnId().equals(request.turnId()) && pruned.recording(), "pruning preserves request lease and recording policy");
            var split = new SplitRequest(room, 4, god, List.of(new Candidate("group", List.of(player), "relation")), "", List.of(sourced, playerLine));
            String input = MythAiRoomConversationEngine.splitInput(split, profile, Map.of());
            check(!input.contains(sourced.text()) && input.contains(playerLine.text()), "actual split decision also excludes revoked source evidence");
            DialogueMemoryBridge.invalidateRoom(source);
            check(DialogueMemoryBridge.excludedRoomHistory(source, List.of(sourced.text())).contains(sourced.text()), "closing source cannot erase revocation guard for copied history");
        } finally { guards.remove(source); }

        Path directory = Files.createTempDirectory(root, "published-room-");
        var members = Map.of(player, "record_player", peer, "record_peer");
        var delivered = Map.of(player, new RoomDialogueEvent.Delivery("record_player", true, 3),
                peer, new RoomDialogueEvent.Delivery("record_peer", false, 2),
                spectator, new RoomDialogueEvent.Delivery("spectator", true, 0));
        String raw = "INITIAL_FULL_TEXT\n" + "한글🙂".repeat(700) + "\nEND_INITIAL";
        var initial = event(room, god, members, delivered, "NPC", god.toString(), raw, RecordingScope.TEST_RECORDING);
        try (var log = new RoomDialogueLog()) {
            check(log.published(directory, initial).get(), "non-LLM initial Encounter is recorded after dispatch");
            var files = transcripts(directory);
            check(files.size() == 2, "public spectators do not create duplicate participant logs");
            for (Path path : files) {
                String body = Files.readString(path);
                check(body.contains(raw) && body.contains("[message=" + initial.messageId() + "]") && body.contains("[sourceTurn=NONE]"),
                        "full raw text and shared logical identity survive long HUD paging");
                check(body.indexOf("INITIAL_FULL_TEXT") == body.lastIndexOf("INITIAL_FULL_TEXT"), "HUD pages do not duplicate a logical utterance");
            }
            var consent = event(room, god, members, Map.of(), "PLAYER", player.toString(), "CONSENT_WITHOUT_AI", RecordingScope.TEST_RECORDING);
            check(log.published(directory, consent).get(), "accepted player consent is retained even without LLM request or local chat display");
            check(readPlayer(directory, "record_player").contains("CONSENT_WITHOUT_AI") && !readPlayer(directory, "record_peer").contains("CONSENT_WITHOUT_AI"),
                    "undispatched peer cannot acquire a transcript receipt");
            var noDelivery = event(room, god, members, Map.of(), "NPC", god.toString(), "NPC_NOT_DELIVERED", RecordingScope.TEST_RECORDING);
            check(!log.published(directory, noDelivery).get(), "NPC with no dispatch creates no participant transcript");
            var off = event(UUID.randomUUID(), god, members, delivered, "NPC", god.toString(), "OFF_MUST_NOT_PERSIST", RecordingScope.TEST_EPHEMERAL);
            check(!log.published(directory, off).get() && transcripts(directory).size() == 2, "recording off adds neither files nor content");
            check(transcripts(directory).stream().allMatch(p -> !read(p).contains("OFF_MUST_NOT_PERSIST") && !read(p).contains("NPC_NOT_DELIVERED")),
                    "off and undelivered content absent on disk");
            try {
                var huge = event(UUID.randomUUID(), god, members, delivered, "NPC", god.toString(), "x".repeat(132000), RecordingScope.TEST_RECORDING);
                log.published(directory, huge).get(); throw new AssertionError("oversized body silently truncated");
            } catch (IllegalArgumentException | java.util.concurrent.ExecutionException expected) { checks++; }
        }
        return checks;
    }
    private static RoomDialogueEvent event(UUID room, ResourceLocation god, Map<UUID,String> members,
            Map<UUID,RoomDialogueEvent.Delivery> deliveries, String role, String speaker, String text, RecordingScope scope) {
        return new RoomDialogueEvent(UUID.randomUUID(), room, 4, Optional.empty(), RoomType.PUBLIC_MOBILE,
                scope, role, speaker, text, Set.of(god.toString()), members, deliveries, System.currentTimeMillis());
    }
    private static List<Path> transcripts(Path directory) throws Exception {
        try (var paths = Files.walk(directory.resolve("ai-dialogue-logs"))) { return paths.filter(Files::isRegularFile).toList(); }
    }
    private static String readPlayer(Path directory, String name) throws Exception {
        try (var paths = Files.list(directory.resolve("ai-dialogue-logs").resolve(name))) { return read(paths.findFirst().orElseThrow()); }
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception failure) { throw new AssertionError(failure); } }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
