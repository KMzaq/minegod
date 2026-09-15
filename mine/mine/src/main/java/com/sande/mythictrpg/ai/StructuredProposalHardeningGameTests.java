package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.proposal.ProposalDecodeResult;
import com.sande.mythictrpg.ai.proposal.QuestProposal;
import com.sande.mythictrpg.ai.proposal.QuestRewardContext;
import com.sande.mythictrpg.ai.proposal.RequestDisposition;
import com.sande.mythictrpg.ai.proposal.RequestJudgmentProposal;
import com.sande.mythictrpg.ai.proposal.RequestKind;
import com.sande.mythictrpg.ai.proposal.StructuredProposalDecoder;
import com.sande.mythictrpg.ai.proposal.VouchRequestProposal;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Integration hardening coverage for untrusted structured LLM proposal data. */
@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StructuredProposalHardeningGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation GOD = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,
            "hardening_fixture");

    private StructuredProposalHardeningGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void unknownActionsAndForeignParticipantIdsAreRejectedBeforeGameGateway(GameTestHelper helper) {
        Fixture fixture = fixture();
        StructuredProposalDecoder decoder = new StructuredProposalDecoder();
        ProposalDecodeResult unknown = decoder.decode(new AiDialogueModels.Proposal("give_admin_sword", "금지", "금지",
                List.of(fixture.playerParticipantId()), Map.of()), fixture.session(), QuestRewardContext.safeDefaults());
        ProposalDecodeResult foreignGod = decoder.decode(new AiDialogueModels.Proposal(QuestProposal.TYPE, "가짜 신", "가짜 신",
                List.of(fixture.playerParticipantId()), Map.of("giverNpcIds", "minecraft:fake", "shared", "false",
                "targetConcept", "가짜", "narrativeReason", "금지")), fixture.session(), QuestRewardContext.safeDefaults());

        helper.assertValueEqual(unknown.status(), ProposalDecodeResult.Status.REJECTED,
                "Unknown game action was not rejected by the AI proposal allow-list");
        helper.assertValueEqual(foreignGod.status(), ProposalDecodeResult.Status.REJECTED,
                "A proposal naming an NPC outside the session was not rejected");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void vouchProposalRequiresCurrentActivePlayersAndPresentGod(GameTestHelper helper) {
        Fixture fixture = fixture();
        StructuredProposalDecoder decoder = new StructuredProposalDecoder();
        AiDialogueModels.Proposal valid = new AiDialogueModels.Proposal(VouchRequestProposal.TYPE, "보증 요청", "보증 요청",
                List.of(fixture.playerBParticipantId()), Map.of("npcId", GOD.toString(), "sponsorPlayerId",
                fixture.playerA().toString(), "beneficiaryPlayerId", fixture.playerB().toString(), "reason", "동료의 약속"));
        AiDialogueModels.Proposal invalid = new AiDialogueModels.Proposal(VouchRequestProposal.TYPE, "위조 보증", "위조 보증",
                List.of(fixture.playerBParticipantId()), Map.of("npcId", GOD.toString(), "sponsorPlayerId",
                UUID.randomUUID().toString(), "beneficiaryPlayerId", fixture.playerB().toString(), "reason", "위조"));

        helper.assertValueEqual(decoder.decode(valid, fixture.session(), QuestRewardContext.safeDefaults()).status(),
                ProposalDecodeResult.Status.ACCEPTED, "A valid non-binding vouch request was rejected");
        helper.assertValueEqual(decoder.decode(invalid, fixture.session(), QuestRewardContext.safeDefaults()).status(),
                ProposalDecodeResult.Status.REJECTED, "A vouch request with a non-participant sponsor was accepted");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void requestJudgmentCannotGrantOrTargetAnotherPlayersRequest(GameTestHelper helper) {
        Fixture fixture = fixture();
        StructuredProposalDecoder decoder = new StructuredProposalDecoder();
        AiDialogueModels.Proposal valid = new AiDialogueModels.Proposal(RequestJudgmentProposal.TYPE, "판단", "판단",
                List.of(fixture.playerParticipantId()), Map.of("npcId", GOD.toString(), "requesterPlayerId",
                fixture.playerA().toString(), "requestKind", RequestKind.ITEM.name(), "disposition",
                RequestDisposition.NEGOTIATE.name(), "reason", "재료부터 확인한다"));
        AiDialogueModels.Proposal foreignTarget = new AiDialogueModels.Proposal(RequestJudgmentProposal.TYPE, "위조", "위조",
                List.of(fixture.playerBParticipantId()), Map.of("npcId", GOD.toString(), "requesterPlayerId",
                fixture.playerB().toString(), "requestKind", RequestKind.ITEM.name(), "disposition",
                RequestDisposition.ACCEPT.name(), "reason", "다른 플레이어의 요청"));

        ProposalDecodeResult accepted = decoder.decode(valid, fixture.session(), QuestRewardContext.safeDefaults(),
                java.util.Optional.empty(), fixture.playerA());
        ProposalDecodeResult rejected = decoder.decode(foreignTarget, fixture.session(), QuestRewardContext.safeDefaults(),
                java.util.Optional.empty(), fixture.playerA());
        helper.assertValueEqual(accepted.status(), ProposalDecodeResult.Status.ACCEPTED,
                "The triggering player's non-binding request judgment was rejected");
        helper.assertValueEqual(rejected.status(), ProposalDecodeResult.Status.REJECTED,
                "A request judgment targeted another player's request");
        helper.succeed();
    }

    private static Fixture fixture() {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        AiDialogueModels.SessionSnapshot session = new AiDialogueModels.SessionSnapshot(UUID.randomUUID(), List.of(
                new AiDialogueModels.Participant("player:" + playerA, AiDialogueModels.ParticipantKind.PLAYER, "A",
                        playerA, null, AiDialogueModels.ParticipantState.ACTIVE),
                new AiDialogueModels.Participant("player:" + playerB, AiDialogueModels.ParticipantKind.PLAYER, "B",
                        playerB, null, AiDialogueModels.ParticipantState.ACTIVE),
                new AiDialogueModels.Participant("divine:" + GOD, AiDialogueModels.ParticipantKind.DIVINE, "Fixture God",
                        null, GOD, AiDialogueModels.ParticipantState.ACTIVE)),
                new AiDialogueModels.LocationSnapshot("minecraft:overworld", 0, 64, 0), Instant.now(), List.of(), "", "", false);
        return new Fixture(playerA, playerB, session);
    }

    private record Fixture(UUID playerA, UUID playerB, AiDialogueModels.SessionSnapshot session) {
        String playerParticipantId() {
            return "player:" + playerA;
        }

        String playerBParticipantId() {
            return "player:" + playerB;
        }
    }
}
