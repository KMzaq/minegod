package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.function.Function;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import com.sande.mythictrpg.recording.server.LegacyRecordingQuota;
import com.sande.mythictrpg.recording.server.ManagedStoreRegistry;
import com.sande.mythictrpg.recording.server.ManagedSavedDataIo;
import net.minecraft.world.level.storage.LevelResource;

/** Independent versioned state; does not add entities, alter player profiles or migrate LP files. */
public final class RumorSavedData extends SavedData {
    public enum RecordingStatus { DISABLED, WAITING_COMMIT, READY, UNAVAILABLE_METADATA, UNAVAILABLE_LIMIT, UNAVAILABLE_CURSOR }
    private static final Gson JSON = new Gson();
    private static final Factory<RumorSavedData> FACTORY = new Factory<>(RumorSavedData::new, RumorSavedData::load);
    private RumorLedger ledger = new RumorLedger();
    private int storageVersion = 1;
    private CompoundTag rejected;
    private final LegacyRecordingQuota.SavedDataGate quota = new LegacyRecordingQuota.SavedDataGate(ManagedStoreRegistry.RUMOR);
    private final Thread recordingThread = Thread.currentThread();
    private boolean recordingEnabled;
    private RumorRecordingState.State recording;
    private Tag rejectedRecording;
    private final AtomicReference<RumorRecordingState.DurableView> durableRecording = new AtomicReference<>();
    private final AtomicLong recordingFence = new AtomicLong();
    private final AtomicInteger pendingWrites = new AtomicInteger();
    public static RumorSavedData get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Rumor state requires server thread");
        RumorSavedData data = server.overworld().getDataStorage().computeIfAbsent(FACTORY, "mythictrpg_memory_rumor_v1");
        data.bindQuota(server); return data;
    }
    public RumorSavedData() { installMutationGate(); setDirty(); }
    public boolean ready() { return rejected == null; }
    public java.util.UUID worldId() { requireReady(); return ledger.worldId(); }
    RumorLedger.Snapshot snapshot() { requireReady(); return ledger.snapshot(); }
    <T> T access(MinecraftServer server, Function<RumorLedger,T> operation) {
        if (!server.isSameThread()) throw new IllegalStateException("Rumor state requires server thread");
        requireReady(); bindQuota(server); long before = ledger.revision();
        try { return operation.apply(ledger); }
        finally { if (before != ledger.revision()) setDirty(); }
    }
    /** AI receives only authorized immutable claims, never the mutable ledger or other recipients' records. */
    public java.util.List<RumorLedger.HeardRumor> heard(MinecraftServer server, java.util.UUID subject,
            String god, java.util.Set<java.util.UUID> audience) {
        return ready() ? access(server, ledger -> CourierRumorService.heard(server,ledger,subject,god,audience).stream()
                .map(h -> ReputationService.decorate(server,subject,god,audience,h)).toList()) : java.util.List.of();
    }
    private void requireReady() { if (!ready()) throw new IllegalStateException("Rumor state quarantined"); }
    private void bindQuota(MinecraftServer server) {
        quota.bind(server.getWorldPath(LevelResource.ROOT).resolve("data/mythictrpg_memory_rumor_v1.dat"));
        installMutationGate();
    }
    private void installMutationGate() {
        ledger.mutationGate(() -> recording != null || quota.enabled(), (prospective, maintenance) -> {
            RumorRecordingState.State next = null;
            if (recording != null) {
                try { next = RumorRecordingState.advance(recording, ledger.snapshot(), prospective, recordingEnabled); }
                catch (IllegalArgumentException invalidMetadata) {
                    next = RumorRecordingState.unavailable(recording, RumorRecordingState.Status.UNAVAILABLE_MISMATCH, prospective);
                }
            }
            String gameJson = JSON.toJson(prospective);
            // Metadata is bounded prospectively, too. A provenance failure disables the adapter, not the game operation.
            if (next != null && !RumorRecordingState.supported(gameJson, RumorRecordingState.encode(next)))
                next = RumorRecordingState.unavailable(next, RumorRecordingState.Status.UNAVAILABLE_LIMIT, prospective);
            if (!quota.admit(gameJson, maintenance)) return false;
            if (next != null) {
                recording = next;
                if (next.status() != RumorRecordingState.Status.ACTIVE) invalidateRecordingCheckpoint();
            }
            return true;
        });
    }
    /** Game-thread policy input. OFF preserves metadata but never gives OFF-born roots/receipts a new origin. */
    public void recordingEnabled(boolean enabled) {
        requireRecordingThread(); recordingEnabled = enabled;
        if (!enabled || !ready() || recording != null || rejectedRecording != null) return;
        var snapshot = ledger.snapshot(); var candidate = RumorRecordingState.begin(snapshot);
        String gameJson = JSON.toJson(snapshot);
        if (!RumorRecordingState.supported(gameJson, RumorRecordingState.encode(candidate)))
            candidate = RumorRecordingState.unavailable(candidate, RumorRecordingState.Status.UNAVAILABLE_LIMIT, snapshot);
        if (!quota.admit(gameJson, true)) return;
        recording = candidate; setDirty();
    }
    /** Thread-safe confirmed immutable disk state; safe even after the last game tick during the I/O shutdown fence. */
    public Optional<RumorRecordingState.DurableView> recordingSnapshot() {
        return Optional.ofNullable(durableRecording.get());
    }
    /** Content-free game-thread diagnostic; not a substitute for a confirmed source snapshot. */
    public RecordingStatus recordingStatus() {
        requireRecordingThread();
        if (!ready() || rejectedRecording != null) return RecordingStatus.UNAVAILABLE_METADATA;
        if (recording != null && recording.status() != RumorRecordingState.Status.ACTIVE) return switch (recording.status()) {
            case UNAVAILABLE_LIMIT -> RecordingStatus.UNAVAILABLE_LIMIT;
            case UNAVAILABLE_CURSOR -> RecordingStatus.UNAVAILABLE_CURSOR;
            default -> RecordingStatus.UNAVAILABLE_METADATA;
        };
        if (!recordingEnabled) return RecordingStatus.DISABLED;
        return durableRecording.get() == null ? RecordingStatus.WAITING_COMMIT : RecordingStatus.READY;
    }
    /** Targeted nonblocking save coalesces in-flight work; vanilla final saves still queue their newer dirty snapshot. */
    public void flushRecordingSnapshot(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Rumor state requires server thread");
        requireRecordingThread(); bindQuota(server);
        if (ready() && recording != null && isDirty() && pendingWrites.get() == 0)
            save(server.getWorldPath(LevelResource.ROOT).resolve("data/mythictrpg_memory_rumor_v1.dat").toFile(), server.registryAccess());
    }
    private void requireRecordingThread() {
        if (Thread.currentThread() != recordingThread) throw new IllegalStateException("Rumor recording requires owning game thread");
    }
    private void invalidateRecordingCheckpoint() {
        synchronized (durableRecording) { recordingFence.incrementAndGet(); durableRecording.set(null); }
    }
    @Override public boolean isDirty() { return super.isDirty() || quota.retryNeeded(); }
    @Override public void save(java.io.File file, HolderLookup.Provider registries) {
        // Vanilla final save must enqueue newer dirty state behind an older write; there may be no next tick to retry.
        if (!isDirty()) return;
        boolean queued = false;
        final AtomicInteger completion = new AtomicInteger();
        pendingWrites.incrementAndGet();
        try {
            CompoundTag frozen = save(new CompoundTag(), registries);
            final long fence = recordingFence.get();
            final RumorRecordingState.DurableView checkpoint = recording != null
                    && recording.status() == RumorRecordingState.Status.ACTIVE && rejected == null && rejectedRecording == null
                    ? new RumorRecordingState.DurableView(recording,
                        JSON.fromJson(frozen.getString("state"), RumorLedger.Snapshot.class)) : null;
            queued = ManagedSavedDataIo.queue(file, frozen, quota, result -> {
                // This runs after atomic force/move and quota reconciliation, possibly after the last server tick.
                // It only publishes the immutable captured view; no game state or server.execute is touched here.
                try {
                    synchronized (durableRecording) {
                        if (result.status() == ManagedSavedDataIo.WriteStatus.COMMITTED && checkpoint != null
                                && recordingFence.get() == fence) durableRecording.set(checkpoint);
                    }
                } finally { if (completion.compareAndSet(0, 1)) pendingWrites.decrementAndGet(); }
            });
            if (queued) setDirty(false);
        } finally { if (!queued && completion.compareAndSet(0, 1)) pendingWrites.decrementAndGet(); }
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (rejected != null) return rejected.copy();
        var snapshot=ledger.snapshot();
        if(snapshot.evidence().stream().anyMatch(e->e.proof()!=null))storageVersion=RumorLedger.VERSION;
        // Reading an LP/PERSONAL world while this feature is OFF must not upgrade its storage format.
        if(storageVersion==1)snapshot=new RumorLedger.Snapshot(1,snapshot.worldId(),snapshot.couriers(),snapshot.evidence(),snapshot.claims(),snapshot.pending(),snapshot.receipts());
        tag.putInt("dataVersion", storageVersion); tag.putString("state", JSON.toJson(snapshot));
        if (rejectedRecording != null) tag.put(RumorRecordingState.NBT_KEY, rejectedRecording.copy());
        else if (recording != null) tag.putString(RumorRecordingState.NBT_KEY, RumorRecordingState.encode(recording));
        return tag;
    }
    public static RumorSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RumorSavedData data = new RumorSavedData();
        try {
            if (!java.util.Set.of(1,RumorLedger.VERSION).contains(tag.getInt("dataVersion")) || tag.getString("state").length() > 16_000_000) throw new IllegalArgumentException("Invalid schema/size");
            var snapshot=JSON.fromJson(tag.getString("state"), RumorLedger.Snapshot.class);
            if(snapshot.version()!=tag.getInt("dataVersion"))throw new IllegalArgumentException("mismatched rumor schema");
            data.ledger = RumorLedger.restore(snapshot);
            data.storageVersion = snapshot.version();
            if (tag.contains(RumorRecordingState.NBT_KEY)) {
                try {
                    if (!tag.contains(RumorRecordingState.NBT_KEY, Tag.TAG_STRING))
                        throw new IllegalArgumentException("Invalid recording metadata type");
                    // Validate against exactly what was on disk, before restore prunes stale pending deliveries.
                    data.recording = RumorRecordingState.decode(tag.getString(RumorRecordingState.NBT_KEY), snapshot);
                    if (data.recording.status() == RumorRecordingState.Status.ACTIVE)
                        data.durableRecording.set(new RumorRecordingState.DurableView(data.recording, snapshot));
                    var restored = data.ledger.snapshot();
                    if (!RumorRecordingState.fingerprint(snapshot).equals(RumorRecordingState.fingerprint(restored)))
                        data.recording = RumorRecordingState.advance(data.recording, snapshot, restored, false);
                } catch (RuntimeException failure) {
                    data.recording = null; data.rejectedRecording = tag.get(RumorRecordingState.NBT_KEY).copy();
                    data.invalidateRecordingCheckpoint();
                    MythicTrpg.LOGGER.warn("Rumor recording metadata unavailable; original metadata and game state retained");
                }
            }
            data.installMutationGate();
        } catch (RuntimeException failure) {
            data.rejected = tag.copy(); MythicTrpg.LOGGER.error("Rumor state preserved read-only after load failure", failure);
        }
        return data;
    }
}
