package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.proposal.ProposalDecodeResult;
import com.sande.mythictrpg.ai.proposal.QuestRewardContext;
import com.sande.mythictrpg.ai.proposal.StructuredProposalDecoder;
import com.sande.mythictrpg.ai.proposal.VouchResolutionProposal;
import com.sande.mythictrpg.ai.vouch.PendingVouchInteraction;
import com.sande.mythictrpg.ai.vouch.VouchStance;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Coverage for AI-owned Vouch conversation state; it deliberately tests no game-side relationship mutation. */
@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VouchConversationGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation GOD = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "vouch_fixture");

    private VouchConversationGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void pendingVouchesAreSessionScopedAndOnlyTheNamedSponsorCanResolve(GameTestHelper helper) {
        UUID sponsorA = UUID.randomUUID();
        UUID beneficiaryA = UUID.randomUUID();
        UUID sponsorB = UUID.randomUUID();
        UUID beneficiaryB = UUID.randomUUID();
        ConversationSessionManager manager = new ConversationSessionManager();
        ConversationSessionManager.Session sessionA = session(manager, sponsorA, beneficiaryA);
        ConversationSessionManager.Session sessionB = session(manager, sponsorB, beneficiaryB);
        PendingVouchInteraction pendingA = pending(sponsorA, beneficiaryA, "A의 부탁");
        PendingVouchInteraction pendingB = pending(sponsorB, beneficiaryB, "B의 부탁");

        helper.assertTrue(sessionA.beginPendingVouch(pendingA), "Session A could not open its vouch request");
        helper.assertTrue(sessionB.beginPendingVouch(pendingB), "Session B could not open its vouch request");
        helper.assertValueEqual(sessionA.pendingVouch().orElseThrow().beneficiaryPlayerId(), beneficiaryA,
                "Session A received Session B's pending vouch state");
        helper.assertValueEqual(sessionB.pendingVouch().orElseThrow().beneficiaryPlayerId(), beneficiaryB,
                "Session B received Session A's pending vouch state");

        VouchResolutionProposal forged = new VouchResolutionProposal(GOD, sponsorB, beneficiaryA,
                VouchStance.SUPPORT, "대리 보증");
        helper.assertTrue(!sessionA.resolvePendingVouch(forged),
                "A player outside the pending interaction resolved Session A's vouch");
        helper.assertTrue(sessionA.resolvePendingVouch(new VouchResolutionProposal(GOD, sponsorA, beneficiaryA,
                VouchStance.SUPPORT, "믿을 만하다")), "The named sponsor could not resolve Session A's vouch");
        helper.assertTrue(sessionA.pendingVouch().isEmpty(), "Resolved Session A vouch was retained");
        helper.assertValueEqual(sessionB.pendingVouch().orElseThrow().beneficiaryPlayerId(), beneficiaryB,
                "Resolving Session A unexpectedly changed Session B's vouch");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void vouchResolutionRequiresThePendingSponsorAndMatchingParticipants(GameTestHelper helper) {
        UUID sponsor = UUID.randomUUID();
        UUID beneficiary = UUID.randomUUID();
        ConversationSessionManager manager = new ConversationSessionManager();
        ConversationSessionManager.Session session = session(manager, sponsor, beneficiary);
        PendingVouchInteraction pending = pending(sponsor, beneficiary, "작은 부탁");
        helper.assertTrue(session.beginPendingVouch(pending), "Fixture vouch could not be created");

        AiDialogueModels.Proposal raw = new AiDialogueModels.Proposal(VouchResolutionProposal.TYPE, "보증", "보증",
                List.of("player:" + beneficiary), Map.of("npcId", GOD.toString(), "sponsorPlayerId", sponsor.toString(),
                "beneficiaryPlayerId", beneficiary.toString(), "stance", "SUPPORT", "reason", "믿을 수 있다"));
        StructuredProposalDecoder decoder = new StructuredProposalDecoder();
        ProposalDecodeResult allowed = decoder.decode(raw, session.snapshot(8), QuestRewardContext.safeDefaults(),
                session.pendingVouch(), sponsor);
        ProposalDecodeResult forgedTurn = decoder.decode(raw, session.snapshot(8), QuestRewardContext.safeDefaults(),
                session.pendingVouch(), beneficiary);
        helper.assertValueEqual(allowed.status(), ProposalDecodeResult.Status.ACCEPTED,
                "The named sponsor's valid vouch resolution was rejected");
        helper.assertValueEqual(forgedTurn.status(), ProposalDecodeResult.Status.REJECTED,
                "A different player's turn resolved the pending vouch");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void leavingSponsorClearsPendingVouch(GameTestHelper helper) {
        UUID sponsor = UUID.randomUUID();
        UUID beneficiary = UUID.randomUUID();
        ConversationSessionManager manager = new ConversationSessionManager();
        ConversationSessionManager.Session session = session(manager, sponsor, beneficiary);
        helper.assertTrue(session.beginPendingVouch(pending(sponsor, beneficiary, "이탈 검사")),
                "Fixture vouch could not be created");
        session.setPlayerState(sponsor, AiDialogueModels.ParticipantState.OUTSIDE);
        helper.assertTrue(session.pendingVouch().isEmpty(), "A departed sponsor left a stale pending vouch behind");
        helper.succeed();
    }

    private static ConversationSessionManager.Session session(ConversationSessionManager manager, UUID sponsor,
            UUID beneficiary) {
        return manager.create(List.of(
                ConversationSessionManager.player("Sponsor", sponsor, AiDialogueModels.ParticipantState.ACTIVE),
                ConversationSessionManager.player("Beneficiary", beneficiary, AiDialogueModels.ParticipantState.ACTIVE),
                ConversationSessionManager.divine(GOD, "Fixture God")),
                new AiDialogueModels.LocationSnapshot("minecraft:overworld", 0, 64, 0), 8);
    }

    private static PendingVouchInteraction pending(UUID sponsor, UUID beneficiary, String request) {
        Instant now = Instant.now();
        return new PendingVouchInteraction(GOD, sponsor, beneficiary, request, "동료의 평판", now, now.plusSeconds(300));
    }
}
