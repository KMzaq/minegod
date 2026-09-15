package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.example.DialogueExampleRole;
import com.sande.mythictrpg.ai.example.DialogueExampleSnippet;
import com.sande.mythictrpg.ai.example.DialogueExampleTag;
import com.sande.mythictrpg.ai.example.DialogueExampleTurn;
import com.sande.mythictrpg.ai.intent.ConversationAct;
import com.sande.mythictrpg.ai.proposal.QuestRewardContext;
import com.sande.mythictrpg.ai.relationship.CurrentEmotion;
import com.sande.mythictrpg.ai.relationship.RelationshipMetrics;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Prompt-contract tests for the A-F dialogue cases. They test prompt inputs, not nondeterministic model prose. */
@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AiPromptStructureGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation GOD = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "fortuna_fixture");

    private AiPromptStructureGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void testANewQuestionUsesProfileAndPlayerText(GameTestHelper helper) {
        PromptFixture fixture = prompt("오늘은 운이 좋을까?", List.of(), "R_NEUTRAL");
        helper.assertTrue(fixture.system().contains("[NPC_IDENTITY_AND_PERSONALITY:")
                        && fixture.system().contains("[SELECTED_DIALOGUE_GUIDELINES:"),
                "Test A prompt omitted NPC profile or dialogue guidelines");
        helper.assertTrue(fixture.user().startsWith("[CURRENT_PLAYER_MESSAGE]\n오늘은 운이 좋을까?"),
                "Test A prompt did not preserve the new player question as the highest-priority input");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void testBRecentConversationDoesNotReceiveReferenceExamples(GameTestHelper helper) {
        PromptFixture fixture = prompt("음 나중에 열어볼래", List.of("오늘은 운이 좋을까?", "그걸 미리 알려주면 재미없잖아."),
                "R_FRIENDLY");
        helper.assertTrue(fixture.user().contains("[RECENT_CONVERSATION]")
                        && fixture.user().contains("그걸 미리 알려주면 재미없잖아.")
                        && fixture.user().contains("[CURRENT_PLAYER_MESSAGE]\n음 나중에 열어볼래"),
                "Test B prompt did not separate live history from the current player turn");
        helper.assertTrue(!fixture.system().contains("[REFERENCE_DIALOGUE_EXAMPLES:")
                        && !fixture.system().contains("저 문을 열어도 돼?")
                        && !fixture.system().contains("REFERENCE_DOOR"),
                "Test B phase-2 prompt still contains reference-example material");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void testCSmallTalkIsNotReplacedByExampleContent(GameTestHelper helper) {
        PromptFixture fixture = prompt("심심해.", List.of(), "R_FRIENDLY");
        helper.assertTrue(fixture.user().contains("[CURRENT_PLAYER_MESSAGE]\n심심해."),
                "Test C prompt omitted the unrelated small-talk input");
        helper.assertTrue(fixture.system().contains("Natural conversation is more important than demonstrating every guideline"),
                "Test C prompt did not prioritize natural dialogue over guideline performance");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void testDRelationshipGuidanceChangesPromptContext(GameTestHelper helper) {
        for (String tier : List.of("R_WARY", "R_FRIENDLY", "R_TRUSTED", "R_DEEP_BOND")) {
            PromptFixture fixture = prompt("날 믿어?", List.of(), tier);
            helper.assertTrue(fixture.system().contains("derivedTags: [" + tier + "]"),
                    "Test D prompt omitted relationship tier " + tier);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void testEUnknownQuestionRetainsDialogueGuidelines(GameTestHelper helper) {
        PromptFixture fixture = prompt("너도 가끔 외로워?", List.of(), "R_TRUSTED");
        helper.assertTrue(fixture.system().contains("Use the profile's concise, calm, and curious manner"),
                "Test E prompt did not retain NPC-specific dialogue guidance for a new question");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void testFUnsupportedWorldFactsAreExplicitlyRestricted(GameTestHelper helper) {
        PromptFixture fixture = prompt("그 장소에 가본 적 있어?", List.of(), "R_NEUTRAL");
        helper.assertTrue(fixture.system().contains("Do not invent a place, item, person, event, action, or changing game state"),
                "Test F prompt did not prohibit unsupported world facts");
        helper.assertTrue(fixture.user().contains("[CURRENT_GAME_CONTEXT]"),
                "Test F prompt omitted the authoritative current-game context boundary");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void testGExampleContaminationIsRemovedFromGenerationPrompt(GameTestHelper helper) {
        PromptFixture fixture = prompt("오늘 뭐 했어?", List.of(), "R_NEUTRAL");
        helper.assertTrue(!fixture.system().contains("문은 네 앞에")
                        && !fixture.system().contains("저 문을 열어도 돼?")
                        && !fixture.system().contains("REFERENCE_DOOR"),
                "Test G leaked a door-themed reference example into phase-2 generation");
        helper.assertTrue(fixture.user().startsWith("[CURRENT_PLAYER_MESSAGE]\n오늘 뭐 했어?"),
                "Test G did not make the live player question the first generation input");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void casualFeelingGetsSocialResponseDirective(GameTestHelper helper) {
        PromptFixture fixture = prompt("심심해.", List.of(), "R_FRIENDLY");
        helper.assertTrue(fixture.user().contains("mode: CASUAL_FEELING")
                        && fixture.user().contains("proposal_policy: []")
                        && fixture.user().contains("preferred_speech_characters: 48"),
                "A casual feeling did not receive the narrative-only social directive");
        helper.assertTrue(fixture.user().contains("Do not define, interpret, diagnose, reframe, or teach"),
                "A casual feeling can still be turned into an abstract lesson");
        DialogueTurnDirective directive = DialogueTurnDirective.plan("안녕", List.of(), 1,
                com.sande.mythictrpg.ai.intent.ConversationIntent.heuristicFallback());
        helper.assertTrue(directive.mode() == DialogueTurnDirective.Mode.GREETING
                        && "안녕.".equals(directive.compactSpeech("안녕, 이 길에서 네가 선택할 기회를 기다리고 있었어.")),
                "A greeting can still retain an unnecessary persona motif");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void unresolvedReferenceGetsClarificationDirective(GameTestHelper helper) {
        PromptFixture fixture = prompt("그 장소에 가본 적 있어?", List.of(), "R_NEUTRAL");
        helper.assertTrue(fixture.user().contains("mode: UNKNOWN_REFERENCE")
                        && fixture.user().contains("Ask what the player means or say that the context is insufficient"),
                "An unidentified place can still be treated as a current world fact");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void repeatedGreetingGetsFreshSocialDirective(GameTestHelper helper) {
        PromptFixture fixture = prompt("안녕", List.of("안녕", "안녕."), "R_NEUTRAL");
        helper.assertTrue(fixture.user().contains("mode: REPEATED_GREETING")
                        && fixture.user().contains("never repeat the exact greeting"),
                "A repeated greeting can still be generated as an identical isolated greeting");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void conversationActsSeparateCompanyCorrectionAndAnswer(GameTestHelper helper) {
        helper.assertTrue(ConversationAct.resolve(ConversationAct.UNSPECIFIED,
                        "심심하니까 너랑 얘기하러 왔잖아", "").equals(ConversationAct.SEEKING_COMPANY),
                "An explicit wish to talk with the NPC was not recognized");
        helper.assertTrue(ConversationAct.resolve(ConversationAct.UNSPECIFIED, "아니 심심하다고", "심심할 때는 뭐 해?")
                        .equals(ConversationAct.CORRECTING_NPC),
                "A correction was not recognized ahead of a generic small-talk label");
        helper.assertTrue(ConversationAct.resolve(ConversationAct.UNSPECIFIED, "할 게 없으니까 심심하지", "심심할 때는 뭐 해?")
                        .equals(ConversationAct.ANSWERING_NPC_QUESTION),
                "An answer to the NPC's question was not distinguished from a new complaint");
        helper.succeed();
    }

    private static PromptFixture prompt(String currentText, List<String> earlierTexts, String relationshipTier) {
        UUID playerId = UUID.randomUUID();
        String playerParticipantId = "player:" + playerId;
        String divineParticipantId = "divine:" + GOD;
        List<AiDialogueModels.ConversationTurn> history = new java.util.ArrayList<>();
        for (int index = 0; index < earlierTexts.size(); index++) {
            String speaker = index % 2 == 0 ? playerParticipantId : divineParticipantId;
            history.add(new AiDialogueModels.ConversationTurn(UUID.randomUUID(), Instant.now(), speaker, earlierTexts.get(index),
                    List.of(playerParticipantId)));
        }
        history.add(new AiDialogueModels.ConversationTurn(UUID.randomUUID(), Instant.now(), playerParticipantId, currentText,
                List.of(playerParticipantId)));
        AiDialogueModels.SessionSnapshot session = new AiDialogueModels.SessionSnapshot(UUID.randomUUID(), List.of(
                new AiDialogueModels.Participant(playerParticipantId, AiDialogueModels.ParticipantKind.PLAYER, "Player",
                        playerId, null, AiDialogueModels.ParticipantState.ACTIVE),
                new AiDialogueModels.Participant(divineParticipantId, AiDialogueModels.ParticipantKind.DIVINE, "Fortuna",
                        null, GOD, AiDialogueModels.ParticipantState.ACTIVE)),
                new AiDialogueModels.LocationSnapshot("minecraft:overworld", 0, 64, 0), Instant.now(), history, "", "", false);
        AiDialogueModels.RelationshipContext relationship = new AiDialogueModels.RelationshipContext(
                RelationshipMetrics.neutral(), CurrentEmotion.calm(), List.of(relationshipTier),
                List.of("The listed relationship tier changes manner, not game state."));
        AiDialogueModels.GodPersona persona = new AiDialogueModels.GodPersona("Fortuna",
                "Use the profile's concise, calm, and curious manner. Do not promise outcomes.",
                "A deity of chance who watches choices.", List.of(), List.of());
        DialogueExampleSnippet example = new DialogueExampleSnippet("REFERENCE_DOOR",
                java.util.Set.of(DialogueExampleTag.P_MYSTERIOUS, DialogueExampleTag.S_INFORMATION_REQUEST), List.of(
                        new DialogueExampleTurn(DialogueExampleRole.PLAYER, "저 문을 열어도 돼?"),
                        new DialogueExampleTurn(DialogueExampleRole.NPC, "문은 네 앞에 있으니 열어보든가.")));
        AiDialogueModels.ConversationContext context = new AiDialogueModels.ConversationContext(session, playerParticipantId,
                Map.of(divineParticipantId, persona), Map.of(playerParticipantId, Map.of(GOD.toString(), relationship)),
                List.of(), Map.of(), Map.of(), Map.of(divineParticipantId, List.of(example)), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of("testOnly", true), QuestRewardContext.safeDefaults(), java.util.Optional.empty(), List.of(),
                com.sande.mythictrpg.ai.intent.ConversationIntent.heuristicFallback());
        List<AiDialogueModels.OllamaMessage> messages = new AiContextBuilder().messages(context,
                AiDialogueConfig.INSTANCE.settings());
        return new PromptFixture(messages.getFirst().content(), messages.get(1).content());
    }

    private record PromptFixture(String system, String user) {
    }
}
