package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded single-writer journal; all disk IO and snapshot construction stay off the game thread. */
public final class MemoryJournal implements AutoCloseable {
    public enum Source { PLAYER_STATEMENT, NPC_UTTERANCE, GAME_CONFIRMED, HEARSAY_NPC }
    public enum Result { STORED, DUPLICATE, STALE, FULL, UNAVAILABLE }
    public record Key(UUID world, String god, UUID player) {
        public Key { Objects.requireNonNull(world); Objects.requireNonNull(player);
            if (god == null || !god.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid God ID"); }
    }
    public record Entry(UUID id, Key key, UUID session, long turn, Source source, Set<UUID> audience,
            long occurredAt, String text, boolean important) {
        public Entry {
            Objects.requireNonNull(id); Objects.requireNonNull(key); Objects.requireNonNull(session); Objects.requireNonNull(source);
            audience = Set.copyOf(audience);
            if (turn < 0 || occurredAt < 0 || audience.isEmpty() || audience.size() > 16 || !audience.contains(key.player())
                    || text == null || text.isBlank() || text.length() > 1200) throw new IllegalArgumentException("Invalid memory entry");
        }
    }
    public record View(long revision, List<Entry> entries, Set<UUID> retired) {
        public View { entries = List.copyOf(entries); retired = Set.copyOf(retired); }
    }
    public record ReadView(Key key, Set<UUID> audience, List<Entry> entries, Set<UUID> pending,
            boolean ready, boolean failed) {
        public ReadView { audience = Set.copyOf(audience); entries = List.copyOf(entries); pending = Set.copyOf(pending); }
    }
    private record Published(Map<Key, List<Entry>> index, View view) {}
    private record Checkpoint(int version, long sequence, List<Entry> entries, Set<UUID> retired) {}
    private record Operation(int version, long sequence, String type, Entry entry, UUID target) {}
    private static final Gson JSON = new Gson();
    private static final int MAX_ENTRIES = 12_000, MAX_BUCKET = 2_000, MAX_RETIRED = 50_000;
    private static final long MAX_DISK = 64L * 1024 * 1024;
    private static final long CASUAL_LIFETIME = Duration.ofDays(30).toMillis();
    private static final java.util.regex.Pattern NON_WORD = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile(" +");
    private final Path directory;
    private final ThreadPoolExecutor writer;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Set<UUID> retired = new HashSet<>();
    private volatile View view = new View(0, List.of(), Set.of());
    private volatile Map<Key, List<Entry>> index = Map.of();
    private volatile Map<UUID, Entry> active = Map.of();
    private volatile Published published = new Published(Map.of(), view);
    private final Map<UUID, Entry> pending = new ConcurrentHashMap<>();
    private volatile boolean ready, failed, closed;
    private volatile String failureReason = "";
    private final AtomicLong rejected = new AtomicLong();
    private long sequence;
    private int sinceCheckpoint;
    private FileChannel leaseChannel;
    private FileLock lease;

    public MemoryJournal(Path directory) { this(directory, 256); }
    public MemoryJournal(Path directory, int queueCapacity) {
        this.directory = directory.toAbsolutePath().normalize();
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), r -> {
            Thread thread = new Thread(r, "mythai-memory-journal"); thread.setDaemon(true); return thread;
        }) {
            @Override protected void terminated() {
                try { if (lease != null) lease.release(); }
                catch (IOException error) { fail(error); }
                finally {
                    try { if (leaseChannel != null) leaseChannel.close(); }
                    catch (IOException error) { fail(error); }
                }
            }
        };
        writer.execute(() -> {
            try { load(); ready = true; } catch (Exception error) { fail(error); }
        });
    }
    public boolean ready() { return ready && !failed && !closed; }
    public boolean failed() { return failed; }
    public String failureReason() { return failureReason; }
    public long rejectedWrites() { return rejected.get(); }
    public View view() { return view; }
    /** Immutable permission-scoped read-your-writes input; pending is NOT a durability acknowledgement. */
    public ReadView readView(Key key, Set<UUID> audience) {
        Published state = published;
        Map<UUID,Entry> allowed = new LinkedHashMap<>();
        if (!audience.isEmpty() && audience.contains(key.player())) {
            for (Entry e : state.index().getOrDefault(key, List.of()))
                if (e.audience().containsAll(audience)) allowed.put(e.id(), e);
            for (Entry e : pending.values())
                if (e.key().equals(key) && e.audience().containsAll(audience) && !state.view().retired().contains(e.id()))
                    allowed.putIfAbsent(e.id(), e);
        }
        Set<UUID> waiting = new HashSet<>(allowed.keySet());
        waiting.removeAll(state.index().getOrDefault(key, List.of()).stream().map(Entry::id).toList());
        return new ReadView(key, audience, List.copyOf(allowed.values()), waiting, ready(), failed());
    }
    /** Same-bucket additions/corrections invalidate a lookup, except this accepted turn's own transcript. */
    public boolean stillCurrent(ReadView expected, UUID session, long turn) {
        if (!expected.ready()) return true; // no historical claim was made
        ReadView current = readView(expected.key(), expected.audience());
        java.util.function.Predicate<Entry> prior = e -> !(e.session().equals(session) && e.turn() == turn
                && (e.source() == Source.PLAYER_STATEMENT || e.source() == Source.NPC_UTTERANCE || e.source() == Source.HEARSAY_NPC));
        return current.ready() && new HashSet<>(expected.entries().stream().filter(prior).toList())
                .equals(new HashSet<>(current.entries().stream().filter(prior).toList()));
    }
    public boolean stillCurrent(List<Entry> selected) {
        Map<UUID, Entry> snapshot = active;
        return ready() && selected.stream().allMatch(entry -> entry.equals(snapshot.get(entry.id())));
    }

