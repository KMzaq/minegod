package com.sande.mythictrpg.quest;

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

/** Persistent per-assignment timing used by optional quest reminders. */
final class QuestReminderState extends SavedData {
    private static final int DATA_VERSION = 1;
    private static final String FILE_NAME = "mythictrpg_quest_reminders";
    private static final Factory<QuestReminderState> FACTORY = new Factory<>(
            QuestReminderState::new, QuestReminderState::load);

    private final Map<Key, Tracker> trackers = new LinkedHashMap<>();

    static QuestReminderState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    private static QuestReminderState load(CompoundTag tag, HolderLookup.Provider registries) {
        QuestReminderState state = new QuestReminderState();
        try {
            if (!tag.contains("dataVersion", Tag.TAG_ANY_NUMERIC) || tag.getInt("dataVersion") != DATA_VERSION) {
                throw new IllegalArgumentException("Unsupported quest reminder data version");
            }
            ListTag list = tag.getList("trackers", Tag.TAG_COMPOUND);
            for (int index = 0; index < list.size(); index++) {
                CompoundTag entry = list.getCompound(index);
                ResourceLocation questId = ResourceLocation.parse(entry.getString("quest"));
                UUID playerId = UUID.fromString(entry.getString("player"));
                Tracker tracker = new Tracker(entry.getLong("lastRelevant"), entry.getLong("firstUnrelated"),
                        entry.getLong("lastUnrelated"), entry.getLong("lastReminder"),
                        entry.getInt("unrelatedCount"), entry.getInt("reminderCount"));
                if (state.trackers.putIfAbsent(new Key(questId, playerId), tracker) != null) {
                    throw new IllegalArgumentException("Duplicate quest reminder tracker");
                }
            }
        } catch (RuntimeException exception) {
            state.trackers.clear();
            MythicTrpg.LOGGER.error("Rejected quest reminder state; timers restart safely", exception);
        }
        return state;
    }

    Tracker ensure(ResourceLocation questId, UUID playerId, long gameTime) {
        Key key = new Key(questId, playerId);
        Tracker existing = trackers.get(key);
        if (existing != null) {
            return existing;
        }
        Tracker created = Tracker.initial(gameTime);
        trackers.put(key, created);
        setDirty();
        return created;
    }

    Tracker observe(ResourceLocation questId, UUID playerId, long gameTime, boolean relevant) {
        Key key = new Key(questId, playerId);
        Tracker current = ensure(questId, playerId, gameTime);
        Tracker updated = relevant ? current.relevantAt(gameTime) : current.unrelatedAt(gameTime);
        trackers.put(key, updated);
        setDirty();
        return updated;
    }

    void markReminder(ResourceLocation questId, UUID playerId, long gameTime) {
        Key key = new Key(questId, playerId);
        Tracker current = ensure(questId, playerId, gameTime);
        trackers.put(key, current.remindedAt(gameTime));
        setDirty();
    }

    void remove(ResourceLocation questId, UUID playerId) {
        if (trackers.remove(new Key(questId, playerId)) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("dataVersion", DATA_VERSION);
        ListTag list = new ListTag();
        trackers.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag value = new CompoundTag();
            value.putString("quest", entry.getKey().questId().toString());
            value.putString("player", entry.getKey().playerId().toString());
            value.putLong("lastRelevant", entry.getValue().lastRelevantGameTick());
            value.putLong("firstUnrelated", entry.getValue().firstUnrelatedGameTick());
            value.putLong("lastUnrelated", entry.getValue().lastUnrelatedGameTick());
            value.putLong("lastReminder", entry.getValue().lastReminderGameTick());
            value.putInt("unrelatedCount", entry.getValue().unrelatedActionCount());
            value.putInt("reminderCount", entry.getValue().reminderCount());
            list.add(value);
        });
        tag.put("trackers", list);
        return tag;
    }

    record Tracker(long lastRelevantGameTick, long firstUnrelatedGameTick,
            long lastUnrelatedGameTick, long lastReminderGameTick,
            int unrelatedActionCount, int reminderCount) {
        Tracker {
            if (lastRelevantGameTick < 0L || firstUnrelatedGameTick < -1L
                    || lastUnrelatedGameTick < -1L || lastReminderGameTick < -1L
                    || unrelatedActionCount < 0 || reminderCount < 0) {
                throw new IllegalArgumentException("Invalid quest reminder tracker");
            }
        }

        static Tracker initial(long gameTime) {
            return new Tracker(gameTime, -1L, -1L, -1L, 0, 0);
        }

        Tracker relevantAt(long gameTime) {
            return new Tracker(gameTime, -1L, -1L, lastReminderGameTick, 0, reminderCount);
        }

        Tracker unrelatedAt(long gameTime) {
            long first = firstUnrelatedGameTick < 0L ? gameTime : firstUnrelatedGameTick;
            return new Tracker(lastRelevantGameTick, first, gameTime, lastReminderGameTick,
                    Math.min(Integer.MAX_VALUE, unrelatedActionCount + 1), reminderCount);
        }

        Tracker remindedAt(long gameTime) {
            return new Tracker(lastRelevantGameTick, firstUnrelatedGameTick, lastUnrelatedGameTick,
                    gameTime, unrelatedActionCount, Math.min(Integer.MAX_VALUE, reminderCount + 1));
        }

        boolean eligible(long gameTime, QuestReminderPolicy policy) {
            return firstUnrelatedGameTick >= 0L && unrelatedActionCount > 0
                    && gameTime - firstUnrelatedGameTick >= policy.unrelatedActivityTicks()
                    && (lastReminderGameTick < 0L
                            || gameTime - lastReminderGameTick >= policy.cooldownTicks());
        }
    }

    private record Key(ResourceLocation questId, UUID playerId) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int quest = questId.compareTo(other.questId);
            return quest != 0 ? quest : playerId.toString().compareTo(other.playerId.toString());
        }
    }
}
