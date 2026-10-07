package com.sande.mythictrpg.raid;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Authoritative raid attempts. Gameplay progress is not subject to the optional AI archive quota. */
public final class RaidState extends SavedData {
    private static final Factory<RaidState> FACTORY = new Factory<>(RaidState::new, RaidState::load);
    public enum Status { FORMING, QUEUED, STARTING, ACTIVE, CLEANUP_PENDING, VICTORY_PENDING, SUCCEEDED, FAILED, CANCELLED }
    public record ReturnPoint(ResourceLocation dimension, Vec3 position, float yaw, float pitch) {
        static ReturnPoint of(ServerPlayer p) { return new ReturnPoint(p.serverLevel().dimension().location(), p.position(), p.getYRot(), p.getXRot()); }
    }
    public static final class Member {
        final UUID playerId;
        int deaths;
        long offlineSince = -1;
        boolean eliminated, returned, awaitingRespawn;
        ReturnPoint returnPoint;
        Member(UUID player) { playerId = player; }
        public UUID playerId() { return playerId; }
        public int deaths() { return deaths; }
        public boolean eliminated() { return eliminated; }
    }
    public static final class Attempt {
        final UUID id, leader, teamId;
        final RaidDefinition definition;
        final long createdAt;
        final Set<UUID> invited;
        final Map<UUID, Member> members = new LinkedHashMap<>();
        Status status = Status.FORMING;
        RaidDefinition.Arena arena;
        UUID bossId;
        final Set<UUID> spawnedEntities = new LinkedHashSet<>();
        final Set<UUID> deadEntities = new LinkedHashSet<>();
        final Set<UUID> cleanedEntities = new LinkedHashSet<>();
        final Set<UUID> rewardRecipients = new LinkedHashSet<>();
        long queuedAt;
        long startedAt;
        int phaseMask;
        String reason = "";
        Status pendingFinalStatus;
        boolean storyPublished;
        Attempt(UUID id, UUID leader, UUID team, RaidDefinition definition, Set<UUID> invited, long createdAt) {
            this.id = id; this.leader = leader; this.teamId = team; this.definition = definition;
            this.invited = Set.copyOf(invited); this.createdAt = createdAt;
        }
        public UUID id() { return id; }
        public UUID leader() { return leader; }
        public RaidDefinition definition() { return definition; }
        public Status status() { return status; }
        public String reason() { return reason; }
        public List<UUID> playerIds() { return List.copyOf(members.keySet()); }
        public boolean terminal() { return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED; }
        public boolean occupiesPlayerSlot() { return status == Status.FORMING || status == Status.QUEUED
                || status == Status.STARTING || status == Status.ACTIVE || status == Status.CLEANUP_PENDING; }
        public boolean ownsArena() { return arena != null && (status == Status.STARTING
                || status == Status.ACTIVE || status == Status.CLEANUP_PENDING); }
        public Optional<RaidDefinition.Arena> arena() { return Optional.ofNullable(arena); }
    }

