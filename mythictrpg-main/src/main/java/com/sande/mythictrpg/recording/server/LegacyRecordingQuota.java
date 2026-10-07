package com.sande.mythictrpg.recording.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Game-only bridge. Absent registration is OFF; never intercepts ordinary game SavedData. */
public final class LegacyRecordingQuota {
    public static final long SAVED_DATA_WRITE_BOUND = 128 * 1024;
    private static final Object LOCK = new Object();
    private static final Map<Path, WorldRecordingBudget> ACTIVE = new HashMap<>();
    private static final Set<Path> STARTING = new HashSet<>();
    private static final Map<Path, Set<Ticket>> IN_FLIGHT = new HashMap<>();
    private static final Map<Path, Map<String, String>> DIAGNOSTICS = new HashMap<>();
    private static final Map<Path, Map<String, LegacyLimits>> LIMITS = new HashMap<>();
    private static final ThreadLocal<Ticket> WORKER_SCOPE = new ThreadLocal<>();
    private LegacyRecordingQuota() {}

    /** Startup worker only, before reporting archive ready. Rejects racing unbudgeted OFF writes. */
    public static void install(Path worldRoot, WorldRecordingBudget budget) throws IOException {
        Path world = worldRoot.toAbsolutePath().normalize();
        if (!world.equals(budget.registry().worldRoot())) throw new IOException("QUOTA_WORLD_MISMATCH");
        synchronized (LOCK) {
            if (ACTIVE.containsKey(world)) throw new IOException("QUOTA_ALREADY_INSTALLED");
            if (!IN_FLIGHT.getOrDefault(world, Set.of()).isEmpty()) throw new IOException("QUOTA_LEGACY_WRITER_BUSY");
            ACTIVE.put(world, budget); STARTING.add(world);
        }
        try { budget.refresh(); }
        catch (IOException failure) {
            synchronized (LOCK) { ACTIVE.remove(world, budget); STARTING.remove(world); }
            throw failure;
        }
        synchronized (LOCK) { STARTING.remove(world); }
    }
    /** Call only after the server's legacy writers have drained. Never silently drops live reservations. */
    public static void uninstall(Path worldRoot, WorldRecordingBudget budget) throws IOException {
        Path world = worldRoot.toAbsolutePath().normalize();
        synchronized (LOCK) {
            requireDrained(world, budget);
            ACTIVE.remove(world); DIAGNOSTICS.remove(world); LIMITS.remove(world);
        }
    }
    /** Before the archive clean-shutdown marker: a pending/failed record snapshot means shutdown is not clean. */
    public static void requireDrained(Path worldRoot, WorldRecordingBudget budget) throws IOException {
        Path world = worldRoot.toAbsolutePath().normalize();
        synchronized (LOCK) {
            if (ACTIVE.get(world) != budget) throw new IOException("QUOTA_WORLD_BINDING_MISMATCH");
            if (STARTING.contains(world)) throw new IOException("QUOTA_STARTING");
            if (!IN_FLIGHT.getOrDefault(world, Set.of()).isEmpty()) throw new IOException("QUOTA_LEGACY_WRITER_BUSY");
        }
    }
    public static boolean active(Path worldRoot) {
        synchronized (LOCK) { return ACTIVE.containsKey(worldRoot.toAbsolutePath().normalize()); }
    }
    /** The supplied root must be the server's known store root/file, not a user-selected arbitrary path. */
    public static Ticket reserve(Path storeRoot, String storeId, long maximumAdditionalBytes, boolean maintenance) throws IOException {
        Path path = storeRoot.toAbsolutePath().normalize();
        if (maximumAdditionalBytes < 0) throw new IllegalArgumentException("Negative reservation");
        Path world = worldFor(path, storeId);
        Ticket scope = WORKER_SCOPE.get();
        if (scope != null) return scope.borrow(path, storeId, maximumAdditionalBytes);
        synchronized (LOCK) {
            if (STARTING.contains(world)) throw new IOException("QUOTA_STARTING");
            WorldRecordingBudget budget = ACTIVE.get(world);
            WorldRecordingBudget.Reservation reservation = null;
            if (budget != null) {
                if (!budget.registry().root(storeId).equals(path)) throw new IOException("QUOTA_UNREGISTERED_STORE_PATH");
                reservation = budget.reserve(storeId, maximumAdditionalBytes, maintenance);
            }
            Ticket ticket = new Ticket(world, path, storeId, budget, reservation, maximumAdditionalBytes, null);
            IN_FLIGHT.computeIfAbsent(world, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(ticket);
            return ticket;
        }
    }
    @FunctionalInterface public interface CheckedOperation<T> { T run() throws Exception; }
    /** Worker-scoped cumulative bound: nested store/control appends consume, rather than double-reserve, admission. */
    public static <T> T within(Ticket ticket, CheckedOperation<T> operation) throws Exception {
        if (WORKER_SCOPE.get() != null) throw new IllegalStateException("Nested quota work scope");
        ticket.started = true; WORKER_SCOPE.set(ticket);
        try { ticket.validateBeforeWrite(); return operation.run(); }
        finally { WORKER_SCOPE.remove(); ticket.close(); }
    }
    public static Optional<Ticket> tryReserve(Path storeRoot, String storeId, long maximumAdditionalBytes, boolean maintenance) {
        try { return Optional.of(reserve(storeRoot, storeId, maximumAdditionalBytes, maintenance)); }
        catch (IOException failure) { report(worldFor(storeRoot.toAbsolutePath().normalize(), storeId), storeId, failure.getMessage()); return Optional.empty(); }
    }
    public static Map<String, String> diagnostics(Path worldRoot) {
        synchronized (LOCK) { return Map.copyOf(DIAGNOSTICS.getOrDefault(worldRoot.toAbsolutePath().normalize(), Map.of())); }
    }
    /** Separate technical limits remain visible; the shared byte cap does not remove legacy indexes/codecs. */
    public record LegacyLimits(long maxBytes, int maxEntries, int maxFrameBytes, String limitingReason) { }
    public static void legacyLimits(Path storeRoot, String storeId, long maxBytes, int maxEntries, int maxFrameBytes, String reason) {
        synchronized (LOCK) { LIMITS.computeIfAbsent(worldFor(storeRoot.toAbsolutePath().normalize(), storeId), ignored -> new HashMap<>())
                .put(storeId, new LegacyLimits(maxBytes, maxEntries, maxFrameBytes, reason)); }
    }
    public static Map<String, LegacyLimits> legacyLimits(Path worldRoot) {
        synchronized (LOCK) { return Map.copyOf(LIMITS.getOrDefault(worldRoot.toAbsolutePath().normalize(), Map.of())); }
    }
    public static void report(Path worldRoot, String storeId, String reason) {
        synchronized (LOCK) { DIAGNOSTICS.computeIfAbsent(worldRoot.toAbsolutePath().normalize(), ignored -> new HashMap<>())
                .put(storeId, reason == null ? "QUOTA_UNCERTAIN" : reason); }
    }
    private static Path worldFor(Path path, String storeId) {
        return switch (storeId) {
            case ManagedStoreRegistry.ACTION_LEDGER, ManagedStoreRegistry.GOD_WATCH, ManagedStoreRegistry.RECORDING -> Objects.requireNonNull(path.getParent());
            case ManagedStoreRegistry.RUMOR, ManagedStoreRegistry.REPUTATION -> Objects.requireNonNull(path.getParent().getParent());
            default -> throw new IllegalArgumentException("Unregistered legacy store");
        };
    }
    /** Only rumor/reputation own one of these. Captured reservations outlive setDirty(false). */
    public static final class SavedDataGate {
        private final String storeId;
        private Path file;
        private Ticket pending;
        private final ArrayDeque<Ticket> failed = new ArrayDeque<>();
        private boolean retryNeeded;
        public SavedDataGate(String storeId) {
            if (!Set.of(ManagedStoreRegistry.RUMOR, ManagedStoreRegistry.REPUTATION).contains(storeId))
                throw new IllegalArgumentException("Only dedicated recording SavedData can be quota gated");
            this.storeId = storeId;
        }
        public synchronized void bind(Path target) {
            Path normalized = target.toAbsolutePath().normalize();
            if (file != null && !file.equals(normalized)) throw new IllegalStateException("SavedData world changed");
            file = normalized;
            legacyLimits(file, storeId, 0, 4096, 65_535, "LEGACY_INDEX_OR_NBT_STRING_LIMIT");
        }
        public synchronized boolean enabled() { return file != null && active(worldFor(file, storeId)); }
        /** Prospective immutable state is checked and reserved BEFORE mutating the live ledger. */
        public synchronized boolean admit(String json, boolean maintenance) {
            if (!enabled()) return true;
            if (!supportedJson(json)) {
                report(worldFor(file, storeId), storeId, "LEGACY_NBT_STRING_LIMIT"); return false;
            }
            if (pending != null) return pending.canAdmit();
            if (!failed.isEmpty()) { pending = failed.removeFirst(); return pending.canAdmit(); }
            pending = tryReserve(file, storeId, SAVED_DATA_WRITE_BOUND, maintenance).orElse(null);
            return pending != null;
        }
        /** On the server thread, freeze the snapshot and detach its admission for the I/O worker. */
        public synchronized Ticket capture(Path target, String json) throws IOException {
            bind(target);
            if (enabled() && !supportedJson(json)) {
                report(worldFor(file, storeId), storeId, "LEGACY_NBT_STRING_LIMIT");
                throw new IOException("LEGACY_NBT_STRING_LIMIT");
            }
            if (pending == null && !failed.isEmpty()) pending = failed.removeFirst();
            Ticket result = pending == null ? reserve(file, storeId, SAVED_DATA_WRITE_BOUND, true) : pending;
            pending = null;
            return result;
        }
        /** The callback must run after atomic move/flush (not merely after serialization or queueing). */
        public void completed(Ticket ticket) throws IOException {
            ticket.close();
            List<Ticket> obsolete;
            synchronized (this) { obsolete = new ArrayList<>(failed); failed.clear(); retryNeeded = false; }
            for (Ticket prior : obsolete) {
                try { prior.close(); }
                catch (IOException failure) { failed(prior, failure); }
            }
        }
        /** Keep the bounded reservation through the retry, including any abandoned physical temp. */
        public synchronized void failed(Ticket ticket, Exception failure) {
            if (!failed.contains(ticket)) failed.addLast(ticket);
            retryNeeded = true; ticket.failed("SAVEDDATA_IO_RETRY_PENDING");
        }
        public synchronized boolean retryNeeded() { return retryNeeded; }
        public synchronized void rejected(Exception failure) {
            retryNeeded = true;
            if (file != null) report(worldFor(file, storeId), storeId, failure.getMessage());
        }
        /** Existing wire format is one NBT StringTag; no implicit chunk/schema migration. */
        public static boolean supportedJson(String value) {
            if (value == null || value.length() > 65_535) return false;
            int length = 0;
            for (int i = 0; i < value.length(); i++) {
                int c = value.charAt(i); length += c >= 1 && c <= 127 ? 1 : c > 2047 ? 3 : 2;
                if (length > 65_535) return false;
            }
            return true;
        }
    }
    public static final class Ticket implements AutoCloseable {
        private final Path world, path;
        private final String storeId;
        private final WorldRecordingBudget budget;
        private final WorldRecordingBudget.Reservation reservation;
        private final Ticket parent;
        private long unspent;
        private boolean started;
        private boolean closed;
        private Ticket(Path world, Path path, String storeId, WorldRecordingBudget budget, WorldRecordingBudget.Reservation reservation,
                long maximumAdditionalBytes, Ticket parent) {
            this.world = world; this.path = path; this.storeId = storeId; this.budget = budget; this.reservation = reservation;
            this.unspent = maximumAdditionalBytes; this.parent = parent;
        }
        private synchronized Ticket borrow(Path requestedPath, String requestedStore, long bound) throws IOException {
            if (closed || !path.equals(requestedPath) || !storeId.equals(requestedStore)
                    || bound < 0 || bound > unspent) throw new IOException("QUOTA_WORK_BOUND_EXCEEDED");
            unspent -= bound;
            return new Ticket(world, path, storeId, budget, null, bound, this);
        }
        public boolean enforced() { return budget != null; }
        private synchronized boolean canAdmit() {
            if (closed) return false;
            if (budget == null) return true;
            var snapshot = budget.snapshot();
            try { return !snapshot.state().startsWith("QUOTA_")
                    && Math.addExact(snapshot.usedPhysicalBytes(), snapshot.outstandingReservations()) <= snapshot.limitBytes(); }
            catch (ArithmeticException overflow) { return false; }
        }
        public Path storeRoot() { return path; }
        public synchronized void validateBeforeWrite() throws IOException {
            if (closed) throw new IOException("QUOTA_TICKET_ALREADY_CLOSED");
            started = true;
            if (budget != null) {
                if (!canAdmit()) throw new IOException("QUOTA_CHANGED_AFTER_ADMISSION");
                budget.registry().validate(path);
            }
        }
        /** Worker-only: include abandoned bytes while retaining the full next-attempt growth bound. */
        public void measureFailedIo() throws IOException { if (budget != null) budget.refresh(); }
        /** Actual worker termination only, including failed partial writes. Double completion is rejected. */
        @Override public synchronized void close() throws IOException {
            if (closed) throw new IllegalStateException("Quota ticket already completed");
            if (parent != null) { closed = true; return; }
            if (budget != null) budget.reconcile(reservation);
            synchronized (LOCK) {
                Set<Ticket> tickets = IN_FLIGHT.get(world);
                if (tickets == null || !tickets.remove(this)) throw new IllegalStateException("Untracked quota ticket");
                if (tickets.isEmpty()) IN_FLIGHT.remove(world);
                closed = true;
            }
        }
        /** Queue rejection/drain only. Never cancels an operation that has entered its I/O scope. */
        public synchronized void cancelUnstarted() {
            if (closed) return;
            if (started || parent != null) throw new IllegalStateException("Cannot cancel started quota work");
            if (budget != null) budget.cancelUnstarted(reservation);
            synchronized (LOCK) {
                Set<Ticket> tickets = IN_FLIGHT.get(world);
                if (tickets == null || !tickets.remove(this)) throw new IllegalStateException("Untracked quota ticket");
                if (tickets.isEmpty()) IN_FLIGHT.remove(world);
                closed = true;
            }
        }
        public synchronized boolean completed() { return closed; }
        public void failed(String reason) { report(world, storeId, reason); }
    }
}
