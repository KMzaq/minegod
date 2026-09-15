package com.sande.mythictrpg.quest.dynamic;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Server-wide persistent state for at most one active generated SIDE quest per player. */
public final class GeneratedQuestState extends SavedData {
    public static final int CURRENT_DATA_VERSION = 1;
    private static final String FILE_NAME = "mythictrpg_generated_quests";
    private static final Factory<GeneratedQuestState> FACTORY = new Factory<>(
            GeneratedQuestState::new, GeneratedQuestState::load);

    private final Map<UUID, GeneratedQuestInstance> activeByPlayer = new LinkedHashMap<>();
    private final Map<CooldownKey, Long> lastAssignedGameTime = new LinkedHashMap<>();
    private CompoundTag rejectedRawData;
    private String rejectionReason;

    public static GeneratedQuestState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    public Optional<GeneratedQuestInstance> active(UUID playerId) {
        return Optional.ofNullable(activeByPlayer.get(playerId));
    }

    public List<GeneratedQuestInstance> activeQuests() {
        return List.copyOf(activeByPlayer.values());
    }

    public long lastAssigned(UUID playerId, ResourceLocation templateId) {
        return lastAssignedGameTime.getOrDefault(new CooldownKey(playerId, templateId), Long.MIN_VALUE);
    }

    public boolean cooldownReady(UUID playerId, ResourceLocation templateId,
            long gameTime, long cooldownTicks) {
        long previous = lastAssigned(playerId, templateId);
        return previous == Long.MIN_VALUE || gameTime >= previous + cooldownTicks;
    }

    public void create(GeneratedQuestInstance instance) {
        ensureWritable();
        if (activeByPlayer.putIfAbsent(instance.playerId(), instance) != null) {
            throw new IllegalStateException("player already has an active generated quest");
        }
        lastAssignedGameTime.put(new CooldownKey(instance.playerId(), instance.templateId()),
                instance.createdGameTime());
        setDirty();
    }

    public void replace(GeneratedQuestInstance instance) {
        ensureWritable();
        GeneratedQuestInstance current = activeByPlayer.get(instance.playerId());
        if (current == null || !current.instanceId().equals(instance.instanceId())) {
            throw new IllegalStateException("generated quest instance is no longer active");
        }
        activeByPlayer.put(instance.playerId(), instance);
        setDirty();
    }

    public boolean remove(UUID playerId, UUID instanceId) {
        ensureWritable();
        GeneratedQuestInstance current = activeByPlayer.get(playerId);
        if (current == null || !current.instanceId().equals(instanceId)) {
            return false;
        }
        activeByPlayer.remove(playerId);
        setDirty();
        return true;
    }

    public boolean isWritable() {
        return rejectedRawData == null;
    }

