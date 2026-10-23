package com.sande.mythictrpg.prototype;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sande.mythictrpg.skills.SkillMana;
import com.sande.mythictrpg.weaponskill.WeaponSkillState;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Real OP command dispatch and isolated practice lifecycles, without resetting any player state. */
@GameTestHolder("mythictrpg_prototype_catalog")
@PrefixGameTestTemplate(false)
public final class PrototypePracticeGameTests {
    @GameTest(templateNamespace = "mythictrpg_prototype_catalog", template = "empty", batch = "prototype_practice_commands", timeoutTicks = 100)
    public static void actualOperatorCommandsRecoverTargetsScheduleDamageAndEnd(GameTestHelper h) {
        var fixture = new Fixture(h);
        try {
            var owner = fixture.connect(0);
            h.assertTrue(!fixture.ordinaryCanCreate(owner), "ordinary command source registered a practice arena");
            h.assertTrue(PrototypeCombatAccess.scope(owner).filter(s->PrototypeCombatAccess.wilderness(owner,s)).isPresent(), "rejected ordinary command acquired practice authority or removed wilderness access");
            h.assertValueEqual(fixture.command(owner, "arena"), 1, "OP arena command failed");
            var ids = List.copyOf(PrototypeCombatAccess.targets(owner));
            h.assertValueEqual(ids.size(), 4, "practice arena did not register all four targets");
            var scope = PrototypeCombatAccess.scope(owner).orElseThrow();
            for (UUID id : ids) {
                var target = fixture.target(id);
                h.assertTrue(PrototypeCombatAccess.enemy(owner, scope, target), "spawned target was not an eligible encounter enemy");
                target.setHealth(50);
            }
            var manaState = WeaponSkillState.get(owner.server);
            manaState.trySpend(owner.getUUID(), "test:practice_resource_check", 30, 80, SkillMana.now(owner));
            double mana = SkillMana.mana(owner);
            h.assertValueEqual(fixture.command(owner, "targets"), 1, "OP target reset command");
            for (UUID id : ids) h.assertValueEqual(fixture.target(id).getHealth(), 200.0F, "target reset did not restore HP");
            h.assertTrue(SkillMana.mana(owner) == mana, "target reset restored player mana");
            owner.setHealth(20);
            h.assertValueEqual(fixture.command(owner, "attack invalid_kind 1"), 0, "unknown practice attack was scheduled");
            h.assertValueEqual(fixture.command(owner, "attack melee 1"), 1, "OP melee scheduling command");
            h.runAfterDelay(3, () -> {
                try {
                    h.assertValueEqual(owner.getHealth(), 16.0F, "scheduled practice melee did not apply one real four-point hit");
                    h.assertValueEqual(fixture.command(owner, "attack projectile 40"), 1, "future practice attack scheduling");
                    h.assertValueEqual(fixture.command(owner, "end"), 1, "OP end command");
                    h.assertTrue(PrototypeCombatAccess.scope(owner).filter(scope::equals).isEmpty(), "end retained owner practice scope");
                    for (UUID id : ids) h.assertTrue(fixture.removed(id), "end retained a live practice target");
                    h.assertTrue(queueFor(owner).isEmpty(), "end retained the owner's pending attacks");
                    fixture.close();
                    h.succeed();
                } catch (RuntimeException | Error failure) { fixture.close(); throw failure; }
            });
        } catch (RuntimeException | Error failure) { fixture.close(); throw failure; }
    }

