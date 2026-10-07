package com.sande.mythictrpg.godavatar.activity;

import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.function.Consumer;

/** Real SavedData/NBT persistence, without an LLM request or a fabricated player/room identity. */
@GameTestHolder("mythictrpg_npc_activity_memory")
@PrefixGameTestTemplate(false)
public final class NpcActivityMemoryGameTests {
    private static final String GOD = "mythictrpg:demeter";
    private NpcActivityMemoryGameTests() {}

    @GameTest(templateNamespace = "mythictrpg_npc_activity_memory", template = "empty", timeoutTicks = 40)
    public static void sourceAndAffectSurviveOffSaveLoad(GameTestHelper helper) throws Exception {
        var mode = MemoryFoundationSettings.class.getDeclaredField("mode"); mode.setAccessible(true);
        Object previous = mode.get(null);
        try {
            mode.set(null, MemoryFoundationSettings.Mode.PERSONAL);
            var state = new NpcActivityWorldState(); var event = event("COMPLETED");
            require(state.activityMemory().record(event), "event not recorded");
            require(state.isDirty() && state.revision() == 0, "memory did not dirty data or changed physical-access revision");
            state.setDirty(false);
            require(!state.activityMemory().record(event) && !state.isDirty(), "duplicate event dirtied save");
            var view = state.activityMemory().view(GOD, Set.of(GOD), Set.of(), "READ");
            require(state.activityMemory().applyAffect(view, new NpcActivityMemory.Affect("차분했던 해석", List.of(event.eventId())), Set.of(GOD), Set.of()), "affect rejected");
            state.remember(event.actorId(), "legacy operational history");
            var plan = new CompoundTag(); plan.putString("choice", "resume existing activity"); state.suspended(event.actorId(), plan);
            var saved = state.save(new CompoundTag(), helper.getLevel().registryAccess());
            require(saved.getInt("version") == 2, "activity schema not v2");
            mode.set(null, MemoryFoundationSettings.Mode.OFF);
            var restored = load(saved, helper);
            require(restored.ready() && !restored.isDirty(), "valid OFF load rejected or dirtied");
            require(restored.activityMemory().view(GOD, Set.of(GOD), Set.of(), "").equals(NpcActivityMemory.View.empty(GOD)), "OFF exposed saved memory");
            require(!restored.activityMemory().record(event("STARTED")), "OFF wrote new event");
            require(restored.history(event.actorId()).equals(List.of("legacy operational history")) && restored.suspended(event.actorId()).orElseThrow().equals(plan), "OFF removed operational state");
            require(restored.save(new CompoundTag(), helper.getLevel().registryAccess()).equals(saved), "OFF resave discarded original memory or affect");
            mode.set(null, MemoryFoundationSettings.Mode.PERSONAL);
            var visible = restored.activityMemory().view(GOD, Set.of(GOD), Set.of(), "");
            require(visible.experiences().size() == 1 && visible.affect().sourceEventIds().equals(List.of(event.eventId())), "reenabling lost persisted memory/affect");
            helper.succeed();
        } finally { mode.set(null, previous); }
    }

    @GameTest(templateNamespace = "mythictrpg_npc_activity_memory", template = "empty", timeoutTicks = 40)
    public static void legacyHistoryIsNotPromoted(GameTestHelper helper) throws Exception {
        var state = new NpcActivityWorldState(); UUID actor = UUID.randomUUID();
        state.remember(actor, "COMPLETED READ: private book title was not typed provenance");
        var plan = new CompoundTag(); plan.putString("target", "legacy plan"); state.suspended(actor, plan);
        var legacy = state.save(new CompoundTag(), helper.getLevel().registryAccess()); legacy.putInt("version", 1); legacy.remove("activityMemory");
        var restored = load(legacy, helper);
        require(restored.ready() && restored.history(actor).equals(state.history(actor)), "legacy operational history lost");
        require(restored.suspended(actor).orElseThrow().equals(plan), "legacy suspended plan lost");
        var migrated = restored.save(new CompoundTag(), helper.getLevel().registryAccess());
        require(migrated.getInt("version") == 2 && migrated.getList("activityMemory", Tag.TAG_COMPOUND).isEmpty(), "untyped history promoted into authoritative experience");
        helper.succeed();
    }

