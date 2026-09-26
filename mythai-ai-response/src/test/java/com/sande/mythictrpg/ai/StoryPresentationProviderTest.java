package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import com.sande.mythictrpg.story.presentation.StoryAiPresentationContracts.*;
import com.sande.mythictrpg.story.presentation.StoryRoomDisclosure;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** No server and no LLM: actual prompt projection, strict result parsing, and audience intersection. */
public final class StoryPresentationProviderTest {
    private static int checks;
    public static void main(String[] args) {
        var god = ResourceLocation.parse("mythictrpg:demeter");
        var snapshot = new Snapshot(UUID.randomUUID(), UUID.randomUUID(), god, PresentationKind.DIRECT_DIALOGUE,
                GenerationPolicy.AI_PARAPHRASE, List.of(new AllowedStatement("ALLOWED_FACT", true, "statement_1")),
                List.of("amused"), List.of(new HookOffer("hook_1", "OFFER_TITLE", "OFFER_SUMMARY")), 2);
        var persona = new AiTestContentRegistryBridge.Profile("name", "PRIVATE_IDENTITY", "PRIVATE_BACKGROUND",
                List.of("STYLE_PERSONALITY"), List.of("STYLE_VALUES"), List.of("STYLE_VOICE"), List.of("STYLE_GUIDELINE"),
                Map.of(), Map.of(), List.of("STYLE_RESTRICTION"), List.of());
        String prompt = StoryOllamaPresentationProvider.messages(snapshot, persona).toString();
        check(prompt.contains("ALLOWED_FACT") && prompt.contains("STYLE_PERSONALITY") && prompt.contains("hook_1"), "allowed facts, persona and aliases retained");
        check(!prompt.contains("PRIVATE_IDENTITY") && !prompt.contains("PRIVATE_BACKGROUND"), "private profile identity/background not sent");
        check(!prompt.contains(snapshot.requestId().toString()) && !prompt.contains(snapshot.audiencePlayerId().toString()), "internal request and player IDs omitted");
        String relationalPrompt = StoryOllamaPresentationProvider.messages(snapshot, persona,
                Map.of("mythictrpg:fortuna", List.of("S_WARY"))).toString();
        check(relationalPrompt.contains("staticRelationsFromSpeaker") && relationalPrompt.contains("mythictrpg:fortuna")
                && relationalPrompt.contains("S_WARY"), "authored relation tags retain their exact target direction in event rendering");
        var parsed = StoryOllamaPresentationProvider.parse(snapshot, result(god.toString(), List.of(), List.of()));
        check(parsed.speech().equals(List.of("전달할 대사")) && parsed.proposedHookAlias().isEmpty(), "single permitted speaker accepted");
        var hook = new AiDialogueModels.Proposal("story_event_hook", "", "", List.of(), Map.of("hook_alias", "hook_1"));
        check(StoryOllamaPresentationProvider.parse(snapshot, result(god.toString(), List.of(), List.of(hook)))
                .proposedHookAlias().orElseThrow().equals("hook_1"), "offered alias accepted");
        reject(() -> StoryOllamaPresentationProvider.parse(snapshot, result("mythictrpg:fortuna", List.of(), List.of())), "different speaker rejected");
        reject(() -> StoryOllamaPresentationProvider.parse(snapshot, result(god.toString(), List.of("other-player"), List.of())), "model audience rejected");
        reject(() -> StoryOllamaPresentationProvider.parse(snapshot, AiDialogueModels.StructuredAiResult.empty()), "empty rendering rejected");
        reject(() -> StoryOllamaPresentationProvider.parse(snapshot, new AiDialogueModels.StructuredAiResult(
                Collections.nCopies(3, new AiDialogueModels.Speech(god.toString(), "line", List.of())), "", List.of())), "speech count limit enforced");
        for (var invalid : List.of(
                new AiDialogueModels.Proposal("give_item", "", "", List.of(), Map.of()),
                new AiDialogueModels.Proposal("story_event_hook", "", "", List.of(), Map.of("hook_alias", "invented")),
                new AiDialogueModels.Proposal("story_event_hook", "", "", List.of(), Map.of("token", UUID.randomUUID().toString())),
                new AiDialogueModels.Proposal("story_event_hook", "", "", List.of("someone"), Map.of("hook_alias", "hook_1")),
                new AiDialogueModels.Proposal("story_event_hook", "", "", List.of(), Map.of("hook_alias", "hook_1", "event_id", "secret")))) {
            reject(() -> StoryOllamaPresentationProvider.parse(snapshot, result(god.toString(), List.of(), List.of(invalid))), "unauthorized proposal rejected");
            check(StoryConversationContextBridge.allowedAlias(snapshot, invalid).isEmpty(), "ordinary room bridge rejects unauthorized proposal");
        }
        check(StoryConversationContextBridge.allowedAlias(snapshot, hook).orElseThrow().equals("hook_1"), "ordinary bridge accepts exact offered alias");
        String roomPrompt = StoryConversationContextBridge.prompt(snapshot);
        check(roomPrompt.contains("ALLOWED_FACT") && !roomPrompt.contains(snapshot.requestId().toString()), "room prompt contains no capability IDs");
        var selected = new AiDialogueModels.Proposal("story_disclose", "", "", List.of(), Map.of("statement_aliases", "statement_1"));
        check(StoryConversationContextBridge.selectedAliases(snapshot,List.of(selected,selected)).equals(List.of("statement_1")), "canonical aliases are bounded and deduplicated");
        for (var invalid : List.of(
                new AiDialogueModels.Proposal("story_disclose", "", "", List.of(), Map.of("statement_aliases", "statement_1,statement_8")),
                new AiDialogueModels.Proposal("story_disclose", "", "", List.of("other"), Map.of("statement_aliases", "statement_1")),
                new AiDialogueModels.Proposal("story_disclose", "", "", List.of(), Map.of("statement_aliases", "statement_1", "fact_id", "secret")),
                new AiDialogueModels.Proposal("give_item", "", "", List.of(), Map.of("statement_aliases", "statement_1"))))
            check(StoryConversationContextBridge.selectedAliases(snapshot,List.of(invalid)).isEmpty(), "no target IDs, raw facts or unapproved aliases accepted");
        var peer = ResourceLocation.parse("mythictrpg:fortuna");
        var privateAudience = new RoomAudiencePolicy(RoomAudienceMode.PRIVATE_ROOM, Set.of(peer));
        check(privateAudience.permits(false, 2, Set.of(god,peer), god), "explicit multi-player private disclosure allowed");
        check(!privateAudience.permits(true, 2, Set.of(god,peer), god), "private permission cannot become public");
        check(!privateAudience.permits(false, 2, Set.of(god,ResourceLocation.parse("mythictrpg:lubras")), god), "unlisted listener rejected");
        check(!RoomAudiencePolicy.ownerOnly().permits(false,1,Set.of(god,peer),god), "legacy owner-only excludes other God");
        check(new RoomAudiencePolicy(RoomAudienceMode.PUBLIC,Set.of()).permits(true,10,Set.of(god,peer),god), "authored public disclosure supports global audience");
        check(!new RoomAudiencePolicy(RoomAudienceMode.NEVER,Set.of()).permits(false,1,Set.of(god),god), "NEVER stays withheld in one-to-one room");
        check(StoryRoomDisclosure.allowedLevel(2, false, 0, List.of(2, 1), List.of(2)) == 1, "private audience gets minimum disclosed level");
        check(StoryRoomDisclosure.allowedLevel(2, false, 0, List.of(2, 0), List.of(2)) == 0, "one denied player prevents disclosure");
        check(StoryRoomDisclosure.allowedLevel(2, false, 0, List.of(2), List.of(0)) == 0, "unknown listening God prevents disclosure");
        check(StoryRoomDisclosure.allowedLevel(2, true, 0, List.of(2), List.of()) == 0, "private fact cannot enter public chat");
        check(StoryRoomDisclosure.allowedLevel(2, true, 1, List.of(2), List.of(2)) == 1, "public knowledge cap retained");
        check(StoryRoomDisclosure.allowedLevel(2, false, 0, List.of(), List.of()) == 0, "empty audience fails closed");
        check(StoryRoomDisclosure.allowedLevel(0, false, 0, List.of(2), List.of()) == 0, "speaker cannot use unknown facts");
        for (int speaker = 0; speaker <= 4; speaker++) for (int publicLevel = 0; publicLevel <= 4; publicLevel++)
            for (int player = 0; player <= 4; player++) for (int otherGod = 0; otherGod <= 4; otherGod++) {
                int permitted = StoryRoomDisclosure.allowedLevel(speaker, true, publicLevel, List.of(player), List.of(otherGod));
                check(permitted == Math.min(Math.min(speaker, publicLevel), Math.min(player, otherGod)), "all audience caps intersect");
            }
        System.out.println("StoryPresentationProviderTest: PASS (" + checks + " checks; no server or LLM)");
    }
    private static AiDialogueModels.StructuredAiResult result(String god, List<String> audience, List<AiDialogueModels.Proposal> proposals) {
        return new AiDialogueModels.StructuredAiResult(List.of(new AiDialogueModels.Speech(god, "전달할 대사", audience)), "", proposals);
    }
    private static void reject(Runnable test, String message) {
        try { test.run(); throw new AssertionError(message); } catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
