package com.sande.mythictrpg.godavatar.activity;

import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode;
import java.util.*;

/** Portable projection evidence only; no server, recording files or model requests. */
public final class ActivityRoomExperienceTest {
    private static int checks;
    public static void main(String[] args) {
        var world = UUID.randomUUID(); var id = UUID.randomUUID();
        String god = "mythictrpg:fortuna";
        var memory = new NpcActivityMemory.Memory(id, 120, "READ", "DECORATIVE", "COMPLETED", "", "");
        var ref = ActivityRoomExperience.reference(world, god, memory);
        var sources = new HashMap<UUID, NpcActivityMemory.Memory>(); sources.put(id, memory);
        java.util.function.BiFunction<String, UUID, Optional<NpcActivityMemory.Memory>> lookup = (owner, event) ->
                owner.equals(god) ? Optional.ofNullable(sources.get(event)) : Optional.empty();
        check(ActivityRoomExperience.current(world, ref, lookup), "surviving original projection accepted");
        var unrelated = UUID.randomUUID();
        sources.put(unrelated, new NpcActivityMemory.Memory(unrelated, 121, "REST", "DECORATIVE", "STARTED", "", ""));
        check(ActivityRoomExperience.current(world, ref, lookup), "unrelated later events do not revoke historical evidence");
        sources.put(id, new NpcActivityMemory.Memory(id, 120, "READ", "DECORATIVE", "COMPLETED", "", ""));
        check(ActivityRoomExperience.current(world, ref, lookup), "reload of identical original needs no live object identity");
        check(!ActivityRoomExperience.current(UUID.randomUUID(), ref, lookup), "foreign world denied");
        sources.put(id, new NpcActivityMemory.Memory(id, 120, "READ", "DECORATIVE", "FAILED", "", ""));
        check(!ActivityRoomExperience.current(world, ref, lookup), "changed result cannot use old reference");
        sources.remove(id);
        check(!ActivityRoomExperience.current(world, ref, lookup), "removed original cannot be resurrected by a reference");
        sources.put(id, memory);
        var bundle = ActivityRoomExperience.reference(world, god, sources.values());
        check(ActivityRoomExperience.current(world, bundle, lookup), "all visible experience and affect roots fit one evidence reference");
        sources.remove(unrelated);
        check(!ActivityRoomExperience.current(world, bundle, lookup), "removing any affect basis invalidates the whole dependency bundle");
        sources.put(unrelated, new NpcActivityMemory.Memory(unrelated, 121, "REST", "DECORATIVE", "STARTED", "", ""));
        check(!ActivityRoomExperience.current(world, ref, (owner, event) -> Optional.empty()), "new unauthorized audience receives no source");
        check(!ActivityRoomExperience.current(world, ref, (owner, event) -> Optional.of(sources.get(unrelated))), "lookup cannot substitute another event");
        check(!ActivityRoomExperience.current(world, new RoomEvidenceReference("UNKNOWN", ref.payload()), lookup), "unknown source kind denied");
        for (String malformed : List.of("{}", "null", "[]", ref.payload().replace(god, "not a God"),
                ref.payload().replace(id.toString(), UUID.randomUUID().toString()),
                ref.payload().substring(0, ref.payload().length() - 1) + ",\"extra\":1}"))
            check(!ActivityRoomExperience.current(world, new RoomEvidenceReference(ActivityRoomExperience.KIND, malformed), lookup), "malformed or foreign source denied");
        check(!ActivityRoomExperience.policyAllows(Mode.OFF, true, true), "global OFF blocks retrieval");
        check(!ActivityRoomExperience.policyAllows(null, true, true), "unavailable policy is not an enabled mode");
        check(!ActivityRoomExperience.policyAllows(Mode.PERSONAL, false, true), "ephemeral turn blocks retrieval");
        check(!ActivityRoomExperience.policyAllows(Mode.PERSONAL, true, false), "stale room turn blocks retrieval");
        check(ActivityRoomExperience.policyAllows(Mode.PERSONAL, true, true), "current recording room allowed");
        check(ActivityRoomExperience.policyAllows(Mode.RUMOR_TEST, true, true), "explicit recording test retains mode-scoped read");
        System.out.println("ActivityRoomExperienceTest: PASS (" + checks + " checks; no server or LLM)");
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
