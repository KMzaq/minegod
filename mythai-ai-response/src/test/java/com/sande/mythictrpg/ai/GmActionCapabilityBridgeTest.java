package com.sande.mythictrpg.ai;

import com.google.gson.JsonParser;
import com.sande.mythai.response.memory.DialogueMemoryBridge;
import com.sande.mythictrpg.ai.action.AiActionCapability;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.ai.example.DialogueExampleTag;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/** Production proposal normalization and room admission. No model or live game execution. */
public final class GmActionCapabilityBridgeTest {
    private static final ResourceLocation GOD = ResourceLocation.parse("test:gm");
    private static int checks;

    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Files.createDirectories(
                java.nio.file.Path.of("build/gm-action-bridge-test").toAbsolutePath()));
        directiveShape();
        exactEvaluationQuest();
        socialIntentIsNotPermission();
        System.out.println("GmActionCapabilityBridgeTest: " + checks + " checks PASS (no gameplay or model)");
    }

    private static void directiveShape() {
        String choice = UUID.randomUUID().toString();
        var cap = capability("npc_directive", null);
        for (String type : List.of("npc_directive", "mythictrpg:npc_directive"))
            check(normalize(type, Map.of("choice_id", choice), List.of(), List.of(cap)) != null, "exact directive accepted");
        for (String type : List.of("other:npc_directive", "execute_command", "ban", "kick"))
            check(normalize(type, Map.of("choice_id", choice), List.of(), List.of(cap)) == null, "foreign/admin action rejected");
        for (String invalid : List.of("", "STOP", "CONTINUE", "1-1-1-1-1", choice.toUpperCase(Locale.ROOT), " " + choice))
            check(normalize("npc_directive", Map.of("choice_id", invalid), List.of(), List.of(cap)) == null, "strict UUID only");
        check(normalize("npc_directive", Map.of("choice_id", choice), List.of(), List.of()) == null, "absent capability rejected");
        check(normalize("npc_directive", Map.of("choice_id", choice), List.of(),
                List.of(new AiActionCapability(ResourceLocation.parse("other:npc_directive"), Optional.empty(), "foreign"))) == null,
                "foreign capability cannot authorize");
        check(normalize("npc_directive", Map.of("choice_id", choice), List.of(UUID.randomUUID().toString()), List.of(cap)) == null,
                "model target rejected");
        for (String extra : List.of("target", "coordinates", "damage", "command", "force"))
            check(normalize("npc_directive", Map.of("choice_id", choice, extra, "injected"), List.of(), List.of(cap)) == null,
                    "undeclared fields rejected");
        var context = new StringBuilder();
        AiActionCapabilityBridge.appendCapabilities(context, List.of(cap));
        check(context.toString().contains("NPC_DIRECTIVE_CONTEXT") && context.toString().contains("does not prove arrival"),
                "prompt binds exact current choice and distinguishes acceptance from completion");
    }

    private static void exactEvaluationQuest() {
        var quest = ResourceLocation.parse("test:build_a");
        var cap = capability("structure_evaluation_request", quest);
        for (String type : List.of("structure_evaluation_request", "mythictrpg:structure_evaluation_request"))
            check(normalize(type, Map.of("quest_id", quest.toString()), List.of(), List.of(cap)) != null, "listed evaluation quest accepted");
        for (String raw : List.of("test:build_b", "build_a", " " + quest, quest + " "))
            check(normalize("structure_evaluation_request", Map.of("quest_id", raw), List.of(), List.of(cap)) == null, "unlisted quest rejected");
        check(normalize("other:structure_evaluation_request", Map.of("quest_id", quest.toString()), List.of(), List.of(cap)) == null,
                "foreign evaluation type rejected");
        check(normalize("structure_evaluation_request", Map.of("quest_id", quest.toString()), List.of(),
                List.of(capability("structure_evaluation_request", null))) == null, "unbound capability grants no quest");
        check(normalize("structure_evaluation_request", Map.of("quest_id", quest.toString()), List.of("other-player"), List.of(cap)) == null,
                "evaluation cannot name another player");
        for (String extra : List.of("score", "pass", "reward", "evidence"))
            check(normalize("structure_evaluation_request", Map.of("quest_id", quest.toString(), extra, "injected"), List.of(), List.of(cap)) == null,
                    "AI evaluation/award cannot replace game verdict");
    }

    private static void socialIntentIsNotPermission() {
        var profile = new AiTestContentRegistryBridge.Profile("fixture", "identity", "description", List.of("proud"),
                List.of("autonomy"), List.of("P_SHORT"), List.of(), Map.of(), Map.of(), List.of(), List.of());
        var content = new AiTestContentRegistryBridge.ContentSnapshot(profile, List.of(), List.of(), List.of(), List.of(), 1);
        var proposal = new AiDialogueModels.Proposal("npc_directive", "", "", List.of(), Map.of("choice_id", UUID.randomUUID().toString()));
        for (String text : List.of("안녕", "심심해", "왜 거기 있어?", "강해지고 싶어", "잡아줘")) {
            var request = request(text, false, false);
            var prompt = new AiTestDialogueAdapter.RoomPrompt(request, DialogueMemoryBridge.EMPTY, Map.of(GOD, content));
            var intent = new ConversationIntent(Set.of(DialogueExampleTag.S_INFORMATION_REQUEST), Set.of(), 90, ConversationIntent.Source.LOCAL_LLM);
            var messages = prompt.generation(intent);
            check(prompt.gameplayProposalsAllowed(), "social/information classification does not disable a live primary");
            check(JsonParser.parseString(messages.getLast().content()).getAsJsonObject().get("gameplayProposalsAllowed").getAsBoolean(),
                    "model receives live decision permission");
            check(prompt.admittedProposals(List.of(proposal)).equals(List.of(proposal)), "proposal reaches strict normalizer and game validator");
        }
        for (Request request : List.of(request("잡아줘", true, false), request("잡아줘", false, true))) {
            var prompt = new AiTestDialogueAdapter.RoomPrompt(request, DialogueMemoryBridge.EMPTY, Map.of(GOD, content));
            check(!prompt.gameplayProposalsAllowed() && prompt.admittedProposals(List.of(proposal)).isEmpty(),
                    "read-only and secondary cannot execute despite request wording");
        }
    }

    private static Request request(String text, boolean readOnly, boolean secondary) {
        return new Request(UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), "fixture", List.of(GOD), GOD,
                text, List.of(), readOnly, false, false, List.of(new GodState(GOD, "R_NEUTRAL", "E_CURIOUS", "", null)), secondary);
    }
    private static AiActionCapability capability(String type, ResourceLocation id) {
        return new AiActionCapability(ResourceLocation.parse("mythictrpg:" + type), Optional.ofNullable(id), "fixture");
    }
    private static AiDialogueModels.Proposal normalize(String type, Map<String, String> parameters, List<String> targets,
                                                       List<AiActionCapability> capabilities) {
        return AiActionCapabilityBridge.normalizeAuthorized(new AiDialogueModels.Proposal(type, "", "", targets, parameters),
                GOD, "fixture", capabilities, id -> Optional.empty());
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
