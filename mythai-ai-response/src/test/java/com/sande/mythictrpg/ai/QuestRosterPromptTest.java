package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.action.AiActionCapability;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythai.response.memory.DialogueMemoryBridge;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

public final class QuestRosterPromptTest {
    private static int checks;
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:fortuna");
    private static final String QUEST = "mythictrpg:roster_test";
    private static String context(String quest) { return "[QUEST_ROSTER_REQUESTS]\n[\"" + quest + "\"]\n[/QUEST_ROSTER_REQUESTS]\n"; }
    private static Request request(String context, boolean readOnly, boolean secondary) {
        return new Request(UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), "fixture", List.of(GOD), GOD,
                "왜 그걸 쓰는 거야? 지금은 그만.", List.of(), readOnly, false, false,
                List.of(new GodState(GOD, "R_NEUTRAL", "E_UNASSESSED", context, null)), secondary);
    }
    private static AiDialogueModels.Proposal proposal(String type, Map<String,String> parameters, List<String> targets) {
        return new AiDialogueModels.Proposal(type, "참가자 관리", "변경 전 확인", targets, parameters);
    }
    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/quest-roster-ai-test").toAbsolutePath()));
        var p = proposal("quest_roster_request", Map.of("quest_id", QUEST), List.of());
        var a = request(context(QUEST), false, false);
        check(QuestRosterPrompt.allows(a, p), "listed own quest menu");
        check(!QuestRosterPrompt.allows(request(context("mythictrpg:other"), false, false), p), "session B cannot use A quest");
        check(!QuestRosterPrompt.allows(request(context(QUEST), true, false), p), "read-only block");
        check(!QuestRosterPrompt.allows(request(context(QUEST), false, true), p), "secondary block");
        check(!QuestRosterPrompt.allows(request(context(QUEST) + context(QUEST), false, false), p), "duplicate authority section");
        for (String bad : List.of("", "[QUEST_ROSTER_REQUESTS]{}[/QUEST_ROSTER_REQUESTS]", "[QUEST_ROSTER_REQUESTS][123][/QUEST_ROSTER_REQUESTS]"))
            check(QuestRosterPrompt.quests(request(bad, false, false)).isEmpty(), "malformed context");
        var cap = new AiActionCapability(ResourceLocation.parse("mythictrpg:quest_roster_request"), Optional.empty(), "fixture");
        check(AiActionCapabilityBridge.normalizeAuthorized(p, GOD, "포기", List.of(cap), id -> Optional.empty()) != null, "normalizer accepts menu");
        for (String key : List.of("target_id", "consent", "refund", "operation", "score")) {
            var invalid = proposal("quest_roster_request", Map.of("quest_id", QUEST, key, "yes"), List.of());
            check(!QuestRosterPrompt.allows(a, invalid), "intent gate rejects model mechanics");
            check(AiActionCapabilityBridge.normalizeAuthorized(invalid, GOD, "포기", List.of(cap), id -> Optional.empty()) == null, "normalizer rejects model mechanics");
        }
        check(AiActionCapabilityBridge.normalizeAuthorized(proposal("other:quest_roster_request", Map.of("quest_id", QUEST), List.of()), GOD, "", List.of(cap), id -> Optional.empty()) == null, "foreign namespace");
        check(AiActionCapabilityBridge.normalizeAuthorized(proposal("quest_roster_request", Map.of("quest_id", QUEST), List.of(UUID.randomUUID().toString())), GOD, "", List.of(cap), id -> Optional.empty()) == null, "foreign target");
        var profile = new AiTestContentRegistryBridge.Profile("포르투나", "identity", "description", List.of("proud"), List.of("autonomy"),
                List.of("P_SHORT"), List.of(), Map.of(), Map.of(), List.of(), List.of());
        var content = new AiTestContentRegistryBridge.ContentSnapshot(profile, List.of(), List.of(), List.of(), List.of(), 1);
        var prompt = new AiTestDialogueAdapter.RoomPrompt(a, DialogueMemoryBridge.EMPTY, Map.of(GOD, content));
        var intent = new com.sande.mythictrpg.ai.intent.ConversationIntent(Set.of(com.sande.mythictrpg.ai.example.DialogueExampleTag.S_INFORMATION_REQUEST),
                Set.of(), 90, com.sande.mythictrpg.ai.intent.ConversationIntent.Source.LOCAL_LLM);
        var messages = prompt.generation(intent);
        check(!prompt.gameplayProposalsAllowed(), "ordinary gameplay remains intent-gated");
        check(prompt.admittedProposals(List.of(p, proposal("reward_proposal", Map.of(), List.of()))).equals(List.of(p)), "information query can open menu only");
        check(messages.getLast().content().contains("questRosterMenuQuestIds") && messages.getLast().content().contains(QUEST), "actual primary prompt carries menu IDs");
        System.out.println("QuestRosterPromptTest: " + checks + " checks PASS");
    }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
