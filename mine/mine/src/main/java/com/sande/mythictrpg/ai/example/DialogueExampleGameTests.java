package com.sande.mythictrpg.ai.example;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.agent.KnowledgePermissions;
import com.sande.mythictrpg.ai.agent.NpcAgent;
import com.sande.mythictrpg.ai.agent.NpcIdentity;
import com.sande.mythictrpg.ai.relationship.CurrentEmotion;
import com.sande.mythictrpg.ai.relationship.RelationshipMetrics;
import com.sande.mythictrpg.ai.tag.ExampleStyleTag;
import com.sande.mythictrpg.ai.intent.ConversationIntent;
import com.sande.mythictrpg.ai.intent.ConversationIntentRouter;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DialogueExampleGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation GOD = id("example_fixture");

    private DialogueExampleGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void sharedLibraryRanksTheMultiTagExampleWithoutPersonaCopies(GameTestHelper helper) {
        DialogueExample matching = example("EX_0142", Set.of(DialogueExampleTag.P_GRUFF,
                DialogueExampleTag.P_INDIRECT_CARE, DialogueExampleTag.R_CLOSE, DialogueExampleTag.S_ITEM_REQUEST));
        DialogueExample weakMatch = example("EX_GREETING", Set.of(DialogueExampleTag.P_GRUFF,
                DialogueExampleTag.R_STRANGER, DialogueExampleTag.S_CHAT));
        DialogueExampleRetriever retriever = new WeightedTagDialogueExampleRetriever(() -> List.of(weakMatch, matching));

        List<DialogueExampleSnippet> selected = retriever.retrieve(new ExampleRetrievalQuery(
                new WeightedExampleStyleContext(Map.of(
                        DialogueExampleTag.P_GRUFF, 5,
                        DialogueExampleTag.P_INDIRECT_CARE, 4,
                        DialogueExampleTag.R_CLOSE, 4,
                        DialogueExampleTag.S_ITEM_REQUEST, 5)), 3));
        helper.assertValueEqual(selected.getFirst().exampleId(), "EX_0142",
                "The most compatible reusable example was not ranked first");
        helper.assertValueEqual(selected.getFirst().dialogue().get(1).role(), DialogueExampleRole.NPC,
                "The reusable example dialogue was not preserved");
        List<DialogueExampleSnippet> limited = retriever.retrieve(new ExampleRetrievalQuery(
                new WeightedExampleStyleContext(Map.of(DialogueExampleTag.P_GRUFF, 5)), 1));
        helper.assertValueEqual(limited.size(), 1,
                "The configured example limit did not bound prompt candidates");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void tagGuidanceComposesSituationRelationshipAndVoiceWithoutCrossProductExamples(
            GameTestHelper helper) {
        TagDialogueGuidance gruff = guidance(DialogueExampleTag.P_GRUFF, 50, "말을 짧게 한다.");
        TagDialogueGuidance close = guidance(DialogueExampleTag.R_CLOSE, 78, "친밀함은 기본 말투를 부드럽게만 조절한다.");
        TagDialogueGuidance request = guidance(DialogueExampleTag.S_ITEM_REQUEST, 90, "조건을 묻고 완료를 주장하지 않는다.");
        java.util.Map<DialogueExampleTag, TagDialogueGuidance> profiles = java.util.Map.of(
                gruff.tag(), gruff, close.tag(), close, request.tag(), request);
        TagDialogueGuidanceRepository repository = new TagDialogueGuidanceRepository() {
            @Override
            public java.util.Optional<TagDialogueGuidance> find(DialogueExampleTag tag) {
                return java.util.Optional.ofNullable(profiles.get(tag));
            }

            @Override
            public List<TagDialogueGuidance> all() {
                return List.copyOf(profiles.values());
            }
        };
        List<TagDialogueGuidance> selected = new TagDialogueGuidanceResolver(repository).resolve(
                new WeightedExampleStyleContext(Map.of(DialogueExampleTag.P_GRUFF, 3,
                        DialogueExampleTag.R_CLOSE, 5, DialogueExampleTag.S_ITEM_REQUEST, 6)), 3);
        helper.assertValueEqual(selected.stream().map(TagDialogueGuidance::tag).toList(),
                List.of(DialogueExampleTag.S_ITEM_REQUEST, DialogueExampleTag.R_CLOSE, DialogueExampleTag.P_GRUFF),
                "Situation and relationship priority did not constrain the base voice in the composed tag guidance");
        helper.assertTrue(selected.stream().allMatch(profile -> !profile.dialogue().isEmpty()),
                "The bounded example budget did not reserve a demonstration for each active tag category");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void contextTagsCombineSpeechRelationshipEmotionSituationAndAudience(GameTestHelper helper) {
        UUID speakerPlayer = UUID.randomUUID();
        UUID listenerPlayer = UUID.randomUUID();
        String speakerId = "player:" + speakerPlayer;
        String listenerId = "player:" + listenerPlayer;
        AiDialogueModels.Participant speaker = new AiDialogueModels.Participant(speakerId,
                AiDialogueModels.ParticipantKind.PLAYER, "Speaker", speakerPlayer, null,
                AiDialogueModels.ParticipantState.ACTIVE);
        AiDialogueModels.Participant listener = new AiDialogueModels.Participant(listenerId,
                AiDialogueModels.ParticipantKind.PLAYER, "Listener", listenerPlayer, null,
                AiDialogueModels.ParticipantState.LISTENER);
        AiDialogueModels.Participant divine = new AiDialogueModels.Participant("divine:" + GOD,
                AiDialogueModels.ParticipantKind.DIVINE, "Example God", null, GOD,
                AiDialogueModels.ParticipantState.ACTIVE);
        AiDialogueModels.SessionSnapshot session = new AiDialogueModels.SessionSnapshot(UUID.randomUUID(),
                List.of(speaker, listener, divine), new AiDialogueModels.LocationSnapshot("minecraft:overworld", 0, 64, 0),
                Instant.now(), List.of(), "", "", false);
        AiDialogueModels.RelationshipContext closeAndAngry = new AiDialogueModels.RelationshipContext(
                new RelationshipMetrics(80, 70, 30, 0), new CurrentEmotion(Map.of("anger", 90)), List.of(), List.of());
        AiDialogueModels.RelationshipContext listenerNeutral = new AiDialogueModels.RelationshipContext(
                RelationshipMetrics.neutral(), CurrentEmotion.calm(), List.of(), List.of());
        Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationships = Map.of(
                speakerId, Map.of(GOD.toString(), closeAndAngry),
                listenerId, Map.of(GOD.toString(), listenerNeutral));
        NpcAgent agent = new NpcAgent(new NpcIdentity(GOD, "Example God"), GOD, Map.of(), List.of(), List.of(),
                List.of(), Set.of(ExampleStyleTag.P_GRUFF, ExampleStyleTag.P_INDIRECT_CARE),
                KnowledgePermissions.none(), CurrentEmotion.calm(), List.of(), List.of());

        DialogueExampleContextResolver resolver = new DialogueExampleContextResolver();
        Set<DialogueExampleTag> tags = resolver.resolve(session, speakerId, divine,
                closeAndAngry, agent, "이 검을 만들어줄래?", relationships);
        helper.assertTrue(tags.containsAll(Set.of(DialogueExampleTag.P_GRUFF, DialogueExampleTag.P_INDIRECT_CARE,
                DialogueExampleTag.R_CLOSE, DialogueExampleTag.E_ANGRY, DialogueExampleTag.S_ITEM_REQUEST,
                DialogueExampleTag.C_GROUP, DialogueExampleTag.C_UNTRUSTED_LISTENER)),
                "Example selection did not reflect all independently-derived context dimensions: " + tags);
        WeightedExampleStyleContext weighted = resolver.resolveWeighted(session, speakerId, divine,
                closeAndAngry, agent, "이 검을 만들어줄래?", relationships);
        helper.assertValueEqual(weighted.weight(DialogueExampleTag.P_GRUFF), 5,
                "Gruff speech style did not receive its high selection weight");
        helper.assertValueEqual(weighted.weight(DialogueExampleTag.P_INDIRECT_CARE), 4,
                "Indirect care speech style did not receive its selection weight");
        helper.assertValueEqual(weighted.weight(DialogueExampleTag.R_CLOSE), 4,
                "Close relationship did not receive its selection weight");
        helper.assertValueEqual(weighted.weight(DialogueExampleTag.E_ANGRY), 2,
                "Emotion did not receive its lower selection weight");
        helper.assertValueEqual(weighted.weight(DialogueExampleTag.S_ITEM_REQUEST), 5,
                "Requested situation did not receive its high selection weight");
        helper.assertValueEqual(weighted.weight(DialogueExampleTag.C_GROUP), 2,
                "Group context did not receive its lower selection weight");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void npcScopedExamplesNeverCrossIntoAnotherNpc(GameTestHelper helper) {
        ResourceLocation hephaestus = id("greek_olympian_hephaestus");
        ResourceLocation athena = id("greek_olympian_athena");
        DialogueExample hephaestusOnly = new DialogueExample("EX_HEPHAESTUS_ONLY",
                Set.of(DialogueExampleTag.P_GRUFF, DialogueExampleTag.S_ITEM_REQUEST), Set.of(hephaestus),
                List.of(new DialogueExampleTurn(DialogueExampleRole.PLAYER, "만들어줘"),
                        new DialogueExampleTurn(DialogueExampleRole.NPC, "재료부터 가져와.")));
        DialogueExample global = example("EX_GLOBAL_ITEM", Set.of(DialogueExampleTag.S_ITEM_REQUEST));
        DialogueExampleRetriever retriever = new WeightedTagDialogueExampleRetriever(
                () -> List.of(hephaestusOnly, global));
        WeightedExampleStyleContext style = new WeightedExampleStyleContext(Map.of(DialogueExampleTag.S_ITEM_REQUEST, 5));
        List<DialogueExampleSnippet> hephSelected = retriever.retrieve(new ExampleRetrievalQuery(style, hephaestus, 5));
        List<DialogueExampleSnippet> athenaSelected = retriever.retrieve(new ExampleRetrievalQuery(style, athena, 5));
        helper.assertTrue(hephSelected.stream().anyMatch(example -> example.exampleId().equals("EX_HEPHAESTUS_ONLY")),
                "The owning NPC could not retrieve its scoped example");
        helper.assertTrue(athenaSelected.stream().noneMatch(example -> example.exampleId().equals("EX_HEPHAESTUS_ONLY")),
                "An NPC-scoped example leaked into another NPC context");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void fastKeywordIntentAvoidsClassifierForExplicitItemRequest(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        AiDialogueModels.Participant player = new AiDialogueModels.Participant("player:" + playerId,
                AiDialogueModels.ParticipantKind.PLAYER, "Player", playerId, null,
                AiDialogueModels.ParticipantState.ACTIVE);
        AiDialogueModels.Participant divine = new AiDialogueModels.Participant("divine:" + GOD,
                AiDialogueModels.ParticipantKind.DIVINE, "Example God", null, GOD,
                AiDialogueModels.ParticipantState.ACTIVE);
        AiDialogueModels.SessionSnapshot session = new AiDialogueModels.SessionSnapshot(UUID.randomUUID(),
                List.of(player, divine), new AiDialogueModels.LocationSnapshot("minecraft:overworld", 0, 64, 0),
                Instant.now(), List.of(), "", "", false);
        ConversationIntent intent = new ConversationIntentRouter().tryFastIntent("철검을 만들어줘", session).orElseThrow();
        helper.assertTrue(intent.source() == ConversationIntent.Source.HEURISTIC,
                "Explicit keyword request did not use the deterministic routing path");
        helper.assertTrue(intent.tags().contains(DialogueExampleTag.S_ITEM_REQUEST),
                "Explicit item request did not receive S_ITEM_REQUEST");
        helper.succeed();
    }

    private static DialogueExample example(String id, Set<DialogueExampleTag> tags) {
        return new DialogueExample(id, tags, List.of(new DialogueExampleTurn(DialogueExampleRole.PLAYER, "Can you help?"),
                new DialogueExampleTurn(DialogueExampleRole.NPC, "Bring the materials.")));
    }

    private static TagDialogueGuidance guidance(DialogueExampleTag tag, int priority, String rule) {
        return new TagDialogueGuidance(tag, priority, List.of(rule), List.of(
                new DialogueExampleTurn(DialogueExampleRole.PLAYER, "도와줄 수 있어?"),
                new DialogueExampleTurn(DialogueExampleRole.NPC, "상황을 말해라.")));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }
}
