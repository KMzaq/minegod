package com.sande.mythictrpg.ai.integration.mythictrpg;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.AiDialogueModels;
import com.sande.mythictrpg.ai.proposal.GameProposalValidationFeedback;
import com.sande.mythictrpg.ai.proposal.QuestProposalConstraints;
import com.sande.mythictrpg.ai.proposal.QuestRewardConstraints;
import com.sande.mythictrpg.ai.proposal.QuestRewardContext;
import com.sande.mythictrpg.ai.proposal.RewardProposal;
import com.sande.mythictrpg.ai.proposal.RewardProposalConstraints;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
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
public final class MythicTrpgConversationSnapshotGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation GOD = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,
            "snapshot_fixture");

    private MythicTrpgConversationSnapshotGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void canonicalPlayerAffinityIsReadAsPromptInputWithoutAiPersistence(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        PlayerMythDataService profiles = PlayerMythDataService.get(helper.getLevel().getServer());
        profiles.setAffinity(playerId, GOD, 1_000);
        String playerParticipantId = "player:" + playerId;
        AiDialogueModels.SessionSnapshot session = new AiDialogueModels.SessionSnapshot(UUID.randomUUID(), List.of(
                new AiDialogueModels.Participant(playerParticipantId, AiDialogueModels.ParticipantKind.PLAYER,
                        "Snapshot Player", playerId, null, AiDialogueModels.ParticipantState.ACTIVE),
                new AiDialogueModels.Participant("divine:" + GOD, AiDialogueModels.ParticipantKind.DIVINE,
                        "Snapshot God", null, GOD, AiDialogueModels.ParticipantState.ACTIVE)),
                new AiDialogueModels.LocationSnapshot("minecraft:overworld", 0, 64, 0), Instant.now(), List.of(),
                "", "", false);

        var captured = new MythicTrpgConversationSnapshotProvider(helper.getLevel().getServer())
                .capture(session, playerParticipantId);
        var relationship = captured.relationshipsByPlayerParticipantId().get(playerParticipantId).get(GOD.toString());
        helper.assertValueEqual(relationship.metrics().affinity(), 100,
                "The bounded AI prompt view did not read canonical affinity");
        helper.assertValueEqual(relationship.metrics().trust(), 0,
                "The default adapter unexpectedly invented an extra relationship axis");
        helper.assertValueEqual(profiles.find(playerId).orElseThrow().affinities().get(GOD), 1_000,
                "Capturing game context changed the authoritative player affinity");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void gameOwnedQuestRewardContextIsRelayedWithoutQuestOrRewardMutation(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        String playerParticipantId = "player:" + playerId;
        AiDialogueModels.SessionSnapshot session = new AiDialogueModels.SessionSnapshot(UUID.randomUUID(), List.of(
                new AiDialogueModels.Participant(playerParticipantId, AiDialogueModels.ParticipantKind.PLAYER,
                        "Constraint Player", playerId, null, AiDialogueModels.ParticipantState.ACTIVE),
                new AiDialogueModels.Participant("divine:" + GOD, AiDialogueModels.ParticipantKind.DIVINE,
                        "Constraint God", null, GOD, AiDialogueModels.ParticipantState.ACTIVE)),
                new AiDialogueModels.LocationSnapshot("minecraft:overworld", 0, 64, 0), Instant.now(), List.of(),
                "", "", false);
        QuestRewardContext expected = new QuestRewardContext(new QuestRewardConstraints(
                new QuestProposalConstraints(3, Set.of("collect"), Set.of("minecraft:rose_bush")),
                new RewardProposalConstraints(2, Set.of("utility_item"), Set.of())), List.of(
                new GameProposalValidationFeedback(RewardProposal.TYPE, GameProposalValidationFeedback.Status.REJECTED,
                        "Requested reward cannot be granted", Map.of("maxPowerLevel", "2"))));

        var captured = new MythicTrpgConversationSnapshotProvider(helper.getLevel().getServer(),
                (ignoredSession, ignoredTrigger) -> expected).capture(session, playerParticipantId);
        helper.assertValueEqual(captured.questRewardContext(), expected,
                "The game-owned Quest/Reward context was not relayed into the AI snapshot");
        helper.succeed();
    }
}