    @GameTest(templateNamespace = "mythictrpg_npc_activity_memory", template = "empty", timeoutTicks = 40)
    public static void malformedMemoryPreservedFailClosed(GameTestHelper helper) throws Exception {
        var mode = MemoryFoundationSettings.class.getDeclaredField("mode"); mode.setAccessible(true);
        Object previous = mode.get(null);
        try {
            mode.set(null, MemoryFoundationSettings.Mode.PERSONAL);
            var state = new NpcActivityWorldState(); state.activityMemory().record(event("COMPLETED"));
            var saved = state.save(new CompoundTag(), helper.getLevel().registryAccess());
            List<Consumer<CompoundTag>> corruption = List.of(
                    tag -> savedEvent(tag).putString("kind", "ASSASSINATE"),
                    tag -> savedEvent(tag).putString("time", "1"),
                    tag -> savedEvent(tag).remove("run"),
                    tag -> savedEvent(tag).putString("mode", "UNKNOWN"),
                    tag -> savedEvent(tag).putString("phase", "PROPOSED_AS_DONE"),
                    tag -> savedEvent(tag).putString("god", "mythictrpg:fortuna"),
                    tag -> savedEvent(tag).putString("unknown", "must not silently discard"),
                    tag -> tag.getList("activityMemory", Tag.TAG_COMPOUND).add(savedGod(tag).copy()),
                    tag -> savedGod(tag).getList("events", Tag.TAG_COMPOUND).add(savedEvent(tag).copy()),
                    tag -> savedGod(tag).putLong("revision", -1),
                    tag -> { savedGod(tag).putString("affectHint", "사라진 출처"); var refs = new ListTag(); refs.add(StringTag.valueOf(UUID.randomUUID().toString())); savedGod(tag).put("affectSources", refs); },
                    tag -> { var heard = new ListTag(); heard.add(StringTag.valueOf("not-a-uuid")); savedEvent(tag).put("heardPlayers", heard); },
                    tag -> { var heard = new ListTag(); heard.add(StringTag.valueOf(GOD)); heard.add(StringTag.valueOf(GOD)); savedEvent(tag).put("heardGods", heard); },
                    tag -> { var events = savedGod(tag).getList("events", Tag.TAG_COMPOUND); var first = savedEvent(tag).copy(); while (events.size() < 65) events.add(first.copy()); },
                    tag -> tag.remove("activityMemory"));
            int index = 0;
            for (var change : corruption) {
                var malformed = saved.copy(); change.accept(malformed); var rejected = load(malformed, helper);
                require(!rejected.ready(), "malformed activity data accepted: " + index);
                require(rejected.activityMemory().view(GOD, Set.of(GOD), Set.of(), "").experiences().isEmpty(), "rejected data leaked memory: " + index);
                require(!rejected.activityMemory().record(event("STARTED")), "rejected data accepted new memory: " + index);
                require(rejected.save(new CompoundTag(), helper.getLevel().registryAccess()).equals(malformed), "rejected raw data was rewritten: " + index); index++;
            }
            helper.succeed();
        } finally { mode.set(null, previous); }
    }
    private static CompoundTag savedGod(CompoundTag tag) { return tag.getList("activityMemory", Tag.TAG_COMPOUND).getCompound(0); }
    private static CompoundTag savedEvent(CompoundTag tag) { return savedGod(tag).getList("events", Tag.TAG_COMPOUND).getCompound(0); }
    private static NpcActivityWorldState load(CompoundTag tag, GameTestHelper helper) throws Exception {
        var load = NpcActivityWorldState.class.getDeclaredMethod("load", CompoundTag.class, HolderLookup.Provider.class); load.setAccessible(true);
        return (NpcActivityWorldState) load.invoke(null, tag, helper.getLevel().registryAccess());
    }
    private static NpcActivityMemory.Event event(String phase) {
        return new NpcActivityMemory.Event(UUID.randomUUID(), UUID.randomUUID(), GOD, UUID.randomUUID(), 1,
                "mythictrpg:read", "READ", "REAL", phase, "private exact place and book contents", "", "", Set.of(), Set.of());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
