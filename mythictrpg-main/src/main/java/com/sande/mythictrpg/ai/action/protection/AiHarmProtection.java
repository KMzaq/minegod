package com.sande.mythictrpg.ai.action.protection;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** World-owned protection, never keyed by a conversation, template, connection or NPC instance. */
public final class AiHarmProtection extends SavedData {
    private static final String FILE_NAME = "mythictrpg_ai_harm_protection";
    private static final int VERSION = 1;
    private static final int MAX_PLAYERS = 65_536; // Storage bound, not a damage or balance setting.
    private static final Factory<AiHarmProtection> FACTORY = new Factory<>(AiHarmProtection::new, AiHarmProtection::load);
    private final Map<UUID, Protection> protectedPlayers = new LinkedHashMap<>();
    private CompoundTag rejected;

    private record Protection(ResourceLocation lastKiller, long deathCount, Set<ResourceLocation> renewedCombat) {
        private Protection {
            Objects.requireNonNull(lastKiller);
            if (deathCount <= 0 || renewedCombat.size() > 256) throw new IllegalArgumentException("Invalid AI harm protection");
            renewedCombat = Set.copyOf(renewedCombat);
        }
    }

    public static AiHarmProtection get(MinecraftServer server) {
        requireThread(server);
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    /** Must be checked immediately before each damage application, including subsequent AI melee hits. */
    public static boolean damageAllowed(ServerPlayer victim, ResourceLocation godId) {
        return victim.isAlive() && get(victim.server).allows(victim.getUUID(), godId);
    }

    /** For explicit AI damage sources that do not carry a God avatar in their DamageSource. */
    public static void afterDamage(ServerPlayer victim, ResourceLocation godId, boolean wasAlive) {
        if (wasAlive && !victim.isAlive()) get(victim.server).recordDeath(victim.getUUID(), godId);
    }

    /** Call only after a real, successful, voluntary player attack; text, threats and AI proposals are not evidence. */
    public static void voluntaryAttack(ServerPlayer player, ResourceLocation attackedGod) {
        if (player.isAlive()) get(player.server).renewCombat(player.getUUID(), attackedGod);
    }

    boolean allows(UUID player, ResourceLocation god) {
        Objects.requireNonNull(player); Objects.requireNonNull(god);
        if (rejected != null) return false;
        Protection protection = protectedPlayers.get(player);
        return protection == null ? protectedPlayers.size() < MAX_PLAYERS : protection.renewedCombat().contains(god);
    }

    void recordDeath(UUID player, ResourceLocation god) {
        Objects.requireNonNull(player); Objects.requireNonNull(god);
        if (rejected != null) return;
        Protection previous = protectedPlayers.get(player);
        if (previous == null && protectedPlayers.size() >= MAX_PLAYERS) return; // precheck denies new victims at capacity.
        long count = previous == null ? 1 : previous.deathCount() == Long.MAX_VALUE ? Long.MAX_VALUE : previous.deathCount() + 1;
        protectedPlayers.put(player, new Protection(god, count, Set.of()));
        setDirty();
    }

    void renewCombat(UUID player, ResourceLocation god) {
        Objects.requireNonNull(player); Objects.requireNonNull(god);
        if (rejected != null) return;
        Protection previous = protectedPlayers.get(player);
        if (previous == null || previous.renewedCombat().contains(god)) return;
        var renewed = new java.util.LinkedHashSet<>(previous.renewedCombat());
        if (renewed.size() >= 256) return;
        renewed.add(god);
        protectedPlayers.put(player, new Protection(previous.lastKiller(), previous.deathCount(), renewed));
        setDirty();
    }

    /** A disconnect or restart is not an invitation to continue the previous fight. */
    public static void endRenewedCombat(ServerPlayer player) {
        AiHarmProtection state = get(player.server);
        Protection previous = state.protectedPlayers.get(player.getUUID());
        if (state.rejected == null && previous != null && !previous.renewedCombat().isEmpty()) {
            state.protectedPlayers.put(player.getUUID(), new Protection(previous.lastKiller(), previous.deathCount(), Set.of()));
            state.setDirty();
        }
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejected != null) return rejected.copy();
        tag.putInt("schemaVersion", VERSION);
        ListTag rows = new ListTag();
        protectedPlayers.forEach((player, protection) -> {
            CompoundTag row = new CompoundTag(); row.putUUID("playerId", player);
            row.putString("lastKillerGodId", protection.lastKiller().toString()); row.putLong("deathCount", protection.deathCount());
            // An in-progress renewed fight deliberately does not survive restart.
            rows.add(row);
        });
        tag.put("protectedPlayers", rows);
        return tag;
    }

    public static AiHarmProtection load(CompoundTag tag, HolderLookup.Provider registries) {
        AiHarmProtection state = new AiHarmProtection();
        try {
            if (tag.getInt("schemaVersion") != VERSION || !tag.contains("protectedPlayers", Tag.TAG_LIST))
                throw new IllegalArgumentException("Unknown AI harm protection schema");
            ListTag rows = (ListTag) tag.get("protectedPlayers");
            if (rows.size() > MAX_PLAYERS || (!rows.isEmpty()
                    && rows.getElementType() != Tag.TAG_COMPOUND)) throw new IllegalArgumentException("AI protection storage limit/type");
            for (Tag value : rows) {
                CompoundTag row = (CompoundTag) value;
                if (!row.hasUUID("playerId") || !row.contains("deathCount", Tag.TAG_LONG)) throw new IllegalArgumentException("Invalid AI protection row");
                var protection = new Protection(ResourceLocation.parse(row.getString("lastKillerGodId")), row.getLong("deathCount"), Set.of());
                if (state.protectedPlayers.putIfAbsent(row.getUUID("playerId"), protection) != null)
                    throw new IllegalArgumentException("Duplicate AI protection victim");
            }
        } catch (RuntimeException invalid) {
            state.protectedPlayers.clear(); state.rejected = tag.copy();
            MythicTrpg.LOGGER.error("AI harm protection data preserved; AI harm blocked until data is repaired", invalid);
        }
        return state;
    }

    private static void requireThread(MinecraftServer server) {
        if (server == null || !server.isSameThread()) throw new IllegalStateException("AI protection requires game thread");
    }
}
