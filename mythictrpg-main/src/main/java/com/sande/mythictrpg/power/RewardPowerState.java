package com.sande.mythictrpg.power;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Authoritative acquisition evidence, NOT an affinity/quest/reward execution database. Never prunes best gear. */
public final class RewardPowerState extends SavedData {
    public static final String FILE_NAME = "mythictrpg_reward_power_v1";
    private static final int MAX_PLAYERS = 4096, MAX_VARIANTS = 512, MAX_TOTAL_VARIANTS = 8192;
    private static final Factory<RewardPowerState> FACTORY = new Factory<>(RewardPowerState::new, RewardPowerState::load);
    public record History(boolean complete, String reason, List<CompoundTag> items) {
        public History { items = items.stream().map(CompoundTag::copy).toList(); }
        @Override public List<CompoundTag> items() { return items.stream().map(CompoundTag::copy).toList(); }
    }
    private final Map<UUID, History> histories = new LinkedHashMap<>();
    private CompoundTag rejected;
    private UUID cleanSession;
    public static RewardPowerState get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Reward acquisition evidence requires game thread");
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }
    public boolean ready() { return rejected == null; }
    public UUID cleanSession() { return cleanSession; }
    public Optional<History> history(UUID player) { return ready() ? Optional.ofNullable(histories.get(player)) : Optional.empty(); }
    /** Only the join adapter may prove a player has no preexisting Mythic profile/reward history. */
    void beginPlayer(UUID player, boolean provenFresh) {
        if (!ready() || histories.containsKey(player) || histories.size() >= MAX_PLAYERS) return;
        histories.put(player, new History(provenFresh, provenFresh ? "COMPLETE_FROM_FIRST_JOIN" : "LEGACY_HISTORY_UNKNOWN", List.of())); setDirty();
    }
    void record(UUID player, CompoundTag identity) {
        if (!ready()) return; beginPlayer(player, false);
        History old = histories.get(player); if (old == null) return;
        if (old.items.contains(identity)) return;
        if (old.items.size() >= MAX_VARIANTS || histories.values().stream().mapToInt(h -> h.items.size()).sum() >= MAX_TOTAL_VARIANTS
                || identity.toString().length() > 32_768) { gap(player, "ACQUISITION_HISTORY_CAPACITY"); return; }
        List<CompoundTag> changed = new ArrayList<>(old.items); changed.add(identity.copy());
        histories.put(player, new History(old.complete, old.reason, changed)); setDirty();
    }
    void gap(UUID player, String reason) {
        if (!ready()) return; beginPlayer(player, false); History old = histories.get(player);
        if (old != null) { histories.put(player, new History(false, reason, old.items)); setDirty(); }
    }
    void invalidateCoverage(String reason) { if (ready()) for (UUID player : List.copyOf(histories.keySet())) gap(player, reason); }
    void cleanSession(UUID session) { if (ready()) { cleanSession = session; setDirty(); } }
    public static CompoundTag identity(ItemStack stack, HolderLookup.Provider registries) {
        if (stack.isEmpty()) throw new IllegalArgumentException("Empty acquisition");
        Tag encoded = stack.copyWithCount(1).save(registries);
        if (!(encoded instanceof CompoundTag tag)) throw new IllegalArgumentException("Invalid acquisition encoding");
        CompoundTag identity = tag.copy(); identity.putInt("count", 1); return identity;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (!ready()) return rejected.copy();
        tag.putInt("schemaVersion", 1); if (cleanSession != null) tag.putUUID("cleanSession", cleanSession);
        ListTag players = new ListTag();
        histories.forEach((id, history) -> {
            CompoundTag player = new CompoundTag(); player.putUUID("player", id); player.putBoolean("complete", history.complete);
            player.putString("reason", history.reason); ListTag items = new ListTag(); history.items.forEach(item -> items.add(item.copy()));
            player.put("items", items); players.add(player);
        });
        tag.put("players", players); return tag;
    }
    public static RewardPowerState load(CompoundTag tag, HolderLookup.Provider registries) {
        RewardPowerState state = new RewardPowerState();
        try {
            if (tag.getInt("schemaVersion") != 1 || !tag.contains("players", Tag.TAG_LIST)) throw new IllegalArgumentException("Power history schema");
            if (tag.contains("cleanSession") && !tag.hasUUID("cleanSession")) throw new IllegalArgumentException("Invalid clean session");
            if (tag.hasUUID("cleanSession")) state.cleanSession = tag.getUUID("cleanSession");
            ListTag players = tag.getList("players", Tag.TAG_COMPOUND); int total = 0;
            requireCompounds(tag, "players");
            if (players.size() > MAX_PLAYERS) throw new IllegalArgumentException("Player capacity");
            for (int i = 0; i < players.size(); i++) {
                CompoundTag player = players.getCompound(i); UUID id = player.getUUID("player");
                requireCompounds(player, "items");
                ListTag items = player.getList("items", Tag.TAG_COMPOUND); List<CompoundTag> identities = new ArrayList<>();
                if (items.size() > MAX_VARIANTS || (total += items.size()) > MAX_TOTAL_VARIANTS) throw new IllegalArgumentException("Variant capacity");
                for (int j = 0; j < items.size(); j++) {
                    CompoundTag item = items.getCompound(j); if (item.toString().length() > 32_768 || item.getInt("count") != 1 || !item.contains("id", Tag.TAG_STRING)) throw new IllegalArgumentException("Invalid reward identity");
                    if (identities.contains(item)) throw new IllegalArgumentException("Duplicate reward identity"); identities.add(item.copy());
                }
                String reason = player.getString("reason");
                if (reason.isBlank() || reason.length() > 128 || !player.contains("complete", Tag.TAG_BYTE)
                        || state.histories.putIfAbsent(id, new History(player.getBoolean("complete"), reason, identities)) != null)
                    throw new IllegalArgumentException("Invalid acquisition coverage");
            }
        } catch (RuntimeException failure) { state.rejected = tag.copy(); state.histories.clear(); }
        return state;
    }
    private static void requireCompounds(CompoundTag parent, String name) {
        if (!(parent.get(name) instanceof ListTag list) || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Expected compound list " + name);
    }
}
