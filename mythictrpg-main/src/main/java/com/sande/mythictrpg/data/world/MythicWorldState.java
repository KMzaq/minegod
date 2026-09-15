package com.sande.mythictrpg.data.world;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class MythicWorldState extends SavedData {
    public static final int CURRENT_DATA_VERSION = 2;
    private static final String FILE_NAME = "mythictrpg_world";
    private static final Factory<MythicWorldState> FACTORY = new Factory<>(
            MythicWorldState::new,
            MythicWorldState::load
    );

    private int dataVersion = CURRENT_DATA_VERSION;
    private Set<ResourceLocation> unlockedGods = new LinkedHashSet<>();
    private Map<ResourceLocation, Integer> questProgress = new LinkedHashMap<>();
    private CompoundTag rejectedRawData;
    private String rejectionReason;

    public static MythicWorldState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    private static MythicWorldState load(CompoundTag tag, HolderLookup.Provider registries) {
        MythicWorldState state = new MythicWorldState();
        try {
            int version = requireVersion(tag);
            if (version < 1 || version > CURRENT_DATA_VERSION) {
                throw unsupportedVersion(version);
            }

            state.dataVersion = CURRENT_DATA_VERSION;
            state.unlockedGods = readIdSet(tag, "unlockedGods");
            state.questProgress = version < 2 ? new LinkedHashMap<>() : readQuestProgress(tag);
        } catch (RuntimeException exception) {
            state.rejectedRawData = tag.copy();
            state.rejectionReason = exception.getMessage();
            MythicTrpg.LOGGER.error("Rejected Mythic world data without replacing or rewriting it: {}",
                    exception.getMessage(), exception);
        }
        return state;
    }

    public int dataVersion() {
        return dataVersion;
    }

    public Set<ResourceLocation> unlockedGods() {
        return Set.copyOf(unlockedGods);
    }

    public boolean isGodUnlocked(ResourceLocation godId) {
        return unlockedGods.contains(godId);
    }

    public boolean unlockGod(ResourceLocation godId) {
        ensureWritable();
        if (unlockedGods.add(godId)) {
            setDirty();
            return true;
        }
        return false;
    }

    /** Server-wide God/faction quest progression. A missing track has the required starting value, zero. */
    public Map<ResourceLocation, Integer> questProgress() {
        return Map.copyOf(questProgress);
    }

    public int questProgress(ResourceLocation progressTrackId) {
        return questProgress.getOrDefault(progressTrackId, 0);
    }

    public int setQuestProgress(ResourceLocation progressTrackId, int value) {
        ensureWritable();
        requireProgress(value);
        int previous = questProgress(progressTrackId);
        if (previous == value) {
            return previous;
        }
        if (value == 0) {
            questProgress.remove(progressTrackId);
        } else {
            questProgress.put(progressTrackId, value);
        }
        setDirty();
        return value;
    }

    /** Called only after the authoritative Quest Engine commits a completion exactly once. */
    public int applyQuestClearProgress(ResourceLocation progressTrackId, int progressOnClear) {
        if (progressOnClear < 0 || progressOnClear > 100) {
            throw new IllegalArgumentException("progressOnClear must be between 0 and 100: " + progressOnClear);
        }
        return setQuestProgress(progressTrackId, Math.min(100, questProgress(progressTrackId) + progressOnClear));
    }

    public boolean isRejected() {
        return rejectedRawData != null;
    }

    public String rejectionReason() {
        return rejectionReason;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejectedRawData != null) {
            return rejectedRawData.copy();
        }

        tag.putInt("dataVersion", dataVersion);
        ListTag unlocked = new ListTag();
        unlockedGods.stream().sorted().map(ResourceLocation::toString).map(StringTag::valueOf).forEach(unlocked::add);
        tag.put("unlockedGods", unlocked);
        tag.put("questProgress", writeQuestProgress(questProgress));
        return tag;
    }

    private void ensureWritable() {
        if (rejectedRawData != null) {
            throw new IllegalStateException("Mythic world data is read-only because loading was rejected: "
                    + rejectionReason);
        }
    }

    private static int requireVersion(CompoundTag tag) {
        if (!tag.contains("dataVersion", Tag.TAG_ANY_NUMERIC)) {
            throw new IllegalArgumentException("Missing numeric dataVersion");
        }
        return tag.getInt("dataVersion");
    }

    private static IllegalArgumentException unsupportedVersion(int version) {
        if (version > CURRENT_DATA_VERSION) {
            return new IllegalArgumentException("Unknown future world dataVersion " + version
                    + " (supported: " + CURRENT_DATA_VERSION + ")");
        }
        return new IllegalArgumentException("World dataVersion " + version
                + " has no migration to version " + CURRENT_DATA_VERSION);
    }

    private static Set<ResourceLocation> readIdSet(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Missing list '" + key + "'");
        }
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (int index = 0; index < list.size(); index++) {
            String value = list.getString(index);
            ResourceLocation id = ResourceLocation.tryParse(value);
            if (id == null || !value.contains(":")) {
                throw new IllegalArgumentException("Invalid namespaced ID in '" + key + "': " + value);
            }
            if (!result.add(id)) {
                throw new IllegalArgumentException("Duplicate ID in '" + key + "': " + id);
            }
        }
        return result;
    }

    private static Map<ResourceLocation, Integer> readQuestProgress(CompoundTag tag) {
        if (!tag.contains("questProgress", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Missing questProgress list");
        }
        ListTag list = tag.getList("questProgress", Tag.TAG_COMPOUND);
        Map<ResourceLocation, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            ResourceLocation trackId = parseId(entry.getString("track"), "questProgress.track");
            if (!entry.contains("value", Tag.TAG_ANY_NUMERIC)) {
                throw new IllegalArgumentException("Missing quest progress value for " + trackId);
            }
            int value = entry.getInt("value");
            requireProgress(value);
            if (result.putIfAbsent(trackId, value) != null) {
                throw new IllegalArgumentException("Duplicate quest progress track " + trackId);
            }
        }
        return result;
    }

    private static ListTag writeQuestProgress(Map<ResourceLocation, Integer> progress) {
        ListTag list = new ListTag();
        progress.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            requireProgress(entry.getValue());
            CompoundTag stored = new CompoundTag();
            stored.putString("track", entry.getKey().toString());
            stored.putInt("value", entry.getValue());
            list.add(stored);
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

    private static void requireProgress(int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("Quest progress must be between 0 and 100: " + value);
        }
    }
}