    public Optional<String> rejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejectedRawData != null) {
            return rejectedRawData.copy();
        }
        tag.putInt("dataVersion", CURRENT_DATA_VERSION);
        ListTag active = new ListTag();
        activeByPlayer.values().stream().sorted(java.util.Comparator.comparing(
                value -> value.playerId().toString())).map(GeneratedQuestState::saveInstance)
                .forEach(active::add);
        tag.put("active", active);
        ListTag cooldowns = new ListTag();
        lastAssignedGameTime.entrySet().stream().sorted(java.util.Comparator
                .comparing((Map.Entry<CooldownKey, Long> entry) -> entry.getKey().playerId().toString())
                .thenComparing(entry -> entry.getKey().templateId().toString())).forEach(entry -> {
                    CompoundTag stored = new CompoundTag();
                    stored.putUUID("player", entry.getKey().playerId());
                    stored.putString("template", entry.getKey().templateId().toString());
                    stored.putLong("time", entry.getValue());
                    cooldowns.add(stored);
                });
        tag.put("cooldowns", cooldowns);
        return tag;
    }

    static GeneratedQuestState load(CompoundTag tag, HolderLookup.Provider registries) {
        GeneratedQuestState state = new GeneratedQuestState();
        try {
            if (!tag.contains("dataVersion", Tag.TAG_ANY_NUMERIC)
                    || tag.getInt("dataVersion") != CURRENT_DATA_VERSION) {
                throw new IllegalArgumentException("unsupported or missing generated quest dataVersion");
            }
            ListTag active = requireList(tag, "active");
            for (int index = 0; index < active.size(); index++) {
                GeneratedQuestInstance instance = loadInstance(active.getCompound(index));
                if (state.activeByPlayer.putIfAbsent(instance.playerId(), instance) != null) {
                    throw new IllegalArgumentException("duplicate active generated quest player");
                }
            }
            ListTag cooldowns = requireList(tag, "cooldowns");
            for (int index = 0; index < cooldowns.size(); index++) {
                CompoundTag stored = cooldowns.getCompound(index);
                CooldownKey key = new CooldownKey(requireUuid(stored, "player"),
                        id(stored.getString("template"), "cooldowns.template"));
                if (!stored.contains("time", Tag.TAG_ANY_NUMERIC)
                        || state.lastAssignedGameTime.putIfAbsent(key, stored.getLong("time")) != null) {
                    throw new IllegalArgumentException("invalid or duplicate generated quest cooldown");
                }
            }
        } catch (RuntimeException exception) {
            state.activeByPlayer.clear();
            state.lastAssignedGameTime.clear();
            state.rejectedRawData = tag.copy();
            state.rejectionReason = exception.getMessage();
            MythicTrpg.LOGGER.error("Rejected generated quest data without replacing it: {}",
                    exception.getMessage(), exception);
        }
        return state;
    }

    private static CompoundTag saveInstance(GeneratedQuestInstance value) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("instance", value.instanceId());
        tag.putString("template", value.templateId().toString());
        tag.putUUID("player", value.playerId());
        tag.putString("god", value.godId().toString());
        tag.putString("title", value.title());
        tag.putString("summary", value.summary());
        tag.putString("observation", value.observationTypeId().toString());
        tag.putString("subject", value.subjectId().toString());
        tag.putInt("required", value.requiredCount());
        tag.putInt("progress", value.progress());
        tag.putString("rewardTable", value.rewardTableId().toString());
        tag.putInt("rewardTier", value.rewardTier());
        tag.putBoolean("catchUpApplied", value.catchUpApplied());
        tag.putLong("created", value.createdGameTime());
        tag.putLong("expires", value.expiresGameTime());
        tag.putLong("ftbQuest", value.ftbQuestId());
        tag.putLong("ftbMarker", value.ftbMarkerQuestId());
        tag.putLong("ftbTask", value.ftbTaskId());
        return tag;
    }

    private static GeneratedQuestInstance loadInstance(CompoundTag tag) {
        return new GeneratedQuestInstance(requireUuid(tag, "instance"),
                id(tag.getString("template"), "active.template"), requireUuid(tag, "player"),
                id(tag.getString("god"), "active.god"), tag.getString("title"), tag.getString("summary"),
                id(tag.getString("observation"), "active.observation"),
                id(tag.getString("subject"), "active.subject"), tag.getInt("required"),
                tag.getInt("progress"), id(tag.getString("rewardTable"), "active.rewardTable"),
                tag.getInt("rewardTier"), tag.getBoolean("catchUpApplied"), tag.getLong("created"),
                tag.getLong("expires"), tag.getLong("ftbQuest"), tag.getLong("ftbMarker"),
                tag.getLong("ftbTask"));
    }

    private void ensureWritable() {
        if (!isWritable()) {
            throw new IllegalStateException("generated quest state is read-only: " + rejectionReason);
        }
    }

    private static ListTag requireList(CompoundTag tag, String field) {
        if (!tag.contains(field, Tag.TAG_LIST)) {
            throw new IllegalArgumentException("missing list '" + field + "'");
        }
        ListTag list = tag.getList(field, Tag.TAG_COMPOUND);
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("invalid list element type for '" + field + "'");
        }
        return list;
    }

    private static UUID requireUuid(CompoundTag tag, String field) {
        if (!tag.hasUUID(field)) {
            throw new IllegalArgumentException("missing UUID '" + field + "'");
        }
        return tag.getUUID(field);
    }

    private static ResourceLocation id(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !value.contains(":")) {
            throw new IllegalArgumentException("invalid namespaced ID in " + field + ": " + value);
        }
        return id;
    }

    private record CooldownKey(UUID playerId, ResourceLocation templateId) {
    }
}
