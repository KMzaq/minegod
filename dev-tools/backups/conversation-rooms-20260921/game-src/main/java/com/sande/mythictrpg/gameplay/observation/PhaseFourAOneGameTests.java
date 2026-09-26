package com.sande.mythictrpg.gameplay.observation;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.data.player.ItemHistoryRecordResult;
import com.sande.mythictrpg.data.player.PlayerMythDataService;
import com.sande.mythictrpg.data.world.MythicWorldState;
import com.sande.mythictrpg.gameplay.stat.DistanceStatistic;
import com.sande.mythictrpg.gameplay.stat.MinecraftPlayerGameplayStatisticsView;
import com.sande.mythictrpg.gameplay.stat.StatisticReadStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PhaseFourAOneGameTests {
    private static final String TEMPLATE = "bastion/mobs/empty";

    private PhaseFourAOneGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void statisticsBoundaryKeepsUnavailableExplicit(GameTestHelper helper) {
        FakePlayer player = fakePlayer(helper, UUID.randomUUID(), "Stats4A1");
        var online = MinecraftPlayerGameplayStatisticsView.online(player);
        helper.assertValueEqual(online.blockMined(Blocks.STONE).status(), StatisticReadStatus.AVAILABLE,
                "online block statistic unavailable");
        helper.assertValueEqual(online.entityKilled(EntityType.ZOMBIE).status(), StatisticReadStatus.AVAILABLE,
                "online entity statistic unavailable");
        helper.assertValueEqual(online.totalMobKills().status(), StatisticReadStatus.AVAILABLE,
                "online mob kills unavailable");
        helper.assertValueEqual(online.animalsBred().status(), StatisticReadStatus.AVAILABLE,
                "online animals bred unavailable");
        helper.assertValueEqual(online.deaths().status(), StatisticReadStatus.AVAILABLE,
                "online deaths unavailable");
        helper.assertValueEqual(online.playTime().status(), StatisticReadStatus.AVAILABLE,
                "online play time unavailable");
        for (DistanceStatistic statistic : DistanceStatistic.values()) {
            helper.assertValueEqual(online.distance(statistic).status(), StatisticReadStatus.AVAILABLE,
                    "online distance unavailable: " + statistic);
        }

        var unavailable = MinecraftPlayerGameplayStatisticsView.unavailable();
        helper.assertValueEqual(unavailable.blockMined(Blocks.STONE).status(), StatisticReadStatus.UNAVAILABLE,
                "unavailable block statistic was flattened");
        helper.assertValueEqual(unavailable.distance(DistanceStatistic.WALK).status(), StatisticReadStatus.UNAVAILABLE,
                "unavailable distance statistic was flattened");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void eventAdaptersCreateTypedObservations(GameTestHelper helper) {
        GameplayIngressService.INSTANCE.resetForTesting();
        List<GameplayObservation<?>> captured = captureSink(helper);
        FakePlayer player = fakePlayer(helper, UUID.randomUUID(), "Observe4A1");
        BlockPos pos = new BlockPos(1, 2, 3);

        GameplayObservationAdapters.onBlockBreak(new BlockEvent.BreakEvent(
                helper.getLevel(), pos, Blocks.STONE.defaultBlockState(), player));
        drain(helper);
        GameplayObservation<?> block = only(captured, helper, "block observation");
        helper.assertValueEqual(block.type(), GameplayObservationTypes.BLOCK_BROKEN, "block type");
        helper.assertValueEqual(((BlockBrokenPayload) block.payload()).blockId(), id(Blocks.STONE),
                "block payload ID");
        helper.assertValueEqual(((BlockBrokenPayload) block.payload()).dimensionId(), Level.OVERWORLD.location(),
                "block dimension");
        helper.assertValueEqual(((BlockBrokenPayload) block.payload()).position(), pos,
                "block position snapshot");

        captured.clear();
        Cow parentA = cow(helper);
        Cow parentB = cow(helper);
        Cow child = cow(helper);
        GameplayObservationAdapters.onBabyEntitySpawn(new TestBabyEntitySpawnEvent(parentA, parentB, child, player));
        drain(helper);
        GameplayObservation<?> bred = only(captured, helper, "breeding observation");
        helper.assertValueEqual(bred.type(), GameplayObservationTypes.ANIMAL_BRED, "breeding type");
        helper.assertValueEqual(((AnimalBredPayload) bred.payload()).childEntityTypeId(), id(EntityType.COW),
                "breeding child ID");

        captured.clear();
        Zombie zombie = new Zombie(EntityType.ZOMBIE, helper.getLevel());
        GameplayObservationAdapters.onLivingDeath(new LivingDeathEvent(zombie,
                player.damageSources().playerAttack(player)));
        drain(helper);
        GameplayObservation<?> kill = only(captured, helper, "kill observation");
        helper.assertValueEqual(kill.type(), GameplayObservationTypes.ENTITY_KILLED, "kill type");
        helper.assertValueEqual(((EntityKilledPayload) kill.payload()).killedEntityTypeId(), id(EntityType.ZOMBIE),
                "kill entity ID");

        captured.clear();
        FakePlayer victim = fakePlayer(helper, UUID.randomUUID(), "Victim4A1");
        GameplayObservationAdapters.onLivingDeath(new LivingDeathEvent(victim, victim.damageSources().generic()));
        drain(helper);
        GameplayObservation<?> death = only(captured, helper, "death observation");
        helper.assertValueEqual(death.type(), GameplayObservationTypes.PLAYER_DIED, "death type");
        helper.assertValueEqual(death.initiatingPlayerId(), victim.getUUID(), "death player ID");

        captured.clear();
        GameplayObservationAdapters.onItemHistoryRecorded(player, id(Items.NAUTILUS_SHELL),
                ItemHistoryRecordResult.NEW_RECORD);
        drain(helper);
        GameplayObservation<?> item = only(captured, helper, "item observation");
        helper.assertValueEqual(item.type(), GameplayObservationTypes.ITEM_FIRST_OBTAINED, "item type");
        helper.assertValueEqual(((ItemFirstObtainedPayload) item.payload()).itemId(), id(Items.NAUTILUS_SHELL),
                "item ID");

        captured.clear();
        GameplayObservationAdapters.onItemHistoryRecorded(player, id(Items.NAUTILUS_SHELL),
                ItemHistoryRecordResult.ALREADY_RECORDED);
        drain(helper);
        helper.assertTrue(captured.isEmpty(), "duplicate item history emitted an observation");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void killAttributionIsPlayerOnly(GameTestHelper helper) {
        GameplayIngressService.INSTANCE.resetForTesting();
        List<GameplayObservation<?>> captured = captureSink(helper);
        FakePlayer player = fakePlayer(helper, UUID.randomUUID(), "Arrow4A1");

        Arrow arrow = new Arrow(EntityType.ARROW, helper.getLevel());
        arrow.setOwner(player);
        Zombie projectileVictim = new Zombie(EntityType.ZOMBIE, helper.getLevel());
        GameplayObservationAdapters.onLivingDeath(new LivingDeathEvent(projectileVictim,
                player.damageSources().arrow(arrow, player)));
        drain(helper);
        helper.assertValueEqual(only(captured, helper, "projectile kill").type(),
                GameplayObservationTypes.ENTITY_KILLED, "projectile kill attribution");

        captured.clear();
        Zombie indirectVictim = new Zombie(EntityType.ZOMBIE, helper.getLevel());
        GameplayObservationAdapters.onLivingDeath(new LivingDeathEvent(indirectVictim,
                indirectVictim.damageSources().generic()));
        drain(helper);
        helper.assertTrue(captured.isEmpty(), "non-player kill emitted observation");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = TEMPLATE)
    public static void ingressCoalescesWithoutPersistentSideEffects(GameTestHelper helper) {
        GameplayIngressService.INSTANCE.resetForTesting();
        List<GameplayObservation<?>> captured = captureSink(helper);
        MinecraftServer server = helper.getLevel().getServer();
        FakePlayer player = fakePlayer(helper, UUID.randomUUID(), "Coalesce4A1");
        FakePlayer other = fakePlayer(helper, UUID.randomUUID(), "Other4A1");
        int profilesBefore = PlayerMythDataService.get(server).activeProfiles().size();
        int unlockedBefore = MythicWorldState.get(server).unlockedGods().size();

        GameplayObservationAdapters.onBlockBreak(new BlockEvent.BreakEvent(
                helper.getLevel(), BlockPos.ZERO, Blocks.STONE.defaultBlockState(), player));
        GameplayObservationAdapters.onBlockBreak(new BlockEvent.BreakEvent(
                helper.getLevel(), BlockPos.ZERO.above(), Blocks.STONE.defaultBlockState(), player));
        GameplayObservationAdapters.onBlockBreak(new BlockEvent.BreakEvent(
                helper.getLevel(), BlockPos.ZERO, Blocks.DIRT.defaultBlockState(), player));
        GameplayObservationAdapters.onBlockBreak(new BlockEvent.BreakEvent(
                helper.getLevel(), BlockPos.ZERO, Blocks.STONE.defaultBlockState(), other));
        helper.assertValueEqual(GameplayIngressService.INSTANCE.pendingCountForTesting(), 3,
                "same player/type/subject did not coalesce before drain");

        drain(helper);
        helper.assertValueEqual(captured.size(), 3, "coalesced drain size");
        int emitted = captured.size();
        drain(helper);
        helper.assertValueEqual(captured.size(), emitted, "empty queue reprocessed observations");

        helper.assertValueEqual(PlayerMythDataService.get(server).activeProfiles().size(), profilesBefore,
                "observation ingress changed player profiles");
        helper.assertValueEqual(MythicWorldState.get(server).unlockedGods().size(), unlockedBefore,
                "observation ingress changed world state");
        helper.assertValueEqual(GameplayIngressService.INSTANCE.emittedCountForTesting(), 3L,
                "unexpected extra sink emissions");
        helper.succeed();
    }

    private static List<GameplayObservation<?>> captureSink(GameTestHelper helper) {
        List<GameplayObservation<?>> captured = new ArrayList<>();
        GameplayIngressService.INSTANCE.setSinkForTesting((server, observation) -> captured.add(observation));
        return captured;
    }

    private static GameplayObservation<?> only(List<GameplayObservation<?>> captured,
            GameTestHelper helper, String description) {
        helper.assertValueEqual(captured.size(), 1, description + " count");
        return captured.get(0);
    }

    private static void drain(GameTestHelper helper) {
        GameplayIngressService.INSTANCE.drain(helper.getLevel().getServer());
    }

    private static FakePlayer fakePlayer(GameTestHelper helper, UUID uuid, String name) {
        return new FakePlayer(helper.getLevel(), new GameProfile(uuid, name));
    }

    private static Cow cow(GameTestHelper helper) {
        return new Cow(EntityType.COW, helper.getLevel());
    }

    private static ResourceLocation id(net.minecraft.world.level.block.Block block) {
        return BuiltInRegistries.BLOCK.getKey(block);
    }

    private static ResourceLocation id(net.minecraft.world.entity.EntityType<?> entityType) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
    }

    private static ResourceLocation id(net.minecraft.world.item.Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private static final class TestBabyEntitySpawnEvent extends BabyEntitySpawnEvent {
        private final FakePlayer causedByPlayer;

        private TestBabyEntitySpawnEvent(Cow parentA, Cow parentB, Cow child, FakePlayer causedByPlayer) {
            super(parentA, parentB, child);
            this.causedByPlayer = causedByPlayer;
        }

        @Override
        public FakePlayer getCausedByPlayer() {
            return causedByPlayer;
        }
    }
}