    @GameTest(templateNamespace = "mythictrpg_prototype_catalog", template = "empty", batch = "prototype_practice_roster", timeoutTicks = 100)
    public static void fivePlayerLimitMemberLeaveAndOwnerEndKeepCorrectParticipants(GameTestHelper h) {
        try (var fixture = new Fixture(h)) {
            var owner = fixture.connect(0);
            var members = new ArrayList<ServerPlayer>();
            for (int i = 1; i <= 5; i++) members.add(fixture.connect(i));
            h.assertValueEqual(fixture.command(owner, "arena"), 1, "owner could not create arena");
            var scope = PrototypeCombatAccess.scope(owner).orElseThrow();
            var ids = List.copyOf(PrototypeCombatAccess.targets(owner));
            for (int i = 0; i < 4; i++) {
                h.assertValueEqual(fixture.command(members.get(i), "join " + owner.getGameProfile().getName()), 1, "valid party member did not join");
                h.assertValueEqual(PrototypeCombatAccess.scope(members.get(i)).orElseThrow(), scope, "participant joined another scope");
            }
            h.assertValueEqual(fixture.command(members.get(4), "join " + owner.getGameProfile().getName()), 0, "sixth participant exceeded five-player limit");
            h.assertTrue(PrototypeCombatAccess.scope(members.get(4)).isEmpty(), "rejected sixth participant acquired scope");
            h.assertValueEqual(fixture.command(members.getFirst(), "end"), 1, "member leave command");
            h.assertTrue(PrototypeCombatAccess.scope(members.getFirst()).isEmpty(), "leaving member retained membership");
            h.assertValueEqual(PrototypeCombatAccess.scope(owner).orElseThrow(), scope, "one member leaving erased owner's arena");
            for (int i = 1; i < 4; i++)
                h.assertValueEqual(PrototypeCombatAccess.scope(members.get(i)).orElseThrow(), scope, "one member leaving erased another member");
            for (UUID id : ids) h.assertTrue(!fixture.removed(id), "member leave discarded shared targets");
            h.assertValueEqual(fixture.command(members.get(4), "join " + owner.getGameProfile().getName()), 1, "freed party slot did not become available");
            h.assertValueEqual(fixture.command(owner, "end"), 1, "owner end command");
            h.assertTrue(PrototypeCombatAccess.scope(owner).filter(scope::equals).isEmpty(), "owner end retained owner membership");
            for (var member : members) h.assertTrue(PrototypeCombatAccess.scope(member).filter(scope::equals).isEmpty(), "owner end retained a member's practice scope");
            for (UUID id : ids) h.assertTrue(fixture.removed(id), "owner end left shared targets alive");
        }
        h.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_prototype_catalog", template = "empty", batch = "prototype_practice_cleanup", timeoutTicks = 100)
    public static void registeredTargetsSuppressDropsAndExperienceAndStopCleansRuntime(GameTestHelper h) {
        try (var fixture = new Fixture(h)) {
            var owner = fixture.connect(0);
            h.assertValueEqual(fixture.command(owner, "arena"), 1, "cleanup arena creation");
            var ids = List.copyOf(PrototypeCombatAccess.targets(owner));
            var target = fixture.target(ids.getFirst());
            var scope = PrototypeCombatAccess.scope(owner).orElseThrow();
            var dropped = new ItemEntity(h.getLevel(), target.getX(), target.getY(), target.getZ(), new ItemStack(Items.DIAMOND));
            var drops = new LivingDropsEvent(target, owner.damageSources().playerAttack(owner), new ArrayList<>(List.of(dropped)), true);
            PrototypeCommands.drops(drops);
            h.assertTrue(drops.isCanceled(), "registered practice target could drop farmable items");
            var xp = new LivingExperienceDropEvent(target, owner, 50);
            PrototypeCommands.xp(xp);
            h.assertValueEqual(xp.getDroppedExperience(), 0, "registered practice target could drop farmable XP");
            var unrelated = EntityType.HUSK.create(h.getLevel());
            if (unrelated == null) throw new IllegalStateException("unrelated husk fixture");
            var ordinaryDrops = new LivingDropsEvent(unrelated, owner.damageSources().playerAttack(owner), new ArrayList<>(), true);
            var ordinaryXp = new LivingExperienceDropEvent(unrelated, owner, 17);
            PrototypeCommands.drops(ordinaryDrops);
            PrototypeCommands.xp(ordinaryXp);
            h.assertTrue(!ordinaryDrops.isCanceled() && ordinaryXp.getDroppedExperience() == 17, "practice suppression affected unrelated mobs");
            h.assertValueEqual(fixture.command(owner, "attack melee 40"), 1, "cleanup scheduled attack");
            var server = owner.server;
            // Call only these modules' handlers; posting global stop events would stop unrelated test services.
            PrototypeCommands.stopping(new ServerStoppingEvent(server));
            for (UUID id : ids) h.assertTrue(fixture.removed(id), "normal stop did not discard a persistent practice target");
            PrototypeCommands.stopped(new ServerStoppedEvent(server));
            PrototypeCombatAccess.stopped(new ServerStoppedEvent(server));
            h.assertTrue(!runtimeMap(PrototypeCommands.class, "ARENAS").containsKey(server), "stopped retained arena owner objects");
            h.assertTrue(!runtimeMap(PrototypeCommands.class, "SCHEDULED").containsKey(server), "stopped retained pending player attacks");
            h.assertTrue(!runtimeMap(PrototypeCombatAccess.class, "TRIALS").containsKey(server), "stopped retained encounter memberships");
            h.assertTrue(runtimeMap(PrototypeCombatAccess.class, "ATTACKS").isEmpty(), "stopped retained per-hit permissions");
            h.assertTrue(!PrototypeCombatAccess.valid(owner,scope), "stopped trial remained usable");
            dropped.discard();
            unrelated.discard();
        }
        h.succeed();
    }

    private static List<?> queueFor(ServerPlayer player) {
        Object value = runtimeMap(PrototypeCommands.class, "SCHEDULED").get(player.server);
        return value instanceof List<?> queue ? queue : List.of();
    }

    private static Map<?, ?> runtimeMap(Class<?> type, String fieldName) {
        try { Field field = type.getDeclaredField(fieldName); field.setAccessible(true); return (Map<?, ?>) field.get(null); }
        catch (ReflectiveOperationException invalid) { throw new IllegalStateException(invalid); }
    }

    static final class Fixture implements AutoCloseable {
        final GameTestHelper h;
        final BlockPos base;
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        final List<ServerPlayer> players = new ArrayList<>();
        final List<EmbeddedChannel> channels = new ArrayList<>();
        boolean closed;

        Fixture(GameTestHelper h) {
            this.h = h;
            base = h.absolutePos(new BlockPos(1, 2, 1));
            // Fixed test floor/corridor only. The actual command under test must not alter these blocks.
            for (int x = -1; x <= 3; x++) for (int z = -1; z <= 11; z++) for (int y = -1; y <= 3; y++) {
                BlockPos pos = base.offset(x, y, z);
                h.getLevel().getChunkAt(pos);
                blocks.put(pos, h.getLevel().getBlockState(pos));
                h.getLevel().setBlockAndUpdate(pos, y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }

        ServerPlayer connect(int index) {
            String name = "Prac" + index + UUID.randomUUID().toString().substring(0, 8);
            var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(UUID.randomUUID(), name), false);
            var player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channels.add(new EmbeddedChannel(connection));
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
            player.server.getPlayerList().placeNewPlayer(connection, player, cookie);
            // Embedded test players do not wait through the normal login grace period.
            // Remove it only in this fixture so the scheduled incoming-damage assertion is meaningful.
            try {
                Field field = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
                field.setAccessible(true);
                field.setInt(player, 0);
            } catch (ReflectiveOperationException invalid) { throw new IllegalStateException("practice fixture spawn grace", invalid); }
            player.setGameMode(GameType.SURVIVAL);
            player.moveTo(base.getX() + .5 + Math.min(index, 2), base.getY(), base.getZ() + .5, 0, 0);
            player.setNoGravity(true);
            player.setOnGround(true);
            player.setHealth(20);
            players.add(player);
            return player;
        }

        int command(ServerPlayer player, String command) {
            try { return player.server.getCommands().getDispatcher().execute("mythprototype " + command, player.createCommandSourceStack().withPermission(2)); }
            catch (CommandSyntaxException invalid) { throw new IllegalStateException("prototype command fixture: " + command, invalid); }
        }

        boolean ordinaryCanCreate(ServerPlayer player) {
            try { return player.server.getCommands().getDispatcher().execute("mythprototype arena", player.createCommandSourceStack().withPermission(0)) != 0; }
            catch (CommandSyntaxException expected) { return false; }
        }

        LivingEntity target(UUID id) {
            var entity = h.getLevel().getEntity(id);
            if (!(entity instanceof LivingEntity target)) throw new IllegalStateException("missing practice target " + id);
            return target;
        }

        boolean removed(UUID id) { var entity = h.getLevel().getEntity(id); return entity == null || entity.isRemoved(); }

        @Override public void close() {
            if (closed) return;
            closed = true;
            for (var player : players) {
                command(player, "end");
                player.server.getPlayerList().remove(player);
            }
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
            blocks.forEach((pos, state) -> h.getLevel().setBlockAndUpdate(pos, state));
        }
    }
}
