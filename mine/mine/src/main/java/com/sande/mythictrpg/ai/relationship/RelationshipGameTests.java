package com.sande.mythictrpg.ai.relationship;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RelationshipGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation GOD = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,
            "relationship_test_god");

    private RelationshipGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void relationshipMetricsPreserveAllFourDimensions(GameTestHelper helper) {
        RelationshipMetrics caseOne = new RelationshipMetrics(-100, -100, -100, 100);
        RelationshipMetrics caseTwo = new RelationshipMetrics(100, 100, 100, 0);
        RelationshipMetrics caseThree = new RelationshipMetrics(-60, 80, 70, 30);
        helper.assertValueEqual(caseOne.affinity(), -100, "Case 1 affinity was changed");
        helper.assertValueEqual(caseTwo.trust(), 100, "Case 2 trust was changed");
        helper.assertValueEqual(caseThree.respect(), 70, "Case 3 respect was changed");
        helper.assertValueEqual(caseThree.caution(), 30, "Case 3 caution was changed");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void emotionIsIndependentAndRelationshipValidationIsStrict(GameTestHelper helper) {
        RelationshipDataService data = RelationshipDataService.get(helper.getLevel().getServer());
        UUID player = UUID.randomUUID();
        CurrentEmotion anger = new CurrentEmotion(Map.of("anger", 95, "gratitude", 5));
        PlayerMythDataService.get(helper.getLevel().getServer()).setAffinity(player, GOD, 85);
        data.setEmotion(player, GOD, anger);
        helper.assertValueEqual(data.relationship(player, GOD).affinity(), 85,
                "AI did not read affinity from the canonical player profile");
        helper.assertValueEqual(data.emotion(player, GOD), anger,
                "Emotion data was not stored independently");

        expectRejected(helper, () -> new RelationshipMetrics(-101, 0, 0, 0), "affinity below minimum");
        expectRejected(helper, () -> new RelationshipMetrics(0, 101, 0, 0), "trust above maximum");
        expectRejected(helper, () -> new RelationshipMetrics(0, 0, -101, 0), "respect below minimum");
        expectRejected(helper, () -> new RelationshipMetrics(0, 0, 0, 101), "caution above maximum");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void resolverAndExampleRetrieverExposeRelationshipTagExtensionPoint(GameTestHelper helper) {
        RelationshipMetrics rival = new RelationshipMetrics(-60, 80, 70, 60);
        RelationshipStateResolver resolver = new DefaultRelationshipStateResolver();
        helper.assertTrue(resolver.resolve(rival).contains(RelationshipTag.R_RIVAL),
                "Mixed relationship did not expose R_RIVAL tag");

        AiDialogueModels.GodPersona persona = new AiDialogueModels.GodPersona("Test God", "Speak briefly.", "",
                List.of(), List.of("[R_FRIENDLY] Friendly example", "[R_RIVAL] Rival example", "Neutral example"));
        RelationshipExampleRetriever retriever = new RelationshipExampleRetriever(resolver);
        List<String> examples = retriever.retrieve(persona, rival, 3);
        helper.assertTrue(examples.contains("Rival example"), "R_RIVAL-tagged example was not retrieved");
        helper.assertTrue(!examples.contains("Friendly example"), "Unmatched R_FRIENDLY example was retrieved");
        helper.succeed();
    }

    private static void expectRejected(GameTestHelper helper, Runnable action, String description) {
        try {
            action.run();
            helper.fail(description + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected: constructors reject invalid source values rather than silently clamping them.
        }
    }
}
