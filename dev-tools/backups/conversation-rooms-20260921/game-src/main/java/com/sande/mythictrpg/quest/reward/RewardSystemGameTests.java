package com.sande.mythictrpg.quest.reward;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RewardSystemGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation FORTUNA = id("fortuna");

    private RewardSystemGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void addAndReplacePoliciesResolveAgainstGodDefault(GameTestHelper helper) {
        RewardChoiceOption first = new RewardChoiceOption(id("choice/first"), "첫 번째",
                List.of(new AffinityRewardEntry(75)));
        RewardChoiceOption second = new RewardChoiceOption(id("choice/second"), "두 번째",
                List.of(new TitleRewardEntry(id("title/test"), "시험 칭호")));
        QuestRewardPolicy add = new QuestRewardPolicy(QuestRewardPolicy.Mode.ADD, 2, "선택",
                List.of(new AffinityRewardEntry(25)), List.of(first, second));
        QuestRewardResolver.Resolution added = QuestRewardResolver.resolve(
                FORTUNA, Optional.of(add), Optional.empty());
        helper.assertValueEqual(added.status(), QuestRewardResolver.Status.RESOLVED,
                "ADD policy did not resolve");
        helper.assertValueEqual(added.reward().orElseThrow().automaticRewards().size(), 2,
                "ADD did not combine the God tier and direct reward");

        QuestRewardPolicy replace = new QuestRewardPolicy(QuestRewardPolicy.Mode.REPLACE, 0, "선택",
                List.of(new AffinityRewardEntry(30)), List.of(first, second));
        QuestRewardResolver.Resolution replaced = QuestRewardResolver.resolve(
                FORTUNA, Optional.of(replace), Optional.empty());
        helper.assertValueEqual(replaced.status(), QuestRewardResolver.Status.RESOLVED,
                "REPLACE policy did not resolve");
        helper.assertValueEqual(replaced.reward().orElseThrow().automaticRewards().size(), 1,
                "REPLACE unexpectedly retained the God default tier");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void mixedRewardJsonIsStrictAndQuestAffinityMayExceedAiLimit(GameTestHelper helper) {
        RewardEntry affinity = RewardEntryCodec.parse(JsonParser.parseString(
                "{\"type\":\"affinity\",\"amount\":75}").getAsJsonObject(), "test");
        RewardEntry blessing = RewardEntryCodec.parse(JsonParser.parseString(
                "{\"type\":\"blessing\",\"effectId\":\"minecraft:speed\","
                        + "\"durationTicks\":1200,\"amplifier\":1}").getAsJsonObject(), "test");
        RewardEntry title = RewardEntryCodec.parse(JsonParser.parseString(
                "{\"type\":\"title\",\"titleId\":\"mythictrpg:swift\","
                        + "\"displayName\":\"신속한 자\"}").getAsJsonObject(), "test");
        RewardEntry currency = RewardEntryCodec.parse(JsonParser.parseString(
                "{\"type\":\"currency\",\"amount\":250}").getAsJsonObject(), "test");
        RewardEntry shopUnlock = RewardEntryCodec.parse(JsonParser.parseString(
                "{\"type\":\"unlock_shop_product\",\"shopId\":\"mythictrpg:witch_buy\","
                        + "\"productId\":\"mythictrpg:witch_healing_potion\"}").getAsJsonObject(), "test");
        helper.assertTrue(RewardExecutionService.validate(List.of(affinity, blessing, title, currency, shopUnlock),
                RewardGrantPurpose.QUEST).allowed(), "valid mixed quest reward was rejected");
        helper.assertFalse(RewardExecutionService.validate(List.of(affinity),
                RewardGrantPurpose.AI_ACTION).allowed(), "AI action exceeded its +50 affinity limit");
        expectRejected(helper, () -> RewardEntryCodec.parse(JsonParser.parseString(
                "{\"type\":\"affinity\",\"amount\":1,\"unknown\":true}").getAsJsonObject(), "test"),
                "unknown reward field was accepted");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void nonItemRewardsExecuteAndAffinityClamps(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(playerId, "RewardTester"));
        List<RewardEntry> rewards = List.of(new AffinityRewardEntry(200),
                new BlessingRewardEntry(ResourceLocation.withDefaultNamespace("speed"), 1200, 1),
                new TitleRewardEntry(id("title/swift"), "신속한 자"));
        for (int index = 0; index < 6; index++) {
            helper.assertTrue(RewardExecutionService.grant(player, FORTUNA, rewards,
                    RewardGrantPurpose.QUEST).granted(), "mixed non-item reward failed");
        }
        var profile = PlayerMythDataService.get(helper.getLevel().getServer()).find(playerId).orElseThrow();
        helper.assertValueEqual(profile.affinities().get(FORTUNA), 1000,
                "quest affinity did not clamp to +1000");
        helper.assertTrue(profile.unlockedTitles().contains(id("title/swift")),
                "permanent title was not unlocked");
        helper.assertTrue(player.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED),
                "temporary blessing effect was not applied");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void pendingChoiceRoundTripsAndCanBeSelectedOnlyOnce(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        ResourceLocation source = id("quest/claim_round_trip");
        RewardChoiceOption first = new RewardChoiceOption(id("choice/a"), "A",
                List.of(new AffinityRewardEntry(10)));
        RewardChoiceOption second = new RewardChoiceOption(id("choice/b"), "B",
                List.of(new AffinityRewardEntry(20)));
        RewardClaim claim = new RewardClaim(UUID.randomUUID(), playerId, FORTUNA, source, "선택",
                List.of(new AffinityRewardEntry(5)), List.of(first, second), false,
                Optional.empty(), 100L);
        RewardClaimState state = new RewardClaimState();
        state.create(claim);
        state.markAutomaticGranted(claim.claimId());
        helper.assertTrue(state.find(claim.claimId()).orElseThrow().pendingChoice(),
                "choice did not become pending");

        CompoundTag saved = state.save(new CompoundTag(), helper.getLevel().registryAccess());
        RewardClaimState loaded = RewardClaimState.load(saved, helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.pendingFor(playerId).size(), 1,
                "pending choice was lost during persistence");
        loaded.markSelected(claim.claimId(), first.optionId());
        helper.assertTrue(loaded.find(claim.claimId()).orElseThrow().fullyClaimed(),
                "selected choice was not finalized");
        expectStateRejected(helper, () -> loaded.markSelected(claim.claimId(), second.optionId()),
                "second selection was accepted");
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, path);
    }

    private static void expectRejected(GameTestHelper helper, Runnable operation, String message) {
        try {
            operation.run();
            helper.fail(message);
        } catch (IllegalArgumentException expected) {
            // Expected strict authoring rejection.
        }
    }

    private static void expectStateRejected(GameTestHelper helper, Runnable operation, String message) {
        try {
            operation.run();
            helper.fail(message);
        } catch (IllegalStateException expected) {
            // Expected monotonic claim rejection.
        }
    }
}
