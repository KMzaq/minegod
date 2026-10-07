package com.sande.mythictrpg.ai.relationship;

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
import java.util.UUID;

/**
 * Legacy server-local storage retained only to preserve existing files and AI-owned current emotion. New
 * conversations do not read its relationship collection: canonical affinity comes from PlayerMythProfile and
 * future axes must be supplied through a game-owned provider.
 */
public final class RelationshipRepository extends SavedData implements RelationshipProvider {
    public static final int CURRENT_DATA_VERSION = 1;
    private static final String FILE_NAME = "mythictrpg_ai_relationships";
    private static final Factory<RelationshipRepository> FACTORY = new Factory<>(RelationshipRepository::new,
            RelationshipRepository::load);

    private Map<RelationshipKey, RelationshipMetrics> relationships = new LinkedHashMap<>();
    private Map<RelationshipKey, CurrentEmotion> emotions = new LinkedHashMap<>();
    private CompoundTag rejectedRawData;
    private String rejectionReason;

    public static RelationshipRepository get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    private static RelationshipRepository load(CompoundTag tag, HolderLookup.Provider registries) {
        RelationshipRepository repository = new RelationshipRepository();
        try {
            if (!tag.contains("dataVersion", Tag.TAG_ANY_NUMERIC)) {
                throw new IllegalArgumentException("Missing numeric dataVersion");
            }
            int version = tag.getInt("dataVersion");
            if (version != CURRENT_DATA_VERSION) {
                throw new IllegalArgumentException("Unsupported AI relationship dataVersion " + version);
            }
            repository.relationships = readRelationships(tag);
            repository.emotions = readEmotions(tag);
        } catch (RuntimeException exception) {
            repository.rejectedRawData = tag.copy();
            repository.rejectionReason = exception.getMessage();
            MythicTrpg.LOGGER.error("Rejected AI relationship data without rewriting it: {}", exception.getMessage(),
                    exception);
        }
        return repository;
    }

    @Override
    public RelationshipMetrics relationship(UUID playerId, ResourceLocation godId) {
        return relationships.getOrDefault(new RelationshipKey(playerId, godId), RelationshipMetrics.neutral());
    }

    @Override
    public CurrentEmotion emotion(UUID playerId, ResourceLocation godId) {
        return emotions.getOrDefault(new RelationshipKey(playerId, godId), CurrentEmotion.calm());
    }

    public boolean isReady() {
        return rejectedRawData == null;
    }

    public String rejectionReason() {
        return rejectionReason;
    }

    void putRelationship(RelationshipKey key, RelationshipMetrics metrics) {
        ensureWritable();
        if (!metrics.equals(relationships.get(key))) {
            relationships.put(key, metrics);
            setDirty();
        }
    }

    void putEmotion(RelationshipKey key, CurrentEmotion emotion) {
        ensureWritable();
        if (!emotion.equals(emotions.get(key))) {
            emotions.put(key, emotion);
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejectedRawData != null) {
            return rejectedRawData.copy();
        }
        tag.putInt("dataVersion", CURRENT_DATA_VERSION);
        tag.put("relationships", writeRelationships());
        tag.put("emotions", writeEmotions());
        return tag;
    }

    private ListTag writeRelationships() {
        ListTag entries = new ListTag();
        relationships.entrySet().stream().sorted(Map.Entry.comparingByKey(RelationshipRepository::compareKeys))
                .forEach(entry -> {
                    RelationshipMetrics value = entry.getValue();
                    CompoundTag tag = writeKey(entry.getKey());
                    tag.putInt("affinity", value.affinity());
                    tag.putInt("trust", value.trust());
                    tag.putInt("respect", value.respect());
                    tag.putInt("caution", value.caution());
                    entries.add(tag);
                });
        return entries;
    }

    private ListTag writeEmotions() {
        ListTag entries = new ListTag();
        emotions.entrySet().stream().sorted(Map.Entry.comparingByKey(RelationshipRepository::compareKeys))
                .forEach(entry -> {
                    CompoundTag tag = writeKey(entry.getKey());
                    CompoundTag intensity = new CompoundTag();
                    entry.getValue().intensities().forEach(intensity::putInt);
                    tag.put("intensities", intensity);
                    entries.add(tag);
                });
        return entries;
    }

    private static Map<RelationshipKey, RelationshipMetrics> readRelationships(CompoundTag root) {
        if (!root.contains("relationships", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Missing relationships list");
        }
        Map<RelationshipKey, RelationshipMetrics> loaded = new LinkedHashMap<>();
        ListTag entries = root.getList("relationships", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            RelationshipKey key = readKey(entry, "relationships[" + index + "]");
            RelationshipMetrics metrics = new RelationshipMetrics(requireInt(entry, "affinity", key),
                    requireInt(entry, "trust", key), requireInt(entry, "respect", key), requireInt(entry, "caution", key));
            if (loaded.putIfAbsent(key, metrics) != null) {
                throw new IllegalArgumentException("Duplicate relationship entry for " + key);
            }
        }
        return loaded;
    }

    private static Map<RelationshipKey, CurrentEmotion> readEmotions(CompoundTag root) {
        if (!root.contains("emotions", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Missing emotions list");
        }
        Map<RelationshipKey, CurrentEmotion> loaded = new LinkedHashMap<>();
        ListTag entries = root.getList("emotions", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            RelationshipKey key = readKey(entry, "emotions[" + index + "]");
            if (!entry.contains("intensities", Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("Missing emotion intensities for " + key);
            }
            CompoundTag values = entry.getCompound("intensities");
            Map<String, Integer> intensity = new LinkedHashMap<>();
            for (String name : values.getAllKeys()) {
                if (!values.contains(name, Tag.TAG_ANY_NUMERIC)) {
                    throw new IllegalArgumentException("Non-numeric emotion '" + name + "' for " + key);
                }
                intensity.put(name, values.getInt(name));
            }
            if (loaded.putIfAbsent(key, new CurrentEmotion(intensity)) != null) {
                throw new IllegalArgumentException("Duplicate emotion entry for " + key);
            }
        }
        return loaded;
    }

    private static CompoundTag writeKey(RelationshipKey key) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("playerId", key.playerId());
        tag.putString("god", key.godId().toString());
        return tag;
    }

    private static RelationshipKey readKey(CompoundTag entry, String field) {
        if (!entry.hasUUID("playerId") || !entry.contains("god", Tag.TAG_STRING)) {
            throw new IllegalArgumentException("Missing relationship identity in " + field);
        }
        String rawGod = entry.getString("god");
        ResourceLocation godId = ResourceLocation.tryParse(rawGod);
        if (godId == null || !rawGod.contains(":")) {
            throw new IllegalArgumentException("Invalid god ID in " + field + ": " + rawGod);
        }
        return new RelationshipKey(entry.getUUID("playerId"), godId);
    }

    private static int requireInt(CompoundTag entry, String key, RelationshipKey relationshipKey) {
        if (!entry.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw new IllegalArgumentException("Missing " + key + " for " + relationshipKey);
        }
        return entry.getInt(key);
    }

    private static int compareKeys(RelationshipKey left, RelationshipKey right) {
        int player = left.playerId().compareTo(right.playerId());
        return player != 0 ? player : left.godId().compareTo(right.godId());
    }

    private void ensureWritable() {
        if (rejectedRawData != null) {
            throw new IllegalStateException("AI relationship repository is read-only because loading was rejected: "
                    + rejectionReason);
        }
    }
}
