package com.sande.mythictrpg.godavatar.activity;

import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Game-owned authored facilities, hard exclusions and activity history. No relationship or lore truth. */
public final class NpcActivityWorldState extends SavedData {
    private static final Factory<NpcActivityWorldState> FACTORY = new Factory<>(NpcActivityWorldState::new, NpcActivityWorldState::load);
    public record Place(String name, ResourceLocation dimension, BlockPos pos, Set<String> tags) {
        public Place { pos = pos.immutable(); tags = Set.copyOf(tags); }
    }
    public record Exclusion(ResourceLocation dimension, BlockPos center, int radius) {
        public Exclusion { Objects.requireNonNull(dimension); center = center.immutable(); }
    }
    private final Map<String, Place> places = new LinkedHashMap<>();
    private final Map<String, Exclusion> exclusions = new LinkedHashMap<>();
    private final Map<UUID, List<String>> history = new LinkedHashMap<>();
    private final Map<UUID, CompoundTag> suspended = new LinkedHashMap<>();
    private CompoundTag rejected;
    private long revision;
    private final NpcActivityMemory activityMemory = new NpcActivityMemory(
            () -> ready() && MemoryFoundationSettings.mode() != MemoryFoundationSettings.Mode.OFF, this::setDirty);
    public static NpcActivityWorldState get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "mythictrpg_npc_activities"); }
    public boolean ready() { return rejected == null; }
    public long revision() { return revision; }
    public NpcActivityMemory activityMemory() { return activityMemory; }
    public Collection<Place> places() { return List.copyOf(places.values()); }
    public boolean allowed(ResourceLocation dimension, BlockPos pos) {
        return ready() && exclusions.values().stream().noneMatch(e -> e.dimension().equals(dimension)
                && Math.abs(pos.getX() - (long)e.center().getX()) <= e.radius()
                && Math.abs(pos.getY() - (long)e.center().getY()) <= e.radius()
                && Math.abs(pos.getZ() - (long)e.center().getZ()) <= e.radius());
    }
    public void place(Place place) {
        requireReady(); if (!place.name().matches("[a-z0-9_]{1,64}") || place.tags().isEmpty() || place.tags().size() > 16
                || place.tags().stream().anyMatch(t -> !t.matches("[a-z_]{1,40}")) || places.size() >= 1024 && !places.containsKey(place.name()))
            throw new IllegalArgumentException("Invalid/bounded facility");
        places.put(place.name(), place); changed();
    }
    public void removePlace(String name) { requireReady(); places.remove(name); changed(); }
    public void deny(String name, Exclusion value) {
        requireReady(); if (!name.matches("[a-z0-9_]{1,64}") || value.radius() < 0 || value.radius() > 64 || exclusions.size() >= 1024 && !exclusions.containsKey(name))
            throw new IllegalArgumentException("Invalid exclusion");
        exclusions.put(name, value); changed();
    }
    public void allow(String name) { requireReady(); exclusions.remove(name); changed(); }
    public List<String> history(UUID actor) { return history.getOrDefault(actor, List.of()); }
    public void remember(UUID actor, String value) {
        requireReady(); var list = new ArrayList<>(history(actor)); list.add(value.substring(0, Math.min(400, value.length())));
        if (!history.containsKey(actor) && history.size() >= 2048) history.remove(history.keySet().iterator().next());
        while (list.size() > 8) list.removeFirst(); history.put(actor, List.copyOf(list)); setDirty();
    }
    public void suspended(UUID actor, CompoundTag value) { requireReady(); if (value == null) suspended.remove(actor); else suspended.put(actor, value.copy()); setDirty(); }
    public Optional<CompoundTag> suspended(UUID actor) { return Optional.ofNullable(suspended.get(actor)).map(CompoundTag::copy); }
    private void changed() { revision++; setDirty(); }
    private void requireReady() { if (!ready()) throw new IllegalStateException("Activity saved data rejected; repair required"); }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!ready()) return rejected.copy();
        tag.putInt("version", 2); tag.putLong("revision", revision);
        var ps = new ListTag(); for (var p : places.values()) { var t = new CompoundTag(); t.putString("name", p.name()); t.putString("dim", p.dimension().toString()); t.putLong("pos", p.pos().asLong()); t.putString("tags", String.join(",", new TreeSet<>(p.tags()))); ps.add(t); } tag.put("places", ps);
        var es = new ListTag(); exclusions.forEach((name, e) -> { var t = new CompoundTag(); t.putString("name", name); t.putString("dim", e.dimension().toString()); t.putLong("pos", e.center().asLong()); t.putInt("radius", e.radius()); es.add(t); }); tag.put("exclusions", es);
        var hs = new ListTag(); history.forEach((actor, lines) -> { var t = new CompoundTag(); t.putUUID("actor", actor); var ls = new ListTag(); lines.forEach(s -> ls.add(StringTag.valueOf(s))); t.put("lines", ls); hs.add(t); }); tag.put("history", hs);
        var ss = new ListTag(); suspended.forEach((actor, value) -> { var t = new CompoundTag(); t.putUUID("actor", actor); t.put("plan", value.copy()); ss.add(t); }); tag.put("suspended", ss);
        tag.put("activityMemory", saveMemory());
        return tag;
    }
    private static NpcActivityWorldState load(CompoundTag tag, HolderLookup.Provider registries) {
        var s = new NpcActivityWorldState();
        try {
            if (!tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1 && tag.getInt("version") != 2 || !tag.contains("revision", Tag.TAG_LONG)
                    || tag.getLong("revision") < 0) throw new IllegalArgumentException("Saved activity version/revision");
            for (String list : List.of("places", "exclusions", "history", "suspended")) {
                if (!tag.contains(list, Tag.TAG_LIST) || tag.getList(list, Tag.TAG_COMPOUND).size() > 2048
                        || ((ListTag)tag.get(list)).getElementType() != Tag.TAG_COMPOUND && !((ListTag)tag.get(list)).isEmpty())
                    throw new IllegalArgumentException("Malformed activity saved list " + list);
            }
            for (var value : tag.getList("places", Tag.TAG_COMPOUND)) {
                var p = (CompoundTag)value;
                if (!p.contains("name", Tag.TAG_STRING) || !p.contains("dim", Tag.TAG_STRING) || !p.contains("pos", Tag.TAG_LONG) || !p.contains("tags", Tag.TAG_STRING))
                    throw new IllegalArgumentException("Malformed facility");
            }
            for (var value : tag.getList("exclusions", Tag.TAG_COMPOUND)) {
                var e = (CompoundTag)value;
                if (!e.contains("name", Tag.TAG_STRING) || !e.contains("dim", Tag.TAG_STRING) || !e.contains("pos", Tag.TAG_LONG) || !e.contains("radius", Tag.TAG_INT))
                    throw new IllegalArgumentException("Malformed administrator exclusion");
            }
            for (var value : tag.getList("places", Tag.TAG_COMPOUND)) {
                var t = (CompoundTag)value;
                if (s.places.containsKey(t.getString("name"))) throw new IllegalArgumentException("Duplicate saved facility");
                s.place(new Place(t.getString("name"), NpcActivityDefinitions.id(t.getString("dim")), BlockPos.of(t.getLong("pos")), Set.of(t.getString("tags").split(","))));
            }
            for (var value : tag.getList("exclusions", Tag.TAG_COMPOUND)) {
                var t = (CompoundTag)value;
                if (s.exclusions.containsKey(t.getString("name"))) throw new IllegalArgumentException("Duplicate saved exclusion");
                s.deny(t.getString("name"), new Exclusion(NpcActivityDefinitions.id(t.getString("dim")), BlockPos.of(t.getLong("pos")), t.getInt("radius")));
            }
            for (var value : tag.getList("history", Tag.TAG_COMPOUND)) {
                var t = (CompoundTag)value;
                if (!t.hasUUID("actor") || s.history.containsKey(t.getUUID("actor"))) throw new IllegalArgumentException("Invalid saved history actor");
                var lines = readStrings(t, "lines", 8);
                if (lines.stream().anyMatch(line -> line.length() > 400)) throw new IllegalArgumentException("Oversize saved activity history");
                s.history.put(t.getUUID("actor"), List.copyOf(lines));
            }
            for (var value : tag.getList("suspended", Tag.TAG_COMPOUND)) {
                var t = (CompoundTag)value;
                if (!t.hasUUID("actor") || s.suspended.containsKey(t.getUUID("actor"))) throw new IllegalArgumentException("Invalid suspended actor");
                require(t, "plan", Tag.TAG_COMPOUND); s.suspended.put(t.getUUID("actor"), t.getCompound("plan").copy());
            }
            if (tag.getInt("version") == 2) s.activityMemory.restore(loadMemory(tag));
            else if (tag.contains("activityMemory")) throw new IllegalArgumentException("Unexpected memory in legacy activity data");
            s.revision = tag.getLong("revision"); s.setDirty(false);
        } catch (RuntimeException invalid) { s.rejected = tag.copy(); }
        return s;
    }
    private ListTag saveMemory() {
        var result = new ListTag();
        for (var god : activityMemory.snapshot()) {
            var entry = new CompoundTag(); entry.putString("god", god.godId()); entry.putLong("revision", god.revision());
            var events = new ListTag();
            for (var event : god.events()) {
                var value = new CompoundTag(); value.putUUID("event", event.eventId()); value.putUUID("run", event.runId());
                value.putString("god", event.godId()); value.putUUID("actor", event.actorId()); value.putLong("time", event.gameTime());
                value.putString("activity", event.activityId()); value.putString("kind", event.kind()); value.putString("mode", event.mode());
                value.putString("phase", event.phase()); value.putString("detail", event.detail()); value.putString("speaker", event.speakerGodId());
                value.putString("speech", event.speech()); value.put("heardGods", strings(new TreeSet<>(event.heardGodIds())));
                value.put("heardPlayers", strings(event.heardPlayers().stream().map(UUID::toString).sorted().toList())); events.add(value);
            }
            entry.put("events", events); entry.putString("affectHint", god.affect().hint());
            entry.put("affectSources", strings(god.affect().sourceEventIds().stream().map(UUID::toString).toList())); result.add(entry);
        }
        return result;
    }
    private static List<NpcActivityMemory.StoredGod> loadMemory(CompoundTag root) {
        var result = new ArrayList<NpcActivityMemory.StoredGod>();
        for (Tag rawGod : list(root, "activityMemory", Tag.TAG_COMPOUND, NpcActivityMemory.MAX_GODS)) {
            var god = (CompoundTag) rawGod;
            keys(god, "god", "revision", "events", "affectHint", "affectSources");
            require(god, "god", Tag.TAG_STRING); require(god, "revision", Tag.TAG_LONG); require(god, "affectHint", Tag.TAG_STRING);
            var events = new ArrayList<NpcActivityMemory.Event>();
            for (Tag rawEvent : list(god, "events", Tag.TAG_COMPOUND, NpcActivityMemory.MAX_EVENTS_PER_GOD)) {
                var event = (CompoundTag) rawEvent;
                keys(event, "event", "run", "god", "actor", "time", "activity", "kind", "mode", "phase", "detail", "speaker", "speech", "heardGods", "heardPlayers");
                for (String key : List.of("god", "activity", "kind", "mode", "phase", "detail", "speaker", "speech")) require(event, key, Tag.TAG_STRING);
                require(event, "time", Tag.TAG_LONG);
                for (String key : List.of("event", "run", "actor")) if (!event.hasUUID(key)) throw new IllegalArgumentException("Invalid activity UUID");
                List<String> heardGods = readStrings(event, "heardGods", 16), heardPlayers = readStrings(event, "heardPlayers", 256);
                unique(heardGods); unique(heardPlayers);
                var players = new LinkedHashSet<UUID>(); heardPlayers.forEach(value -> players.add(uuid(value)));
                events.add(new NpcActivityMemory.Event(event.getUUID("event"), event.getUUID("run"), event.getString("god"), event.getUUID("actor"),
                        event.getLong("time"), event.getString("activity"), event.getString("kind"), event.getString("mode"), event.getString("phase"),
                        event.getString("detail"), event.getString("speaker"), event.getString("speech"), new LinkedHashSet<>(heardGods), players));
            }
            var refs = readStrings(god, "affectSources", NpcActivityMemory.MAX_EVENTS_PER_GOD).stream().map(NpcActivityWorldState::uuid).toList();
            result.add(new NpcActivityMemory.StoredGod(god.getString("god"), god.getLong("revision"), events,
                    new NpcActivityMemory.Affect(god.getString("affectHint"), refs)));
        }
        return result;
    }
    private static ListTag strings(Collection<String> values) {
        var list = new ListTag(); values.forEach(value -> list.add(StringTag.valueOf(value))); return list;
    }
    private static List<String> readStrings(CompoundTag tag, String key, int limit) {
        var result = new ArrayList<String>();
        for (Tag value : list(tag, key, Tag.TAG_STRING, limit)) result.add(value.getAsString());
        return result;
    }
    private static ListTag list(CompoundTag tag, String key, int type, int limit) {
        require(tag, key, Tag.TAG_LIST); var list = (ListTag) tag.get(key);
        if (list.size() > limit || !list.isEmpty() && list.getElementType() != type) throw new IllegalArgumentException("Invalid activity memory list");
        return list;
    }
    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Missing/invalid activity memory field " + key);
    }
    private static void keys(CompoundTag tag, String... expected) {
        if (!tag.getAllKeys().equals(Set.of(expected))) throw new IllegalArgumentException("Unknown/missing activity memory fields");
    }
    private static void unique(List<?> values) {
        if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("Duplicate activity audience member");
    }
    private static UUID uuid(String value) {
        var uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) throw new IllegalArgumentException("Noncanonical activity UUID");
        return uuid;
    }
}
