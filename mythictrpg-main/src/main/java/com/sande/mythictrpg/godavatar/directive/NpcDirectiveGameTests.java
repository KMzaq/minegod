package com.sande.mythictrpg.godavatar.directive;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.action.*;
import com.sande.mythictrpg.ai.api.RoomConversationEngineRouter;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.godavatar.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.*;

/** Native room scopes, real pathfinding and melee goals; no LLM is invoked. */
@GameTestHolder("mythictrpg_npc_directive")
@PrefixGameTestTemplate(false)
public final class NpcDirectiveGameTests {
    private static final ResourceLocation GOD = ResourceLocation.parse("mythictrpg:demeter");
    private static final ResourceLocation TYPE = ResourceLocation.parse("mythictrpg:npc_directive");
    private static final NpcDirectiveRuntime RUNTIME = NpcDirectiveRuntime.INSTANCE;

    @GameTest(templateNamespace = "mythictrpg_npc_directive", template = "empty", batch = "npc_directive", timeoutTicks = 200)
    public static void choicesRejectForeignRoomsReplayNewOrdersAndRaidLeases(GameTestHelper helper) throws Exception {
        try (Fixture f = new Fixture(helper)) {
            var target = f.hostile(3);
            String attack = f.choice("ATTACK_HOSTILE");
            var foreign = ConversationRooms.INSTANCE.create(f.player, RoomType.PRIVATE, List.of(GOD), RecordingScope.STANDARD);
            var scope = ConversationRooms.INSTANCE.actionScope(f.player, foreign.roomId(), foreign.revision(), GOD).orElseThrow();
            helper.assertFalse(RUNTIME.canChoose(new AiActionContext(f.player.server, f.player, Optional.of(scope)),
                    f.proposal(scope.sessionId(), attack)), "room A choice crossed into room B");
            helper.assertFalse(RUNTIME.canChoose(f.context(), f.proposal(UUID.randomUUID(), attack)), "foreign action session accepted");
            var source = f.proposal(f.scope().sessionId(), attack);
            helper.assertTrue(RUNTIME.canChoose(f.context(), source), "valid observed hostile not offered");
            var first = AiActionGateway.submitRoom(f.player, f.room.roomId(), f.room.revision(), GOD,
                    "npc_directive", "", "", Map.of("choice_id", attack), false);
            helper.assertValueEqual(first.status(), AiActionResult.Status.EXECUTED, "real gateway directive: " + first.reason());
            helper.assertValueEqual(first.details().get("status"), "STARTED", "combat start falsely claimed a kill");
            helper.assertValueEqual(first.details().get("work_completed"), "false", "combat start claimed completion");
            helper.assertTrue(f.actor.getTarget() == target, "physical melee target was not set");
            helper.assertFalse(RUNTIME.canChoose(f.context(), source), "consumed choice replayed with fresh proposal id");

            String follow = f.choice("FOLLOW");
            var destination = f.feet.offset(0, 0, 4);
            helper.assertTrue(GodAvatarService.INSTANCE.move(f.actor, destination), "external move fixture did not start");
            helper.assertFalse(RUNTIME.active(f.actor), "external move did not release AI directive");
            helper.assertFalse(RUNTIME.canChoose(f.context(), f.proposal(f.scope().sessionId(), follow)), "late AI stole a newer external order");
            helper.assertTrue(f.actor.getTarget() == null, "old combat survived external move");

            f.respawn();
            String staleTarget = f.choice("ATTACK_HOSTILE"); target.discard();
            helper.assertFalse(RUNTIME.canChoose(f.context(), f.proposal(f.scope().sessionId(), staleTarget)), "removed hostile remained executable");
            f.respawn();
            String staleRoom = f.choice("FOLLOW");
            ConversationRooms.INSTANCE.leave(f.player, f.room, "FIXTURE_END");
            helper.assertFalse(RUNTIME.canChoose(f.contextForOldRoom(), f.proposal(f.session, staleRoom)), "ended room executed a physical order");

            GodAvatarService.INSTANCE.despawn(f.actor);
            UUID raid = UUID.randomUUID();
            helper.assertTrue(GodAvatarService.INSTANCE.acquireRaidLease(f.player.server, GOD, raid), "raid lease fixture failed");
            try {
                f.actor = GodAvatarService.INSTANCE.spawnForRaid(f.level(), GOD, Vec3.atBottomCenterOf(f.feet), raid).orElseThrow();
                f.room = foreign; f.session = scope.sessionId();
                helper.assertFalse(f.capture().contains("available_choices"), "dialogue stole a raid-owned avatar");
            } finally {
                GodAvatarService.INSTANCE.despawn(f.actor);
                GodAvatarService.INSTANCE.releaseRaidLease(f.player.server, GOD, raid);
            }
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "mythictrpg_npc_directive", template = "empty", batch = "npc_directive_motion", timeoutTicks = 300)
    public static void approachWalksCombatDamagesAndProtectionRechecksTheAttacker(GameTestHelper helper) throws Exception {
        Fixture f = new Fixture(helper);
        helper.runAtTickTime(299, f::close);
        try {
            f.player.setPos(Vec3.atBottomCenterOf(f.feet.offset(5, 0, 0)));
            Vec3 origin = f.actor.position();
            var result = f.execute(f.choice("APPROACH"));
            helper.assertTrue(result.executed(), "approach did not start: " + result.reason());
            helper.assertTrue(f.actor.position().distanceToSqr(origin) < .001, "approach teleported actor");
            helper.startSequence().thenWaitUntil(() -> helper.assertTrue(f.actor.distanceToSqr(f.player) <= 4,
                            "waiting for actual navigation, distance=" + f.actor.distanceToSqr(f.player)))
                    .thenExecute(() -> {
                        try {
                            helper.assertTrue(f.actor.position().distanceToSqr(origin) > 1, "navigation did not move the actor");
                            RUNTIME.cancel(f.actor);
                            f.mob = f.hostileAt(f.actor.position().add(1.5, 0, 0));
                            f.healthBefore = f.mob.getHealth();
                            helper.assertTrue(f.execute(f.choice("ATTACK_HOSTILE")).executed(), "attack did not start");
                        } catch (Throwable failure) { f.close(); throw failure; }
                    })
                    .thenWaitUntil(() -> helper.assertTrue(f.mob.getHealth() < f.healthBefore,
                            "waiting for real melee damage, health=" + f.mob.getHealth()))
                    .thenExecute(() -> {
                        try {
                            f.mob.discard(); RUNTIME.tick(f.actor);
                            helper.assertFalse(RUNTIME.active(f.actor), "dead/removed target did not stop pursuit");
                            f.mob = f.hostileAt(f.actor.position().add(2, 0, 0));
                            helper.assertTrue(f.execute(f.choice("PROTECT_PARTICIPANT")).executed(), "protection did not start");
                            helper.assertTrue(f.actor.getTarget() == null, "protection attacked a non-attacking hostile");
                            f.mob.setTarget(f.player); f.actor.tickCount = 20; RUNTIME.tick(f.actor);
                            helper.assertTrue(f.actor.getTarget() == f.mob, "protection ignored actual attacker");
                            helper.assertTrue(RUNTIME.combatAllowed(f.actor, f.mob), "actual attacker denied");
                            f.mob.setTarget(null);
                            helper.assertFalse(RUNTIME.combatAllowed(f.actor, f.mob), "protection kept attacking after aggression ended");
                            f.actor.tickCount = 30; RUNTIME.tick(f.actor);
                            helper.assertTrue(f.actor.getTarget() == null, "protection retained stale target");
                            f.player.setPos(f.player.position().add(40, 0, 0)); RUNTIME.tick(f.actor);
                            helper.assertFalse(RUNTIME.active(f.actor), "follow/protection pursued beyond authored range");
                        } finally { f.close(); }
                    }).thenSucceed();
        } catch (Throwable failure) { f.close(); throw failure; }
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final Field definitions, installed;
        final Object oldDefinitions;
        final boolean oldInstalled;
        final ServerPlayer player;
        final io.netty.channel.embedded.EmbeddedChannel channel;
        final Set<Long> forced = new HashSet<>();
        final List<Mob> spawned = new ArrayList<>();
        final BlockPos feet;
        GodAvatarEntity actor;
        ConversationRoomSnapshot room;
        UUID session;
        Mob mob;
        float healthBefore;
        boolean closed;
        Fixture(GameTestHelper helper) throws Exception {
            this.helper = helper;
            definitions = field(GodAvatarDefinitionManager.class, "definitions");
            installed = field(RoomConversationEngineRouter.class, "installed");
            oldDefinitions = definitions.get(GodAvatarDefinitionManager.INSTANCE);
            oldInstalled = installed.getBoolean(RoomConversationEngineRouter.INSTANCE);
            definitions.set(GodAvatarDefinitionManager.INSTANCE, Map.of(GOD, new GodAvatarDefinition(GOD,
                    new GodAvatarDefinition.Appearance(0, 1), new GodAvatarDefinition.Stats(40, .25, 2, 0, 24),
                    new GodAvatarDefinition.Movement(true, false, true, 1, 16, 24),
                    new GodAvatarDefinition.Combat(true, true, false, true), new GodAvatarDefinition.Placement(false, 0), 2)));
            installed.setBoolean(RoomConversationEngineRouter.INSTANCE, true);
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "Directive"), false);
            player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
            player.setGameMode(GameType.SURVIVAL);
            feet = helper.absolutePos(new BlockPos(2, 2, 2));
            for (int x = (feet.getX() >> 4) - 2; x <= (feet.getX() >> 4) + 2; x++)
                for (int z = (feet.getZ() >> 4) - 2; z <= (feet.getZ() >> 4) + 2; z++) {
                    level().getChunk(x, z);
                    long key = net.minecraft.world.level.ChunkPos.asLong(x, z);
                    if (!level().getForcedChunks().contains(key)) { level().setChunkForced(x, z, true); forced.add(key); }
                }
            for (int x = -8; x <= 10; x++) for (int z = -8; z <= 10; z++) {
                var block = feet.offset(x, 0, z); level().setBlockAndUpdate(block.below(), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 4; y++) level().setBlockAndUpdate(block.above(y), Blocks.AIR.defaultBlockState());
            }
            player.setPos(Vec3.atBottomCenterOf(feet.offset(-3, 0, 0)));
            respawn();
            room = ConversationRooms.INSTANCE.create(player, RoomType.PRIVATE, List.of(GOD), RecordingScope.STANDARD);
            session = scope().sessionId();
        }
        net.minecraft.server.level.ServerLevel level() { return helper.getLevel(); }
        void respawn() {
            if (actor != null && !actor.isRemoved()) GodAvatarService.INSTANCE.despawn(actor);
            actor = GodAvatarService.INSTANCE.spawn(level(), GOD, Vec3.atBottomCenterOf(feet)).orElseThrow();
            actor.setOnGround(true);
        }
        Mob hostile(int x) { return hostileAt(Vec3.atBottomCenterOf(feet.offset(x, 0, 0))); }
        Mob hostileAt(Vec3 at) {
            var value = EntityType.HUSK.create(level());
            if (value == null) throw new IllegalStateException("Husk fixture creation failed");
            value.setNoAi(true); value.setPos(at); value.setOnGround(true); level().addFreshEntity(value); spawned.add(value); return value;
        }
        AiActionScope scope() { return ConversationRooms.INSTANCE.actionScope(player, room.roomId(), room.revision(), GOD).orElseThrow(); }
        AiActionContext context() { return new AiActionContext(player.server, player, Optional.of(scope())); }
        AiActionContext contextForOldRoom() { return new AiActionContext(player.server, player, Optional.of(new AiActionScope(session, GOD))); }
        AiActionProposal proposal(UUID selectedSession, String choice) {
            return new AiActionProposal(UUID.randomUUID(), selectedSession, TYPE, GOD, player.getUUID(), "", "", Map.of("choice_id", choice));
        }
        String capture() { return RUNTIME.capture(player, room.roomId(), room.revision(), GOD, session); }
        String choice(String kind) {
            String json = capture();
            int start = json.indexOf('{');
            if (start < 0) throw new IllegalStateException("No choices: " + json);
            for (var item : JsonParser.parseString(json.substring(start)).getAsJsonObject().getAsJsonArray("available_choices")) {
                var value = item.getAsJsonObject();
                if (value.get("directive").getAsString().equals(kind)) return value.get("choice_id").getAsString();
            }
            throw new IllegalStateException("Missing choice " + kind + ": " + json);
        }
        AiActionExecution execute(String selected) { return RUNTIME.execute(context(), proposal(session, selected)); }
        @Override public void close() {
            if (closed) return; closed = true;
            if (actor != null && !actor.isRemoved()) GodAvatarService.INSTANCE.despawn(actor);
            for (var value : spawned) value.discard();
            for (var value : ConversationRooms.INSTANCE.memberships(player)) ConversationRooms.INSTANCE.leave(player, value, "FIXTURE_END");
            try { definitions.set(GodAvatarDefinitionManager.INSTANCE, oldDefinitions); installed.setBoolean(RoomConversationEngineRouter.INSTANCE, oldInstalled); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            player.server.getPlayerList().remove(player); channel.finishAndReleaseAll();
            for (long chunk : forced) { var pos = new net.minecraft.world.level.ChunkPos(chunk); level().setChunkForced(pos.x, pos.z, false); }
        }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        var value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private NpcDirectiveGameTests() { }
}
