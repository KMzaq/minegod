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
public final class RelationshipContractGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation NPC = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "lubras");

    private RelationshipContractGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void relationshipSnapshotsAreIndependentForEachNpcPlayerPair(GameTestHelper helper) {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        RelationshipSnapshotProvider snapshots = (playerId, npcId) -> {
            if (playerId.equals(playerA.toString()) && npcId.equals(NPC.toString())) {
                return java.util.Optional.of(snapshot(playerA, 80, 90, 75, 5));
            }
            if (playerId.equals(playerB.toString()) && npcId.equals(NPC.toString())) {
                return java.util.Optional.of(snapshot(playerB, 20, 15, 35, 60));
            }
            return java.util.Optional.empty();
        };
        RelationshipSnapshot a = snapshots.find(playerA.toString(), NPC.toString()).orElseThrow();
        RelationshipSnapshot b = snapshots.find(playerB.toString(), NPC.toString()).orElseThrow();
        helper.assertValueEqual(a.axes().get(RelationshipAxes.AFFINITY), 80,
                "Player A affinity was not read from its independent pair");
        helper.assertValueEqual(a.axes().get(RelationshipAxes.TRUST), 90,
                "Player A trust was not read from its independent pair");
        helper.assertValueEqual(b.axes().get(RelationshipAxes.AFFINITY), 20,
                "Player B affinity was mixed with Player A");
        helper.assertValueEqual(b.axes().get(RelationshipAxes.CAUTION), 60,
                "Player B caution was mixed with Player A");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void snapshotsSupportFutureAxesWithoutChangingNpcFields(GameTestHelper helper) {
        RelationshipSnapshot snapshot = new RelationshipSnapshot(NPC.toString(), UUID.randomUUID().toString(), Map.of(
                RelationshipAxes.AFFINITY, 12,
                RelationshipAxes.TRUST, 30,
                RelationshipAxes.RESPECT, 44,
                RelationshipAxes.CAUTION, 8,
                "loyalty", 61,
                "fear", -10));
        helper.assertValueEqual(snapshot.axis("loyalty").orElseThrow(), 61,
                "A future relationship axis was not preserved");
        helper.assertValueEqual(snapshot.axis("fear").orElseThrow(), -10,
                "A second future relationship axis was not preserved");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void relationshipChangeProposalCannotMutateAuthoritativeState(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        PlayerMythDataService profiles = PlayerMythDataService.get(helper.getLevel().getServer());
        profiles.setAffinity(player, NPC, 20);

        RelationshipChangeProposal proposal = new RelationshipChangeProposal(NPC.toString(), player.toString(),
                Map.of(RelationshipAxes.TRUST, -4, RelationshipAxes.RESPECT, 2), "플레이어가 약속을 어겼다.");
        helper.assertValueEqual(proposal.type(), RelationshipChangeProposal.TYPE,
                "Relationship proposal type is not the stable contract type");
        helper.assertValueEqual(profiles.find(player).orElseThrow().affinities().get(NPC), 20,
                "Constructing an AI relationship proposal changed game-authoritative affinity");
        helper.succeed();
    }

    private static RelationshipSnapshot snapshot(UUID playerId, int affinity, int trust, int respect, int caution) {
        return new RelationshipSnapshot(NPC.toString(), playerId.toString(), Map.of(
                RelationshipAxes.AFFINITY, affinity,
                RelationshipAxes.TRUST, trust,
                RelationshipAxes.RESPECT, respect,
                RelationshipAxes.CAUTION, caution));
    }
}
