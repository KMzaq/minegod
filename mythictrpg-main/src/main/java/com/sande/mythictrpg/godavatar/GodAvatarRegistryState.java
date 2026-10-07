package com.sande.mythictrpg.godavatar;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** One durable entity-instance reservation per canonical God ID, including unloaded chunks. */
public final class GodAvatarRegistryState extends SavedData {
    private static final String FILE_NAME = "mythictrpg_god_avatars";
    private static final Factory<GodAvatarRegistryState> FACTORY = new Factory<>(
            GodAvatarRegistryState::new, GodAvatarRegistryState::load);
    private final Map<ResourceLocation, Entry> avatars = new LinkedHashMap<>();
    private final Map<ResourceLocation, UUID> raidOwners = new LinkedHashMap<>();
    private CompoundTag rejectedRawData;

    public static GodAvatarRegistryState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    public Optional<Entry> find(ResourceLocation godId) { return Optional.ofNullable(avatars.get(godId)); }
    public Optional<UUID> raidOwner(ResourceLocation godId) { return Optional.ofNullable(raidOwners.get(godId)); }
    public boolean isReady() { return rejectedRawData == null; }

    public boolean acquireRaid(ResourceLocation godId, UUID attemptId) {
        if (!isReady() || avatars.containsKey(godId) || raidOwners.containsKey(godId)) return false;
        raidOwners.put(godId, attemptId);
        setDirty();
        return true;
    }

    public void releaseRaid(ResourceLocation godId, UUID attemptId) {
        if (attemptId.equals(raidOwners.get(godId))) {
            raidOwners.remove(godId);
            setDirty();
        }
    }

    public boolean reserve(ResourceLocation godId, UUID entityId, ResourceLocation dimension) {
        if (!isReady() || avatars.containsKey(godId)) return false;
        avatars.put(godId, new Entry(entityId, dimension));
        setDirty();
        return true;
    }

    public void release(ResourceLocation godId, UUID entityId) {
        Entry current = avatars.get(godId);
        if (current != null && current.entityId().equals(entityId)) {
            avatars.remove(godId);
            setDirty();
        }
    }

    public boolean owns(ResourceLocation godId, UUID entityId) {
        Entry current = avatars.get(godId);
        return current != null && current.entityId().equals(entityId);
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejectedRawData != null) return rejectedRawData.copy();
        tag.putInt("dataVersion", 1);
        ListTag entries = new ListTag();
        avatars.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(value -> {
            CompoundTag encoded = new CompoundTag();
            encoded.putString("godId", value.getKey().toString());
            encoded.putUUID("entityId", value.getValue().entityId());
            encoded.putString("dimension", value.getValue().dimension().toString());
            entries.add(encoded);
        });
        tag.put("avatars", entries);
        ListTag leases = new ListTag();
        raidOwners.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(value -> {
            CompoundTag encoded = new CompoundTag();
            encoded.putString("godId", value.getKey().toString());
            encoded.putUUID("attemptId", value.getValue());
            leases.add(encoded);
        });
        tag.put("raidOwners", leases);
        return tag;
    }

    private static GodAvatarRegistryState load(CompoundTag tag, HolderLookup.Provider registries) {
        GodAvatarRegistryState state = new GodAvatarRegistryState();
        if (tag.getInt("dataVersion") != 1) {
            state.rejectedRawData = tag.copy();
            MythicTrpg.LOGGER.error("God avatar registry has unsupported dataVersion; avatar spawn is disabled");
            return state;
        }
        for (Tag raw : tag.getList("avatars", Tag.TAG_COMPOUND)) {
            CompoundTag encoded = (CompoundTag) raw;
            ResourceLocation god = ResourceLocation.tryParse(encoded.getString("godId"));
            ResourceLocation dimension = ResourceLocation.tryParse(encoded.getString("dimension"));
            if (god == null || dimension == null || !encoded.hasUUID("entityId")) {
                state.rejectedRawData = tag.copy();
                MythicTrpg.LOGGER.error("God avatar registry contains invalid entry; avatar spawn is disabled");
                return state;
            }
            if (state.avatars.putIfAbsent(god, new Entry(encoded.getUUID("entityId"), dimension)) != null) {
                state.rejectedRawData = tag.copy();
                MythicTrpg.LOGGER.error("God avatar registry contains duplicate {}; avatar spawn is disabled", god);
                return state;
            }
        }
        for (Tag raw : tag.getList("raidOwners", Tag.TAG_COMPOUND)) {
            CompoundTag encoded = (CompoundTag) raw;
            ResourceLocation god = ResourceLocation.tryParse(encoded.getString("godId"));
            if (god == null || !encoded.hasUUID("attemptId")
                    || state.raidOwners.putIfAbsent(god, encoded.getUUID("attemptId")) != null) {
                state.rejectedRawData = tag.copy();
                MythicTrpg.LOGGER.error("God avatar registry contains invalid raid owner; avatar spawn is disabled");
                return state;
            }
        }
        return state;
    }

    public record Entry(UUID entityId, ResourceLocation dimension) {}
}
