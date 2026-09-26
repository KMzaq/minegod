package com.sande.mythictrpg.relation;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Sparse, server-wide and directional current relations between Gods. */
public final class DynamicGodRelationState extends SavedData implements GodRelationView {
    public static final int CURRENT_DATA_VERSION = 1;
    public static final int MAX_HISTORY_PER_DIRECTION = 16;
    private static final String FILE_NAME = "mythictrpg_god_relations";
    private static final Factory<DynamicGodRelationState> FACTORY = new Factory<>(
            DynamicGodRelationState::new, DynamicGodRelationState::load);

    private final Map<GodRelationKey, GodRelationSnapshot> relations = new LinkedHashMap<>();
    private final Map<ResourceLocation, Integer> transitionApplications = new LinkedHashMap<>();
    private CompoundTag rejectedRawData;
    private String rejectionReason;

    public static DynamicGodRelationState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    @Override
    public boolean isReady() {
        return rejectedRawData == null;
    }

    @Override
    public Optional<GodRelationSnapshot> find(ResourceLocation sourceGodId, ResourceLocation targetGodId) {
        return Optional.ofNullable(relations.get(new GodRelationKey(sourceGodId, targetGodId)));
    }

    public Map<GodRelationKey, GodRelationSnapshot> snapshot() {
        return Map.copyOf(relations);
    }

    public Optional<String> rejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

    public synchronized int applicationCount(ResourceLocation transitionId) {
        return transitionApplications.getOrDefault(transitionId, 0);
    }

    synchronized void commit(Map<GodRelationKey, GodRelationSnapshot> replacements,
            ResourceLocation transitionId, int maxApplications) {
        ensureWritable();
        int applications = applicationCount(transitionId);
        if (applications >= maxApplications) {
            throw new IllegalStateException("God relation transition application limit reached: " + transitionId);
        }
        replacements.forEach((key, value) -> {
            if (!key.equals(value.key())) {
                throw new IllegalArgumentException("Relation replacement key does not match its snapshot");
            }
        });
        relations.putAll(replacements);
        transitionApplications.put(transitionId, applications + 1);
        setDirty();
    }

