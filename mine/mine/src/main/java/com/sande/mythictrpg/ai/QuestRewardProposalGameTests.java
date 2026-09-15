package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.context.GameConversationSnapshot;
import com.sande.mythictrpg.ai.proposal.GameProposalValidationFeedback;
import com.sande.mythictrpg.ai.proposal.ProposalDecodeResult;
import com.sande.mythictrpg.ai.proposal.QuestProposal;
import com.sande.mythictrpg.ai.proposal.QuestProposalConstraints;
import com.sande.mythictrpg.ai.proposal.QuestRewardConstraints;
import com.sande.mythictrpg.ai.proposal.QuestRewardContext;
import com.sande.mythictrpg.ai.proposal.QuestRewardProposalDecoder;
import com.sande.mythictrpg.ai.proposal.RewardProposal;
import com.sande.mythictrpg.ai.proposal.RewardProposalConstraints;
import com.sande.mythictrpg.ai.relationship.CurrentEmotion;
import com.sande.mythictrpg.ai.relationship.RelationshipMetrics;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuestRewardProposalGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation GOD_A = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "quest_god_a");
    private static final ResourceLocation GOD_B = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "quest_god_b");
    private static final QuestRewardProposalDecoder DECODER = new QuestRewardProposalDecoder();

    private QuestRewardProposalGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void friendlySmalltalkCanCarryAConceptQuestProposal(GameTestHelper helper) {
        Fixture fixture = fixture(1, 1);
        ProposalDecodeResult decoded = DECODER.decode(quest("심심한 자의 심부름", fixture.playerIds(), GOD_A,
                false, "collect", "대장간에 필요한 장미 덤불", "minecraft:rose_bush", 3, 2,
                "친한 플레이어가 심심해하자 작은 일을 맡긴다", ""), fixture.session(), allowedContext());

        helper.assertValueEqual(decoded.status(), ProposalDecodeResult.Status.ACCEPTED,
                "A constraint-compliant narrative quest proposal was rejected");
        QuestProposal quest = (QuestProposal) decoded.proposal().orElseThrow();
        helper.assertValueEqual(quest.concept().title(), "심심한 자의 심부름",
                "Quest proposal did not preserve its narrative concept");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void lowRelationshipCanUseAConditionalQuestConcept(GameTestHelper helper) {
        Fixture fixture = fixture(1, 1);
        ProposalDecodeResult decoded = DECODER.decode(quest("신뢰를 위한 첫걸음", fixture.playerIds(), GOD_A,
                false, "visit", "먼저 맹세의 돌을 찾아 진심을 보일 것", "", 0, 1,
                "낮은 신뢰도이므로 즉시 보상 대신 조건부 일을 제안한다", ""), fixture.session(), allowedContext());

        helper.assertValueEqual(decoded.status(), ProposalDecodeResult.Status.ACCEPTED,
                "A conditional quest concept was rejected even though the game allowed it");
        helper.assertTrue(((QuestProposal) decoded.proposal().orElseThrow()).narrativeReason().contains("조건부"),
                "Conditional relationship rationale was lost from the proposal");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void twoPlayersCanReceiveSharedQuestProposal(GameTestHelper helper) {
        Fixture fixture = fixture(2, 1);
        ProposalDecodeResult decoded = DECODER.decode(quest("둘이서 드는 짐", fixture.playerIds(), GOD_A, true,
                "collect", "함께 운반할 광석", "", 4, 2, "둘이 함께 요청했고 협력이 필요하다", ""),
                fixture.session(), allowedContext());

        helper.assertValueEqual(decoded.status(), ProposalDecodeResult.Status.ACCEPTED,
                "A valid shared quest proposal was rejected");
        QuestProposal quest = (QuestProposal) decoded.proposal().orElseThrow();
        helper.assertTrue(quest.shared() && quest.participantPlayerIds().size() == 2,
                "Shared quest participants were not retained independently of the session");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void multipleGodsRequireAnExplicitCollaborationReason(GameTestHelper helper) {
        Fixture fixture = fixture(1, 2);
        ProposalDecodeResult rejected = DECODER.decode(quest("공동 시험", fixture.playerIds(), GOD_A + "," + GOD_B,
                false, "visit", "두 신의 경계", "", 0, 2, "두 신이 플레이어를 시험한다", ""), fixture.session(),
                allowedContext());
        ProposalDecodeResult accepted = DECODER.decode(quest("공동 시험", fixture.playerIds(), GOD_A + "," + GOD_B,
                false, "visit", "두 신의 경계", "", 0, 2, "두 신이 플레이어를 시험한다",
                "각 신의 영역을 모두 지나야 하는 하나의 시험이다"), fixture.session(), allowedContext());

        helper.assertValueEqual(rejected.status(), ProposalDecodeResult.Status.REJECTED,
                "Multiple givers were accepted without a collaboration reason");
        helper.assertValueEqual(accepted.status(), ProposalDecodeResult.Status.ACCEPTED,
                "A justified multiple-giver quest proposal was rejected");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void rewardProposalRemainsConceptOnlyAndDoesNotGiveMinecraftItems(GameTestHelper helper) {
        Fixture fixture = fixture(1, 1);
        ProposalDecodeResult decoded = DECODER.decode(reward("작은 대장간의 답례", fixture.playerIds(), "OFFER",
                "utility_item", "dragon_claw_tool", "용의 발톱을 다듬을 수 있는 특별한 도구", "용의 발톱깎이", "", 2,
                "수고에 대한 보상 아이디어"), fixture.session(), allowedContext());

        helper.assertValueEqual(decoded.status(), ProposalDecodeResult.Status.ACCEPTED,
                "A catalogue-compliant concept reward was rejected");
        RewardProposal proposal = (RewardProposal) decoded.proposal().orElseThrow();
        helper.assertTrue(proposal.concept().orElseThrow().concreteItemId().isEmpty(),
                "A reward concept unexpectedly resolved or created a Minecraft item");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void excessiveRewardIsRejectedUntilItIsDowngradedOrNegotiated(GameTestHelper helper) {
        Fixture fixture = fixture(1, 1);
        ProposalDecodeResult excessive = DECODER.decode(reward("과한 요구", fixture.playerIds(), "OFFER", "utility_item",
                "dragon_claw_tool", "너무 강한 도구", "과한 발톱깎이", "", 4, "플레이어의 과한 요구"),
                fixture.session(), allowedContext());
        QuestRewardContext feedbackContext = new QuestRewardContext(allowedContext().constraints(), List.of(
                new GameProposalValidationFeedback(RewardProposal.TYPE, GameProposalValidationFeedback.Status.REJECTED,
                        "Requested reward cannot be granted", Map.of("maxPowerLevel", "2"))));
        ProposalDecodeResult downgraded = DECODER.decode(reward("작은 답례", fixture.playerIds(), "DOWNGRADE", "utility_item",
                "dragon_claw_tool", "허용 범위 안의 작은 도구", "작은 발톱깎이", "", 2,
                "강한 보상은 거절하고 낮은 보상을 제안한다"), fixture.session(), feedbackContext);

        helper.assertValueEqual(excessive.status(), ProposalDecodeResult.Status.REJECTED,
                "Reward above the game maximum was accepted");
        helper.assertValueEqual(downgraded.status(), ProposalDecodeResult.Status.ACCEPTED,
                "Constraint-compliant downgrade was rejected");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void proposalGatewayDefaultsToNoGameMutation(GameTestHelper helper) {
        Fixture fixture = fixture(1, 1);
        AiDialogueModels.Proposal raw = quest("기록만 되는 제안", fixture.playerIds(), GOD_A, false, "visit", "돌기둥", "",
                0, 1, "게임 검증기에 전달할 서사 제안", "");
        ProposalDecodeResult decoded = DECODER.decode(raw, fixture.session(), allowedContext());
        AiDialogueModels.ConversationContext context = conversationContext(fixture.session(), fixture.playerParticipantIds().getFirst(),
                QuestRewardContext.safeDefaults(), Map.of());
        AiProposalGateway.ProposalDecision decision = AiProposalGateway.INSTANCE.submit(new AiDialogueModels.ProposalEnvelope(
                fixture.session().sessionId(), raw, fixture.session(), context, decoded.proposal()));

        helper.assertValueEqual(decision.status(), AiProposalGateway.Status.REJECTED,
                "The default AI proposal gateway must not execute a quest/reward proposal");
        helper.assertValueEqual(fixture.session().history().size(), 0,
                "Decoding/submitting a proposal unexpectedly changed the conversation or game state");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void negotiationPromptRetainsRelationshipEmotionAndValidatorFeedback(GameTestHelper helper) {
        Fixture fixture = fixture(1, 1);
        String playerParticipant = fixture.playerParticipantIds().getFirst();
        AiDialogueModels.RelationshipContext relationship = new AiDialogueModels.RelationshipContext(
                new RelationshipMetrics(80, 65, 70, 10), new CurrentEmotion(Map.of("anger", 75)), List.of("R_CLOSE"),
                List.of("친하지만 현재 분노해 있다."));
        QuestRewardContext feedback = new QuestRewardContext(allowedContext().constraints(), List.of(
                new GameProposalValidationFeedback(RewardProposal.TYPE, GameProposalValidationFeedback.Status.REJECTED,
                        "Requested reward cannot be granted", Map.of("maxPowerLevel", "2"))));
        AiDialogueModels.ConversationContext context = conversationContext(fixture.session(), playerParticipant, feedback,
                Map.of(playerParticipant, Map.of(GOD_A.toString(), relationship)));
        List<AiDialogueModels.OllamaMessage> messages = new AiContextBuilder().messages(context,
                AiDialogueConfig.INSTANCE.settings());

        helper.assertTrue(messages.getFirst().content().contains("reward_proposal"),
                "Reward negotiation output schema was not explained to the model");
        helper.assertTrue(messages.get(1).content().contains("Requested reward cannot be granted")
                        && messages.get(1).content().contains("anger"),
                "Relationship/emotion context or game validator feedback was lost before reward negotiation");
        helper.succeed();
    }

    private static QuestRewardContext allowedContext() {
        return new QuestRewardContext(new QuestRewardConstraints(
                new QuestProposalConstraints(3, Set.of("collect", "visit", "hunt"), Set.of("minecraft:rose_bush")),
                new RewardProposalConstraints(2, Set.of("utility_item", "consumable", "minor_blessing"), Set.of())),
                List.of());
    }

    private static AiDialogueModels.Proposal quest(String title, List<UUID> playerIds, Object giverIds, boolean shared,
            String objectiveType, String targetConcept, String concreteItemId, int amount, int difficulty,
            String narrativeReason, String collaborationReason) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("giverNpcIds", giverIds.toString());
        parameters.put("shared", Boolean.toString(shared));
        parameters.put("objectiveType", objectiveType);
        parameters.put("targetConcept", targetConcept);
        parameters.put("concreteItemId", concreteItemId);
        parameters.put("suggestedAmount", Integer.toString(amount));
        parameters.put("difficulty", Integer.toString(difficulty));
        parameters.put("narrativeReason", narrativeReason);
        parameters.put("collaborationReason", collaborationReason);
        return new AiDialogueModels.Proposal(QuestProposal.TYPE, title, narrativeReason, playerIds.stream()
                .map(playerId -> "player:" + playerId).toList(), parameters);
    }

    private static AiDialogueModels.Proposal reward(String title, List<UUID> playerIds, String decision, String category,
            String theme, String description, String suggestedName, String concreteItemId, int powerLevel,
            String narrativeReason) {
        return new AiDialogueModels.Proposal(RewardProposal.TYPE, title, narrativeReason, playerIds.stream()
                .map(playerId -> "player:" + playerId).toList(), Map.of("negotiationDecision", decision,
                "category", category, "theme", theme, "description", description, "suggestedName", suggestedName,
                "concreteItemId", concreteItemId, "powerLevel", Integer.toString(powerLevel), "narrativeReason",
                narrativeReason));
    }

    private static AiDialogueModels.ConversationContext conversationContext(AiDialogueModels.SessionSnapshot session,
            String triggeringPlayer, QuestRewardContext proposalContext,
            Map<String, Map<String, AiDialogueModels.RelationshipContext>> relationships) {
        return new AiDialogueModels.ConversationContext(session, triggeringPlayer, Map.of(), relationships, List.of(),
                Map.of(), Map.of(), Map.of(), Map.of("questSource", "external"), proposalContext,
                List.of(QuestProposal.TYPE, RewardProposal.TYPE));
    }

    private static Fixture fixture(int playerCount, int godCount) {
        List<UUID> players = java.util.stream.IntStream.range(0, playerCount).mapToObj(ignored -> UUID.randomUUID()).toList();
        List<AiDialogueModels.Participant> participants = new java.util.ArrayList<>();
        for (UUID player : players) {
            participants.add(new AiDialogueModels.Participant("player:" + player, AiDialogueModels.ParticipantKind.PLAYER,
                    "Player", player, null, AiDialogueModels.ParticipantState.ACTIVE));
        }
        participants.add(new AiDialogueModels.Participant("divine:" + GOD_A, AiDialogueModels.ParticipantKind.DIVINE,
                "God A", null, GOD_A, AiDialogueModels.ParticipantState.ACTIVE));
        if (godCount > 1) {
            participants.add(new AiDialogueModels.Participant("divine:" + GOD_B, AiDialogueModels.ParticipantKind.DIVINE,
                    "God B", null, GOD_B, AiDialogueModels.ParticipantState.ACTIVE));
        }
        return new Fixture(players, new AiDialogueModels.SessionSnapshot(UUID.randomUUID(), participants,
                new AiDialogueModels.LocationSnapshot("minecraft:overworld", 0, 64, 0), Instant.now(), List.of(), "", "", false));
    }

    private record Fixture(List<UUID> playerIds, AiDialogueModels.SessionSnapshot session) {
        List<String> playerParticipantIds() {
            return playerIds.stream().map(player -> "player:" + player).toList();
        }
    }
}
