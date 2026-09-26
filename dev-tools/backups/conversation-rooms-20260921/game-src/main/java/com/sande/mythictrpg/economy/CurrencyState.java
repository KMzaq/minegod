package com.sande.mythictrpg.economy;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative, per-player Gold balances. */
public final class CurrencyState extends SavedData {
    public static final long MAX_BALANCE = 9_000_000_000L;
    private static final int DATA_VERSION = 1;
    private static final String FILE_NAME = "mythictrpg_currency";
    private static final Factory<CurrencyState> FACTORY = new Factory<>(CurrencyState::new, CurrencyState::load);

    private Map<UUID, Long> balances = new LinkedHashMap<>();
    private CompoundTag rejectedRawData;
    private String rejectionReason;

    public static CurrencyState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    static CurrencyState load(CompoundTag tag, HolderLookup.Provider registries) {
        CurrencyState state = new CurrencyState();
        try {
            if (!tag.contains("dataVersion", Tag.TAG_ANY_NUMERIC) || tag.getInt("dataVersion") != DATA_VERSION) {
                throw new IllegalArgumentException("Unsupported currency dataVersion");
            }
            if (!tag.contains("balances", Tag.TAG_LIST)) {
                throw new IllegalArgumentException("Missing currency balances list");
            }
            Map<UUID, Long> loaded = new LinkedHashMap<>();
            ListTag list = tag.getList("balances", Tag.TAG_COMPOUND);
            for (int index = 0; index < list.size(); index++) {
                CompoundTag entry = list.getCompound(index);
                if (!entry.hasUUID("player") || !entry.contains("balance", Tag.TAG_ANY_NUMERIC)) {
                    throw new IllegalArgumentException("Invalid currency balance at index " + index);
                }
                long balance = entry.getLong("balance");
                if (balance < 0L || balance > MAX_BALANCE) {
                    throw new IllegalArgumentException("Currency balance is outside 0.." + MAX_BALANCE);
                }
                if (loaded.putIfAbsent(entry.getUUID("player"), balance) != null) {
                    throw new IllegalArgumentException("Duplicate player currency balance");
                }
            }
            state.balances = loaded;
        } catch (RuntimeException exception) {
            state.rejectedRawData = tag.copy();
            state.rejectionReason = exception.getMessage();
            MythicTrpg.LOGGER.error("Rejected currency data without replacing it: {}",
                    exception.getMessage(), exception);
        }
        return state;
    }

    public boolean isReady() {
        return rejectedRawData == null;
    }

    public String rejectionReason() {
        return rejectionReason;
    }

    public long balance(UUID playerId) {
        return balances.getOrDefault(playerId, 0L);
    }

    public boolean setBalance(UUID playerId, long amount) {
        ensureWritable();
        if (amount < 0L || amount > MAX_BALANCE) {
            return false;
        }
        if (amount == 0L) {
            if (balances.remove(playerId) != null) {
                setDirty();
            }
            return true;
        }
        Long previous = balances.put(playerId, amount);
        if (previous == null || previous.longValue() != amount) setDirty();
        return true;
    }

    public boolean credit(UUID playerId, long amount) {
        ensureWritable();
        if (amount < 0L) return false;
        long current = balance(playerId);
        if (amount > MAX_BALANCE - current) return false;
        return setBalance(playerId, current + amount);
    }

    public boolean debit(UUID playerId, long amount) {
        ensureWritable();
        if (amount < 0L) return false;
        long current = balance(playerId);
        if (current < amount) return false;
        return setBalance(playerId, current - amount);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejectedRawData != null) return rejectedRawData.copy();
        tag.putInt("dataVersion", DATA_VERSION);
        ListTag list = new ListTag();
        balances.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag stored = new CompoundTag();
            stored.putUUID("player", entry.getKey());
            stored.putLong("balance", entry.getValue());
            list.add(stored);
        });
        tag.put("balances", list);
        return tag;
    }

    private void ensureWritable() {
        if (rejectedRawData != null) {
            throw new IllegalStateException("Currency data is read-only: " + rejectionReason);
        }
    }
}
