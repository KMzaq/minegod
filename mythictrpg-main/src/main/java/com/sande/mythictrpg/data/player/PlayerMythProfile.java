package com.sande.mythictrpg.data.player;

import com.sande.mythictrpg.gameplay.metric.GameplayMetricKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

public final class PlayerMythProfile {
    public static final int CURRENT_DATA_VERSION = 4;
    public static final int MAX_CUSTOM_GAMEPLAY_COUNTERS = 256;
    public static final int MIN_AFFINITY = -1_000;
    public static final int MAX_AFFINITY = 1_000;

    private final int dataVersion;
    private final ParticipationStatus participationStatus;
    private final Map<ResourceLocation, Integer> affinities;
    private final Set<ResourceLocation> encounteredGods;
    private final Set<ResourceLocation> identifiedGods;
    private final Set<ResourceLocation> obtainedItems;
    private final Set<ResourceLocation> unlockedTitles;
    private final Map<GameplayMetricKey, Long> customGameplayCounters;

    private PlayerMythProfile(int dataVersion, ParticipationStatus participationStatus,
            Map<ResourceLocation, Integer> affinities, Set<ResourceLocation> encounteredGods,
            Set<ResourceLocation> identifiedGods, Set<ResourceLocation> obtainedItems,
            Set<ResourceLocation> unlockedTitles,
            Map<GameplayMetricKey, Long> customGameplayCounters) {
        this.dataVersion = dataVersion;
        this.participationStatus = participationStatus;
        this.affinities = Map.copyOf(affinities);
        this.encounteredGods = Set.copyOf(encounteredGods);
        this.identifiedGods = Set.copyOf(identifiedGods);
        this.obtainedItems = Set.copyOf(obtainedItems);
        this.unlockedTitles = Set.copyOf(unlockedTitles);
        this.customGameplayCounters = Map.copyOf(customGameplayCounters);
    }

