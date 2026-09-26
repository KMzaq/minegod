package com.sande.mythictrpg.ai;

import com.sande.mythai.response.memory.DialogueMemoryBridge;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** No network: actual prompt reuse, audience filtering, room token race and strict model decision parsing. */
public final class RoomConversationEngineTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Files.createDirectories(java.nio.file.Path.of(args[0]).toAbsolutePath()));
        var god = ResourceLocation.parse("mythictrpg:fortuna");
        var otherGod = ResourceLocation.parse("mythictrpg:demeter");
        var player = UUID.randomUUID(); var roomA = UUID.randomUUID(); var roomB = UUID.randomUUID();
        var a = request(roomA, player, god, "QUEST_A REWARD_A FEEDBACK_A", "R_FRIENDLY", "E_HAPPY", "HISTORY_A");
        var b = request(roomB, player, god, "QUEST_B REWARD_B FEEDBACK_B", "R_HOSTILE", "E_ANGRY", "HISTORY_B");
        var profile = new AiTestContentRegistryBridge.Profile("포르투나", "IDENTITY_FORTUNA", "description", List.of("PERSONALITY_FORTUNA"),
                List.of("VALUE_FORTUNA"), List.of("T_PLAYFUL"), List.of("GUIDANCE_FORTUNA"), Map.of(), Map.of(), List.of("RESTRICTION_FORTUNA"), List.of());
        var content = new AiTestContentRegistryBridge.ContentSnapshot(profile,
                List.of(new AiTestContentRegistryBridge.Lore("secret", "history secret", "SECRET", 1, List.of("SECRET_LORE_MUST_NOT_LEAK")),
                        new AiTestContentRegistryBridge.Lore("public", "history public", "PUBLIC", 1, List.of("PUBLIC_LORE_RETRIEVED"))),
                List.of(), List.of("RELATION_GUIDANCE_FORTUNA"), List.of("SOCIAL_RIVAL"), 7);
        var memoryA = new DialogueMemoryBridge.Turn(null, null, List.of(), List.of(), "MEMORY_A", 1);
        var memoryB = new DialogueMemoryBridge.Turn(null, null, List.of(), List.of(), "MEMORY_B", 1);
        var promptA = new AiTestDialogueAdapter.RoomPrompt(a, memoryA, Map.of(god, content));
        var promptB = new AiTestDialogueAdapter.RoomPrompt(b, memoryB, Map.of(god, content));
        String a1 = text(promptA.classificationMessages()), b1 = text(promptB.classificationMessages());
        check(a1.contains("HISTORY_A") && !a1.contains("HISTORY_B"), "stage1 A history isolated");
        check(b1.contains("HISTORY_B") && !b1.contains("HISTORY_A"), "stage1 B history isolated");
        String a2 = text(promptA.generation(ConversationIntent.heuristicFallback()));
        String b2 = text(promptB.generation(ConversationIntent.heuristicFallback()));
        for (String marker : List.of("QUEST_A", "REWARD_A", "FEEDBACK_A", "HISTORY_A", "MEMORY_A", "R_FRIENDLY", "E_HAPPY")) {
            check(a2.contains(marker), "A context retained " + marker); check(!b2.contains(marker), "A context excluded from B " + marker);
        }
        for (String marker : List.of("QUEST_B", "REWARD_B", "FEEDBACK_B", "HISTORY_B", "MEMORY_B", "R_HOSTILE", "E_ANGRY")) {
            check(b2.contains(marker), "B context retained " + marker); check(!a2.contains(marker), "B context excluded from A " + marker);
        }
        for (String marker : List.of("IDENTITY_FORTUNA", "PERSONALITY_FORTUNA", "VALUE_FORTUNA", "RELATION_GUIDANCE_FORTUNA", "GUIDANCE_FORTUNA"))
            check(a2.contains(marker) && b2.contains(marker), "existing persona stage retained " + marker);
        check(!a2.contains("SECRET_LORE_MUST_NOT_LEAK") && !b2.contains("SECRET_LORE_MUST_NOT_LEAK"), "known secret is not public evidence");
        String lorePrompt = text(promptA.generation(new ConversationIntent(Set.of(), Set.of("history"), 100, ConversationIntent.Source.LOCAL_LLM)));
        check(lorePrompt.contains("PUBLIC_LORE_RETRIEVED") && !lorePrompt.contains("SECRET_LORE_MUST_NOT_LEAK"), "classified keywords retrieve public lore and cannot disclose known secret");
        check(a2.contains("conversation_leave") && a2.contains("conversation_invite"), "room control capabilities enter prompt");
        check(a2.contains("Conversation-control proposals are governed independently by ROOM_SCOPE"), "gameplay-empty directives cannot suppress permitted room controls");
        var continuedActivity = new AiTestDialogueAdapter.RoomPrompt(a, memoryA, Map.of(god, content), "끝말잇기");
        check(text(continuedActivity.generation(ConversationIntent.heuristicFallback())).contains("name: 끝말잇기"),
                "room conversational activity survives trimmed transcript");
        check(!b2.contains("name: 끝말잇기"), "activity cannot cross rooms sharing same player and God");
        var filtered = promptA.speech(new AiDialogueModels.StructuredAiResult(List.of(
                new AiDialogueModels.Speech(otherGod.toString(), "다른 신 비공개 응답", List.of("player")),
                new AiDialogueModels.Speech(god.toString(), "이 대화에 대한 답변입니다.", List.of("player"))), "", List.of()));
        check(filtered.size() == 1 && filtered.getFirst().speakerId().equals(god.toString()), "result cannot add another God speaker");
        var tokens = new RoomResponseTokens();
        var oldA = tokens.issue(roomA, 2, a.turnId()); var activeB = tokens.issue(roomB, 2, b.turnId());
        check(tokens.current(oldA) && tokens.current(activeB), "same player same God simultaneous rooms accepted");
        var delayedMemory = new CompletableFuture<String>(); var applied = new ArrayList<String>();
        delayedMemory.thenAccept(memory -> { if (tokens.current(oldA)) applied.add(memory); });
        var nextA = tokens.issue(roomA, 3, UUID.randomUUID()); delayedMemory.complete("PRIVATE_OLD_A");
        check(applied.isEmpty() && tokens.current(nextA) && tokens.current(activeB), "late memory cannot enter replacement or neighboring room");
        tokens.invalidate(roomA); check(!tokens.current(nextA) && tokens.current(activeB), "closing A preserves B");
        var split = new SplitRequest(roomB, 2, god, List.of(new Candidate("left", List.of(player), "REL_LEFT"),
                new Candidate("right", List.of(UUID.randomUUID()), "REL_RIGHT")), "relations", List.of());
        check(MythAiRoomConversationEngine.parseSplit(split, decision("left")).candidateKey().equals("left"), "offered split group accepted");
        check(MythAiRoomConversationEngine.parseSplit(split, decision("")).candidateKey().isEmpty(), "explicit leave accepted");
        var longHistory = new ArrayList<HistoryLine>();
        for (int index = 0; index < 100; index++) longHistory.add(new HistoryLine("PLAYER", player.toString(), "same_player", "LINE_" + index + "_" + "x".repeat(500)));
        String boundedSplit = MythAiRoomConversationEngine.splitInput(new SplitRequest(roomB, 2, god, split.candidates(),
                "relations", longHistory, List.of(god, otherGod)), profile, Map.of(god + " -> " + otherGod, List.of("RIVAL")));
        check(boundedSplit.length() <= 32000 && boundedSplit.contains("LINE_99_") && !boundedSplit.contains("LINE_0_")
                && boundedSplit.contains("REL_LEFT") && boundedSplit.contains("REL_RIGHT") && boundedSplit.contains("RIVAL")
                && boundedSplit.contains("PERSONALITY_FORTUNA"), "split trims old history while preserving candidates, latest context, persona and relations");
        try { MythAiRoomConversationEngine.parseSplit(split, decision("invented")); throw new AssertionError("unknown group accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { MythAiRoomConversationEngine.parseSplit(split, AiDialogueModels.StructuredAiResult.empty()); throw new AssertionError("empty decision accepted as leave"); }
        catch (IllegalArgumentException expected) { checks++; }
        tokens.clear(); check(!tokens.current(activeB), "shutdown invalidates all callbacks");
        var directory = java.nio.file.Files.createTempDirectory(java.nio.file.Files.createDirectories(java.nio.file.Path.of(args[0])), "room-log-");
        try (var log = new RoomDialogueLog()) {
            check(!log.append(directory, roomA, player, a.turnId(), false, "OFF", "DO_NOT_RECORD").get(), "recording off rejects log ingress");
            check(!java.nio.file.Files.exists(directory.resolve(roomA.toString())), "recording off creates no log directory");
            check(log.append(directory, roomA, player, a.turnId(), true, "PLAYER", "PRIVATE_ROOM_A_LOG").get(), "A transcript persisted");
            check(log.append(directory, roomB, player, b.turnId(), true, "PLAYER", "PRIVATE_ROOM_B_LOG").get(), "B transcript persisted");
            try (var files = java.nio.file.Files.walk(directory)) {
                var rows = files.filter(java.nio.file.Files::isRegularFile).toList();
                check(rows.size() == 2 && rows.stream().allMatch(p -> p.toString().endsWith(".txt")), "room logs use separate txt files");
                String textA = java.nio.file.Files.readString(rows.stream().filter(p -> p.toString().contains(roomA.toString())).findFirst().orElseThrow());
                check(textA.contains("PRIVATE_ROOM_A_LOG") && !textA.contains("PRIVATE_ROOM_B_LOG") && !textA.contains("DO_NOT_RECORD"), "logs preserve room and recording boundaries");
            }
            log.close();
            check(log.append(directory, roomA, player, a.turnId(), true, "RESTART", "after restart").get(), "log writer restarts for next server lifecycle");
            check(!log.transcript(directory, roomA, player, "OFF_PLAYER", "fortuna", a.turnId(), false, "PLAYER", "OFF_TRANSCRIPT").get(),
                    "recording off cannot write primary transcript");
            check(log.transcript(directory, roomA, player, "same_player", "fortuna", a.turnId(), true, "PLAYER", "PRIMARY_TRANSCRIPT").get(),
                    "primary transcript saved in server-root player directory");
            check(!java.nio.file.Files.exists(directory.resolve("ai-dialogue-logs/OFF_PLAYER")), "off primary transcript makes no player folder");
            try (var files = java.nio.file.Files.list(directory.resolve("ai-dialogue-logs/same_player"))) {
                var path = files.findFirst().orElseThrow();
                check(path.getFileName().toString().contains("_fortuna_" + roomA) && java.nio.file.Files.readString(path).contains("PRIMARY_TRANSCRIPT"),
                        "primary transcript filename includes time, God and collision-free room ID");
            }
        }
        System.out.println("RoomConversationEngineTest: PASS (" + checks + " checks; no server or LLM)");
    }
    private static Request request(UUID room, UUID player, ResourceLocation god, String context, String relation, String emotion, String history) {
        return new Request(room, 2, UUID.randomUUID(), player, "same_player", List.of(god), god, "앞서 한 부탁에 관해 이야기해줘",
                List.of(new HistoryLine("PLAYER", player.toString(), "same_player", history)), false, false, false,
                List.of(new GodState(god, relation, emotion, context, null)));
    }
    private static AiDialogueModels.StructuredAiResult decision(String key) {
        return new AiDialogueModels.StructuredAiResult(List.of(), "", List.of(new AiDialogueModels.Proposal("conversation_split", "", "reason", List.of(), Map.of("candidateKey", key))));
    }
    private static String text(List<AiDialogueModels.OllamaMessage> messages) { return messages.stream().map(AiDialogueModels.OllamaMessage::content).reduce("", (a, b) -> a + "\n" + b); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
