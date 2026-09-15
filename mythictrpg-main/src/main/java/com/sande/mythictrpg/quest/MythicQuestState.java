package com.sande.mythictrpg.quest;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authoritative server-wide quest assignment/completion state. FTB TeamData is
 * deliberately not used as the narrative source of truth.
 */
public final class MythicQuestState extends SavedData {
    public static final int CURRENT_DATA_VERSION = 1;
    private static final String FILE_NAME = "mythictrpg_quests";
    private static final Factory<MythicQuestState> FACTORY = new Factory<>(
            MythicQuestState::new, MythicQuestState::load);

    private final Map<ResourceLocation, Map<UUID, QuestAssignment>> assignments = new LinkedHashMap<>();
    private final Map<ResourceLocation, QuestCompletionRecord> completions = new LinkedHashMap<>();
    private CompoundTag rejectedRawData;
    private String rejectionReason;

    public static MythicQuestState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    private static MythicQuestState load(CompoundTag tag, HolderLookup.Provider registries) {
        MythicQuestState state = new MythicQuestState();
        try {
            int version = requireInt(tag, "dataVersion");
            if (version != CURRENT_DATA_VERSION) {
                throw new IllegalArgumentException("Unsupported quest dataVersion " + version
                        + " (expected " + CURRENT_DATA_VERSION + ")");
            }
            readAssignments(tag, state.assignments);
            readCompletions(tag, state.completions);
        } catch (RuntimeException exception) {
            state.assignments.clear();
            state.completions.clear();
            state.rejectedRawData = tag.copy();
            state.rejectionReason = exception.getMessage();
            MythicTrpg.LOGGER.error("Rejected Mythic quest data without replacing it: {}",
                    exception.getMessage(), exception);
        }
        return state;
    }

    public boolean assign(ResourceLocation questId, UUID playerId, ResourceLocation giverGodId, Instant time) {
        ensureWritable();
        if (completions.containsKey(questId)) {
            return false;
        }
        Map<UUID, QuestAssignment> players = assignments.computeIfAbsent(questId,
                ignored -> new LinkedHashMap<>());
        if (players.containsKey(playerId)) {
            return false;
        }
        players.put(playerId, new QuestAssignment(questId, playerId, giverGodId, time));
        setDirty();
        return true;
    }

    public boolean isAssigned(ResourceLocation questId, UUID playerId) {
        return assignments.getOrDefault(questId, Map.of()).containsKey(playerId);
    }

