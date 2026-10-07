package com.sande.mythictrpg.godavatar.activity;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.godavatar.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Actual physical runtime with a deliberately late fake planner; no network/model or authored gameplay balance. */
@GameTestHolder("mythictrpg_npc_activity")
@PrefixGameTestTemplate(false)
public final class NpcActivityRuntimeGameTests {
    private static final String BATCH = "npc_activity_runtime";
    private static Fixture active;
    private NpcActivityRuntimeGameTests() {}
    @GameTest(templateNamespace = "mythictrpg_npc_activity", template = "empty", timeoutTicks = 360, batch = BATCH)
    public static void actualActivityAuthorityAndLateDecisions(GameTestHelper helper) {
        active = new Fixture(helper); helper.onEachTick(active::tick);
    }
    @AfterBatch(batch = BATCH) public static void cleanup(ServerLevel level) { if (active != null) active.close(); }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;
        final NpcActivityRuntime runtime = NpcActivityRuntime.INSTANCE;
        final ResourceLocation god = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "demeter");
        final ResourceLocation observe = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "fixture_observe");
        final ResourceLocation eat = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "fixture_eat");
        final ResourceLocation dice = ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID, "fixture_dice");
        final List<GodActivityPlanner.Request> requests = new ArrayList<>();
        final List<LateFuture> futures = new ArrayList<>();
        final List<Runnable> restore = new ArrayList<>();
        GodAvatarEntity avatar;
        ServerPlayer player;
        NpcActivityDefinitions.Data definitions;
        BlockPos feet;
        long generation;
        int phase, quietTicks;
        boolean closed;
        Fixture(GameTestHelper helper) { h = helper; }
        void tick() {
            if (closed) return;
            try { advance(); }
            catch (Exception | AssertionError failure) { int failed = phase; close(); h.fail("Activity runtime phase " + failed + ": " + failure); }
        }
        void advance() throws Exception {
            if (phase == 0) {
                var level = h.getLevel();
                Field memoryMode = field(com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.class, "mode");
                Object previousMode = memoryMode.get(null); restore.add(() -> set(memoryMode, null, previousMode));
                memoryMode.set(null, com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.PERSONAL);
                feet = h.absolutePos(new BlockPos(2, 2, 2));
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                    level.setBlockAndUpdate(feet.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                    for (int y = 0; y < 4; y++) level.setBlockAndUpdate(feet.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
                var avatarDef = new GodAvatarDefinition(god, new GodAvatarDefinition.Appearance(0, 1),
                        new GodAvatarDefinition.Stats(20, .25, 2, 0, 16),
                        new GodAvatarDefinition.Movement(false, false, false, 0, 0, 0),
                        new GodAvatarDefinition.Combat(true, true, false, false), new GodAvatarDefinition.Placement(false, 0), 4);
                replace(GodAvatarDefinitionManager.INSTANCE, "definitions", Map.of(god, avatarDef));
                replace(GodAvatarDefinitionManager.INSTANCE, "generation", GodAvatarDefinitionManager.INSTANCE.generation() + 1);
                var def = new NpcActivityDefinition(observe, ActivityKind.OBSERVE, NpcActivityDefinition.Mode.DECORATIVE,
                        Set.of("idle"), 200, Map.of());
                var food = new NpcActivityDefinition(eat, ActivityKind.EAT, NpcActivityDefinition.Mode.REAL,
                        Set.of("idle"), 20, Map.of("item_id", "minecraft:apple"));
                var die = new NpcActivityDefinition(dice, ActivityKind.PLAY, NpcActivityDefinition.Mode.DECORATIVE,
                        Set.of("idle"), 200, Map.of("game", "dice"));
                definitions = new NpcActivityDefinitions.Data(Map.of(observe, def, eat, food, dice, die),
                        Map.of(god, new NpcActivityDefinitions.Policy(List.of(observe, eat, dice), false, 4, 100)));
                generation = NpcActivityDefinitions.INSTANCE.generation() + 1;
                replace(NpcActivityDefinitions.INSTANCE, "data", definitions);
                replace(NpcActivityDefinitions.INSTANCE, "generation", generation);
                Field provider = field(GodActivityPlanner.class, "provider"); Object previous = provider.get(null);
                restore.add(() -> set(provider, null, previous));
                GodActivityPlanner.install(request -> { requests.add(request); var future = new LateFuture(); futures.add(future); return future; });
                avatar = GodAvatarService.INSTANCE.spawn(level, god, Vec3.atBottomCenterOf(feet)).orElseThrow();
                player = h.makeMockServerPlayerInLevel(); player.setPos(avatar.position().add(0, 0, 2));
                require(runtime.request(avatar, observe), "stationary NPC cannot begin local observation");
                tickRuntime();
                require(NpcActivityRuntime.active(avatar) && runtime.state(avatar).contains("ACTIVE"), "local activity did not execute");
                require(experience().experiences().stream().anyMatch(e -> e.phase().equals("STARTED")), "actual start did not enter God memory");
                // Match ServerLevel.tickNonPassenger's pre-incremented periodic tick.
                avatar.tickCount = 20; avatar.tick();
                require(NpcActivityRuntime.active(avatar), "no movement policy interrupted stationary activity");

                // These snapshots exercise the game-internal offer API, not room creation/publication authority.
                var test = room(RecordingScope.TEST_EPHEMERAL);
                String readOnly = runtime.contextFor(player, god, test, true);
                require(runtime.contextCurrent(player, god, test, readOnly), "fresh read-only context not current");
                require(!runtime.choose(player, god, test.roomId(), test.revision(), "STOP"), "read-only offer allowed STOP");
                require(!runtime.choose(player, god, test.roomId(), test.revision(), "CONTINUE"), "read-only offer allowed CONTINUE");
                require(NpcActivityRuntime.active(avatar), "read-only test changed actual activity");
                var normal = room(RecordingScope.STANDARD);
                String current = runtime.contextFor(player, god, normal, false);
                require(runtime.contextCurrent(player, god, normal, current), "fresh writable context not current");
                require(!runtime.choose(player, god, UUID.randomUUID(), normal.revision(), "STOP"), "foreign room changed activity");
                require(!runtime.choose(player, god, normal.roomId(), normal.revision() + 1, "STOP"), "wrong revision changed activity");
                require(runtime.choose(player, god, normal.roomId(), normal.revision(), "STOP"), "authorized stop rejected");
                require(!runtime.choose(player, god, normal.roomId(), normal.revision(), "STOP"), "consumed offer replayed");
                runtime.endDialogue(test.roomId()); runtime.endDialogue(normal.roomId());
                require(!NpcActivityRuntime.active(avatar), "stop left activity active");
                require(experience().experiences().stream().anyMatch(e -> e.phase().equals("INTERRUPTED")), "actual interruption did not enter memory");
                require(runtime.request(avatar, dice), "cannot begin die fixture");
                tickRuntime();
                var firstRoll = NpcActivityWorldState.get(level.getServer()).activityMemory().view(god.toString(), Set.of(god.toString()), Set.of(), "주사위");
                var dieMemory = firstRoll.experiences().stream().filter(e -> e.kind().equals("PLAY") && e.phase().equals("SPEECH")).findFirst().orElseThrow();
                var pauseDice = room(RecordingScope.STANDARD);
                runtime.contextFor(player, god, pauseDice, false); runtime.endDialogue(pauseDice.roomId()); tickRuntime();
                var resumedRoll = NpcActivityWorldState.get(level.getServer()).activityMemory().view(god.toString(), Set.of(god.toString()), Set.of(), "주사위");
                require(resumedRoll.experiences().stream().filter(e -> e.kind().equals("PLAY") && e.phase().equals("SPEECH")).toList().equals(List.of(dieMemory)),
                        "dialogue resume rerolled or duplicated the actual die utterance");
                NpcActivityRuntime.interrupt(avatar, "FIXTURE_DIE_END");
                var world = NpcActivityWorldState.get(level.getServer());
                world.deny("fixture_activity_deny", new NpcActivityWorldState.Exclusion(level.dimension().location(), feet, 3));
                require(!runtime.request(avatar, observe), "administrator exclusion bypassed");
                world.allow("fixture_activity_deny");
                require(runtime.request(avatar, observe), "valid activity could not restart after permission restored");
                tickRuntime(); NpcActivityRuntime.interrupt(avatar, "FIXTURE_STOP");
                require(!NpcActivityRuntime.active(avatar), "explicit interruption left activity active");
                strictSchema();
                savedStateRoundtrip();
                require(runtime.request(avatar, observe), "cannot create activity to suspend");
                tickRuntime();
                NpcActivityRuntime.interrupt(avatar, "UNLOADED");
                require(!NpcActivityRuntime.active(avatar) && world.suspended(avatar.getUUID()).isPresent(), "unload did not persist suspended plan");
                require(!NpcActivityDefinitions.INSTANCE.policy(god).orElseThrow().autonomous(), "resume fixture accidentally enables autonomy");
                wake(); phase = 5;
            } else if (phase == 5) {
                if (!NpcActivityRuntime.active(avatar)) return;
                require(NpcActivityWorldState.get(h.getLevel().getServer()).suspended(avatar.getUUID()).isEmpty(), "resumed plan was not consumed");
                require(requests.isEmpty(), "resume called AI despite autonomy disabled");
                NpcActivityRuntime.interrupt(avatar, "FIXTURE_RESUME_FINISHED");
                avatar.activityInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE, 2));
                require(runtime.request(avatar, eat), "real consume activity refused owned material");
                for (int tick = 0; tick < 21; tick++) tickRuntime();
                require(!NpcActivityRuntime.active(avatar) && avatar.activityInventory().getItem(0).getCount() == 1,
                        "real activity did not consume exactly one item");
                require(experience().experiences().stream().anyMatch(e -> e.kind().equals("EAT") && e.phase().equals("COMPLETED")),
                        "actual work completion did not enter memory");
                tickRuntime(); tickRuntime();
                require(avatar.activityInventory().getItem(0).getCount() == 1, "duplicate same-tick runtime execution consumed again");
                set(field(NpcActivityDefinitions.class, "data"), NpcActivityDefinitions.INSTANCE,
                        new NpcActivityDefinitions.Data(definitions.activities(), Map.of(god,
                                new NpcActivityDefinitions.Policy(List.of(observe, eat), true, 4, 100))));
                set(field(NpcActivityDefinitions.class, "generation"), NpcActivityDefinitions.INSTANCE, ++generation);
                wake(); phase = 1;
            } else if (phase == 1) {
                if (requests.isEmpty()) return;
                require(requests.getFirst().godId().equals(god.toString()), "planner actor mismatch");
                require(!requests.getFirst().candidates().isEmpty(), "planner received no actual opportunities");
                NpcActivityRuntime.interrupt(avatar, "FIXTURE_PENDING_INTERRUPT");
                var request = requests.getFirst();
                require(request.recentActivities().isEmpty() && !request.experience().experiences().isEmpty(), "unproven old strings supplied or typed memory missing");
                require(futures.getFirst().complete(new GodActivityPlanner.Decision(request.requestId(), request.candidates().getFirst().choiceId(), List.of(),
                        new NpcActivityMemory.Affect("late interpretation must not apply", List.of(request.experience().experiences().getFirst().eventId())))),
                        "late-provider fixture should ignore cancellation");
                quietTicks = 0; phase = 2;
            } else if (phase == 2) {
                require(!NpcActivityRuntime.active(avatar), "late interrupted result applied");
                require(experience().affect().hint().isEmpty(), "late interrupted affect applied");
                if (++quietTicks < 3) return;
                wake(); phase = 3;
            } else if (phase == 3) {
                if (requests.size() < 2) return;
                var request = requests.get(1);
                futures.get(1).complete(new GodActivityPlanner.Decision(request.requestId(), "NONE", List.of(),
                        new NpcActivityMemory.Affect("past experience considered; not a fixed mood", List.of(request.experience().experiences().getFirst().eventId()))));
                phase = 6;
            } else if (phase == 6) {
                if (experience().affect().hint().isEmpty()) return;
                require(!NpcActivityRuntime.active(avatar), "NONE affect invented an action");
                require(experience().affect().hint().equals("past experience considered; not a fixed mood"), "valid affect not retained");
                wake(); phase = 7;
            } else if (phase == 7) {
                if (requests.size() < 3) return;
                avatar.invulnerableTime = 0; avatar.hurt(h.getLevel().damageSources().generic(), 10000);
                require(!avatar.isAlive(), "lethal external damage did not occur");
                var request = requests.get(2);
                futures.get(2).complete(new GodActivityPlanner.Decision(request.requestId(), request.candidates().getFirst().choiceId(), List.of(),
                        new NpcActivityMemory.Affect("dead actor result must not apply", List.of(request.experience().experiences().getFirst().eventId()))));
                quietTicks = 0; phase = 4;
            } else {
                require(!NpcActivityRuntime.active(avatar), "late result applied to dead NPC");
                require(!experience().affect().hint().equals("dead actor result must not apply"), "dead actor changed affect");
                if (++quietTicks < 3) return;
                close(); h.succeed();
            }
        }
        void strictSchema() {
            var json = JsonParser.parseString("{\"formatVersion\":1,\"kind\":\"OBSERVE\",\"mode\":\"DECORATIVE\",\"siteTags\":[\"idle\"],\"durationTicks\":200,\"parameters\":{}}").getAsJsonObject();
            require(NpcActivityDefinitions.decode(observe, json).kind() == ActivityKind.OBSERVE, "valid schema rejected");
            json.addProperty("hidden_override", true);
            boolean rejected = false;
            try { NpcActivityDefinitions.decode(observe, json); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "unknown definition fields admitted");
        }
        void savedStateRoundtrip() throws Exception {
            var original = new NpcActivityWorldState();
            var dim = h.getLevel().dimension().location();
            original.place(new NpcActivityWorldState.Place("fixture_library", dim, feet, Set.of("books")));
            original.deny("fixture_locked", new NpcActivityWorldState.Exclusion(dim, feet.east(4), 1));
            original.remember(avatar.getUUID(), "Observed real fixture activity");
            CompoundTag plan = new CompoundTag(); plan.putString("definition", observe.toString()); plan.putInt("remaining", 91);
            original.suspended(avatar.getUUID(), plan);
            var saved = original.save(new CompoundTag(), h.getLevel().registryAccess());
            var load = NpcActivityWorldState.class.getDeclaredMethod("load", CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
            load.setAccessible(true);
            var restored = (NpcActivityWorldState)load.invoke(null, saved, h.getLevel().registryAccess());
            require(restored.ready() && restored.revision() == original.revision(), "saved activity readiness/revision roundtrip");
            require(restored.places().equals(original.places()), "facility definition did not roundtrip");
            require(!restored.allowed(dim, feet.east(4)) && restored.allowed(dim, feet), "exclusion did not roundtrip");
            require(restored.history(avatar.getUUID()).equals(original.history(avatar.getUUID())), "activity history did not roundtrip");
            require(restored.suspended(avatar.getUUID()).orElseThrow().equals(plan), "suspended plan did not roundtrip");
            var unsupported = saved.copy(); unsupported.putInt("version", 999); unsupported.putString("future_payload", "retain exactly");
            var rejected = (NpcActivityWorldState)load.invoke(null, unsupported, h.getLevel().registryAccess());
            require(!rejected.ready() && rejected.save(new CompoundTag(), h.getLevel().registryAccess()).equals(unsupported),
                    "unknown saved version was accepted or overwritten");
        }
        ConversationRoomSnapshot room(RecordingScope scope) {
            return new ConversationRoomSnapshot(UUID.randomUUID(), "A", RoomType.PRIVATE, 1,
                    Set.of(player.getUUID()), Set.of(god.toString()), "", scope);
        }
        NpcActivityMemory.View experience() {
            return NpcActivityWorldState.get(h.getLevel().getServer()).activityMemory().view(god.toString(), Set.of(god.toString()), Set.of(), "EAT");
        }
        void tickRuntime() { runtime.tick(new ServerTickEvent.Post(() -> true, h.getLevel().getServer())); }
        @SuppressWarnings("unchecked") void wake() throws Exception {
            ((Map<UUID, Long>)field(NpcActivityRuntime.class, "nextDecision").get(runtime)).put(avatar.getUUID(), 0L);
        }
        void replace(Object target, String name, Object value) throws Exception {
            Field f = field(target.getClass(), name); Object old = f.get(target); restore.add(() -> set(f, target, old)); f.set(target, value);
        }
        public void close() {
            if (closed) return; closed = true;
            if (avatar != null) { NpcActivityRuntime.interrupt(avatar, "FIXTURE_END"); GodAvatarService.INSTANCE.despawn(avatar); }
            NpcActivityWorldState.get(h.getLevel().getServer()).allow("fixture_activity_deny");
            Collections.reverse(restore); restore.forEach(Runnable::run); active = null;
        }
    }
    private static final class LateFuture extends CompletableFuture<GodActivityPlanner.Decision> {
        @Override public boolean cancel(boolean mayInterrupt) { return false; }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static void set(Field field, Object target, Object value) {
        try { field.set(target, value); } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
