package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.agent.NpcConversationPolicy;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConversationSessionGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";

    private ConversationSessionGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void gameOwnedInteractionIdCanBeAttachedWithoutAiGeneratingOne(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        UUID interactionId = UUID.randomUUID();
        ResourceLocation god = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "session_fixture");
        ConversationSessionManager sessions = new ConversationSessionManager();
        ConversationSessionManager.Session session = sessions.create(List.of(
                ConversationSessionManager.player("Player", playerId, AiDialogueModels.ParticipantState.ACTIVE),
                ConversationSessionManager.divine(god, "Session God")),
                new AiDialogueModels.LocationSnapshot("minecraft:overworld", 0, 64, 0), 8, interactionId);

        helper.assertValueEqual(session.snapshot(8).interactionId().orElseThrow(), interactionId,
                "The game-owned interaction ID was not retained by the conversation session");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void divineSessionOccupancyIsReleasedWhenConversationCloses(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        ResourceLocation god = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "occupancy_fixture");
        ConversationSessionManager sessions = new ConversationSessionManager();
        ConversationSessionManager.Session session = sessions.create(List.of(
                ConversationSessionManager.player("Player", playerId, AiDialogueModels.ParticipantState.ACTIVE),
                ConversationSessionManager.divine(god, "Session God")),
                new AiDialogueModels.LocationSnapshot("minecraft:overworld", 0, 64, 0), 8);

        helper.assertValueEqual(sessions.findByDivine(god).size(), 1,
                "A present divine participant was not tracked as occupying its session");
        helper.assertTrue(!NpcConversationPolicy.singlePhysicalPresence().canJoinSessionCount(
                sessions.findByDivine(god).size()), "The default physical NPC policy allowed a second session");
        sessions.close(session.id());
        helper.assertTrue(sessions.findByDivine(god).isEmpty(),
                "Closing a conversation left stale divine session occupancy behind");
        helper.assertTrue(new NpcConversationPolicy(true, 3).canJoinSessionCount(1),
                "An explicitly remote/multi-presence NPC policy could not permit another session");
        helper.succeed();
    }
}
