package com.sande.mythictrpg.gameplay.observation;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourAThreeBTwoBIngressGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";
    private static final ResourceLocation STONE = BuiltInRegistries.BLOCK.getKey(Blocks.STONE);
    private static final ResourceLocation DIRT = BuiltInRegistries.BLOCK.getKey(Blocks.DIRT);

    private PhaseFourAThreeBTwoBIngressGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void productionAndTestSinksAreExclusiveAndResetRestoresProduction(
            GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayIngressService ingress = GameplayIngressService.forTesting();
        List<ResourceLocation> production = new ArrayList<>();
        List<ResourceLocation> testing = new ArrayList<>();
        GameplayObservationSink productionSink = (ignored, observation) ->
                production.add(observation.subjectId().orElseThrow());
        GameplayObservationSink conflictingSink = (ignored, observation) -> {
        };

        ingress.configureProductionSink(productionSink);
        ingress.configureProductionSink(productionSink);
        expectRejected(helper, () -> ingress.configureProductionSink(conflictingSink),
                "conflicting production sink");
        expectRejected(helper, () -> ingress.configureProductionSink(null),
                "null production sink");
        expectRejected(helper, () -> ingress.setSinkForTesting(null), "null test sink");

        ingress.setSinkForTesting((ignored, observation) ->
                testing.add(observation.subjectId().orElseThrow()));
        ingress.accept(server, observation(UUID.randomUUID(), STONE));
        helper.assertValueEqual(ingress.drain(server), 1, "override drain count");
        helper.assertValueEqual(testing, List.of(STONE), "test override delivery");
        helper.assertTrue(production.isEmpty(), "production sink received override delivery");

        ingress.clearSinkOverrideForTesting();
        ingress.accept(server, observation(UUID.randomUUID(), DIRT));
        ingress.drain(server);
        helper.assertValueEqual(production, List.of(DIRT), "production delivery after clear");

        ingress.setSinkForTesting((ignored, observation) -> testing.add(STONE));
        ingress.accept(server, observation(UUID.randomUUID(), STONE));
        ingress.resetForTesting();
        helper.assertValueEqual(ingress.pendingCountForTesting(), 0, "reset pending count");
        helper.assertValueEqual(ingress.acceptedCountForTesting(), 0L, "reset accepted count");
        helper.assertValueEqual(ingress.emittedCountForTesting(), 0L, "reset emitted count");
        ingress.accept(server, observation(UUID.randomUUID(), STONE));
        ingress.drain(server);
        helper.assertValueEqual(production, List.of(DIRT, STONE),
                "production delivery after reset");
        helper.assertValueEqual(testing, List.of(STONE), "override leaked through reset");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void drainPreservesOrderIsolatesFailuresAndDefersReentrantAccept(
            GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        GameplayIngressService ingress = GameplayIngressService.forTesting();
        UUID playerId = UUID.randomUUID();
        GameplayObservation<BlockBrokenPayload> first = observation(playerId, STONE);
        GameplayObservation<BlockBrokenPayload> second = observation(playerId, DIRT);
        GameplayObservation<ItemFirstObtainedPayload> reentrant = new GameplayObservation<>(
                GameplayObservationTypes.ITEM_FIRST_OBTAINED, playerId, 1,
                new ItemFirstObtainedPayload(ResourceLocation.withDefaultNamespace("stick")));
        List<ResourceLocation> deliveredTypes = new ArrayList<>();
        ingress.configureProductionSink((ignored, observation) -> {
            deliveredTypes.add(observation.type().id());
            if (observation == first) {
                ingress.accept(server, reentrant);
                throw new IllegalStateException("expected isolated test failure");
            }
        });

        ingress.accept(server, first);
        ingress.accept(server, second);
        helper.assertValueEqual(ingress.drain(server), 2, "first drain count");
        helper.assertValueEqual(deliveredTypes, List.of(
                GameplayObservationTypes.BLOCK_BROKEN.id(),
                GameplayObservationTypes.BLOCK_BROKEN.id()), "first batch insertion order");
        helper.assertValueEqual(ingress.emittedCountForTesting(), 2L,
                "attempted delivery count after failure");
        helper.assertValueEqual(ingress.pendingCountForTesting(), 1,
                "reentrant observation pending count");

        helper.assertValueEqual(ingress.drain(server), 1, "second drain count");
        helper.assertValueEqual(deliveredTypes.get(2),
                GameplayObservationTypes.ITEM_FIRST_OBTAINED.id(),
                "reentrant observation delivery type");
        helper.assertValueEqual(ingress.emittedCountForTesting(), 3L,
                "final attempted delivery count");
        helper.succeed();
    }

    private static GameplayObservation<BlockBrokenPayload> observation(UUID playerId,
            ResourceLocation blockId) {
        return new GameplayObservation<>(GameplayObservationTypes.BLOCK_BROKEN, playerId, 0,
                new BlockBrokenPayload(blockId, Level.OVERWORLD.location(), BlockPos.ZERO));
    }

    private static void expectRejected(GameTestHelper helper, Runnable action, String label) {
        try {
            action.run();
            helper.fail(label + " was accepted");
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException expected) {
            // Expected validation rejection.
        }
    }
}
