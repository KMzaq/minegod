package com.sande.mythictrpg.gameplay.observation;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.player.GameplayCounterMutationResult;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import com.sande.mythictrpg.gameplay.metric.GameplayMetricTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourATwoOneCropGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final List<Block> SUPPORTED_CROPS = List.of(
            Blocks.WHEAT,
            Blocks.CARROTS,
            Blocks.POTATOES,
            Blocks.BEETROOTS,
            Blocks.NETHER_WART,
            Blocks.COCOA
    );

    private PhaseFourATwoOneCropGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void matureCropsIncrementAggregateAndSubjectAtomically(GameTestHelper helper) {
        GameplayIngressService.INSTANCE.resetForTesting();
        List<GameplayObservation<?>> captured = captureSink();
        FakePlayer player = fakePlayer(helper, "MatureCrops");
        PlayerMythDataService service = PlayerMythDataService.get(helper.getLevel().getServer());

        NeoForge.EVENT_BUS.post(event(helper, player, matureState(Blocks.WHEAT)));
        for (Block block : SUPPORTED_CROPS.subList(1, SUPPORTED_CROPS.size())) {
            GameplayObservationAdapters.onMatureCropBreak(event(helper, player, matureState(block)));
        }

        PlayerMythProfile profile = service.find(player.getUUID()).orElseThrow();
        GameplayMetricKey aggregate = GameplayMetricKey.aggregate(GameplayMetricTypes.MATURE_CROP_HARVESTED);
        helper.assertValueEqual(profile.customGameplayCounter(aggregate), 6L, "aggregate crop count");
        for (Block block : SUPPORTED_CROPS) {
            GameplayMetricKey subject = GameplayMetricKey.subject(
                    GameplayMetricTypes.MATURE_CROP_HARVESTED, blockId(block));
            helper.assertValueEqual(profile.customGameplayCounter(subject), 1L,
                    blockId(block) + " subject crop count");
        }

        drain(helper);
        List<GameplayObservation<?>> cropObservations = captured.stream()
                .filter(observation -> observation.type() == GameplayObservationTypes.MATURE_CROP_HARVESTED)
                .toList();
        helper.assertValueEqual(cropObservations.size(), 6, "mature crop observation count");
        helper.assertTrue(cropObservations.stream().allMatch(observation ->
                        observation.payload() instanceof MatureCropHarvestPayload),
                "mature crop observation used an untyped payload");

        captured.clear();
        GameplayObservationAdapters.onMatureCropBreak(event(helper, player, matureState(Blocks.WHEAT)));
        GameplayObservationAdapters.onMatureCropBreak(event(helper, player, matureState(Blocks.WHEAT)));
        profile = service.find(player.getUUID()).orElseThrow();
        helper.assertValueEqual(profile.customGameplayCounter(aggregate), 8L,
                "coalescing lost persistent aggregate increments");
        helper.assertValueEqual(profile.customGameplayCounter(GameplayMetricKey.subject(
                GameplayMetricTypes.MATURE_CROP_HARVESTED, blockId(Blocks.WHEAT))), 3L,
                "coalescing lost persistent wheat increments");
        helper.assertValueEqual(GameplayIngressService.INSTANCE.pendingCountForTesting(), 1,
                "same-tick wheat observations did not coalesce");
        drain(helper);
        helper.assertValueEqual(captured.size(), 1, "coalesced wheat observation count");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void immatureCanceledAndUnsupportedBlocksAreExcluded(GameTestHelper helper) {
        GameplayIngressService.INSTANCE.resetForTesting();
        List<GameplayObservation<?>> captured = captureSink();
        FakePlayer player = fakePlayer(helper, "ExcludedCrops");

        GameplayObservationAdapters.onMatureCropBreak(event(helper, player,
                ((CropBlock) Blocks.WHEAT).getStateForAge(0)));
        GameplayObservationAdapters.onMatureCropBreak(event(helper, player,
                Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 0)));
        GameplayObservationAdapters.onMatureCropBreak(event(helper, player,
                Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 0)));
        GameplayObservationAdapters.onMatureCropBreak(event(helper, player, Blocks.STONE.defaultBlockState()));
        GameplayObservationAdapters.onMatureCropBreak(event(helper, player,
                Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3)));

        BlockEvent.BreakEvent canceled = event(helper, player, matureState(Blocks.WHEAT));
        canceled.setCanceled(true);
        GameplayObservationAdapters.onMatureCropBreak(canceled);
        drain(helper);

        helper.assertTrue(captured.isEmpty(), "Excluded crop event emitted an observation");
        helper.assertTrue(PlayerMythDataService.get(helper.getLevel().getServer()).find(player.getUUID()).isEmpty(),
                "Excluded crop event created a player profile");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void rejectedCounterMutationDoesNotEmitObservation(GameTestHelper helper) {
        GameplayIngressService.INSTANCE.resetForTesting();
        List<GameplayObservation<?>> captured = captureSink();
        FakePlayer player = fakePlayer(helper, "RejectedCrop");
        PlayerMythDataService service = PlayerMythDataService.get(helper.getLevel().getServer());
        Map<GameplayMetricKey, Long> existingKeys = new LinkedHashMap<>();
        for (int index = 0; index < PlayerMythProfile.MAX_CUSTOM_GAMEPLAY_COUNTERS - 1; index++) {
            existingKeys.put(GameplayMetricKey.aggregate(
                    ResourceLocation.fromNamespaceAndPath("exampleaddon", "crop_limit_" + index)), 1L);
        }
        helper.assertValueEqual(service.incrementGameplayCounters(player.getUUID(), existingKeys),
                GameplayCounterMutationResult.UPDATED, "limit fixture setup");

        PlayerMythProfile before = service.find(player.getUUID()).orElseThrow();
        GameplayObservationAdapters.onMatureCropBreak(event(helper, player, matureState(Blocks.WHEAT)));
        PlayerMythProfile after = service.find(player.getUUID()).orElseThrow();
        drain(helper);

        helper.assertTrue(after == before, "Rejected crop mutation replaced the profile");
        helper.assertValueEqual(after.customGameplayCounter(
                GameplayMetricKey.aggregate(GameplayMetricTypes.MATURE_CROP_HARVESTED)), 0L,
                "Rejected crop mutation wrote aggregate counter");
        helper.assertValueEqual(after.customGameplayCounter(GameplayMetricKey.subject(
                GameplayMetricTypes.MATURE_CROP_HARVESTED, blockId(Blocks.WHEAT))), 0L,
                "Rejected crop mutation wrote subject counter");
        helper.assertTrue(captured.isEmpty(), "Rejected crop mutation emitted an observation");
        helper.succeed();
    }

    private static BlockState matureState(Block block) {
        if (block instanceof CropBlock crop) {
            return crop.getStateForAge(crop.getMaxAge());
        }
        if (block == Blocks.NETHER_WART) {
            return block.defaultBlockState().setValue(NetherWartBlock.AGE, NetherWartBlock.MAX_AGE);
        }
        if (block == Blocks.COCOA) {
            return block.defaultBlockState().setValue(CocoaBlock.AGE, CocoaBlock.MAX_AGE);
        }
        throw new IllegalArgumentException("Unsupported test crop: " + blockId(block));
    }

    private static BlockEvent.BreakEvent event(GameTestHelper helper, FakePlayer player, BlockState state) {
        return new BlockEvent.BreakEvent(helper.getLevel(), BlockPos.ZERO, state, player);
    }

    private static List<GameplayObservation<?>> captureSink() {
        List<GameplayObservation<?>> captured = new ArrayList<>();
        GameplayIngressService.INSTANCE.setSinkForTesting((server, observation) -> captured.add(observation));
        return captured;
    }

    private static void drain(GameTestHelper helper) {
        GameplayIngressService.INSTANCE.drain(helper.getLevel().getServer());
    }

    private static FakePlayer fakePlayer(GameTestHelper helper, String name) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
    }

    private static ResourceLocation blockId(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block);
    }
}