    public List<QuestAssignment> assignmentsFor(UUID playerId) {
        return assignments.values().stream()
                .map(entries -> entries.get(playerId))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(QuestAssignment::assignedAt))
                .toList();
    }

    public Set<UUID> assignedPlayers(ResourceLocation questId) {
        return Set.copyOf(assignments.getOrDefault(questId, Map.of()).keySet());
    }

    public boolean isCompleted(ResourceLocation questId) {
        return completions.containsKey(questId);
    }

    public Optional<QuestCompletionRecord> completion(ResourceLocation questId) {
        return Optional.ofNullable(completions.get(questId));
    }

    public List<QuestCompletionRecord> historyFor(UUID playerId) {
        return completions.values().stream()
                .filter(record -> record.completedBy().equals(playerId))
                .sorted(Comparator.comparing(QuestCompletionRecord::completedAt).reversed())
                .toList();
    }

    /** Returns empty if another completion already won the server-thread race. */
    public Optional<QuestCompletionRecord> tryComplete(ResourceLocation questId, UUID completedBy,
            Optional<ResourceLocation> completionNpcId, Instant time) {
        ensureWritable();
        if (completions.containsKey(questId)) {
            return Optional.empty();
        }
        Set<UUID> participants = new LinkedHashSet<>(assignedPlayers(questId));
        participants.add(completedBy);
        QuestCompletionRecord record = new QuestCompletionRecord(questId, completedBy,
                completionNpcId, time, participants);
        completions.put(questId, record);
        assignments.remove(questId);
        setDirty();
        return Optional.of(record);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejectedRawData != null) {
            return rejectedRawData.copy();
        }
        tag.putInt("dataVersion", CURRENT_DATA_VERSION);
        tag.put("assignments", writeAssignments());
        tag.put("completions", writeCompletions());
        return tag;
    }

    private ListTag writeAssignments() {
        ListTag list = new ListTag();
        assignments.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(quest ->
                quest.getValue().values().stream()
                        .sorted(Comparator.comparing(assignment -> assignment.playerId().toString()))
                        .forEach(assignment -> {
                            CompoundTag entry = new CompoundTag();
                            entry.putString("quest", assignment.questId().toString());
                            entry.putString("player", assignment.playerId().toString());
                            entry.putString("giver", assignment.giverGodId().toString());
                            entry.putLong("assignedAt", assignment.assignedAt().toEpochMilli());
                            list.add(entry);
                        }));
        return list;
    }

    private ListTag writeCompletions() {
        ListTag list = new ListTag();
        completions.values().stream().sorted(Comparator.comparing(QuestCompletionRecord::questId))
                .forEach(record -> {
                    CompoundTag entry = new CompoundTag();
                    entry.putString("quest", record.questId().toString());
                    entry.putString("player", record.completedBy().toString());
                    record.completionNpcId().ifPresent(id -> entry.putString("npc", id.toString()));
                    entry.putLong("completedAt", record.completedAt().toEpochMilli());
                    ListTag participants = new ListTag();
                    record.assignedPlayersAtCompletion().stream().map(UUID::toString).sorted()
                            .map(StringTag::valueOf).forEach(participants::add);
                    entry.put("assignedPlayers", participants);
                    list.add(entry);
                });
        return list;
    }

    private static void readAssignments(CompoundTag root,
            Map<ResourceLocation, Map<UUID, QuestAssignment>> destination) {
        ListTag list = requireList(root, "assignments", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            ResourceLocation questId = id(entry.getString("quest"), "assignments.quest");
            UUID playerId = uuid(entry.getString("player"), "assignments.player");
            ResourceLocation giver = id(entry.getString("giver"), "assignments.giver");
            Instant assignedAt = Instant.ofEpochMilli(requireLong(entry, "assignedAt"));
            QuestAssignment previous = destination.computeIfAbsent(questId,
                    ignored -> new LinkedHashMap<>()).putIfAbsent(playerId,
                            new QuestAssignment(questId, playerId, giver, assignedAt));
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate assignment for " + questId + " and " + playerId);
            }
        }
    }

    private static void readCompletions(CompoundTag root,
            Map<ResourceLocation, QuestCompletionRecord> destination) {
        ListTag list = requireList(root, "completions", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            ResourceLocation questId = id(entry.getString("quest"), "completions.quest");
            UUID playerId = uuid(entry.getString("player"), "completions.player");
            Optional<ResourceLocation> npc = entry.contains("npc", Tag.TAG_STRING)
                    ? Optional.of(id(entry.getString("npc"), "completions.npc")) : Optional.empty();
            Instant completedAt = Instant.ofEpochMilli(requireLong(entry, "completedAt"));
            ListTag participants = requireList(entry, "assignedPlayers", Tag.TAG_STRING);
            Set<UUID> players = new LinkedHashSet<>();
            for (int playerIndex = 0; playerIndex < participants.size(); playerIndex++) {
                if (!players.add(uuid(participants.getString(playerIndex), "completions.assignedPlayers"))) {
                    throw new IllegalArgumentException("Duplicate completion participant for " + questId);
                }
            }
            QuestCompletionRecord record = new QuestCompletionRecord(questId, playerId, npc,
                    completedAt, players);
            if (destination.putIfAbsent(questId, record) != null) {
                throw new IllegalArgumentException("Duplicate completion for " + questId);
            }
        }
    }

    private static ListTag requireList(CompoundTag tag, String key, int elementType) {
        if (!tag.contains(key, Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Missing list '" + key + "'");
        }
        return tag.getList(key, elementType);
    }

    private static int requireInt(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw new IllegalArgumentException("Missing numeric '" + key + "'");
        }
        return tag.getInt(key);
    }

    private static long requireLong(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw new IllegalArgumentException("Missing numeric '" + key + "'");
        }
        return tag.getLong(key);
    }

    private static ResourceLocation id(String value, String field) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || !value.contains(":")) {
            throw new IllegalArgumentException("Invalid namespaced ID in '" + field + "': " + value);
        }
        return id;
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid UUID in '" + field + "': " + value, exception);
        }
    }

    private void ensureWritable() {
        if (rejectedRawData != null) {
            throw new IllegalStateException("Mythic quest data is read-only because loading was rejected: "
                    + rejectionReason);
        }
    }
}
