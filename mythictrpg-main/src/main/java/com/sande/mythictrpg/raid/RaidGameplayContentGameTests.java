package com.sande.mythictrpg.raid;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sande.mythictrpg.condition.builtin.AlwaysCondition;
import com.sande.mythictrpg.condition.builtin.BuiltinConditionTypes;
import com.sande.mythictrpg.content.ContentDefinitions;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import com.sande.mythictrpg.story.definition.StoryDefinitionManager;
import com.sande.mythictrpg.story.definition.StoryDefinitionSnapshot;
import com.sande.mythictrpg.story.definition.StoryDefinitions.Effect;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.sande.mythictrpg.content.ContentSystemsGameTests.*;

/** Ordinary command/reward/combat path, including a cold arena whose entity sections need real ticks. */
@GameTestHolder("mythictrpg_content_systems")
@PrefixGameTestTemplate(false)
public final class RaidGameplayContentGameTests {
    private RaidGameplayContentGameTests() { }

    @GameTest(templateNamespace = "mythictrpg_content_systems", template = "empty",
            batch = "content_gameplay_raid", timeoutTicks = 200)
    public static void ordinaryInvestigationRewardPreparesAnOrdinaryRaid(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        helper.onEachTick(() -> {
            if (fixture.done) return;
            try { fixture.tick(); }
            catch (Exception | AssertionError failure) {
                fixture.close();
                helper.fail("Ordinary preparation/raid path: " + failure);
            }
        });
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final ArrayList<EmbeddedChannel> channels = new ArrayList<>();
        final List<UUID> runs = new ArrayList<>();
        final ContentDefinitions.Snapshot contentBefore = ContentDefinitions.INSTANCE.snapshot();
        final StoryDefinitionSnapshot storyBefore = StoryDefinitionManager.INSTANCE.snapshot();
        final RaidCatalog.Snapshot raidBefore = RaidCatalog.INSTANCE.snapshot();
        ServerPlayer owner, companion;
        RaidDefinition.Arena arena;
        ContentDefinitions.Countermeasure rule;
        RaidState.Attempt attempt;
        int phase, waitTicks;
        boolean done;
        Fixture(GameTestHelper helper) { this.helper = helper; }

        void tick() throws Exception {
            if (phase == 0) {
                setUp();
                phase = 1;
                return;
            }
            check(!attempt.terminal() && attempt.status != RaidState.Status.CLEANUP_PENDING,
                    "raid ended before loaded combat: " + attempt.status + "/" + attempt.reason);
            if (attempt.status != RaidState.Status.ACTIVE) {
                check(++waitTicks < 150, "cold arena never became entity-ready");
                check(attempt.status == RaidState.Status.STARTING && attempt.bossId == null
                                && attempt.spawnedEntities.isEmpty() && !arena.bounds().contains(owner.position()),
                        "pending arena moved players or spawned a hidden boss");
                return;
            }
            verifyCombat();
            close();
            helper.succeed();
        }

        void setUp() throws Exception {
            owner = connect(helper, "PreparedRaider", channels);
            companion = connect(helper, "JoiningRaider", channels);
            owner.setGameMode(GameType.SURVIVAL);
            companion.setGameMode(GameType.SURVIVAL);
            var raidId = id("gameplay/prepared_raid");
            var arenaId = id("gameplay_prepared_arena");
            var siteId = id("gameplay_preparation_site");
            var eventId = id("gameplay_preparation_event");
            var components = new CompoundTag();
            components.putInt("minecraft:custom_model_data", 31);
            rule = new ContentDefinitions.Countermeasure(id("gameplay_tool"), raidId,
                    ResourceLocation.parse("minecraft:mob_attack"), ResourceLocation.parse("minecraft:feather"),
                    true, 0.5, components);
            var reward = new NpcRewardEntry(rule.item(), 2, rule.components());
            var prepare = event(eventId, false, List.of(new Effect.GrantRewards(id("gameplay_prepare_reward"),
                    ResourceLocation.parse("mythictrpg:demeter"), List.of(reward))));
            swapStory(new StoryDefinitionSnapshot(storyBefore.actors(), storyBefore.locations(), storyBefore.facts(),
                    storyBefore.coverStories(), storyBefore.disclosurePolicies(), Map.of(eventId, prepare),
                    Map.of(), Map.of(), Map.of(), storyBefore.generation() + 1));
            BlockPos sitePosition = helper.absolutePos(new BlockPos(2, 2, 2));
            helper.getLevel().setBlockAndUpdate(sitePosition, Blocks.STONE.defaultBlockState());
            owner.setPos(sitePosition.getX() + 0.5, sitePosition.getY(), sitePosition.getZ() + 2.5);
            var site = new ContentDefinitions.Investigation(siteId, owner.level().dimension().location(), sitePosition,
                    ResourceLocation.parse("minecraft:stone"), 4, eventId, Optional.empty(), 1, 20,
                    new AlwaysCondition(BuiltinConditionTypes.ALWAYS));
            swapContent(new ContentDefinitions.Snapshot(Map.of(siteId, site), Map.of(), Map.of(rule.id(), rule)));
            check(command(owner, "mythinvestigate " + siteId) == 1,
                    "ordinary player could not obtain a tool through investigation and Story reward");
            check(owner.getInventory().countItem(Items.FEATHER) == 2
                    && companion.getInventory().countItem(Items.FEATHER) == 0, "tool was not delivered personally");
            check(command(owner, "mythinvestigate " + siteId) == 0
                    && owner.getInventory().countItem(Items.FEATHER) == 2, "preparation reward duplicated");
            ItemStack expected = reward.createStack(owner.registryAccess());
            int toolSlot = -1;
            for (int slot = 0; slot < owner.getInventory().items.size(); slot++)
                if (ItemStack.isSameItemSameComponents(owner.getInventory().items.get(slot), expected)) {
                    toolSlot = slot; break;
                }
            check(toolSlot >= 0, "reward delivery changed the authored components");
            owner.setItemSlot(EquipmentSlot.OFFHAND, owner.getInventory().removeItemNoUpdate(toolSlot));

            // Well outside the GameTest's tracked chunks; block creation alone must not imply entity readiness.
            Vec3 anchor = Vec3.atBottomCenterOf(sitePosition.offset(512, 0, 0));
            for (int x = -3; x <= 6; x++) for (int z = -3; z <= 6; z++) {
                BlockPos floor = BlockPos.containing(anchor).offset(x, -1, z);
                helper.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                for (int y = 1; y <= 3; y++) helper.getLevel().setBlockAndUpdate(floor.above(y), Blocks.AIR.defaultBlockState());
            }
            arena = RaidDefinition.parseArena(arenaId,
                    JsonParser.parseString(RaidGameTests.arenaJson(anchor, helper.getLevel().dimension().location())).getAsJsonObject());
            var json = JsonParser.parseString(RaidGameTests.fixtureJson()).getAsJsonObject();
            json.getAsJsonArray("arenas").set(0, new JsonPrimitive(arenaId.toString()));
            json.addProperty("minimumPlayers", 2);
            json.addProperty("maximumPlayers", 2);
            var definition = RaidDefinition.parse(raidId, json);
            swapCatalog(new RaidCatalog.Snapshot(Map.of(raidId, definition), Map.of(arenaId, arena)));
            check(command(owner, "mythraid create " + id("gameplay/missing_raid")) == 0
                    && RaidState.get(owner.server).membership(owner.getUUID()).isEmpty(), "undefined raid accepted");

            var cancelled = formAndQueue(raidId);
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, owner.server));
            check(cancelled.status == RaidState.Status.STARTING && cancelled.bossId == null,
                    "cold arena did not wait for actual entity readiness");
            check(!arena.bounds().contains(owner.position()), "player was moved before entity readiness");
            var piston = new net.neoforged.neoforge.event.level.PistonEvent.Pre(helper.getLevel(),
                    BlockPos.containing(arena.entry()), net.minecraft.core.Direction.EAST,
                    net.neoforged.neoforge.event.level.PistonEvent.PistonMoveType.EXTEND);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(piston);
            check(piston.isCanceled(), "STARTING arena did not protect its blocks from piston movement");
            check(command(owner, "mythraid cancel " + cancelled.id) == 1
                    && cancelled.status == RaidState.Status.CANCELLED
                    && RaidState.get(owner.server).arenaAvailable(arena), "STARTING cancellation retained its lease");

