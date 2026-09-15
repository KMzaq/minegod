package com.sande.mythictrpg.ai.relationship;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EmotionContractGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation NPC = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "lubras");

    private EmotionContractGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void friendlyRelationshipCanCoexistWithStrongCurrentAnger(GameTestHelper helper) {
        RelationshipDataService data = RelationshipDataService.get(helper.getLevel().getServer());
        UUID player = UUID.randomUUID();
        CurrentEmotion current = new CurrentEmotion(Map.of(EmotionTypes.ANGER, 95, EmotionTypes.GRATITUDE, 10));
        PlayerMythDataService.get(helper.getLevel().getServer()).setAffinity(player, NPC, 90);
        data.setEmotion(player, NPC, current);

        RelationshipSnapshot relationship = new LegacyRelationshipSnapshotProvider(data).snapshot(player, NPC);
        EmotionSnapshot emotion = new LegacyEmotionSnapshotProvider(data).snapshot(player, NPC);
        helper.assertValueEqual(relationship.axis(RelationshipAxes.AFFINITY).orElseThrow(), 90,
                "Current anger unexpectedly changed long-term affinity");
        helper.assertValueEqual(relationship.axis(RelationshipAxes.TRUST).orElseThrow(), 0,
                "The no-storage default unexpectedly invented an additional relationship axis");
        helper.assertValueEqual(emotion.intensity(EmotionTypes.ANGER).orElseThrow(), 95,
                "Current anger was not preserved independently");
        helper.assertValueEqual(emotion.intensity(EmotionTypes.GRATITUDE).orElseThrow(), 10,
                "A second current emotion was not preserved");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void standardCandidatesAreAvailableButNewEmotionIdsRemainExtensible(GameTestHelper helper) {
        helper.assertTrue(EmotionTypes.standard().containsAll(java.util.Set.of(EmotionTypes.ANGER,
                EmotionTypes.HAPPINESS, EmotionTypes.ANNOYANCE, EmotionTypes.CURIOSITY, EmotionTypes.SADNESS,
                EmotionTypes.GRATITUDE, EmotionTypes.DISAPPOINTMENT, EmotionTypes.FEAR)),
                "Required standard current-emotion candidates are missing");

        EmotionSnapshot snapshot = new EmotionSnapshot(NPC.toString(), UUID.randomUUID().toString(),
                Map.of("remorse", 62, EmotionTypes.CURIOSITY, 18));
        helper.assertValueEqual(snapshot.intensity("remorse").orElseThrow(), 62,
                "A future emotion ID required an AI code change");
        helper.succeed();
    }
}
