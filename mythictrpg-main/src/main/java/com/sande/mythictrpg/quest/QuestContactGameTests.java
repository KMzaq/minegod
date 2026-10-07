package com.sande.mythictrpg.quest;

import com.mojang.authlib.GameProfile;
import com.sande.mythictrpg.ai.api.RoomConversationEngineRouter;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import com.sande.mythictrpg.gameplay.watch.*;
import com.sande.mythictrpg.godavatar.*;
import com.sande.mythictrpg.godavatar.activity.*;
import com.sande.mythictrpg.quest.reward.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;

/** Real SavedData grant, watch writer/session, scene policy, avatar, activity and room authority; no LLM. */
@GameTestHolder("mythictrpg_quest_contact")
@PrefixGameTestTemplate(false)
public final class QuestContactGameTests {
    private static Fixture active;
    @GameTest(templateNamespace = "mythictrpg_quest_contact", template = "empty", timeoutTicks = 500, batch = "quest_contact")
    public static void actualObservationAndPhysicalContact(GameTestHelper helper) {
        active = new Fixture(helper); helper.onEachTick(active::tick);
    }
    @AfterBatch(batch = "quest_contact") public static void cleanup(ServerLevel level) { if (active != null) active.close(); }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;
        final ResourceLocation god = ResourceLocation.parse("mythictrpg:demeter");
        final ResourceLocation otherGod = ResourceLocation.parse("mythictrpg:fortuna");
        final ResourceLocation read = ResourceLocation.parse("mythictrpg:contact_fixture_read");
        final ResourceLocation stroll = ResourceLocation.parse("mythictrpg:contact_fixture_stroll");
        final List<Runnable> restores = new ArrayList<>();
        ServerPlayer player, other;
        GodAvatarEntity avatar;
        AsyncActionLedger raw;
        ActionLedgerService.Runtime installed;
        RewardWatchSettings settings;
        BlockPos feet;
        int phase;
        boolean closed;
        Fixture(GameTestHelper helper) { h = helper; }
        void tick() {
            if (closed) return;
            try {
                if (phase == 0) { setup(); phase = 1; }
                if (phase == 1) {
                    if (!raw.ready().isDone()) return;
                    raw.ready().join();
                    GodWatchRuntime.install(h.getLevel().getServer(), WatchTrialSettings.OFF, settings); phase = 2;
                }
                if (phase == 2) {
                    GodWatchRuntime.tick(player.server);
                    if (!GodWatchRuntime.watchingNow(player, god)) return;
                    checks(); close(); h.succeed();
                }
            } catch (Exception | AssertionError error) { close(); h.fail("Quest contact phase " + phase + ": " + error); }
        }
        @SuppressWarnings("unchecked") void setup() throws Exception {
            var level = h.getLevel();
            require(GodWatchRuntime.current(level.getServer()) == null, "watch fixture must run in a fresh test server");
            feet = h.absolutePos(new BlockPos(2, 2, 2));
            for (int x = -3; x <= 5; x++) for (int z = -3; z <= 5; z++) {
                level.setBlockAndUpdate(feet.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 4; y++) level.setBlockAndUpdate(feet.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
            player = connected(level); other = connected(level);
            player.setPos(Vec3.atBottomCenterOf(feet).add(0, 0, 2)); other.setPos(player.position().add(1, 0, 0));
            var definition = new GodAvatarDefinition(god, new GodAvatarDefinition.Appearance(0, 1),
                    new GodAvatarDefinition.Stats(20, .25, 2, 0, 16),
                    new GodAvatarDefinition.Movement(true, false, true, 1, 16, 16),
                    new GodAvatarDefinition.Combat(true, true, false, true), new GodAvatarDefinition.Placement(false, 0), 4);
            replace(GodAvatarDefinitionManager.INSTANCE, "definitions", Map.of(god, definition));
            replace(GodAvatarDefinitionManager.INSTANCE, "generation", GodAvatarDefinitionManager.INSTANCE.generation() + 1);
            var activities = new NpcActivityDefinitions.Data(Map.of(
                    read, new NpcActivityDefinition(read, ActivityKind.READ, NpcActivityDefinition.Mode.DECORATIVE, Set.of("idle"), 200, Map.of()),
                    stroll, new NpcActivityDefinition(stroll, ActivityKind.STROLL, NpcActivityDefinition.Mode.DECORATIVE, Set.of("idle"), 200, Map.of())),
                    Map.of(god, new NpcActivityDefinitions.Policy(List.of(read, stroll), false, 4, 100)));
            replace(NpcActivityDefinitions.INSTANCE, "data", activities);
            replace(NpcActivityDefinitions.INSTANCE, "generation", NpcActivityDefinitions.INSTANCE.generation() + 1);
            replace(RoomConversationEngineRouter.INSTANCE, "installed", true);
            var runtimes = (Map<MinecraftServer, ActionLedgerService.Runtime>) field(ActionLedgerService.class, "RUNTIMES").get(null);
            var previous = runtimes.get(level.getServer());
            installed = new ActionLedgerService.Runtime();
            var world = com.sande.mythictrpg.rumor.RumorSavedData.get(level.getServer()).worldId();
            raw = new AsyncActionLedger(Files.createTempDirectory(level.getServer().getWorldPath(LevelResource.ROOT), "contact-raw-"),
                    world, new ActionLedgerStore.Limits(8_000_000, 128_000, 5000), 64);
            field(ActionLedgerService.Runtime.class, "worldId").set(installed, world);
            field(ActionLedgerService.Runtime.class, "ledger").set(installed, raw);
            runtimes.put(level.getServer(), installed);
            restores.add(() -> { if (previous == null) runtimes.remove(level.getServer()); else runtimes.put(level.getServer(), previous); });
            var place = new WatchContract.Area(level.dimension().location().toString(), feet.getX()-8, feet.getY()-4, feet.getZ()-8,
                    feet.getX()+8, feet.getY()+8, feet.getZ()+8);
            var policy = new WatchContract.Policy(new WatchContract.Ref("test:quest_contact_watch", 1), god.toString(),
                    "test:sight", "test:contact", List.of(place), Set.of(WatchContract.Field.ACTOR, WatchContract.Field.LOCATION));
            settings = new RewardWatchSettings(1, true, 4_000_000, 5000, 64,
                    List.of(new RewardWatchSettings.Rule(policy, Set.of(ActionRecord.Type.MATURE_CROP_REMOVED), false)), List.of());
            require(!QuestContactService.remoteAvailable(player, god), "no entitlement/session remote call");
            var issued = RewardClaimService.INSTANCE.issue(player, god, ResourceLocation.parse("mythictrpg:contact_watch_" + UUID.randomUUID()),
                    new ResolvedQuestReward("시험 주시", List.of(new WatchRewardEntry(god, "시험 신")), List.of()));
            require(issued.succeeded() && RewardClaimState.get(player.server).hasWatch(player.getUUID(), god), "actual watch reward grant");
            require(!QuestContactService.remoteAvailable(player, god), "entitlement alone must not create current attention");
        }
        void checks() throws Exception {
            require(QuestContactService.remoteAvailable(player, god), "active idle watch session cannot answer");
            require(QuestContactService.call(player, god), "normal remote command path did not answer");
            require(!QuestContactService.canConfirm(player, god, Optional.empty(), Optional.empty(), QuestCompletionMode.PLAYER_RETURN_TO_NPC),
                    "one call receipt escaped its synchronous contact");
            require(!QuestContactService.remoteAvailable(other, god), "another player borrowed watch session");
            require(!QuestContactService.remoteAvailable(player, otherGod), "another God borrowed watch session");
            var home = player.position(); player.setPos(home.add(30, 0, 0));
            require(!QuestContactService.remoteAvailable(player, god), "current scene outside authored area admitted"); player.setPos(home);
            avatar = GodAvatarService.INSTANCE.spawn(h.getLevel(), god, Vec3.atBottomCenterOf(feet)).orElseThrow();
            require(NpcActivityRuntime.INSTANCE.request(avatar, read), "could not begin real reading activity");
            require(!QuestContactService.remoteAvailable(player, god), "reading answered remote call");
            NpcActivityRuntime.interrupt(avatar, "FIXTURE_FINISHED");
            require(NpcActivityRuntime.INSTANCE.request(avatar, stroll), "could not begin real strolling activity");
            require(!QuestContactService.remoteAvailable(player, god), "strolling answered remote call");
            var rooms = ConversationRooms.INSTANCE;
            var room = rooms.create(player, RoomType.PRIVATE, List.of(god), RecordingScope.STANDARD);
            var scope = rooms.actionScope(player, room.roomId(), room.revision(), god).orElseThrow();
            require(QuestContactService.met(player, avatar, scope), "physical contact during stroll rejected");
            require(NpcActivityRuntime.INSTANCE.state(avatar).contains("PAUSED_FOR_DIALOGUE"), "physical meeting did not pause activity");
            require(QuestContactService.canConfirm(player, god, Optional.empty(), Optional.empty(), QuestCompletionMode.PLAYER_RETURN_TO_NPC),
                    "physical watched player's contact rejected");
            require(!QuestContactService.canConfirm(other, god, Optional.empty(), Optional.of(QuestContactLocation.capture(other)),
                    QuestCompletionMode.PLAYER_RETURN_TO_NPC), "place alone conferred contact on another player");
            var second = rooms.create(player, RoomType.PRIVATE, List.of(god), RecordingScope.STANDARD);
            var wrongScope = rooms.actionScope(player, second.roomId(), second.revision(), god).orElseThrow();
            require(!QuestContactService.matchesScope(player, wrongScope), "same God in a second private room borrowed contact");
            rooms.leave(player, room, "FIXTURE_END");
            require(!QuestContactService.matchesScope(player, scope), "ended room contact survived");
            rooms.leave(player, second, "FIXTURE_END");
            NpcActivityRuntime.interrupt(avatar, "FIXTURE_FINISHED");
            avatar.getNavigation().stop();
            avatar.setTarget(other);
            require(!QuestContactService.remoteAvailable(player, god), "combat answered remote call");
            avatar.setTarget(null);
            var foreign = rooms.create(other, RoomType.PRIVATE, List.of(god), RecordingScope.STANDARD);
            require(!QuestContactService.remoteAvailable(player, god), "another player's private conversation admitted remote attention");
            rooms.leave(other, foreign, "FIXTURE_END");
            GodWatchRuntime.current(player.server).suspend(player.getUUID());
            require(!QuestContactService.remoteAvailable(player, god), "queued logout suspension retained current attention");
        }
        void replace(Object target, String name, Object value) throws Exception {
            var field = field(target.getClass(), name); var previous = field.get(target);
            restores.add(() -> { try { field.set(target, previous); } catch (Exception e) { throw new IllegalStateException(e); } });
            field.set(target, value);
        }
        public void close() {
            if (closed) return; closed = true;
            var server = h.getLevel().getServer();
            if (avatar != null) { NpcActivityRuntime.interrupt(avatar, "FIXTURE_STOP"); GodAvatarService.INSTANCE.despawn(avatar); }
            ConversationRooms.INSTANCE.clear(); QuestContactService.clear();
            GodWatchRuntime.stop(server);
            if (raw != null) raw.close();
            if (installed != null) try { ((java.util.concurrent.ExecutorService) field(ActionLedgerService.Runtime.class, "bootstrap").get(installed)).shutdownNow(); }
                catch (Exception ignored) { }
            Collections.reverse(restores); restores.forEach(Runnable::run);
            for (var p : new ServerPlayer[]{player, other}) if (p != null) server.getPlayerList().remove(p);
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static Field field(Class<?> type, String name) throws Exception { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static ServerPlayer connected(ServerLevel level) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "ContactMock"), false);
        var player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        player.server.getPlayerList().placeNewPlayer(connection, player, cookie); return player;
    }
}