    public CompletableFuture<Result> append(Entry entry) {
        Objects.requireNonNull(entry);
        synchronized (pending) {
            if (pending.containsKey(entry.id())) return CompletableFuture.completedFuture(Result.DUPLICATE);
            if (pending.size() >= 257) { rejected.incrementAndGet(); return CompletableFuture.completedFuture(Result.FULL); }
            pending.put(entry.id(), entry);
        }
        CompletableFuture<Result> result = enqueue(() -> {
            if (entries.containsKey(entry.id()) || retired.contains(entry.id())) return Result.DUPLICATE;
            if (entries.size() >= MAX_ENTRIES || index.getOrDefault(entry.key(), List.of()).size() >= MAX_BUCKET) return Result.FULL;
            return persist(new Operation(1, sequence + 1, "ADD", entry, null));
        });
        result.whenComplete((value, failure) -> pending.remove(entry.id(), entry));
        return result;
    }
    /** Explicit revision-aware correction. A topic match alone never overwrites history. */
    public CompletableFuture<Result> supersede(UUID oldId, Entry replacement, long expectedRevision) {
        return enqueue(() -> {
            Entry old = entries.get(oldId);
            if (sequence != expectedRevision || old == null || !old.key().equals(replacement.key())
                    || !old.audience().equals(replacement.audience()) || old.source() != replacement.source()
                    || entries.containsKey(replacement.id()) || retired.contains(replacement.id())) return Result.STALE;
            if (retired.size() >= MAX_RETIRED) return Result.FULL;
            return persist(new Operation(1, sequence + 1, "REPLACE", replacement, oldId));
        });
    }
    public CompletableFuture<Result> delete(UUID id, long expectedRevision) {
        return enqueue(() -> {
            if (sequence != expectedRevision || !entries.containsKey(id)) return Result.STALE;
            if (retired.size() >= MAX_RETIRED) return Result.FULL;
            return persist(new Operation(1, sequence + 1, "DELETE", null, id));
        });
    }
    public CompletableFuture<Result> pin(UUID id, long expectedRevision, boolean important) {
        return enqueue(() -> {
            Entry old = entries.get(id);
            if (sequence != expectedRevision || old == null) return Result.STALE;
            Entry changed = new Entry(old.id(), old.key(), old.session(), old.turn(), old.source(), old.audience(), old.occurredAt(), old.text(), important);
            return persist(new Operation(1, sequence + 1, "PIN", changed, id));
        });
    }
    private CompletableFuture<Result> enqueue(Callable<Result> work) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        if (closed || failed) { future.complete(Result.UNAVAILABLE); return future; }
        try { writer.execute(() -> {
            if (!ready || failed) { future.complete(Result.UNAVAILABLE); return; }
            try { Result result = work.call(); if (result == Result.FULL) rejected.incrementAndGet(); future.complete(result); }
            catch (Exception error) { fail(error); future.complete(Result.UNAVAILABLE); }
        }); } catch (RejectedExecutionException full) { rejected.incrementAndGet(); future.complete(Result.FULL); }
        return future;
    }
    private Result persist(Operation operation) throws IOException {
        Path journal = directory.resolve("journal.jsonl");
        byte[] bytes = (JSON.toJson(operation) + "\n").getBytes(StandardCharsets.UTF_8);
        if (Files.exists(journal) && Files.size(journal) + bytes.length > MAX_DISK) { checkpoint(); }
        try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(false);
        }
        apply(operation); publish();
        if (++sinceCheckpoint >= 128) checkpoint();
        return Result.STORED;
    }
    private void apply(Operation op) {
        if (op.version() != 1 || op.sequence() != sequence + 1) throw new IllegalArgumentException("Invalid memory journal sequence/schema");
        switch (op.type()) {
            case "ADD" -> {
                if (op.entry() == null || entries.containsKey(op.entry().id()) || retired.contains(op.entry().id())) throw new IllegalArgumentException("Duplicate journal entry");
                entries.put(op.entry().id(), op.entry());
            }
            case "REPLACE" -> {
                Entry old = entries.get(op.target());
                if (old == null || op.entry() == null || !old.key().equals(op.entry().key()) || !old.audience().equals(op.entry().audience())
                        || old.source() != op.entry().source() || entries.containsKey(op.entry().id()) || retired.contains(op.entry().id())) throw new IllegalArgumentException("Invalid correction");
                entries.remove(op.target()); retired.add(op.target()); entries.put(op.entry().id(), op.entry());
            }
            case "DELETE" -> { if (entries.remove(op.target()) == null) throw new IllegalArgumentException("Missing deletion target"); retired.add(op.target()); }
            case "PIN" -> {
                Entry old = entries.get(op.target()); Entry changed = op.entry();
                if (old == null || changed == null || !new Entry(old.id(), old.key(), old.session(), old.turn(), old.source(), old.audience(), old.occurredAt(), old.text(), changed.important()).equals(changed)) throw new IllegalArgumentException("Invalid pin");
                entries.put(op.target(), changed);
            }
            default -> throw new IllegalArgumentException("Unknown memory operation");
        }
        if (entries.size() > MAX_ENTRIES || retired.size() > MAX_RETIRED) throw new IllegalArgumentException("Memory capacity exceeded");
        sequence = op.sequence();
    }
    private void load() throws IOException {
        Files.createDirectories(directory);
        leaseChannel = FileChannel.open(directory.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lease = leaseChannel.tryLock();
        if (lease == null) throw new IOException("Another memory writer owns this directory");
        Path checkpoint = directory.resolve("snapshot.json");
        if (Files.exists(checkpoint)) {
            if (Files.size(checkpoint) > MAX_DISK) throw new IOException("Oversized snapshot");
            Checkpoint stored = JSON.fromJson(Files.readString(checkpoint), Checkpoint.class);
            if (stored == null || stored.version() != 1 || stored.sequence() < 0 || stored.entries().size() > MAX_ENTRIES || stored.retired().size() > MAX_RETIRED) throw new IOException("Invalid snapshot");
            for (Entry entry : stored.entries()) if (entries.putIfAbsent(entry.id(), entry) != null) throw new IOException("Duplicate snapshot entry");
            retired.addAll(stored.retired());
            if (entries.keySet().stream().anyMatch(retired::contains)) throw new IOException("Retired entry resurrected");
            sequence = stored.sequence();
        }
        Path journal = directory.resolve("journal.jsonl");
        if (Files.exists(journal)) {
            if (Files.size(journal) > MAX_DISK) throw new IOException("Oversized journal");
            try (var reader = Files.newBufferedReader(journal)) {
                String line; while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.length() > 32768) throw new IOException("Invalid journal row");
                    Operation op = JSON.fromJson(line, Operation.class);
                    if (op == null || op.version() != 1) throw new IOException("Invalid journal operation");
                    if (op.sequence() > sequence) apply(op);
                }
            }
        }
        publish();
    }
    private void publish() {
        Map<Key, List<Entry>> buckets = new HashMap<>();
        for (Entry entry : entries.values()) buckets.computeIfAbsent(entry.key(), ignored -> new ArrayList<>()).add(entry);
        if (buckets.values().stream().anyMatch(list -> list.size() > MAX_BUCKET)) throw new IllegalStateException("Oversized memory bucket");
        buckets.replaceAll((key, value) -> List.copyOf(value));
        index = Map.copyOf(buckets); active = Map.copyOf(entries); view = new View(sequence, List.copyOf(entries.values()), retired);
        published = new Published(index, view);
    }
    private void checkpoint() throws IOException {
        Path target = directory.resolve("snapshot.json"), temporary = directory.resolve("snapshot.next");
        byte[] bytes = JSON.toJson(new Checkpoint(1, sequence, List.copyOf(entries.values()), Set.copyOf(retired))).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_DISK) throw new IOException("Snapshot capacity exceeded");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        if (Files.exists(target)) Files.copy(target, directory.resolve("snapshot.previous.json"), StandardCopyOption.REPLACE_EXISTING);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        // A crash before this truncation is safe: replay skips the checkpointed prefix.
        try (FileChannel channel = FileChannel.open(directory.resolve("journal.jsonl"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.truncate(0); channel.force(true);
        }
        sinceCheckpoint = 0;
    }
    /** Bounded local lexical retrieval. No network, disk read, embedding or LLM call. */
    public List<Entry> search(Key key, Set<UUID> audience, String query, Set<String> recentTexts, long now, int maximum, long budgetNanos) {
        return searchConversation(key, audience, query, recentTexts, List.of(), null, now, maximum, budgetNanos);
    }

    /** One bounded scan; context never widens the world/God/player/audience permission boundary. */
    public List<Entry> searchConversation(Key key, Set<UUID> audience, String query, Set<String> recentTexts,
            List<String> recentPlayerTexts, UUID currentSession, long now, int maximum, long budgetNanos) {
        if (!ready() || audience.isEmpty() || maximum < 1 || budgetNanos <= 0) return List.of();
        String normalized = normalize(query); Set<String> terms = terms(normalized);
        if (normalized.length() < 3 || terms.isEmpty()) return List.of();
        long start = System.nanoTime();
        Set<String> recent = new HashSet<>(); recentTexts.forEach(text -> recent.add(normalize(text)));
        List<String> context = MemoryRecallPolicy.contextQueries(query, recentPlayerTexts).stream().map(MemoryJournal::normalize).toList();
        List<Set<String>> contextTerms = context.stream().map(MemoryJournal::terms).toList();
        boolean returning = currentSession != null && MemoryRecallPolicy.returning(query);
        Entry recentEpisode = null;
        record Match(Entry entry, double score) {}
        List<Match> matches = new ArrayList<>();
        for (Entry entry : index.getOrDefault(key, List.of())) {
            if (System.nanoTime() - start > budgetNanos) return List.of();
            if (!entry.audience().containsAll(audience) || entry.source() == Source.HEARSAY_NPC
                    || (!entry.important() && now - entry.occurredAt() > CASUAL_LIFETIME)) continue;
            String candidate = normalize(entry.text());
            if (recent.contains(candidate) || candidate.equals(normalized)) continue;
            // A return can refer to the latest exchange without repeating its topic words.
            // Carry at most ONE player statement, for six hours, and never claim it was completed.
            if (returning && !entry.session().equals(currentSession) && entry.source() == Source.PLAYER_STATEMENT
                    && now >= entry.occurredAt() && now - entry.occurredAt() <= Duration.ofHours(6).toMillis()
                    && (recentEpisode == null || entry.occurredAt() > recentEpisode.occurredAt())) recentEpisode = entry;
            Set<String> candidateTerms = terms(candidate);
            double relevance = relevance(normalized, terms, candidate, candidateTerms);
            for (int i = 0; i < context.size(); i++) {
                relevance = Math.max(relevance, 0.65 * relevance(context.get(i), contextTerms.get(i), candidate, candidateTerms));
            }
            if (relevance <= 0) continue;
            double age = Math.max(0, now - entry.occurredAt()) / (double) CASUAL_LIFETIME;
            matches.add(new Match(entry, relevance * 100 + (entry.source() == Source.PLAYER_STATEMENT ? 3 : 0)
                    + (entry.important() ? 2 : 0) + 1 / (1 + age)));
        }
        if (recentEpisode != null) {
            Entry episode = recentEpisode;
            if (matches.stream().noneMatch(m -> m.entry().id().equals(episode.id()))) matches.add(new Match(episode, 1));
        }
        matches.sort(Comparator.comparingDouble(Match::score).reversed()
                .thenComparing(Comparator.comparingLong((Match m) -> m.entry().occurredAt()).reversed())
                .thenComparing(m -> m.entry().id()));
        List<Entry> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (Match match : matches) { if (seen.add(normalize(match.entry().text()))) result.add(match.entry()); if (result.size() >= Math.min(3, maximum)) break; }
        // Preserve time order among selected evidence, particularly corrections.
        result.sort(Comparator.comparingLong(Entry::occurredAt).thenComparing(Entry::id));
        return System.nanoTime() - start > budgetNanos ? List.of() : List.copyOf(result);
    }
    private static double relevance(String query, Set<String> queryTerms, String candidate, Set<String> candidateTerms) {
        long overlap = queryTerms.stream().filter(candidateTerms::contains).count();
        double score = overlap / (double) Math.max(1, queryTerms.size());
        return (overlap >= 2 || candidate.contains(query)) && score >= 0.2 ? score : 0;
    }
    private static String normalize(String text) { return NON_WORD.matcher(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)).replaceAll(" ").trim(); }
    private static Set<String> terms(String text) {
        Set<String> terms = new HashSet<>();
        for (String word : SPACES.split(text)) { if (word.length() < 2) continue; terms.add(word); for (int i = 0; i + 1 < word.length(); i++) terms.add(word.substring(i, i + 2)); }
        return terms;
    }
    public static boolean related(String query, String candidate) {
        return related(lexical(query), lexical(candidate));
    }
    public record Lexical(String normalized, Set<String> terms) {}
    public static Lexical lexical(String text) { String normalized = normalize(text); return new Lexical(normalized, terms(normalized)); }
    public static boolean related(Lexical query, Lexical candidate) {
        if (query.normalized().length() < 3) return false;
        long overlap = 0;
        for (String word : query.terms()) if (candidate.terms().contains(word)) overlap++;
        return overlap >= 2 && overlap / (double) Math.max(1, query.terms().size()) >= 0.2;
    }
    private void fail(Exception failure) { failed = true; ready = false; failureReason = failure.getClass().getSimpleName() + ": " + failure.getMessage(); }
    public boolean awaitIdle(Duration timeout) throws Exception {
        if (closed) return writer.isTerminated();
        CompletableFuture<Result> barrier = enqueue(() -> Result.STORED);
        return barrier.get(timeout.toMillis(), TimeUnit.MILLISECONDS) == Result.STORED;
    }
    public boolean close(Duration timeout) {
        closed = true; writer.shutdown();
        try { return writer.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }
    @Override public void close() { close(Duration.ofSeconds(5)); }
}
