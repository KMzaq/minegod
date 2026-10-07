package com.sande.mythictrpg.recording.server;

import java.io.IOException;
import java.util.*;

/** One checked-64-bit budget for all recording stores in one overworld. Not an OS disk quota. */
public final class WorldRecordingBudget {
    public record Snapshot(long limitBytes, long maintenanceHeadroomBytes, long usedPhysicalBytes,
            long outstandingReservations, Map<String, Long> storeBytes, String state) {
        public Snapshot { storeBytes = Map.copyOf(storeBytes); }
    }
    /** Opaque, owner-bound, single-use reservation. Only reconcile AFTER actual I/O termination. */
    public static final class Reservation {
        private final WorldRecordingBudget owner;
        private final String storeId;
        private final long bytes;
        private Reservation(WorldRecordingBudget owner, String storeId, long bytes) {
            this.owner = owner; this.storeId = storeId; this.bytes = bytes;
        }
        public String storeId() { return storeId; }
        public long bytes() { return bytes; }
    }
    private final ManagedStoreRegistry registry;
    private final long limit, headroom;
    private final long warningThreshold, deferThreshold;
    private final Set<Reservation> reservations = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Object measurementLock = new Object();
    private ManagedStoreRegistry.Measurement measured;
    private long outstanding;
    private String uncertainty;
    private String lastRejection = "NONE";

    public WorldRecordingBudget(ManagedStoreRegistry registry, long limitBytes, long maintenanceHeadroomBytes) throws IOException {
        this(registry, limitBytes, maintenanceHeadroomBytes, 0.90, 0.95);
    }
    public WorldRecordingBudget(ManagedStoreRegistry registry, long limitBytes, long maintenanceHeadroomBytes,
            double warningRatio, double deferBackgroundRatio) throws IOException {
        this.registry = Objects.requireNonNull(registry);
        if (limitBytes < 1 || maintenanceHeadroomBytes < 0 || maintenanceHeadroomBytes >= limitBytes)
            throw new IllegalArgumentException("recording quota/headroom");
        if (!Double.isFinite(warningRatio) || !Double.isFinite(deferBackgroundRatio)
                || warningRatio <= 0 || warningRatio >= deferBackgroundRatio || deferBackgroundRatio >= 1)
            throw new IllegalArgumentException("recording quota ratios");
        limit = limitBytes; headroom = maintenanceHeadroomBytes;
        warningThreshold = threshold(limit, warningRatio); deferThreshold = threshold(limit, deferBackgroundRatio);
        measured = registry.measure();
        if (measured.totalBytes() > limit) uncertainty = "QUOTA_ALREADY_OVER_LIMIT";
    }
    public ManagedStoreRegistry registry() { return registry; }
    /** No disk scan on the game thread. Outstanding admissions remain charged until a worker reconciles. */
    public synchronized Optional<Reservation> tryReserve(String storeId, long maxAdditionalBytes, boolean maintenance) {
        registry.root(storeId);
        if (maxAdditionalBytes < 0) throw new IllegalArgumentException("Negative reservation");
        if (uncertainty != null) { lastRejection = uncertainty; return Optional.empty(); }
        try {
            long admitted = Math.addExact(Math.addExact(measured.totalBytes(), outstanding), maxAdditionalBytes);
            if (admitted > limit) { lastRejection = "FULL"; return Optional.empty(); }
            if (!maintenance && Math.addExact(admitted, headroom) > limit) {
                lastRejection = "MAINTENANCE_HEADROOM"; return Optional.empty();
            }
            var result = new Reservation(this, storeId, maxAdditionalBytes);
            outstanding = Math.addExact(outstanding, maxAdditionalBytes); reservations.add(result);
            lastRejection = "NONE"; return Optional.of(result);
        } catch (ArithmeticException overflow) { lastRejection = "QUOTA_ARITHMETIC_OVERFLOW"; return Optional.empty(); }
    }
    public synchronized Reservation reserve(String storeId, long maxAdditionalBytes, boolean maintenance) throws IOException {
        return tryReserve(storeId, maxAdditionalBytes, maintenance).orElseThrow(() -> new IOException(lastRejection));
    }
    /** An admission canceled before any I/O may be returned without a filesystem scan. */
    synchronized void cancelUnstarted(Reservation reservation) {
        if (reservation == null || reservation.owner != this || !reservations.remove(reservation))
            throw new IllegalStateException("Foreign or already completed reservation");
        outstanding = Math.subtractExact(outstanding, reservation.bytes);
    }
    /** Worker only. Failure keeps the reservation and closes admission; no guessed byte release. */
    public void reconcile(Reservation reservation) throws IOException {
        synchronized (measurementLock) {
            synchronized (this) {
                if (reservation == null || reservation.owner != this || !reservations.contains(reservation))
                    throw new IllegalStateException("Foreign or already reconciled reservation");
            }
            refresh();
            synchronized (this) {
                outstanding = Math.subtractExact(outstanding, reservation.bytes); reservations.remove(reservation);
            }
        }
    }
    /** Worker only. Concurrent writers' reservations remain charged (temporarily conservative double counting). */
    public void refresh() throws IOException {
        synchronized (measurementLock) {
            try {
                var next = registry.measure();
                synchronized (this) {
                    measured = next;
                    if (measured.totalBytes() > limit) {
                        uncertainty = "QUOTA_EXTERNAL_OR_UNBOUNDED_GROWTH"; throw new IOException(uncertainty);
                    }
                }
            } catch (IOException | ArithmeticException failure) {
                synchronized (this) { if (uncertainty == null) uncertainty = "QUOTA_UNCERTAIN"; }
                if (failure instanceof IOException io) throw io;
                throw new IOException("QUOTA_UNCERTAIN", failure);
            }
        }
    }
    public synchronized void markUncertain(String reason) {
        uncertainty = reason == null || reason.isBlank() ? "QUOTA_UNCERTAIN" : reason;
    }
    public synchronized Snapshot snapshot() {
        String state = uncertainty;
        if (state == null) {
            try {
                long occupied = Math.addExact(measured.totalBytes(), outstanding);
                if (occupied >= limit) state = "FULL";
                else if (Math.addExact(occupied, headroom) >= limit) state = "MAINTENANCE_HEADROOM";
                else if (occupied >= deferThreshold) state = "DEFER_BACKGROUND";
                else if (occupied >= warningThreshold) state = "WARNING";
                else state = "READY";
            } catch (ArithmeticException overflow) { state = "QUOTA_ARITHMETIC_OVERFLOW"; }
        }
        return new Snapshot(limit, headroom, measured.totalBytes(), outstanding, measured.storeBytes(), state);
    }
    private static long threshold(long limit, double ratio) {
        return java.math.BigDecimal.valueOf(limit).multiply(java.math.BigDecimal.valueOf(ratio))
                .setScale(0, java.math.RoundingMode.CEILING).longValueExact();
    }
}
