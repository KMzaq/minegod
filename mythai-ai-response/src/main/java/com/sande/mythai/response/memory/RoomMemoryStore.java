package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Durable heard-dialogue projections, not authoritative world facts. One full message, many God listeners. */
public final class RoomMemoryStore implements AutoCloseable {
    public static final int MAX_TEXT = 131072, MAX_PLAYERS = 4096, MAX_GODS = 16;
    private static final Gson JSON = new Gson();
    public enum Result { STORED, DUPLICATE, CONFLICT, FULL, STALE, UNAVAILABLE }
    public record Record(UUID messageId, UUID worldId, UUID sourceRoomId, long sourceRevision, long occurredAt,
            String role, String speakerId, String speakerName, String text, boolean publicSpeech,
            Set<UUID> fullPlayerAudience, Set<String> heardGodIds, List<RoomEvidenceReference> evidenceRefs,
            Set<UUID> sourceMessageIds) {
        public Record {
            Objects.requireNonNull(messageId); Objects.requireNonNull(worldId); Objects.requireNonNull(sourceRoomId);
            Objects.requireNonNull(role); Objects.requireNonNull(speakerId); Objects.requireNonNull(speakerName);
            fullPlayerAudience = Set.copyOf(fullPlayerAudience); heardGodIds = Set.copyOf(heardGodIds);
            evidenceRefs = List.copyOf(evidenceRefs); sourceMessageIds = Set.copyOf(sourceMessageIds);
            if (sourceRevision < 0 || occurredAt < 0 || !Set.of("PLAYER", "NPC").contains(role)
                    || text == null || text.isBlank() || text.length() > MAX_TEXT || speakerName.length() > 256
                    || fullPlayerAudience.size() > MAX_PLAYERS || heardGodIds.isEmpty() || heardGodIds.size() > MAX_GODS
                    || heardGodIds.stream().anyMatch(g -> !validGod(g)) || evidenceRefs.size() > 64
                    || evidenceRefs.stream().mapToInt(r -> r.payload().length()).sum() > 262144
                    || sourceMessageIds.size() > 256 || sourceMessageIds.contains(messageId))
                throw new IllegalArgumentException("Invalid room memory record");
            if (role.equals("PLAYER")) UUID.fromString(speakerId);
            // Game publication validates speaker identity separately. A leaving God can speak while only
            // the remaining Gods are certified listeners; the speaker must not get a fabricated receipt.
            else if (!validGod(speakerId)) throw new IllegalArgumentException("Invalid NPC speaker");
        }
    }
    public record Scope(UUID worldId, String readerGodId, UUID requesterId, Set<UUID> playerAudience,
            Set<String> godAudience, boolean publicRoom) {
        public Scope {
            Objects.requireNonNull(worldId); Objects.requireNonNull(requesterId);
            playerAudience = Set.copyOf(playerAudience); godAudience = Set.copyOf(godAudience);
            if (!validGod(readerGodId) || !playerAudience.contains(requesterId) || playerAudience.size() > MAX_PLAYERS
                    || godAudience.isEmpty() || godAudience.size() > MAX_GODS || !godAudience.contains(readerGodId)
                    || godAudience.stream().anyMatch(g -> !validGod(g)))
                throw new IllegalArgumentException("Invalid room memory query scope");
        }
    }
    public record Capacity(long usedBytes, long maxBytes, int entries, int maxEntries) { }
    private record Operation(int version, long sequence, String kind, Record record, UUID target) { }
    private record Snapshot(Map<UUID,Record> records, Map<String,List<Record>> byGod,
            Map<UUID,Set<String>> terms, Set<UUID> retired) { }
    private final Path directory, config;
    private final ThreadPoolExecutor writer;
    private final Map<UUID,Record> records = new LinkedHashMap<>();
    private final Map<UUID,Set<String>> lexicalTerms = new HashMap<>();
    private final Set<UUID> retired = new HashSet<>();
    private final Map<UUID,Record> pending = new ConcurrentHashMap<>();
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of(), Set.of());
    private volatile boolean ready, failed, closed;
    private volatile String failure = "";
    private volatile long diskBytes;
    private volatile MemoryRetentionSettings limits = MemoryRetentionSettings.DEFAULT;
    private long sequence;
    private FileChannel lockChannel;
    private FileLock lock;

    public RoomMemoryStore(Path directory, Path config) {
        this.directory = directory.toAbsolutePath().normalize(); this.config = config;
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(512), work -> {
            var thread = new Thread(work, "mythai-room-memory-writer"); thread.setDaemon(true); return thread;
        }) {
            @Override protected void terminated() {
                try { if (lock != null) lock.release(); } catch (IOException error) { fail(error); }
                finally { try { if (lockChannel != null) lockChannel.close(); } catch (IOException error) { fail(error); } }
            }
        };
        writer.execute(() -> { try { limits = config == null ? MemoryRetentionSettings.DEFAULT : MemoryRetentionSettings.load(config); load(); ready = true; }
            catch (Exception error) { fail(error); } });
    }
    public RoomMemoryStore(Path directory) { this(directory, null); }
    public boolean ready() { return ready && !failed && !closed; }
    public boolean failed() { return failed; }
    public String failureReason() { return failure; }
    public Capacity capacity() { return new Capacity(diskBytes, limits.maxStorageBytes(), snapshot.records().size(), limits.maxEntries()); }
    public List<Record> records() { return List.copyOf(snapshot.records().values()); }

    public CompletableFuture<Result> append(Record record) {
        Objects.requireNonNull(record);
        synchronized (pending) {
            var same = pending.get(record.messageId());
            if (same != null) return CompletableFuture.completedFuture(same.equals(record) ? Result.DUPLICATE : Result.CONFLICT);
            if (pending.size() >= 512) return CompletableFuture.completedFuture(Result.FULL);
            pending.put(record.messageId(), record);
        }
        var result = enqueue(() -> {
            var same = records.get(record.messageId());
            if (same != null) return same.equals(record) ? Result.DUPLICATE : Result.CONFLICT;
            if (retired.contains(record.messageId())) return Result.STALE;
            if (records.size() >= limits.maxEntries()) return Result.FULL;
            // Retain the heard utterance even when a dependency was not retained (for example recording OFF).
            // Missing/retired/cross-world ancestry makes the statement unavailable for recall, never a new fact.
            return persist(new Operation(1, sequence + 1, "ADD", record, null));
        });
        result.whenComplete((value, error) -> pending.remove(record.messageId(), record));
        return result;
    }
    public CompletableFuture<Result> retire(UUID messageId) {
        return enqueue(() -> !records.containsKey(messageId) ? Result.STALE : retired.size() >= 100000 ? Result.FULL
                : persist(new Operation(1, sequence + 1, "RETIRE", null, messageId)));
    }
    private CompletableFuture<Result> enqueue(Callable<Result> action) {
        var result = new CompletableFuture<Result>();
        if (closed || failed) { result.complete(Result.UNAVAILABLE); return result; }
        try { writer.execute(() -> {
            if (!ready()) { result.complete(Result.UNAVAILABLE); return; }
            try { result.complete(action.call()); } catch (Exception error) { fail(error); result.complete(Result.UNAVAILABLE); }
        }); } catch (RejectedExecutionException full) { result.complete(Result.FULL); }
        return result;
    }
    private Result persist(Operation operation) throws IOException {
        byte[] bytes = (JSON.toJson(operation) + "\n").getBytes(StandardCharsets.UTF_8);
        if (diskBytes + bytes.length > limits.maxStorageBytes()) return Result.FULL;
        try (var channel = FileChannel.open(directory.resolve("heard-dialogue.jsonl"), StandardOpenOption.CREATE,
                StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
            var data = ByteBuffer.wrap(bytes); while (data.hasRemaining()) channel.write(data); channel.force(true);
        }
        apply(operation); diskBytes += bytes.length; publish(); return Result.STORED;
    }
    private void apply(Operation operation) {
        if (operation.version() != 1 || operation.sequence() != sequence + 1) throw new IllegalArgumentException("Invalid room memory sequence");
        if (operation.kind().equals("ADD")) {
            var row = Objects.requireNonNull(operation.record());
            if (retired.contains(row.messageId()) || records.containsKey(row.messageId())) throw new IllegalArgumentException("Invalid room memory identity");
            records.put(row.messageId(), row);
            lexicalTerms.put(row.messageId(), Set.copyOf(MemoryJournal.lexical(row.text()).terms()));
        } else if (operation.kind().equals("RETIRE")) {
            if (records.remove(operation.target()) == null) throw new IllegalArgumentException("Missing retired room memory");
            retired.add(operation.target());
            lexicalTerms.remove(operation.target());
        } else throw new IllegalArgumentException("Invalid room memory operation");
        if (records.size() > limits.maxEntries() || retired.size() > 100000) throw new IllegalArgumentException("Room memory capacity");
        sequence = operation.sequence();
    }
    private void load() throws IOException {
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock(); if (lock == null) throw new IOException("Another room memory writer owns this directory");
        var file = directory.resolve("heard-dialogue.jsonl");
        if (Files.exists(file)) {
            diskBytes = Files.size(file); if (diskBytes > limits.maxStorageBytes()) throw new IOException("Room memory exceeds configured budget");
            try (var input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line; while ((line = input.readLine()) != null) {
                    if (line.isBlank() || line.length() > 2_000_000) throw new IOException("Invalid room memory row");
                    var operation = JSON.fromJson(line, Operation.class); if (operation == null) throw new IOException("Missing room memory operation");
                    apply(operation);
                }
            }
        }
        publish();
    }
    private void publish() {
        var byGod = new HashMap<String,List<Record>>();
        for (var record : records.values()) for (String god : record.heardGodIds())
            byGod.computeIfAbsent(record.worldId() + "/" + god, ignored -> new ArrayList<>()).add(record);
        byGod.replaceAll((god, rows) -> List.copyOf(rows));
        snapshot = new Snapshot(Map.copyOf(records), Map.copyOf(byGod), Map.copyOf(lexicalTerms), Set.copyOf(retired));
    }

    /** Candidate search is permission-scoped and off-thread. Game evidence is revalidated before prompt construction. */
    public List<Record> candidates(Scope scope, String question, Set<UUID> recent, int maximum, long budgetNanos) {
        if (!ready() || maximum < 1 || budgetNanos < 1) return List.of();
        long start = System.nanoTime();
        var read = snapshot;
        var all = new LinkedHashMap<UUID,Record>();
        read.byGod().getOrDefault(scope.worldId() + "/" + scope.readerGodId(), List.of()).forEach(row -> all.put(row.messageId(), row));
        pending.values().stream().filter(row -> row.worldId().equals(scope.worldId()) && row.heardGodIds().contains(scope.readerGodId()))
                .forEach(row -> all.putIfAbsent(row.messageId(), row));
        boolean explicit = RecallQuery.explicitRecall(question);
        var role = RecallSourceScope.resolve(question);
        var query = MemoryJournal.lexical(RecallSourceScope.lexicalQuery(question));
        var namedPlayers = new HashSet<String>();
        var matchedNames = new HashMap<String,Boolean>();
        if (explicit && role.role() == RecallSourceScope.Role.UNSPECIFIED) for (var row : all.values())
            if (visible(row, scope, true) && row.role().equals("PLAYER") && !row.speakerName().isBlank()
                    && matchedNames.computeIfAbsent(row.speakerName(), name -> question.matches("(?s).*"
                        + java.util.regex.Pattern.quote(name) + "\\s*(이|가|은|는|의\\s*말).*"))) namedPlayers.add(row.speakerId());
        record Hit(Record row, double score) { }
        var hits = new ArrayList<Hit>();
        for (var row : all.values()) {
            if (System.nanoTime() - start > budgetNanos) return List.of();
            if (snapshot.retired().contains(row.messageId()) || !visible(row, scope, true)
                    || !explicit && recent.contains(row.messageId()) || row.occurredAt() > System.currentTimeMillis()) continue;
            if (explicit && (!sourceAllowed(row, scope, role) || !namedPlayers.isEmpty() && !namedPlayers.contains(row.speakerId()))) continue;
            var terms = read.terms().get(row.messageId());
            if (terms == null) terms = MemoryJournal.lexical(row.text()).terms(); // accepted, not yet durable write
            long overlap = query.terms().stream().filter(terms::contains).count();
            if (overlap < 2 || overlap / (double)Math.max(1, query.terms().size()) < .2) continue;
            hits.add(new Hit(row, overlap / (double)Math.max(1, query.terms().size())));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Comparator.comparingLong((Hit h) -> h.row().occurredAt()).reversed()));
        // Proof validation precedes prompt-level duplicate suppression: a newer revoked duplicate must
        // not erase an older, still-permitted utterance with the same words.
        return hits.stream().map(Hit::row).limit(Math.min(64, maximum)).toList();
    }
    private static boolean sourceAllowed(Record row, Scope scope, RecallSourceScope role) {
        if (row.role().equals("PLAYER")) return role.role() == RecallSourceScope.Role.UNSPECIFIED
                || role.role() == RecallSourceScope.Role.PLAYER && row.speakerId().equals(scope.requesterId().toString());
        return switch (role.role()) {
            case PLAYER -> false;
            case THIS_GOD -> row.speakerId().equals(scope.readerGodId());
            case OTHER_GOD -> !row.speakerId().equals(scope.readerGodId());
            case IDENTIFIED_GOD -> role.speakerGodIds().contains(row.speakerId());
            case ANY_GOD, UNSPECIFIED -> true;
        };
    }
    private static boolean visible(Record row, Scope scope, boolean requireHeard) {
        return row.worldId().equals(scope.worldId()) && (!requireHeard || row.heardGodIds().contains(scope.readerGodId()))
                && (row.publicSpeech() || !scope.publicRoom() && row.fullPlayerAudience().containsAll(scope.playerAudience())
                    && row.heardGodIds().containsAll(scope.godAudience()));
    }
    public boolean evidenceCurrent(Scope scope, Set<UUID> sources, Predicate<RoomEvidenceReference> validator) {
        if (!ready() || sources.size() > 256) return false;
        var current = new HashMap<>(snapshot.records());
        pending.forEach(current::putIfAbsent);
        return RoomMemoryEvidence.current(scope, List.of(), sources, id -> {
            var row = current.get(id);
            return row == null || snapshot.retired().contains(id) ? null : RoomMemoryEvidence.Receipt.from(row);
        }, validator);
    }
    public boolean retired(UUID id) { return snapshot.retired().contains(id); }
    public boolean contains(UUID id) { return !snapshot.retired().contains(id) && (snapshot.records().containsKey(id) || pending.containsKey(id)); }
    public Optional<Record> record(UUID id) {
        if (!ready() || snapshot.retired().contains(id)) return Optional.empty();
        var row = snapshot.records().get(id); return Optional.ofNullable(row == null ? pending.get(id) : row);
    }
    private static boolean validGod(String id) { return id != null && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"); }
    private void fail(Exception error) { failed = true; ready = false; failure = error.getClass().getSimpleName() + ": " + error.getMessage(); }
    public boolean awaitIdle(Duration timeout) throws Exception { return enqueue(() -> Result.STORED).get(timeout.toMillis(), TimeUnit.MILLISECONDS) == Result.STORED; }
    public boolean close(Duration timeout) {
        writer.shutdown();
        try { boolean drained = writer.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS); closed = true; return drained; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); closed = true; return false; }
    }
    @Override public void close() { close(Duration.ofSeconds(5)); }
}