    public static PlayerMythProfile createActive() {
        return new PlayerMythProfile(CURRENT_DATA_VERSION, ParticipationStatus.ACTIVE,
                Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
    }

    static PlayerMythProfile load(CompoundTag tag) {
        int version = requireVersion(tag);
        if (version < 1 || version > CURRENT_DATA_VERSION) {
            throw unsupportedVersion(version);
        }
        if (!tag.contains("participationStatus", Tag.TAG_STRING)) {
            throw new IllegalArgumentException("Missing participationStatus");
        }
        return new PlayerMythProfile(
                CURRENT_DATA_VERSION,
                ParticipationStatus.parse(tag.getString("participationStatus")),
                readAffinities(tag),
                readIdSet(tag, "encounteredGods"),
                readIdSet(tag, "identifiedGods"),
                version == 1 ? Set.of() : readIdSet(tag, "obtainedItems"),
                version < 4 ? Set.of() : readIdSet(tag, "unlockedTitles"),
                version < 3 ? Map.of() : readCustomGameplayCounters(tag)
        );
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", dataVersion);
        tag.putString("participationStatus", participationStatus.name());

        ListTag affinityList = new ListTag();
        affinities.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag affinity = new CompoundTag();
            affinity.putString("god", entry.getKey().toString());
            affinity.putInt("value", entry.getValue());
            affinityList.add(affinity);
        });
        tag.put("affinities", affinityList);
        tag.put("encounteredGods", writeIdSet(encounteredGods));
        tag.put("identifiedGods", writeIdSet(identifiedGods));
        tag.put("obtainedItems", writeIdSet(obtainedItems));
        tag.put("unlockedTitles", writeIdSet(unlockedTitles));
        tag.put("customGameplayCounters", writeCustomGameplayCounters(customGameplayCounters));
        return tag;
    }

    PlayerMythProfile withAffinity(ResourceLocation godId, int value) {
        requireAffinity(value, godId);
        if (affinities.getOrDefault(godId, 0) == value) {
            return this;
        }
        Map<ResourceLocation, Integer> changed = new LinkedHashMap<>(affinities);
        changed.put(godId, value);
        return new PlayerMythProfile(CURRENT_DATA_VERSION, participationStatus, changed,
                encounteredGods, identifiedGods, obtainedItems, unlockedTitles, customGameplayCounters);
    }

    PlayerMythProfile withParticipationStatus(ParticipationStatus status) {
        if (participationStatus == status) {
            return this;
        }
        return new PlayerMythProfile(CURRENT_DATA_VERSION, status, affinities,
                encounteredGods, identifiedGods, obtainedItems, unlockedTitles, customGameplayCounters);
    }

    PlayerMythProfile withEncounteredGod(ResourceLocation godId) {
        return withEncounteredGods(Set.of(godId));
    }

    PlayerMythProfile withEncounteredGods(Collection<ResourceLocation> godIds) {
        if (encounteredGods.containsAll(godIds)) {
            return this;
        }
        Set<ResourceLocation> changed = new LinkedHashSet<>(encounteredGods);
        changed.addAll(godIds);
        return new PlayerMythProfile(CURRENT_DATA_VERSION, participationStatus, affinities,
                changed, identifiedGods, obtainedItems, unlockedTitles, customGameplayCounters);
    }

    PlayerMythProfile withIdentifiedGod(ResourceLocation godId) {
        if (identifiedGods.contains(godId)) {
            return this;
        }
        Set<ResourceLocation> changed = new LinkedHashSet<>(identifiedGods);
        changed.add(godId);
        return new PlayerMythProfile(CURRENT_DATA_VERSION, participationStatus, affinities,
                encounteredGods, changed, obtainedItems, unlockedTitles, customGameplayCounters);
    }

    PlayerMythProfile withObtainedItem(ResourceLocation itemId) {
        if (obtainedItems.contains(itemId)) {
            return this;
        }
        Set<ResourceLocation> changed = new LinkedHashSet<>(obtainedItems);
        changed.add(itemId);
        return new PlayerMythProfile(CURRENT_DATA_VERSION, participationStatus, affinities,
                encounteredGods, identifiedGods, changed, unlockedTitles, customGameplayCounters);
    }

    PlayerMythProfile withUnlockedTitle(ResourceLocation titleId) {
        if (unlockedTitles.contains(titleId)) {
            return this;
        }
        Set<ResourceLocation> changed = new LinkedHashSet<>(unlockedTitles);
        changed.add(titleId);
        return new PlayerMythProfile(CURRENT_DATA_VERSION, participationStatus, affinities,
                encounteredGods, identifiedGods, obtainedItems, changed, customGameplayCounters);
    }

    PlayerMythProfile withCustomGameplayCounters(Map<GameplayMetricKey, Long> counters) {
        if (customGameplayCounters.equals(counters)) {
            return this;
        }
        return new PlayerMythProfile(CURRENT_DATA_VERSION, participationStatus, affinities,
                encounteredGods, identifiedGods, obtainedItems, unlockedTitles, counters);
    }

    public int dataVersion() {
        return dataVersion;
    }

    public ParticipationStatus participationStatus() {
        return participationStatus;
    }

    public Map<ResourceLocation, Integer> affinities() {
        return affinities;
    }

    public Set<ResourceLocation> encounteredGods() {
        return encounteredGods;
    }

    public Set<ResourceLocation> identifiedGods() {
        return identifiedGods;
    }

    public Set<ResourceLocation> obtainedItems() {
        return obtainedItems;
    }

    public Set<ResourceLocation> unlockedTitles() {
        return unlockedTitles;
    }

    public Map<GameplayMetricKey, Long> customGameplayCounters() {
        return customGameplayCounters;
    }

    public long customGameplayCounter(GameplayMetricKey key) {
        return customGameplayCounters.getOrDefault(key, 0L);
    }

    private static Map<ResourceLocation, Integer> readAffinities(CompoundTag tag) {
        if (!tag.contains("affinities", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Missing affinities list");
        }
        ListTag list = tag.getList("affinities", Tag.TAG_COMPOUND);
        Map<ResourceLocation, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < list.size(); index++) {
            CompoundTag value = list.getCompound(index);
            ResourceLocation godId = parseId(value.getString("god"), "affinities");
            if (!value.contains("value", Tag.TAG_ANY_NUMERIC)) {
                throw new IllegalArgumentException("Missing affinity value for " + godId);
            }
            int affinity = Math.max(MIN_AFFINITY, Math.min(MAX_AFFINITY, value.getInt("value")));
            if (result.putIfAbsent(godId, affinity) != null) {
                throw new IllegalArgumentException("Duplicate affinity for " + godId);
            }
        }
        return result;
    }

    private static Set<ResourceLocation> readIdSet(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Missing list '" + key + "'");
        }
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (int index = 0; index < list.size(); index++) {
            ResourceLocation id = parseId(list.getString(index), key);
            if (!result.add(id)) {
                throw new IllegalArgumentException("Duplicate ID in '" + key + "': " + id);
            }
        }
        return result;
    }

    private static ListTag writeIdSet(Set<ResourceLocation> values) {
        ListTag list = new ListTag();
        values.stream().sorted().map(ResourceLocation::toString).map(StringTag::valueOf).forEach(list::add);
        return list;
    }

    private static Map<GameplayMetricKey, Long> readCustomGameplayCounters(CompoundTag tag) {
        if (!tag.contains("customGameplayCounters", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Missing customGameplayCounters list");
        }
        ListTag list = tag.getList("customGameplayCounters", Tag.TAG_COMPOUND);
        if (list.size() > MAX_CUSTOM_GAMEPLAY_COUNTERS) {
            throw new IllegalArgumentException("Too many custom gameplay counters: " + list.size()
                    + " (maximum: " + MAX_CUSTOM_GAMEPLAY_COUNTERS + ")");
        }
        Map<GameplayMetricKey, Long> result = new LinkedHashMap<>();
        for (int index = 0; index < list.size(); index++) {
            CompoundTag value = list.getCompound(index);
            ResourceLocation metricType = parseId(value.getString("metric_type"),
                    "customGameplayCounters.metric_type");
            GameplayMetricKey key = value.contains("subject", Tag.TAG_STRING)
                    ? GameplayMetricKey.subject(metricType,
                            parseId(value.getString("subject"), "customGameplayCounters.subject"))
                    : GameplayMetricKey.aggregate(metricType);
            if (!value.contains("value", Tag.TAG_ANY_NUMERIC)) {
                throw new IllegalArgumentException("Missing custom gameplay counter value for " + key);
            }
            long counter = value.getLong("value");
            if (counter < 0) {
                throw new IllegalArgumentException("Negative custom gameplay counter for " + key + ": " + counter);
            }
            if (result.putIfAbsent(key, counter) != null) {
                throw new IllegalArgumentException("Duplicate custom gameplay counter: " + key);
            }
        }
        return result;
    }

    private static ListTag writeCustomGameplayCounters(Map<GameplayMetricKey, Long> counters) {
        ListTag list = new ListTag();
        counters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag value = new CompoundTag();
            value.putString("metric_type", entry.getKey().metricType().toString());
            entry.getKey().subjectId().ifPresent(subject -> value.putString("subject", subject.toString()));
            value.putLong("value", entry.getValue());
            list.add(value);
        });
        return list;
    }

    private static ResourceLocation parseId(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !value.contains(":")) {
            throw new IllegalArgumentException("Invalid namespaced ID in '" + field + "': " + value);
        }
        return id;
    }

    private static int requireVersion(CompoundTag tag) {
        if (!tag.contains("dataVersion", Tag.TAG_ANY_NUMERIC)) {
            throw new IllegalArgumentException("Missing numeric dataVersion");
        }
        return tag.getInt("dataVersion");
    }

    private static void requireAffinity(int value, ResourceLocation godId) {
        if (value < MIN_AFFINITY || value > MAX_AFFINITY) {
            throw new IllegalArgumentException("Affinity for " + godId + " must be between "
                    + MIN_AFFINITY + " and " + MAX_AFFINITY + ": " + value);
        }
    }

    private static IllegalArgumentException unsupportedVersion(int version) {
        if (version > CURRENT_DATA_VERSION) {
            return new IllegalArgumentException("Unknown future player profile dataVersion " + version
                    + " (supported: " + CURRENT_DATA_VERSION + ")");
        }
        return new IllegalArgumentException("Player profile dataVersion " + version
                + " has no migration to version " + CURRENT_DATA_VERSION);
    }
}
