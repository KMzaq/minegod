package com.sande.mythictrpg.ai.tone;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.agent.KnowledgePermissions;
import com.sande.mythictrpg.ai.agent.NpcAgent;
import com.sande.mythictrpg.ai.agent.NpcIdentity;
import com.sande.mythictrpg.ai.relationship.CurrentEmotion;
import com.sande.mythictrpg.ai.relationship.RelationshipMetrics;
import com.sande.mythictrpg.ai.tag.ExampleStyleTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlayerToneResponseGuidanceGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation GOD = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,
            "tone_fixture");

    private PlayerToneResponseGuidanceGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void informalSpeechIsAcceptedWhenRelationshipIsClose(GameTestHelper helper) {
        PlayerToneResponseGuidance guidance = new PlayerToneResponseGuidanceResolver().resolve(
                Set.of(PlayerSpeechTone.T_INFORMAL), relationship("R_CLOSE"), formalAgent(),
                NpcSocialAuthorityContext.unknown());
        helper.assertValueEqual(guidance.disposition(), ToneResponseDisposition.ACCEPT,
                "Close relationship should accept informal speech even for a formal NPC");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void strangerInformalSpeechGetsOnlyAMildEtiquetteWarning(GameTestHelper helper) {
        PlayerToneResponseGuidance guidance = new PlayerToneResponseGuidanceResolver().resolve(
                Set.of(PlayerSpeechTone.T_INFORMAL), relationship("R_STRANGER"), formalAgent(),
                NpcSocialAuthorityContext.unknown());
        helper.assertValueEqual(guidance.disposition(), ToneResponseDisposition.CAUTION,
                "A stranger's casual speech should be a proportional warning, not an automatic punishment");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void playerAuthorityAllowsCasualSpeechButNotAbuse(GameTestHelper helper) {
        NpcSocialAuthorityContext superior = new NpcSocialAuthorityContext(RelativeAuthority.PLAYER_SUPERIOR,
                List.of("encounter commander"));
        PlayerToneResponseGuidance casual = new PlayerToneResponseGuidanceResolver().resolve(
                Set.of(PlayerSpeechTone.T_INFORMAL), relationship("R_STRANGER"), formalAgent(), superior);
        PlayerToneResponseGuidance rude = new PlayerToneResponseGuidanceResolver().resolve(
                Set.of(PlayerSpeechTone.T_IMPOLITE), relationship("R_STRANGER"), formalAgent(), superior);
        helper.assertValueEqual(casual.disposition(), ToneResponseDisposition.ACCEPT,
                "Game-owned player authority should allow casual delivery in this encounter");
        helper.assertValueEqual(rude.disposition(), ToneResponseDisposition.FIRM_BOUNDARY,
                "Player authority must not turn insulting delivery into an automatic acceptance");
        helper.succeed();
    }

    private static NpcAgent formalAgent() {
        return new NpcAgent(new NpcIdentity(GOD, "Tone Fixture"), GOD, Map.of(), List.of(), List.of(), List.of(),
                Set.of(ExampleStyleTag.P_FORMAL), KnowledgePermissions.none(), CurrentEmotion.calm(), List.of(), List.of());
    }

    private static AiDialogueModels.RelationshipContext relationship(String tag) {
        return new AiDialogueModels.RelationshipContext(RelationshipMetrics.neutral(), CurrentEmotion.calm(),
                List.of(tag), List.of());
    }
}