    final Map<UUID, Attempt> attempts = new LinkedHashMap<>();
    private CompoundTag rejected;
    public static RaidState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "mythictrpg_raids");
    }
    public boolean ready() { return rejected == null; }
    void changed() { setDirty(); }
    public List<Attempt> attempts() { return List.copyOf(attempts.values()); }
    public Optional<Attempt> find(UUID id) { return Optional.ofNullable(attempts.get(id)); }
    public Optional<Attempt> membership(UUID player) {
        return attempts.values().stream().filter(a -> a.occupiesPlayerSlot() && a.members.containsKey(player)).findFirst();
    }
    public boolean arenaAvailable(RaidDefinition.Arena arena) {
        return ready() && attempts.values().stream().filter(Attempt::ownsArena).noneMatch(a ->
                a.arena.id().equals(arena.id()) || a.arena.dimension().equals(arena.dimension())
                        && a.arena.bounds().inflate(16).intersects(arena.bounds()));
    }
    public Attempt create(UUID leader, UUID team, RaidDefinition definition, Set<UUID> invited, long gameTime) {
        if (!ready() || membership(leader).isPresent() || attempts.values().stream().filter(Attempt::occupiesPlayerSlot).count() >= 64)
            throw new IllegalStateException("Raid state unavailable, queue full, or player already joined");
        // Never prune an attempt while it owes an offline player a return teleport.
        if (attempts.size() >= 512) {
            attempts.values().removeIf(a -> a.terminal() && a.members.values().stream().allMatch(m -> m.returnPoint == null || m.returned));
            if (attempts.size() >= 512) throw new IllegalStateException("Raid state awaits offline player recovery");
        }
        var attempt = new Attempt(UUID.randomUUID(), leader, team, definition, invited, gameTime);
        attempt.members.put(leader, new Member(leader)); attempts.put(attempt.id, attempt); setDirty();
        return attempt;
    }
    public boolean join(UUID attemptId, UUID player) {
        Attempt a = attempts.get(attemptId);
        if (!ready() || a == null || a.status != Status.FORMING || membership(player).isPresent()
                || a.members.size() >= a.definition.maximumPlayers()
                || a.definition.mode() == RaidDefinition.Mode.PARTY_ISOLATED && !a.invited.contains(player)) return false;
        a.members.put(player, new Member(player)); setDirty(); return true;
    }
    public boolean queue(UUID attemptId, UUID leader, long gameTime) {
        Attempt a = attempts.get(attemptId);
        if (!ready() || a == null || !a.leader.equals(leader) || a.status != Status.FORMING
                || a.members.size() < a.definition.minimumPlayers()) return false;
        a.status = Status.QUEUED; a.queuedAt = gameTime; setDirty(); return true;
    }

    /** Only the caller's unfrozen membership may be removed; ownership never silently transfers. */
    public boolean leaveForming(UUID attemptId, UUID player) {
        Attempt a = attempts.get(attemptId);
        if (!ready() || a == null || a.status != Status.FORMING || a.leader.equals(player)
                || !a.members.containsKey(player)) return false;
        a.members.remove(player);
        setDirty();
        return true;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejected != null) return rejected.copy();
        tag.putInt("dataVersion", 1); var list = new ListTag();
        for (Attempt a : attempts.values()) {
            var row = new CompoundTag(); row.putUUID("id", a.id); row.putUUID("leader", a.leader); row.putUUID("team", a.teamId);
            row.putString("raidId", a.definition.id().toString()); row.putString("definition", a.definition.frozenJson());
            row.putString("status", a.status.name()); row.putLong("createdAt", a.createdAt); row.putLong("queuedAt", a.queuedAt); row.putLong("startedAt", a.startedAt);
            row.putInt("phaseMask", a.phaseMask); row.putString("reason", a.reason); row.putBoolean("storyPublished", a.storyPublished);
            row.put("invited", uuids(a.invited)); row.put("spawnedEntities", uuids(a.spawnedEntities));
            row.put("deadEntities", uuids(a.deadEntities));
            row.put("cleanedEntities", uuids(a.cleanedEntities));
            row.put("rewardRecipients", uuids(a.rewardRecipients));
            if (a.pendingFinalStatus != null) row.putString("pendingFinalStatus", a.pendingFinalStatus.name());
            if (a.bossId != null) row.putUUID("bossId", a.bossId);
            if (a.arena != null) { row.putString("arenaId", a.arena.id().toString()); row.putString("arena", a.arena.frozenJson()); }
            var members = new ListTag();
            for (Member member : a.members.values()) {
                var m = new CompoundTag(); m.putUUID("id", member.playerId); m.putInt("deaths", member.deaths);
                m.putLong("offlineSince", member.offlineSince); m.putBoolean("eliminated", member.eliminated); m.putBoolean("returned", member.returned);
                m.putBoolean("awaitingRespawn", member.awaitingRespawn);
                if (member.returnPoint != null) {
                    var point = member.returnPoint; var pos = new CompoundTag(); pos.putString("dimension", point.dimension().toString());
                    pos.putDouble("x", point.position().x); pos.putDouble("y", point.position().y); pos.putDouble("z", point.position().z);
                    pos.putFloat("yaw", point.yaw()); pos.putFloat("pitch", point.pitch()); m.put("return", pos);
                }
                members.add(m);
            }
            row.put("members", members); list.add(row);
        }
        tag.put("attempts", list); return tag;
    }
    public static RaidState load(CompoundTag tag, HolderLookup.Provider registries) {
        var state = new RaidState();
        try {
            if (tag.getInt("dataVersion") != 1 || !tag.contains("attempts", Tag.TAG_LIST)) throw new IllegalArgumentException("Unsupported raid data");
            var rows = compoundList(tag, "attempts", false);
            if (rows.size() > 512) throw new IllegalArgumentException("Too many raid records");
            Set<UUID> activePlayers = new LinkedHashSet<>();
            for (Tag raw : rows) {
                var row = (CompoundTag) raw;
                String definitionText = row.getString("definition");
                if (definitionText.length() > 64_000) throw new IllegalArgumentException("Raid snapshot too large");
                var definition = RaidDefinition.parse(RaidDefinition.id(row.getString("raidId")), JsonParser.parseString(definitionText).getAsJsonObject());
                var a = new Attempt(row.getUUID("id"), row.getUUID("leader"), row.getUUID("team"), definition,
                        readUuids(row, "invited", 256), row.getLong("createdAt"));
                a.status = Status.valueOf(row.getString("status")); a.queuedAt = row.getLong("queuedAt"); a.startedAt = row.getLong("startedAt");
                a.phaseMask = row.getInt("phaseMask"); a.reason = row.getString("reason"); a.storyPublished = row.getBoolean("storyPublished");
                if (a.createdAt < 0 || a.queuedAt < 0 || a.startedAt < 0 || a.reason.length() > 256 || a.phaseMask < 0 || a.phaseMask > 255)
                    throw new IllegalArgumentException("Invalid raid state values");
                a.spawnedEntities.addAll(readUuids(row, "spawnedEntities", 257));
                a.deadEntities.addAll(readUuids(row, "deadEntities", 257));
                a.cleanedEntities.addAll(readUuids(row, "cleanedEntities", 257));
                a.rewardRecipients.addAll(readUuids(row, "rewardRecipients", 32));
                if (row.contains("pendingFinalStatus")) a.pendingFinalStatus = Status.valueOf(row.getString("pendingFinalStatus"));
                if (a.status == Status.CLEANUP_PENDING && a.pendingFinalStatus != Status.FAILED
                        && a.pendingFinalStatus != Status.CANCELLED && a.pendingFinalStatus != Status.VICTORY_PENDING)
                    throw new IllegalArgumentException("Invalid cleanup destination");
                if (!a.spawnedEntities.containsAll(a.deadEntities)) throw new IllegalArgumentException("Unknown dead raid entity");
                if (!a.spawnedEntities.containsAll(a.cleanedEntities)) throw new IllegalArgumentException("Unknown cleaned raid entity");
                if (row.hasUUID("bossId")) a.bossId = row.getUUID("bossId");
                if (row.contains("arena")) a.arena = RaidDefinition.parseArena(RaidDefinition.id(row.getString("arenaId")), JsonParser.parseString(row.getString("arena")).getAsJsonObject());
                if (a.arena != null && !definition.arenaIds().contains(a.arena.id())) throw new IllegalArgumentException("Unknown frozen raid arena");
                if (a.bossId != null && !a.spawnedEntities.contains(a.bossId)) throw new IllegalArgumentException("Untracked raid boss");
                if ((a.status == Status.STARTING || a.status == Status.ACTIVE || a.status == Status.VICTORY_PENDING
                        || a.status == Status.CLEANUP_PENDING && a.bossId != null) && a.arena == null)
                    throw new IllegalArgumentException("Active raid lacks frozen arena");
                if (a.status == Status.ACTIVE && a.bossId == null)
                    throw new IllegalArgumentException("Active raid lacks boss/start time");
                if ((a.status == Status.FORMING || a.status == Status.QUEUED) && a.arena != null)
                    throw new IllegalArgumentException("Queued raid unexpectedly owns arena");
                var members = compoundList(row, "members", false);
                if (members.isEmpty() || members.size() > definition.maximumPlayers()) throw new IllegalArgumentException("Invalid raid roster");
                for (Tag value : members) {
                    var m = (CompoundTag) value; var member = new Member(m.getUUID("id"));
                    member.deaths = m.getInt("deaths"); member.offlineSince = m.getLong("offlineSince");
                    member.eliminated = m.getBoolean("eliminated"); member.returned = m.getBoolean("returned");
                    member.awaitingRespawn = m.getBoolean("awaitingRespawn");
                    if (member.deaths < 0 || member.deaths > 100 || member.offlineSince < -1) throw new IllegalArgumentException("Invalid raid member");
                    if (m.contains("return")) {
                        var p = m.getCompound("return");
                        var vec = new Vec3(p.getDouble("x"), p.getDouble("y"), p.getDouble("z"));
                        float yaw = p.getFloat("yaw"), pitch = p.getFloat("pitch");
                        if (!Double.isFinite(vec.x) || !Double.isFinite(vec.y) || !Double.isFinite(vec.z)
                                || Math.abs(vec.x) > 30_000_000 || Math.abs(vec.z) > 30_000_000 || Math.abs(vec.y) > 30_000_000
                                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Invalid return position");
                        member.returnPoint = new ReturnPoint(RaidDefinition.id(p.getString("dimension")), vec, yaw, pitch);
                    }
                    if (a.members.putIfAbsent(member.playerId, member) != null
                            || a.occupiesPlayerSlot() && !activePlayers.add(member.playerId)) throw new IllegalArgumentException("Duplicate raid membership");
                }
                if (!a.members.containsKey(a.leader) || state.attempts.putIfAbsent(a.id, a) != null) throw new IllegalArgumentException("Invalid raid owner or duplicate attempt");
                if (!a.members.keySet().containsAll(a.rewardRecipients)) throw new IllegalArgumentException("Unknown raid reward recipient");
                if (a.status == Status.ACTIVE && a.members.values().stream().anyMatch(m -> m.returnPoint == null))
                    throw new IllegalArgumentException("Active raid lacks return point");
                if (a.ownsArena() && state.attempts.values().stream().filter(other -> other != a && other.ownsArena())
                        .anyMatch(other -> other.arena.dimension().equals(a.arena.dimension()) && other.arena.bounds().inflate(16).intersects(a.arena.bounds())))
                    throw new IllegalArgumentException("Conflicting persisted raid arenas");
            }
        } catch (RuntimeException bad) {
            state.rejected = tag.copy(); state.attempts.clear();
            MythicTrpg.LOGGER.error("Raid state is read-only; original data preserved", bad);
        }
        return state;
    }
    private static ListTag uuids(Set<UUID> values) {
        var list = new ListTag(); for (UUID id : values) { var tag = new CompoundTag(); tag.putUUID("id", id); list.add(tag); } return list;
    }
    private static Set<UUID> readUuids(CompoundTag tag, String key, int max) {
        var list = compoundList(tag, key, key.equals("cleanedEntities"));
        if (list.size() > max) throw new IllegalArgumentException("Too many " + key);
        var values = new LinkedHashSet<UUID>();
        for (Tag raw : list) if (!values.add(((CompoundTag) raw).getUUID("id"))) throw new IllegalArgumentException("Duplicate " + key);
        return Set.copyOf(values);
    }
    private static ListTag compoundList(CompoundTag tag, String key, boolean optional) {
        Tag raw = tag.get(key);
        if (raw == null && optional) return new ListTag();
        if (!(raw instanceof ListTag list) || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid raid list: " + key);
        return list;
    }
}
