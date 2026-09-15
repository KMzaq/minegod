package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.context.GameConversationSnapshot;
import com.sande.mythictrpg.ai.proposal.QuestRewardContext;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ensures the direct LLM context uses bounded advisory reactions, not only the standalone debug path. */
@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AiContextReactionGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation GOD = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,
            "fixture_playful");

    private AiContextReactionGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void boundedReactionGuidelinesReachDirectConversationPrompt(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        String playerParticipant = "player:" + player;
        AiDialogueModels.SessionSnapshot session = new AiDialogueModels.SessionSnapshot(UUID.randomUUID(), List.of(
                new AiDialogueModels.Participant(playerParticipant, AiDialogueModels.ParticipantKind.PLAYER, "Player",
                        player, null, AiDialogueModels.ParticipantState.ACTIVE),
                new AiDialogueModels.Participant("divine:" + GOD, AiDialogueModels.ParticipantKind.DIVINE, "Playful",
                        null, GOD, AiDialogueModels.ParticipantState.ACTIVE)),
                new AiDialogueModels.LocationSnapshot("minecraft:overworld", 0, 64, 0), Instant.now(),
                List.of(new AiDialogueModels.ConversationTurn(UUID.randomUUID(), Instant.now(), playerParticipant,
                        "이 세계는 뭐야?", List.of(playerParticipant))), "", "", false);
        GameConversationSnapshot game = new GameConversationSnapshot(Map.of(), Map.of("health", 2.0F,
                "maxHealth", 20.0F, "dayTime", 18_000L, "raining", false), QuestRewardContext.safeDefaults());

        AiDialogueModels.ConversationContext context = new AiContextBuilder().build(session, playerParticipant, game, 3);
        List<String> guidelineIds = context.reactionGuidelinesByDivineParticipantId().get("divine:" + GOD).stream()
                .map(guideline -> guideline.id()).toList();
        List<AiDialogueModels.OllamaMessage> messages = new AiContextBuilder().messages(context,
                AiDialogueConfig.INSTANCE.settings());
        helper.assertTrue(guidelineIds.contains("LOW_HEALTH") && guidelineIds.contains("UNKNOWN_INFORMATION"),
                "Direct conversation context did not select snapshot-based reaction guidelines");
        helper.assertTrue(messages.getFirst().content().contains("REACTION GUIDANCE FOR THIS NPC ONLY"),
                "Selected reaction guidance did not reach the local LLM prompt");
        helper.succeed();
    }
}
