package com.sande.mythictrpg.gameplay.observation;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.player.PlayerMythProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourATwoThreeGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";

    private PhaseFourATwoThreeGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void loveAndGrowthBecomeOneDeterministicBatch(GameTestHelper helper) {
        TestContext context = reset(helper);
        int profilesBefore = PlayerMythDataService.get(context.server()).activeProfiles().size();
        List<GameplayObservation<?>> captured = captureSink();
        FakePlayer player = fakePlayer(helper, "FeedBatch");
        long gameTime = helper.getLevel().getGameTime();

        TestCow firstAdult = testCow(helper, 1);
        TestCow secondAdult = testCow(helper, 2);
        TestCow baby = testCow(helper, 3);
        baby.setAge(-1_000);

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT, 3));
        helper.assertValueEqual(capture(player, firstAdult),
                AnimalFeedingObservationTracker.CaptureResult.CAPTURED, "first adult capture");
        helper.assertValueEqual(capture(player, secondAdult),
                AnimalFeedingObservationTracker.CaptureResult.CAPTURED, "second adult capture");
        firstAdult.setInLove(player);
        secondAdult.setInLove(player);

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CARROT, 1));
        helper.assertValueEqual(capture(player, baby),
                AnimalFeedingObservationTracker.CaptureResult.CAPTURED, "baby capture");
        baby.setAge(-500);

        helper.assertValueEqual(AnimalFeedingObservationTracker.INSTANCE.verifyForTesting(
                context.server(), gameTime), 1, "published animal feed batch count");
        helper.assertValueEqual(GameplayIngressService.INSTANCE.pendingCountForTesting(), 1,
                "player/entity type batch was not singular");
        GameplayIngressService.INSTANCE.drain(context.server());

        helper.assertValueEqual(captured.size(), 1, "captured batch observation count");
        GameplayObservation<?> observation = captured.getFirst();
        helper.assertValueEqual(observation.type(), GameplayObservationTypes.ANIMAL_FED,
                "animal feeding observation type");
        helper.assertValueEqual(observation.gameTime(), gameTime, "animal feeding observation tick");
        AnimalFedPayload payload = (AnimalFedPayload) observation.payload();
        helper.assertValueEqual(payload.entityTypeId(), id(EntityType.COW), "animal type ID");
        helper.assertValueEqual(payload.entries().size(), 2, "batch entry count");
        helper.assertValueEqual(payload.entries().get(0),
                new AnimalFeedEntry(id(Items.CARROT), FeedingOutcome.GROWTH_ACCELERATED, 1),
                "sorted growth batch entry");
        helper.assertValueEqual(payload.entries().get(1),
                new AnimalFeedEntry(id(Items.WHEAT), FeedingOutcome.LOVE_MODE, 2),
                "love mode batch count");
        helper.assertValueEqual(payload.subjectId().orElseThrow(), id(EntityType.COW),
                "animal feed subject ID");
        helper.assertValueEqual(PlayerMythDataService.get(context.server()).activeProfiles().size(), profilesBefore,
                "animal feeding created a player profile");
        helper.assertValueEqual(PlayerMythProfile.CURRENT_DATA_VERSION, 3,
                "animal feeding changed profile dataVersion");
        cleanup(context.server());
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void invalidCanceledAndIneligibleInteractionsAreExcluded(GameTestHelper helper) {
        TestContext context = reset(helper);
        FakePlayer player = fakePlayer(helper, "FeedExcluded");
        TestCow cow = testCow(helper, 1);

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        helper.assertValueEqual(capture(player, cow),
                AnimalFeedingObservationTracker.CaptureResult.REJECTED_NOT_FOOD, "invalid food");

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT));
        cow.setInLoveTime(100);
        helper.assertValueEqual(capture(player, cow),
                AnimalFeedingObservationTracker.CaptureResult.REJECTED_INELIGIBLE_STATE,
                "already-in-love adult");
        cow.setInLoveTime(0);
        cow.setAge(100);
        helper.assertValueEqual(capture(player, cow),
                AnimalFeedingObservationTracker.CaptureResult.REJECTED_INELIGIBLE_STATE,
                "breeding cooldown adult");

        cow.setAge(0);
        PlayerInteractEvent.EntityInteract canceled =
                new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, cow);
        canceled.setCanceled(true);
        AnimalFeedingObservationTracker.INSTANCE.onEntityInteract(canceled);
        helper.assertValueEqual(AnimalFeedingObservationTracker.INSTANCE.pendingCountForTesting(context.server()), 0,
                "canceled interaction created candidate");
        cleanup(context.server());
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void unchangedStateAndWrongLoveCauseDoNotEmit(GameTestHelper helper) {
        TestContext context = reset(helper);
        FakePlayer player = fakePlayer(helper, "FeedOwner");
        FakePlayer other = fakePlayer(helper, "FeedOther");
        long gameTime = helper.getLevel().getGameTime();
        TestCow unchanged = testCow(helper, 1);
        TestCow wrongCause = testCow(helper, 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT, 2));

        helper.assertValueEqual(capture(player, unchanged),
                AnimalFeedingObservationTracker.CaptureResult.CAPTURED, "unchanged candidate");
        helper.assertValueEqual(capture(player, wrongCause),
                AnimalFeedingObservationTracker.CaptureResult.CAPTURED, "wrong-cause candidate");
        wrongCause.setInLoveTime(600);
        wrongCause.reportedLoveCause = other;

        helper.assertValueEqual(AnimalFeedingObservationTracker.INSTANCE.verifyForTesting(
                context.server(), gameTime), 0, "invalid state batch count");
        helper.assertValueEqual(GameplayIngressService.INSTANCE.acceptedCountForTesting(), 0L,
                "wrong cause or unchanged state emitted observation");
        helper.assertValueEqual(AnimalFeedingObservationTracker.INSTANCE.verifyForTesting(
                context.server(), gameTime + AnimalFeedingObservationTracker.MAX_PENDING_AGE_TICKS), 0,
                "expired candidate batch count");
        helper.assertValueEqual(AnimalFeedingObservationTracker.INSTANCE.pendingCountForTesting(context.server()), 0,
                "unchanged candidate did not expire");
        cleanup(context.server());
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void naturalGrowthDoesNotCountAsAcceleratedGrowth(GameTestHelper helper) {
        TestContext context = reset(helper);
        FakePlayer player = fakePlayer(helper, "FeedNatural");
        TestCow baby = testCow(helper, 1);
        baby.setAge(-100);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT));
        helper.assertValueEqual(capture(player, baby),
                AnimalFeedingObservationTracker.CaptureResult.CAPTURED, "natural growth candidate");

        long gameTime = helper.getLevel().getGameTime();
        baby.setAge(-99);
        AnimalFeedingObservationTracker.INSTANCE.verifyForTesting(context.server(), gameTime);
        baby.setAge(-98);
        AnimalFeedingObservationTracker.INSTANCE.verifyForTesting(context.server(), gameTime + 1);
        baby.setAge(-97);
        AnimalFeedingObservationTracker.INSTANCE.verifyForTesting(context.server(), gameTime + 2);

        helper.assertValueEqual(GameplayIngressService.INSTANCE.acceptedCountForTesting(), 0L,
                "natural growth emitted feeding observation");
        helper.assertValueEqual(AnimalFeedingObservationTracker.INSTANCE.pendingCountForTesting(context.server()), 0,
                "natural growth candidate did not expire");
        cleanup(context.server());
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void payloadIsValidatedSortedAndImmutable(GameTestHelper helper) {
        List<AnimalFeedEntry> source = new ArrayList<>();
        source.add(new AnimalFeedEntry(id(Items.WHEAT), FeedingOutcome.LOVE_MODE, 2));
        source.add(new AnimalFeedEntry(id(Items.CARROT), FeedingOutcome.GROWTH_ACCELERATED, 1));
        AnimalFedPayload payload = new AnimalFedPayload(id(EntityType.COW), source);
        source.clear();
        helper.assertValueEqual(payload.entries().size(), 2, "payload defensive copy");
        helper.assertValueEqual(payload.entries().getFirst().foodItemId(), id(Items.CARROT),
                "payload deterministic order");
        expectRejected(helper, () -> payload.entries().add(
                new AnimalFeedEntry(id(Items.APPLE), FeedingOutcome.LOVE_MODE, 1)), "mutable payload entries");
        expectRejected(helper, () -> new AnimalFedPayload(id(EntityType.COW), List.of()), "empty payload");
        expectRejected(helper, () -> new AnimalFeedEntry(id(Items.WHEAT), FeedingOutcome.LOVE_MODE, 0),
                "non-positive count");
        expectRejected(helper, () -> new AnimalFedPayload(id(EntityType.COW), List.of(
                new AnimalFeedEntry(id(Items.WHEAT), FeedingOutcome.LOVE_MODE, 1),
                new AnimalFeedEntry(id(Items.WHEAT), FeedingOutcome.LOVE_MODE, 2))), "duplicate entry");

        List<AnimalFeedEntry> maximum = new ArrayList<>();
        for (int index = 0; index < AnimalFedPayload.MAX_ENTRIES; index++) {
            maximum.add(new AnimalFeedEntry(
                    ResourceLocation.fromNamespaceAndPath("phase4a23test", "food_" + index),
                    FeedingOutcome.LOVE_MODE, 1));
        }
        helper.assertValueEqual(new AnimalFedPayload(id(EntityType.COW), maximum).entries().size(), 64,
                "maximum payload entries");
        maximum.add(new AnimalFeedEntry(ResourceLocation.fromNamespaceAndPath("phase4a23test", "food_64"),
                FeedingOutcome.LOVE_MODE, 1));
        expectRejected(helper, () -> new AnimalFedPayload(id(EntityType.COW), maximum),
                "payload entry limit");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void candidatesAreDeduplicatedBoundedAndCleanedUp(GameTestHelper helper) {
        TestContext context = reset(helper);
        FakePlayer player = fakePlayer(helper, "FeedLifecycle");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT, 64));
        TestCow first = new TestCow(helper.getLevel());
        helper.assertValueEqual(capture(player, first),
                AnimalFeedingObservationTracker.CaptureResult.CAPTURED, "first candidate");
        helper.assertValueEqual(capture(player, first),
                AnimalFeedingObservationTracker.CaptureResult.DEDUPLICATED, "same-tick duplicate");
        helper.assertValueEqual(AnimalFeedingObservationTracker.INSTANCE.pendingCountForTesting(context.server()), 1,
                "deduplicated pending count");

        AnimalFeedingObservationTracker.discard(context.server());
        for (int index = 0; index < AnimalFeedingObservationTracker.MAX_PENDING_PER_PLAYER; index++) {
            helper.assertValueEqual(capture(player, new TestCow(helper.getLevel())),
                    AnimalFeedingObservationTracker.CaptureResult.CAPTURED,
                    "per-player candidate " + index);
        }
        helper.assertValueEqual(capture(player, new TestCow(helper.getLevel())),
                AnimalFeedingObservationTracker.CaptureResult.REJECTED_LIMIT, "per-player candidate limit");
        helper.assertValueEqual(AnimalFeedingObservationTracker.INSTANCE.pendingCountForTesting(context.server()), 64,
                "bounded pending count");

        AnimalFeedingObservationTracker.INSTANCE.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
        helper.assertValueEqual(AnimalFeedingObservationTracker.INSTANCE.pendingCountForTesting(context.server()), 0,
                "logout candidate cleanup");

        for (int playerIndex = 0; playerIndex <
                AnimalFeedingObservationTracker.MAX_PENDING_SERVER
                        / AnimalFeedingObservationTracker.MAX_PENDING_PER_PLAYER; playerIndex++) {
            FakePlayer boundedPlayer = fakePlayer(helper, "FeedLimit" + playerIndex);
            boundedPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT, 64));
            for (int candidateIndex = 0;
                    candidateIndex < AnimalFeedingObservationTracker.MAX_PENDING_PER_PLAYER; candidateIndex++) {
                helper.assertValueEqual(capture(boundedPlayer, new TestCow(helper.getLevel())),
                        AnimalFeedingObservationTracker.CaptureResult.CAPTURED,
                        "server candidate " + playerIndex + "/" + candidateIndex);
            }
        }
        FakePlayer overflowPlayer = fakePlayer(helper, "FeedOverflow");
        overflowPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT));
        helper.assertValueEqual(capture(overflowPlayer, new TestCow(helper.getLevel())),
                AnimalFeedingObservationTracker.CaptureResult.REJECTED_LIMIT, "server candidate limit");
        helper.assertValueEqual(AnimalFeedingObservationTracker.INSTANCE.pendingCountForTesting(context.server()),
                AnimalFeedingObservationTracker.MAX_PENDING_SERVER, "server-bounded pending count");
        helper.assertTrue(AnimalFeedingObservationTracker.hasStateForTesting(context.server()),
                "runtime state disappeared before server discard");
        AnimalFeedingObservationTracker.discard(context.server());
        helper.assertTrue(!AnimalFeedingObservationTracker.hasStateForTesting(context.server()),
                "server discard retained feeding runtime state");
        GameplayIngressService.INSTANCE.resetForTesting();
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void nonServerPlayerIsRejectedWithoutPersistentMutation(GameTestHelper helper) {
        TestContext context = reset(helper);
        int profilesBefore = PlayerMythDataService.get(context.server()).activeProfiles().size();
        NonServerPlayer player = new NonServerPlayer(helper.getLevel(), UUID.randomUUID());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT));
        TestCow cow = new TestCow(helper.getLevel());
        helper.assertValueEqual(AnimalFeedingObservationTracker.INSTANCE.captureCandidate(
                        player, cow, InteractionHand.MAIN_HAND),
                AnimalFeedingObservationTracker.CaptureResult.REJECTED_NOT_SERVER_PLAYER,
                "non-server player capture");
        helper.assertValueEqual(PlayerMythDataService.get(context.server()).activeProfiles().size(), profilesBefore,
                "rejected feeding changed player repository");
        helper.assertValueEqual(PlayerMythProfile.CURRENT_DATA_VERSION, 3,
                "rejected feeding changed profile dataVersion");
        cleanup(context.server());
        helper.succeed();
    }

    private static TestContext reset(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        AnimalFeedingObservationTracker.discard(server);
        GameplayIngressService.INSTANCE.resetForTesting();
        return new TestContext(server);
    }

    private static void cleanup(MinecraftServer server) {
        AnimalFeedingObservationTracker.discard(server);
        GameplayIngressService.INSTANCE.resetForTesting();
    }

    private static List<GameplayObservation<?>> captureSink() {
        List<GameplayObservation<?>> captured = new ArrayList<>();
        GameplayIngressService.INSTANCE.setSinkForTesting((server, observation) -> captured.add(observation));
        return captured;
    }

    private static AnimalFeedingObservationTracker.CaptureResult capture(ServerPlayer player, Cow cow) {
        return AnimalFeedingObservationTracker.INSTANCE.captureCandidate(
                player, cow, InteractionHand.MAIN_HAND);
    }

    private static FakePlayer fakePlayer(GameTestHelper helper, String name) {
        return new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
    }

    private static TestCow testCow(GameTestHelper helper, int offset) {
        TestCow cow = new TestCow(helper.getLevel());
        BlockPos position = helper.absolutePos(new BlockPos(offset, 2, 1));
        cow.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5, 0.0F, 0.0F);
        cow.setPersistenceRequired();
        if (!helper.getLevel().addFreshEntity(cow)) {
            helper.fail("Failed to add test cow");
        }
        return cow;
    }

    private static ResourceLocation id(net.minecraft.world.item.Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private static ResourceLocation id(EntityType<?> entityType) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
    }

    private static void expectRejected(GameTestHelper helper, Runnable operation, String stage) {
        try {
            operation.run();
            helper.fail(stage + " was accepted");
        } catch (IllegalArgumentException | UnsupportedOperationException | NullPointerException expected) {
            // Expected validation rejection.
        }
    }

    private record TestContext(MinecraftServer server) {
    }

    private static final class TestCow extends Cow {
        private ServerPlayer reportedLoveCause;

        private TestCow(ServerLevel level) {
            super(EntityType.COW, level);
        }

        @Override
        public boolean isFood(ItemStack stack) {
            return stack.is(Items.WHEAT) || stack.is(Items.CARROT);
        }

        @Nullable
        @Override
        public ServerPlayer getLoveCause() {
            return reportedLoveCause != null ? reportedLoveCause : super.getLoveCause();
        }
    }

    private static final class NonServerPlayer extends Player {
        private NonServerPlayer(ServerLevel level, UUID playerId) {
            super(level, BlockPos.ZERO, 0.0F, new GameProfile(playerId, "NonServerFeed"));
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return false;
        }
    }
}