    synchronized void removeForTesting(Set<GodRelationKey> keys, Set<ResourceLocation> transitionIds) {
        keys.forEach(relations::remove);
        transitionIds.forEach(transitionApplications::remove);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejectedRawData != null) {
            return rejectedRawData.copy();
        }
        tag.putInt("dataVersion", CURRENT_DATA_VERSION);
        ListTag stored = new ListTag();
        relations.values().stream().sorted(java.util.Comparator.comparing(GodRelationSnapshot::key))
                .map(DynamicGodRelationState::writeRelation).forEach(stored::add);
        tag.put("relations", stored);
        ListTag applications = new ListTag();
        transitionApplications.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag application = new CompoundTag();
            application.putString("transition", entry.getKey().toString());
            application.putInt("count", entry.getValue());
            applications.add(application);
        });
        tag.put("transitionApplications", applications);
        return tag;
    }

    public static DynamicGodRelationState load(CompoundTag tag, HolderLookup.Provider registries) {
        DynamicGodRelationState state = new DynamicGodRelationState();
        try {
            if (!tag.contains("dataVersion", Tag.TAG_ANY_NUMERIC)
                    || tag.getInt("dataVersion") != CURRENT_DATA_VERSION) {
                throw new IllegalArgumentException("Unsupported or missing God relation dataVersion");
            }
            ListTag stored = requireList(tag, "relations", Tag.TAG_COMPOUND);
            for (int index = 0; index < stored.size(); index++) {
                GodRelationSnapshot relation = readRelation(stored.getCompound(index));
                if (state.relations.putIfAbsent(relation.key(), relation) != null) {
                    throw new IllegalArgumentException("Duplicate God relation " + relation.key());
                }
            }
            ListTag applications = requireList(tag, "transitionApplications", Tag.TAG_COMPOUND);
            for (int index = 0; index < applications.size(); index++) {
                CompoundTag application = applications.getCompound(index);
                ResourceLocation transitionId = id(application.getString("transition"),
                        "transitionApplications.transition");
                int count = application.getInt("count");
                if (count < 1 || count > 1_000) {
                    throw new IllegalArgumentException("Invalid God relation transition application count");
                }
                if (state.transitionApplications.putIfAbsent(transitionId, count) != null) {
                    throw new IllegalArgumentException("Duplicate God relation transition application " + transitionId);
                }
            }
        } catch (RuntimeException exception) {
            state.relations.clear();
            state.transitionApplications.clear();
            state.rejectedRawData = tag.copy();
            state.rejectionReason = exception.getMessage();
            MythicTrpg.LOGGER.error("Rejected God relation data without replacing it: {}",
                    exception.getMessage(), exception);
        }
        return state;
    }

    private static CompoundTag writeRelation(GodRelationSnapshot value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("source", value.key().sourceGodId().toString());
        tag.putString("target", value.key().targetGodId().toString());
        tag.putInt("score", value.score());
        tag.putLong("revision", value.revision());
        tag.putLong("lastChangedGameTime", value.lastChangedGameTime());
        tag.putString("lastCause", value.lastCauseId().toString());
        tag.put("tags", writeTags(value.tags()));
        ListTag history = new ListTag();
        value.recentHistory().forEach(entry -> history.add(writeHistory(entry)));
        tag.put("history", history);
        return tag;
    }

    private static GodRelationSnapshot readRelation(CompoundTag tag) {
        GodRelationKey key = new GodRelationKey(id(tag.getString("source"), "source"),
                id(tag.getString("target"), "target"));
        Set<GodRelationTag> tags = readTags(requireList(tag, "tags", Tag.TAG_STRING));
        ListTag historyTag = requireList(tag, "history", Tag.TAG_COMPOUND);
        if (historyTag.size() > MAX_HISTORY_PER_DIRECTION) {
            throw new IllegalArgumentException("God relation history exceeds " + MAX_HISTORY_PER_DIRECTION);
        }
        List<GodRelationHistoryEntry> history = new ArrayList<>();
        for (int index = 0; index < historyTag.size(); index++) {
            history.add(readHistory(historyTag.getCompound(index)));
        }
        return new GodRelationSnapshot(key, tag.getInt("score"), tags, tag.getLong("revision"),
                tag.getLong("lastChangedGameTime"), id(tag.getString("lastCause"), "lastCause"), history);
    }

    private static CompoundTag writeHistory(GodRelationHistoryEntry value) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("revision", value.revision());
        tag.putLong("gameTime", value.gameTime());
        tag.putString("cause", value.causeId().toString());
        tag.putInt("previousScore", value.previousScore());
        tag.putInt("currentScore", value.currentScore());
        tag.put("addedTags", writeTags(value.addedTags()));
        tag.put("removedTags", writeTags(value.removedTags()));
        return tag;
    }

    private static GodRelationHistoryEntry readHistory(CompoundTag tag) {
        return new GodRelationHistoryEntry(tag.getLong("revision"), tag.getLong("gameTime"),
                id(tag.getString("cause"), "history.cause"), tag.getInt("previousScore"),
                tag.getInt("currentScore"), readTags(requireList(tag, "addedTags", Tag.TAG_STRING)),
                readTags(requireList(tag, "removedTags", Tag.TAG_STRING)));
    }

    private static ListTag writeTags(Set<GodRelationTag> tags) {
        ListTag list = new ListTag();
        tags.stream().map(GodRelationTag::serializedName).sorted().map(StringTag::valueOf).forEach(list::add);
        return list;
    }

    private static Set<GodRelationTag> readTags(ListTag list) {
        LinkedHashSet<GodRelationTag> result = new LinkedHashSet<>();
        for (int index = 0; index < list.size(); index++) {
            if (!result.add(GodRelationTag.parse(list.getString(index)))) {
                throw new IllegalArgumentException("Duplicate God relation tag " + list.getString(index));
            }
        }
        return Set.copyOf(result);
    }

    private void ensureWritable() {
        if (!isReady()) {
            throw new IllegalStateException("God relation state is read-only: " + rejectionReason);
        }
    }

    private static ListTag requireList(CompoundTag tag, String field, int elementType) {
        if (!tag.contains(field, Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Missing list '" + field + "'");
        }
        ListTag list = tag.getList(field, elementType);
        if (!list.isEmpty() && list.getElementType() != elementType) {
            throw new IllegalArgumentException("Invalid list element type for '" + field + "'");
        }
        return list;
    }

    private static ResourceLocation id(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !value.contains(":")) {
            throw new IllegalArgumentException("Invalid namespaced ID in '" + field + "': " + value);
        }
        return id;
    }
}
