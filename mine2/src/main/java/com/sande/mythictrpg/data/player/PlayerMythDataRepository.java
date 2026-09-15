package com.sande.mythictrpg.data.player;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PlayerMythDataRepository extends SavedData {
    public static final int CURRENT_DATA_VERSION = 1;
    private static final String FILE_NAME = "mythictrpg_players";
    private static final Factory<PlayerMythDataRepository> FACTORY = new Factory<>(
            PlayerMythDataRepository::new,
            PlayerMythDataRepository::load
    );

    private Map<UUID, PlayerMythProfile> profiles = new LinkedHashMap<>();
    private CompoundTag rejectedRawData;
    private String rejectionReason;

    public static PlayerMythDataRepository get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    private static PlayerMythDataRepository load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerMythDataRepository repository = new PlayerMythDataRepository();
        try {
            int version = requireVersion(tag);
            if (version != CURRENT_DATA_VERSION) {
                throw unsupportedVersion(version);
            }
            LoadedProfiles loaded = readProfiles(tag);
            repository.profiles = loaded.profiles();
            if (loaded.migrated()) {
                repository.setDirty();
            }
        } catch (RuntimeException exception) {
            repository.rejectedRawData = tag.copy();
            repository.rejectionReason = exception.getMessage();
            MythicTrpg.LOGGER.error("Rejected Player Myth repository without replacing or rewriting it: {}",
                    exception.getMessage(), exception);
        }
        return repository;
    }

    public boolean isReady() {
        return rejectedRawData == null;
    }

    public int dataVersion() {
        return CURRENT_DATA_VERSION;
    }

    public String rejectionReason() {
        return rejectionReason;
    }

    public Optional<PlayerMythProfile> find(UUID playerId) {
        return Optional.ofNullable(profiles.get(playerId));
    }

    public Map<UUID, PlayerMythProfile> profiles() {
        return Map.copyOf(profiles);
    }

    public Map<UUID, PlayerMythProfile> activeProfiles() {
        Map<UUID, PlayerMythProfile> active = new LinkedHashMap<>();
        profiles.forEach((id, profile) -> {
            if (profile.participationStatus() == ParticipationStatus.ACTIVE) {
                active.put(id, profile);
            }
        });
        return Map.copyOf(active);
    }

    PlayerMythProfile getOrCreate(UUID playerId) {
        ensureWritable();
        PlayerMythProfile existing = profiles.get(playerId);
        if (existing != null) {
            return existing;
        }
        PlayerMythProfile created = PlayerMythProfile.createActive();
        profiles.put(playerId, created);
        setDirty();
        return created;
    }

    void put(UUID playerId, PlayerMythProfile profile) {
        ensureWritable();
        profiles.put(playerId, profile);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejectedRawData != null) {
            return rejectedRawData.copy();
        }

        tag.putInt("dataVersion", CURRENT_DATA_VERSION);
        ListTag profileList = new ListTag();
        profiles.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag stored = new CompoundTag();
            stored.putUUID("playerId", entry.getKey());
            stored.put("profile", entry.getValue().save());
            profileList.add(stored);
        });
        tag.put("profiles", profileList);
        return tag;
    }

    private void ensureWritable() {
        if (rejectedRawData != null) {
            throw new IllegalStateException("Player Myth repository is read-only because loading was rejected: "
                    + rejectionReason);
        }
    }

    private static LoadedProfiles readProfiles(CompoundTag tag) {
        if (!tag.contains("profiles", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Missing profiles list");
        }
        ListTag list = tag.getList("profiles", Tag.TAG_COMPOUND);
        Map<UUID, PlayerMythProfile> result = new LinkedHashMap<>();
        boolean migrated = false;
        for (int index = 0; index < list.size(); index++) {
            CompoundTag stored = list.getCompound(index);
            if (!stored.hasUUID("playerId") || !stored.contains("profile", Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("Invalid profile entry at index " + index);
            }
            UUID playerId = stored.getUUID("playerId");
            CompoundTag profileTag = stored.getCompound("profile");
            migrated |= profileTag.getInt("dataVersion") < PlayerMythProfile.CURRENT_DATA_VERSION;
            if (result.putIfAbsent(playerId, PlayerMythProfile.load(profileTag)) != null) {
                throw new IllegalArgumentException("Duplicate player profile: " + playerId);
            }
        }
        return new LoadedProfiles(result, migrated);
    }

    private static int requireVersion(CompoundTag tag) {
        if (!tag.contains("dataVersion", Tag.TAG_ANY_NUMERIC)) {
            throw new IllegalArgumentException("Missing numeric repository dataVersion");
        }
        return tag.getInt("dataVersion");
    }

    private static IllegalArgumentException unsupportedVersion(int version) {
        if (version > CURRENT_DATA_VERSION) {
            return new IllegalArgumentException("Unknown future repository dataVersion " + version
                    + " (supported: " + CURRENT_DATA_VERSION + ")");
        }
        return new IllegalArgumentException("Repository dataVersion " + version
                + " has no migration to version " + CURRENT_DATA_VERSION);
    }

    private record LoadedProfiles(Map<UUID, PlayerMythProfile> profiles, boolean migrated) {
    }
}