            var timedOut = formAndQueue(raidId);
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, owner.server));
            check(timedOut.status == RaidState.Status.STARTING, "second cold attempt was not pending");
            // Exercise the actual deadline branch with its elapsed-time input, without changing saved timestamps.
            var continueStart = RaidRuntime.class.getDeclaredMethod("continueStart",
                    net.minecraft.server.MinecraftServer.class, RaidState.Attempt.class, long.class);
            continueStart.setAccessible(true);
            continueStart.invoke(RaidRuntime.INSTANCE, owner.server, timedOut,
                    timedOut.queuedAt + definition.queueTimeoutTicks());
            check(timedOut.status == RaidState.Status.FAILED && timedOut.reason.equals("START_TIMEOUT")
                    && timedOut.bossId == null && RaidState.get(owner.server).arenaAvailable(arena),
                    "STARTING timeout spawned a boss or retained the lease");

            attempt = formAndQueue(raidId);
            swapContent(ContentDefinitions.Snapshot.empty()); // Existing recruitment keeps its preparation policy.
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, owner.server));
        }

        RaidState.Attempt formAndQueue(ResourceLocation raidId) throws Exception {
            check(command(owner, "mythraid create " + raidId) == 1, "ordinary player could not create authored raid");
            var result = RaidState.get(owner.server).membership(owner.getUUID()).orElseThrow();
            runs.add(result.id);
            check(command(owner, "mythraid start " + result.id) == 0, "minimum roster bypassed");
            check(command(companion, "mythraid join " + result.id) == 1, "ordinary explicit join failed");
            check(command(companion, "mythraid start " + result.id) == 0, "nonleader started raid");
            check(command(owner, "mythraid start " + result.id) == 1, "leader could not queue raid");
            return result;
        }

        void verifyCombat() throws Exception {
            check(arena.bounds().contains(owner.position()) && arena.bounds().contains(companion.position()),
                    "ordinary commands did not move both participants into combat");
            var boss = RaidRuntime.living(helper.getLevel().getEntity(attempt.bossId));
            check(boss != null && attempt.id.equals(RaidRuntime.raidTag(boss)), "actual raid boss was not registered");
            if (boss instanceof Mob mob) mob.setNoAi(true);
            check(attempt.spawnedEntities.size() == 1, "pending startup spawned multiple bosses");
            var bossId = attempt.bossId;
            RaidRuntime.tick(new ServerTickEvent.Post(() -> true, owner.server));
            check(attempt.status == RaidState.Status.ACTIVE && bossId.equals(attempt.bossId)
                    && attempt.spawnedEntities.size() == 1, "next tick lost or duplicated the registered boss");
            clearSpawnProtection(owner);
            owner.setHealth(20);
            owner.invulnerableTime = 0;
            check(owner.hurt(owner.damageSources().mobAttack(boss), 8), "actual boss damage did not reach player");
            check(owner.getHealth() == 16 && owner.getOffhandItem().getCount() == 1,
                    "Story reward tool did not reduce actual raid damage and consume exactly one");
            check(attempt.countermeasures.equals(List.of(rule)), "recruitment lost frozen preparation policy");
            var saved = RaidState.get(owner.server).save(new CompoundTag(), owner.registryAccess());
            check(RaidState.load(saved, owner.registryAccess()).find(attempt.id).orElseThrow().countermeasures.equals(List.of(rule)),
                    "ordinary run did not preserve policy in SavedData");
            check(command(owner, "mythraid cancel " + attempt.id) == 1
                    && attempt.status == RaidState.Status.CANCELLED, "leader could not clean up active run");
            check(RaidState.get(owner.server).membership(owner.getUUID()).isEmpty()
                    && RaidState.get(owner.server).membership(companion.getUUID()).isEmpty(), "cleanup retained participants");
        }

        void close() {
            if (done) return;
            done = true;
            if (owner != null) for (UUID run : runs) {
                RaidRuntime.INSTANCE.cancel(owner, run);
                var saved = RaidState.get(owner.server).find(run).orElse(null);
                if (saved != null && saved.terminal()) {
                    RaidState.get(owner.server).attempts.remove(run);
                    RaidState.get(owner.server).changed();
                }
            }
            try { swapContent(contentBefore); swapStory(storyBefore); swapCatalog(raidBefore); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            if (owner != null) disconnect(owner, List.of());
            if (companion != null) disconnect(companion, List.of());
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        }
    }

    private static int command(ServerPlayer player, String text) throws Exception {
        return player.server.getCommands().getDispatcher().execute(text, player.createCommandSourceStack().withPermission(0));
    }
    private static void clearSpawnProtection(ServerPlayer player) throws ReflectiveOperationException {
        // Vanilla protects a fresh fixture for sixty ticks; production protection remains unchanged.
        var field = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
        field.setAccessible(true); field.setInt(player, 0);
    }
    private static void swapContent(ContentDefinitions.Snapshot snapshot) throws ReflectiveOperationException {
        var field = ContentDefinitions.class.getDeclaredField("snapshot");
        field.setAccessible(true); field.set(ContentDefinitions.INSTANCE, snapshot);
    }
    private static void swapCatalog(RaidCatalog.Snapshot snapshot) throws ReflectiveOperationException {
        var field = RaidCatalog.class.getDeclaredField("snapshot");
        field.setAccessible(true); field.set(RaidCatalog.INSTANCE, snapshot);
    }
}
