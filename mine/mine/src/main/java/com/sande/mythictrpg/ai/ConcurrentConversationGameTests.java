package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.context.ConversationTurnContextSnapshot;
import com.sande.mythictrpg.ai.context.GameConversationSnapshot;
import com.sande.mythictrpg.ai.proposal.GameProposalValidationFeedback;
import com.sande.mythictrpg.ai.proposal.QuestProposalConstraints;
import com.sande.mythictrpg.ai.proposal.QuestRewardConstraints;
import com.sande.mythictrpg.ai.proposal.QuestRewardContext;
import com.sande.mythictrpg.ai.proposal.QuestRewardContextProvider;
import com.sande.mythictrpg.ai.proposal.RewardProposal;
import com.sande.mythictrpg.ai.proposal.RewardProposalConstraints;
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

/** Phase 13 boundary tests. They use only immutable DTOs and never query Minecraft audience/location state. */
@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConcurrentConversationGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation HEPHAESTUS = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,
            "concurrency_hephaestus");
    private static final ResourceLocation ATHENA = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,
            "concurrency_athena");

    private ConcurrentConversationGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void concurrentSessionsKeepTranscriptAndTurnIdentitySeparate(GameTestHelper helper) {
        ConversationSessionManager manager = new ConversationSessionManager();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        ConversationSessionManager.Session sessionA = manager.create(List.of(
                ConversationSessionManager.player("A", playerA, AiDialogueModels.ParticipantState.ACTIVE),
                ConversationSessionManager.divine(HEPHAESTUS, "Hephaestus")), location(), 8);
        ConversationSessionManager.Session sessionB = manager.create(List.of(
                ConversationSessionManager.player("B", playerB, AiDialogueModels.ParticipantState.ACTIVE),
                ConversationSessionManager.divine(ATHENA, "Athena")), location(), 8);

        sessionA.enqueuePlayerTurn(playerA, "player:" + playerA, "A", "A의 대화");
        sessionB.enqueuePlayerTurn(playerB, "player:" + playerB, "B", "B의 대화");
        var turnA = sessionA.beginNextTurn().orElseThrow();
        var turnB = sessionB.beginNextTurn().orElseThrow();
        sessionA.append(new AiDialogueModels.ConversationTurn(UUID.randomUUID(), Instant.now(),
                turnA.queuedTurn().speakerId(), turnA.queuedTurn().text(), List.of("player:" + playerA)));
        sessionB.append(new AiDialogueModels.ConversationTurn(UUID.randomUUID(), Instant.now(),
                turnB.queuedTurn().speakerId(), turnB.queuedTurn().text(), List.of("player:" + playerB)));

        helper.assertTrue(!sessionA.id().equals(sessionB.id()) && turnA.token().turnId() == 1L
                        && turnB.token().turnId() == 1L,
                "Concurrent sessions did not retain independent session/turn identities");
        helper.assertValueEqual(sessionA.snapshot(8).history().getFirst().text(), "A의 대화",
                "Session A transcript was contaminated by Session B");
        helper.assertValueEqual(sessionB.snapshot(8).history().getFirst().text(), "B의 대화",
                "Session B transcript was contaminated by Session A");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void questRewardConstraintsAndFeedbackStayInsideTheirTurnSnapshot(GameTestHelper helper) {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        AiDialogueModels.SessionSnapshot sessionA = session("A", playerA, HEPHAESTUS, "Hephaestus");
        AiDialogueModels.SessionSnapshot sessionB = session("B", playerB, ATHENA, "Athena");
        QuestRewardContext contextA = context(3, "번개창은 너무 강해서 거절됨");
        QuestRewardContext contextB = context(1, "작은 축복만 허용됨");
        QuestRewardContextProvider provider = (session, ignoredTrigger) -> session.sessionId().equals(sessionA.sessionId())
                ? contextA : contextB;

        ConversationTurnContextSnapshot turnA = turn(sessionA, playerA,
                new GameConversationSnapshot(Map.of(), Map.of("session", "A"),
                        provider.capture(sessionA, "player:" + playerA)));
        ConversationTurnContextSnapshot turnB = turn(sessionB, playerB,
                new GameConversationSnapshot(Map.of(), Map.of("session", "B"),
                        provider.capture(sessionB, "player:" + playerB)));

        helper.assertValueEqual(turnA.gameSnapshot().questRewardContext().constraints().reward().maxPowerLevel(), 3,
                "Session A RewardConstraint was not retained in its turn snapshot");
        helper.assertValueEqual(turnB.gameSnapshot().questRewardContext().constraints().reward().maxPowerLevel(), 1,
                "Session B RewardConstraint was contaminated by Session A");
        helper.assertTrue(turnA.gameSnapshot().questRewardContext().validationFeedback().getFirst().reason()
                        .contains("번개창")
                        && turnB.gameSnapshot().questRewardContext().validationFeedback().getFirst().reason()
                        .contains("작은 축복"),
                "Quest/Reward validator feedback leaked across concurrent session turn snapshots");
        helper.succeed();
    }

    private static ConversationTurnContextSnapshot turn(AiDialogueModels.SessionSnapshot session, UUID player,
            GameConversationSnapshot gameSnapshot) {
        return new ConversationTurnContextSnapshot(session.sessionId(), 1L, 1L, UUID.randomUUID(), Instant.now(),
                Instant.now(), "player:" + player, player, session, gameSnapshot);
    }

    private static QuestRewardContext context(int maxPowerLevel, String rejectionReason) {
        return new QuestRewardContext(new QuestRewardConstraints(
                new QuestProposalConstraints(3, Set.of("collect"), Set.of()),
                new RewardProposalConstraints(maxPowerLevel, Set.of("utility_item"), Set.of())), List.of(
                new GameProposalValidationFeedback(RewardProposal.TYPE, GameProposalValidationFeedback.Status.REJECTED,
                        rejectionReason, Map.of("maxPowerLevel", Integer.toString(maxPowerLevel)))));
    }

    private static AiDialogueModels.SessionSnapshot session(String name, UUID player, ResourceLocation god, String godName) {
        return new AiDialogueModels.SessionSnapshot(UUID.randomUUID(), List.of(
                new AiDialogueModels.Participant("player:" + player, AiDialogueModels.ParticipantKind.PLAYER, name,
                        player, null, AiDialogueModels.ParticipantState.ACTIVE),
                new AiDialogueModels.Participant("divine:" + god, AiDialogueModels.ParticipantKind.DIVINE, godName,
                        null, god, AiDialogueModels.ParticipantState.ACTIVE)), location(), Instant.now(), List.of(), "", "", false);
    }

    private static AiDialogueModels.LocationSnapshot location() {
        return new AiDialogueModels.LocationSnapshot("minecraft:overworld", 0, 64, 0);
    }
}
