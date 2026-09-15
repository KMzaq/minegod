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
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Server-wide, per-God focus selected by assignment of the first fixed main quest. */
public final class GodAttentionState extends SavedData {
    public static final int CURRENT_DATA_VERSION = 1;
    private static final String FILE_NAME = "mythictrpg_god_attention";
    private static final Factory<GodAttentionState> FACTORY = new Factory<>(
            GodAttentionState::new, GodAttentionState::load);

    private final Map<ResourceLocation, GodAttentionRecord> byGod = new LinkedHashMap<>();
    private CompoundTag rejectedRawData;
    private String rejectionReason;

    public static GodAttentionState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    public Optional<GodAttentionRecord> record(ResourceLocation godId) {
        return Optional.ofNullable(byGod.get(godId));
    }

    public boolean isFocused(ResourceLocation godId, UUID playerId) {
        return record(godId).map(value -> value.focusedPlayerIds().contains(playerId)).orElse(false);
    }

    public Set<UUID> focusedPlayers(ResourceLocation godId) {
        return record(godId).map(GodAttentionRecord::focusedPlayerIds).orElseGet(Set::of);
    }

    public Set<ResourceLocation> focusedGodIds(UUID playerId) {
        LinkedHashSet<ResourceLocation> result = new LinkedHashSet<>();
        byGod.forEach((godId, record) -> {
            if (record.focusedPlayerIds().contains(playerId)) {
                result.add(godId);
            }
        });
        return Set.copyOf(result);
    }

    /**
     * Claims an unclaimed God, or joins another recipient of the same still-open
     * entry quest. Validation must run immediately before this server-thread call.
     */
    public void recordEntryAssignment(ResourceLocation godId, ResourceLocation entryQuestId,
            UUID playerId, Instant assignedAt) {
        ensureWritable();
        GodAttentionRecord current = byGod.get(godId);
        if (current == null) {
            byGod.put(godId, new GodAttentionRecord(godId, entryQuestId, assignedAt, Set.of(playerId)));
            setDirty();
            return;
        }
        if (!current.entryQuestId().equals(entryQuestId)) {
            throw new IllegalStateException("God " + godId + " was already claimed through "
                    + current.entryQuestId());
        }
        GodAttentionRecord changed = current.withPlayer(playerId);
        if (changed != current) {
            byGod.put(godId, changed);
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejectedRawData != null) {
            return rejectedRawData.copy();
        }
        tag.putInt("dataVersion", CURRENT_DATA_VERSION);
        ListTag records = new ListTag();
        byGod.values().stream().sorted(java.util.Comparator.comparing(value -> value.godId().toString()))
                .forEach(value -> {
                    CompoundTag record = new CompoundTag();
                    record.putString("god", value.godId().toString());
                    record.putString("entryQuest", value.entryQuestId().toString());
                    record.putString("firstAssignedAt", value.firstAssignedAt().toString());
                    ListTag players = new ListTag();
                    value.focusedPlayerIds().stream().map(UUID::toString).sorted()
                            .map(StringTag::valueOf).forEach(players::add);
                    record.put("players", players);
                    records.add(record);
                });
        tag.put("records", records);
        return tag;
    }

    static GodAttentionState load(CompoundTag tag, HolderLookup.Provider registries) {
        GodAttentionState state = new GodAttentionState();
        try {
            if (!tag.contains("dataVersion", Tag.TAG_ANY_NUMERIC)
                    || tag.getInt("dataVersion") != CURRENT_DATA_VERSION) {
                throw new IllegalArgumentException("Unsupported or missing God attention dataVersion");
            }
            ListTag records = requireList(tag, "records", Tag.TAG_COMPOUND);
            for (int index = 0; index < records.size(); index++) {
                CompoundTag raw = records.getCompound(index);
                ResourceLocation godId = id(raw.getString("god"), "records.god");
                ResourceLocation questId = id(raw.getString("entryQuest"), "records.entryQuest");
                Instant firstAssignedAt;
                try {
                    firstAssignedAt = Instant.parse(raw.getString("firstAssignedAt"));
                } catch (DateTimeParseException exception) {
                    throw new IllegalArgumentException("Invalid records.firstAssignedAt", exception);
                }
                LinkedHashSet<UUID> players = new LinkedHashSet<>();
                ListTag rawPlayers = requireList(raw, "players", Tag.TAG_STRING);
                for (int playerIndex = 0; playerIndex < rawPlayers.size(); playerIndex++) {
                    UUID playerId;
                    try {
                        playerId = UUID.fromString(rawPlayers.getString(playerIndex));
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException("Invalid records.players UUID", exception);
                    }
                    if (!players.add(playerId)) {
                        throw new IllegalArgumentException("Duplicate focused player " + playerId);
                    }
                }
                GodAttentionRecord record = new GodAttentionRecord(godId, questId, firstAssignedAt, players);
                if (state.byGod.putIfAbsent(godId, record) != null) {
                    throw new IllegalArgumentException("Duplicate God attention record " + godId);
                }
            }
        } catch (RuntimeException exception) {
            state.byGod.clear();
            state.rejectedRawData = tag.copy();
            state.rejectionReason = exception.getMessage();
            MythicTrpg.LOGGER.error("Rejected God attention data without replacing it: {}",
                    exception.getMessage(), exception);
        }
        return state;
    }

    public boolean isWritable() {
        return rejectedRawData == null;
    }

    public Optional<String> rejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

    private void ensureWritable() {
        if (!isWritable()) {
            throw new IllegalStateException("God attention state is not writable: " + rejectionReason);
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
