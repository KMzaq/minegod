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
import java.util.Set;

public final class MythicWorldState extends SavedData {
    public static final int CURRENT_DATA_VERSION = 1;
    private static final String FILE_NAME = "mythictrpg_world";
    private static final Factory<MythicWorldState> FACTORY = new Factory<>(
            MythicWorldState::new,
            MythicWorldState::load
    );

    private int dataVersion = CURRENT_DATA_VERSION;
    private Set<ResourceLocation> unlockedGods = new LinkedHashSet<>();
    private CompoundTag rejectedRawData;
    private String rejectionReason;

    public static MythicWorldState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    private static MythicWorldState load(CompoundTag tag, HolderLookup.Provider registries) {
        MythicWorldState state = new MythicWorldState();
        try {
            int version = requireVersion(tag);
            if (version != CURRENT_DATA_VERSION) {
                throw unsupportedVersion(version);
            }

            state.dataVersion = version;
            state.unlockedGods = readIdSet(tag, "unlockedGods");
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
}
