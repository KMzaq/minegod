package com.sande.mythictrpg.gameplay.ledger;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import com.sande.mythictrpg.recording.server.LegacyRecordingQuota;
import com.sande.mythictrpg.recording.server.ManagedStoreRegistry;

/** Blocking storage. Only the dedicated ledger worker may call it. No game/AI callbacks. */
public final class ActionLedgerStore implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private static final int MAX_FRAME = 16_384;
    private static final int CONTROL_RESERVE = 4_096;
    static final long APPEND_WRITE_BOUND = MAX_FRAME + 8L + CONTROL_RESERVE;
    public record Limits(long maxBytes, int segmentBytes, int maxIndexedEvents) {
        public Limits {
            if (maxBytes < 65_536 || maxBytes > 1L << 40 || segmentBytes < MAX_FRAME + 8
                    || segmentBytes > 64 * 1024 * 1024 || maxIndexedEvents < 1 || maxIndexedEvents > 1_000_000)
                throw new IllegalArgumentException("Invalid technical limits");
        }
    }
    public record Cursor(UUID worldId, long sequence) {
        public Cursor { Objects.requireNonNull(worldId); if (sequence < 0) throw new IllegalArgumentException("cursor"); }
    }
    public record Page(List<ActionRecord> records, Cursor next, Cursor durableHead) {
        public Page { records = List.copyOf(records); }
    }
    public record GapSummary(long rejected, long firstUtc, long lastUtc, String reason) {
        public GapSummary {
            if (rejected < 0 || firstUtc < 0 || lastUtc < 0 || reason == null || reason.length() > 256)
                throw new IllegalArgumentException("gap summary");
        }
    }
    private record Metadata(int version, UUID worldId) {}
    private record Checkpoint(int version, UUID worldId, long sequence, UUID lastEventId,
                              boolean cleanClose, long writtenAtUtc, GapSummary gaps) {}
    private record Pointer(Path path, long offset, int length, UUID actor, UUID id) {}
    @FunctionalInterface public interface Faults { void at(String point) throws IOException; }
    public static final Faults NO_FAULTS = point -> {};
    private final Path directory;
    private final UUID worldId;
    private final Limits limits;
    private final Faults faults;
    private final NavigableMap<Long, Pointer> order = new TreeMap<>();
    private final Map<UUID, Long> ids = new HashMap<>();
    private final Map<UUID, NavigableMap<Long, Pointer>> actors = new HashMap<>();
    private final Map<UUID, NavigableMap<Long, Pointer>> supportEvents = new HashMap<>();
    private final Map<UUID, Map<String, Long>> supportChoices = new HashMap<>();
    private final Map<UUID, Pointer> understandingControls = new HashMap<>();
    private final Map<UUID, Map<String, Pointer>> understandingFeedback = new HashMap<>();
    private final Map<UUID, NavigableMap<Long, Pointer>> purposeAnnotations = new HashMap<>();
    private final Map<UUID, NavigableMap<Long, Pointer>> characterSignals = new HashMap<>();
    private final Map<UUID, Pointer> purposeRetractions = new HashMap<>();
    private final Map<UUID, com.sande.mythictrpg.understanding.SupportStatistics> supportStatistics = new HashMap<>();
    private FileChannel lockChannel, segment;
    private FileLock lock;
    private Path segmentPath;
    private long segmentSize, segmentBytes, sequence, controlBytes;
    private UUID lastEventId;
    private boolean closed;
    private final boolean recoveredUnclean;
    private final long previousCheckpointUtc;
    private GapSummary gaps = new GapSummary(0, 0, 0, "NONE");

    public ActionLedgerStore(Path directory, UUID worldId, Limits limits) throws IOException {
        this(directory, worldId, limits, NO_FAULTS);
    }
    public ActionLedgerStore(Path directory, UUID worldId, Limits limits, Faults faults) throws IOException {
        this.directory = directory.toAbsolutePath().normalize(); this.worldId = Objects.requireNonNull(worldId);
        this.limits = limits; this.faults = faults;
        LegacyRecordingQuota.legacyLimits(this.directory, ManagedStoreRegistry.ACTION_LEDGER, limits.maxBytes(),
                limits.maxIndexedEvents(), MAX_FRAME, "INDEX_LIMIT_OR_CAPACITY_LIMIT");
        boolean unclean = false; long checkpointUtc = 0;
        try (var startup = LegacyRecordingQuota.reserve(this.directory, ManagedStoreRegistry.ACTION_LEDGER, 0, true)) {
            startup.validateBeforeWrite();
            Files.createDirectories(this.directory);
            lockChannel = FileChannel.open(this.directory.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("LEDGER_LOCKED");
            Path metadata = this.directory.resolve("metadata.json");
            List<Path> segments;
            try (var files = Files.list(this.directory)) {
                segments = files.filter(p -> p.getFileName().toString().matches("segment-[0-9]{20}\\.alog"))
                        .sorted().toList();
            }
            if (Files.exists(metadata)) {
                Metadata m = readControl(metadata, Metadata.class);
                if (m.version != 1 || !worldId.equals(m.worldId)) throw new IOException("WORLD_OR_VERSION_MISMATCH");
            } else {
                if (!segments.isEmpty() || Files.exists(this.directory.resolve("checkpoint.json")))
                    throw new IOException("MISSING_METADATA");
                atomicControl(metadata, new Metadata(1, worldId));
            }
            Checkpoint checkpoint = Files.exists(this.directory.resolve("checkpoint.json"))
                    ? readControl(this.directory.resolve("checkpoint.json"), Checkpoint.class) : null;
            if (checkpoint != null) {
                if (checkpoint.version != 1 || !worldId.equals(checkpoint.worldId) || checkpoint.sequence < 0
                        || checkpoint.gaps == null) throw new IOException("INVALID_CHECKPOINT");
                gaps = checkpoint.gaps; unclean = !checkpoint.cleanClose; checkpointUtc = checkpoint.writtenAtUtc;
            } else if (!segments.isEmpty()) throw new IOException("MISSING_CHECKPOINT");
            for (Path path : segments) recover(path);
            if (checkpoint != null && (checkpoint.sequence > sequence
                    || checkpoint.sequence > 0 && !Objects.equals(order.get(checkpoint.sequence).id, checkpoint.lastEventId)))
                throw new IOException("CHECKPOINT_AHEAD_OR_MISMATCH");
            // A complete forced frame ahead of its checkpoint is recoverable; partial/checksum failures are not overwritten.
            if (checkpoint != null && checkpoint.sequence < sequence) unclean = true;
            checkpoint(false);
        } catch (Exception failure) {
            release();
            if (failure instanceof IOException io) throw io;
            throw new IOException("LEDGER_OPEN_FAILED", failure);
        }
        recoveredUnclean = unclean; previousCheckpointUtc = checkpointUtc;
    }

    public ActionRecord append(ActionRecord.Draft draft) throws IOException {
        requireOpen();
        validatePurpose(draft);
        Long existing = ids.get(draft.occurrenceId());
        if (existing != null) {
            ActionRecord prior = read(order.get(existing));
            if (!prior.event().equals(draft)) throw new IOException("DUPLICATE_ID_CONFLICT");
            return prior;
        }
        if (order.size() >= limits.maxIndexedEvents) throw new IOException("INDEX_LIMIT");
        ActionRecord record = new ActionRecord(1, worldId, Math.addExact(sequence, 1), draft);
        byte[] bytes = JSON.toJson(record).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME) throw new IOException("FRAME_TOO_LARGE");
        int length = bytes.length + 8;
        if (segmentBytes + length + CONTROL_RESERVE > limits.maxBytes) throw new IOException("CAPACITY_LIMIT");
        try (var admission = LegacyRecordingQuota.reserve(directory, ManagedStoreRegistry.ACTION_LEDGER, length, false)) {
        admission.validateBeforeWrite();
        if (segment == null || segmentSize + length > limits.segmentBytes) {
            if (segment != null) segment.close();
            segmentPath = directory.resolve(String.format(Locale.ROOT, "segment-%020d.alog", record.sequence()));
            // A pre-write crash can leave this validated, empty final segment; reuse without truncating it.
            segment = Files.exists(segmentPath) && Files.size(segmentPath) == 0
                    ? FileChannel.open(segmentPath, StandardOpenOption.WRITE)
                    : FileChannel.open(segmentPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            segmentSize = 0;
        }
        long offset = segmentSize;
        CRC32 crc = new CRC32(); crc.update(bytes);
        ByteBuffer frame = ByteBuffer.allocate(length).putInt(bytes.length).putInt((int)crc.getValue()).put(bytes);
        frame.flip(); faults.at("beforeWrite");
        while (frame.hasRemaining()) segment.write(frame);
        segmentSize += length; segmentBytes += length;
        faults.at("afterWrite"); segment.force(true); faults.at("afterForce");
        add(record, new Pointer(segmentPath, offset, length, draft.actorId(), draft.occurrenceId()));
        checkpoint(false); // Receipt/cursor is published only after data and checkpoint force.
        return record;
        }
    }
    public ActionRecord appendTransition(ActionRecord.Draft draft) throws IOException {
        requireOpen();
        Long existing=ids.get(draft.occurrenceId());
        if(existing==null)return append(draft);
        var prior=read(order.get(existing));
        if(!ActionRecord.sameTransition(prior.event(),draft))throw new IOException("TRANSITION_ID_CONFLICT");
        return prior;
    }
    private void recover(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size(), offset = 0;
            while (offset < size) {
                ActionRecord record = readFrame(channel, offset);
                if (offset == 0 && !path.getFileName().toString().equals(String.format(Locale.ROOT,
                        "segment-%020d.alog", record.sequence()))) throw new IOException("SEGMENT_NAME_ORDER_MISMATCH");
                int length = header(channel, offset).getInt() + 8;
                if (record.sequence() != sequence + 1 || ids.containsKey(record.event().occurrenceId()))
                    throw new IOException("CORRUPT_ORDER_OR_DUPLICATE");
                if (order.size() >= limits.maxIndexedEvents) throw new IOException("RECOVERY_INDEX_LIMIT");
                validatePurpose(record.event());
                add(record, new Pointer(path, offset, length, record.event().actorId(), record.event().occurrenceId()));
                offset += length;
            }
            segmentBytes += size;
        }
    }
    private void add(ActionRecord record, Pointer pointer) {
        sequence = record.sequence(); lastEventId = record.event().occurrenceId();
        order.put(sequence, pointer); ids.put(lastEventId, sequence);
        actors.computeIfAbsent(pointer.actor, ignored -> new TreeMap<>()).put(sequence, pointer);
        if(record.event().type()==ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL)
            supportStatistics.computeIfAbsent(pointer.actor,actor->new com.sande.mythictrpg.understanding.SupportStatistics(worldId,actor));
        var statistics=supportStatistics.get(pointer.actor);
        if(statistics!=null)statistics.accept(record);
        if (record.event().type() == ActionRecord.Type.PLAYER_PURPOSE_EVIDENCE)
            purposeAnnotations.computeIfAbsent(pointer.actor, ignored -> new TreeMap<>()).put(sequence, pointer);
        if(record.event().type()==ActionRecord.Type.PLAYER_CHARACTER_SIGNAL)
            characterSignals.computeIfAbsent(pointer.actor,ignored->new TreeMap<>()).put(sequence,pointer);
        if (record.event().type() == ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL)
            understandingControls.put(pointer.actor, pointer);
        if (record.event().type() == ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK) {
            String key = record.event().payload().get("key");
            if ("purpose".equals(key) && "RETRACT".equals(record.event().payload().get("operation")))
                purposeRetractions.put(pointer.actor, pointer);
            if (com.sande.mythictrpg.understanding.PlayerUnderstanding.validFeedbackKey(key))
                understandingFeedback.computeIfAbsent(pointer.actor, ignored -> new HashMap<>()).put(key, pointer);
        }
        if(record.event().details()!=null)for(UUID participant:record.event().details().participants())
            actors.computeIfAbsent(participant,ignored->new TreeMap<>()).put(sequence,pointer);
        boolean supportive=com.sande.mythictrpg.understanding.PlayerUnderstanding.supportRecord(record);
        if(record.event().type()==ActionRecord.Type.RAID_CHOICE &&record.event().details()!=null) {
            var choice=com.sande.mythictrpg.understanding.RaidChoiceEvidence.parse(record.event().payload());
            if(!choice.test()&&!record.event().dimensionId().equals("mythictrpg:raid_trial")
                    &&!record.event().subject().typeId().equals("mythictrpg:trial/sealed_guardian")) {
            String key=record.event().details().runId()+"/"+choice.decisionId()+"/"+choice.kind();
            var index=supportChoices.computeIfAbsent(pointer.actor,k->new HashMap<>());
            Long previous=index.remove(key);
            // Keep only the current positive choice in the bounded self-view. Cancellations invalidate an
            // earlier fact, but unrelated missed/unavailable opportunities never consume profile slots.
            if(previous!=null)supportEvents.getOrDefault(pointer.actor,Collections.emptyNavigableMap()).remove(previous);
            if(supportive)index.put(key,sequence);
            }
        }
        if(supportive) {
            supportEvents.computeIfAbsent(pointer.actor,k->new TreeMap<>()).put(sequence,pointer);
            if(record.event().type()==ActionRecord.Type.BATTLE_RESULT &&record.event().details()!=null)
                for(UUID participant:record.event().details().participants())
                    supportEvents.computeIfAbsent(participant,k->new TreeMap<>()).put(sequence,pointer);
        }
    }
    public Optional<ActionRecord> find(UUID eventId) throws IOException {
        requireOpen(); Long number = ids.get(eventId);
        return number == null ? Optional.empty() : Optional.of(read(order.get(number)));
    }
    private void validatePurpose(ActionRecord.Draft draft) throws IOException {
        if (draft.type() != ActionRecord.Type.PLAYER_PURPOSE_EVIDENCE) return;
        var evidence = com.sande.mythictrpg.understanding.PurposeLedger.decode(draft.payload());
        var source = find(evidence.sourceId()).orElseThrow(() -> new IOException("PURPOSE_SOURCE_MISSING"));
        com.sande.mythictrpg.understanding.PurposeLedger.validateSource(source, evidence);
        if (!draft.equals(com.sande.mythictrpg.understanding.PurposeLedger.annotate(source, evidence)))
            throw new IOException("PURPOSE_ENVELOPE_MISMATCH");
    }
    /** Internal-only source-bound annotations, never an NPC read grant. Overflow returns unknown, not an old goal. */
    public com.sande.mythictrpg.understanding.PurposeLedger.Slice purposes(UUID actor) throws IOException {
        requireOpen();
        var pointer = understandingControls.get(actor);
        var control = pointer == null ? null : read(pointer);
        long start = control == null ? 0 : control.sequence();
        if (control == null || !"START".equals(control.event().payload().get("operation")))
            return new com.sande.mythictrpg.understanding.PurposeLedger.Slice(false, start, sequence, true, List.of());
        var rows = purposeAnnotations.getOrDefault(actor, Collections.emptyNavigableMap()).tailMap(start, false);
        var characterRows=characterSignals.getOrDefault(actor,Collections.emptyNavigableMap()).tailMap(start,false);
        if (rows.size()+characterRows.size() > com.sande.mythictrpg.understanding.PurposeState.MAX_EVIDENCE)
            return new com.sande.mythictrpg.understanding.PurposeLedger.Slice(true, start, sequence, false, List.of());
        var values = new ArrayList<com.sande.mythictrpg.understanding.PurposeEvidence>();
        for(var row:characterRows.values()) {
            var source=read(row);
            values.add(com.sande.mythictrpg.understanding.CharacterGoalSignal.parse(source.event().payload()).evidence(source));
        }
        for (var row : rows.values()) {
            var value = com.sande.mythictrpg.understanding.PurposeLedger.decode(read(row).event().payload());
            if (value.sourceOrder() > start) values.add(value);
        }
        if(values.stream().map(com.sande.mythictrpg.understanding.PurposeEvidence::goal).distinct().count()
                >com.sande.mythictrpg.understanding.PurposeState.MAX_GOALS)
            return new com.sande.mythictrpg.understanding.PurposeLedger.Slice(true,start,sequence,false,List.of());
        var retractionPointer=purposeRetractions.get(actor);
        if (retractionPointer!=null) {
            var retraction=read(retractionPointer);
            if(retraction.sequence()>start) {
                var goals=values.stream().filter(e->e.sourceOrder()<retraction.sequence()).map(com.sande.mythictrpg.understanding.PurposeEvidence::goal).distinct().toList();
                if(values.size()+goals.size()>com.sande.mythictrpg.understanding.PurposeState.MAX_EVIDENCE)
                    return new com.sande.mythictrpg.understanding.PurposeLedger.Slice(true,start,sequence,false,List.of());
                for(var goal:goals) values.add(new com.sande.mythictrpg.understanding.PurposeEvidence(worldId,actor,
                        retraction.event().occurrenceId(),retraction.sequence(),com.sande.mythictrpg.understanding.PurposeLedger.episodeOf(retraction),goal,
                        com.sande.mythictrpg.understanding.PurposeEvidence.Origin.SELF_SPEECH,
                        com.sande.mythictrpg.understanding.PurposeEvidence.Attribution.DIRECT_SELF,
                        com.sande.mythictrpg.understanding.PurposeEvidence.Signal.WITHDRAW,
                        com.sande.mythictrpg.understanding.PurposeEvidence.Context.DIRECTED,false,false,"본인 화면에서 목적 철회. NPC에게 전달한 발언이 아님."));
            }
        }
        return new com.sande.mythictrpg.understanding.PurposeLedger.Slice(true, start, sequence, gaps.rejected() == 0, values);
    }
    /** Internal self-view only. Callers must bind actor to the authenticated player; never send raw rows to clients/NPCs. */
    public ActionRecord understandingControl(UUID actor) throws IOException {
        requireOpen();Objects.requireNonNull(actor);
        var pointer=understandingControls.get(actor);
        return pointer==null?null:read(pointer);
    }
    public com.sande.mythictrpg.understanding.SupportStatistics.Snapshot supportStatistics(UUID actor) throws IOException {
        requireOpen();
        var stats=supportStatistics.get(actor);
        return stats==null?com.sande.mythictrpg.understanding.SupportStatistics.Snapshot.empty(sequence):stats.snapshot(sequence);
    }
    public com.sande.mythictrpg.understanding.PlayerUnderstanding.Slice understanding(UUID actor) throws IOException {
        requireOpen(); Objects.requireNonNull(actor);
        var pointer = understandingControls.get(actor);
        var control = pointer == null ? null : read(pointer);
        var events = new ArrayList<ActionRecord>();
        var feedback = new ArrayList<ActionRecord>();
        boolean truncated = false;
        if (control != null && "START".equals(control.event().payload().get("operation"))) {
            var index = supportEvents.getOrDefault(actor, Collections.emptyNavigableMap());
            for (var entry : index.tailMap(control.sequence(), false).descendingMap().entrySet()) {
                if (events.size() == com.sande.mythictrpg.understanding.PlayerUnderstanding.MAX_EVENTS) { truncated = true; break; }
                events.add(read(entry.getValue()));
            }
            for (var value : understandingFeedback.getOrDefault(actor, Map.of()).values()) feedback.add(read(value));
        }
        return new com.sande.mythictrpg.understanding.PlayerUnderstanding.Slice(control, events, feedback, sequence, truncated);
    }
    /** Admin/internal durable stream only. Consumers persist next only after their own idempotent commit. */
    public Page after(Cursor cursor, UUID actor, int limit) throws IOException {
        requireOpen();
        if (!worldId.equals(cursor.worldId) || cursor.sequence > sequence || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid scoped cursor/limit");
        NavigableMap<Long, Pointer> index = actor == null ? order : actors.getOrDefault(actor, Collections.emptyNavigableMap());
        List<ActionRecord> records = new ArrayList<>(); long next = cursor.sequence;
        for (var entry : index.tailMap(cursor.sequence, false).entrySet()) {
            records.add(read(entry.getValue())); next = entry.getKey(); if (records.size() == limit) break;
        }
        if (records.size() < limit) next = sequence;
        return new Page(records, new Cursor(worldId, next), new Cursor(worldId, sequence));
    }
    private ActionRecord read(Pointer pointer) throws IOException {
        try (FileChannel channel = FileChannel.open(pointer.path, StandardOpenOption.READ)) {
            ActionRecord record = readFrame(channel, pointer.offset);
            if (!record.event().occurrenceId().equals(pointer.id) || !record.event().actorId().equals(pointer.actor)
                    || !Objects.equals(ids.get(pointer.id), record.sequence()))
                throw new IOException("INDEX_CONTENT_MISMATCH");
            return record;
        }
    }
    private static ByteBuffer header(FileChannel channel, long offset) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8); readFully(channel, header, offset); header.flip(); return header;
    }
    private ActionRecord readFrame(FileChannel channel, long offset) throws IOException {
        ByteBuffer header = header(channel, offset); int size = header.getInt(), expected = header.getInt();
        if (size < 1 || size > MAX_FRAME) throw new IOException("CORRUPT_FRAME_LENGTH");
        ByteBuffer data = ByteBuffer.allocate(size); readFully(channel, data, offset + 8);
        CRC32 crc = new CRC32(); crc.update(data.array());
        if ((int)crc.getValue() != expected) throw new IOException("CORRUPT_FRAME_CHECKSUM");
        try {
            ActionRecord record = JSON.fromJson(new String(data.array(), StandardCharsets.UTF_8), ActionRecord.class);
            if (record == null || !worldId.equals(record.worldId())) throw new IOException("CORRUPT_FRAME_WORLD");
            return record;
        } catch (RuntimeException invalid) { throw new IOException("CORRUPT_FRAME_SCHEMA", invalid); }
    }
    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, position);
            if (count <= 0) throw new IOException("TRUNCATED_FRAME");
            position += count;
        }
    }
    private <T> T readControl(Path path, Class<T> type) throws IOException {
        if (Files.size(path) > CONTROL_RESERVE / 2) throw new IOException("CONTROL_FILE_TOO_LARGE");
        try {
            T value = JSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), type);
            if (value == null) throw new IOException("EMPTY_CONTROL_FILE");
            return value;
        } catch (RuntimeException failure) { throw new IOException("CORRUPT_CONTROL_FILE", failure); }
    }
    private void atomicControl(Path path, Object value) throws IOException {
        byte[] bytes = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CONTROL_RESERVE / 4) throw new IOException("CONTROL_WRITE_BUDGET");
        try (var admission = LegacyRecordingQuota.reserve(directory, ManagedStoreRegistry.ACTION_LEDGER, bytes.length, true)) {
        admission.validateBeforeWrite();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        // Only this writer's bounded control temp is replaced. Never alter raw segments on recovery.
        try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer data = ByteBuffer.wrap(bytes); while (data.hasRemaining()) file.write(data); file.force(true);
        }
        for (int attempt = 0; ; attempt++) {
            try {
                faults.at("beforeControlMove");
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                break;
            } catch (AccessDeniedException transientSharingViolation) {
                // Windows may briefly deny atomic replacement. Retry only on the IO worker, bounded to 150ms.
                if (attempt >= 4) throw transientSharingViolation;
                try { Thread.sleep(10L << attempt); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("CONTROL_MOVE_INTERRUPTED", interrupted); }
            }
        }
        }
    }
    public void updateGaps(GapSummary summary) throws IOException { gaps = summary; checkpoint(false); }
    private void checkpoint(boolean cleanClose) throws IOException {
        faults.at("beforeCheckpoint");
        atomicControl(directory.resolve("checkpoint.json"), new Checkpoint(1, worldId, sequence,
                lastEventId, cleanClose, System.currentTimeMillis(), gaps));
        controlBytes = Files.size(directory.resolve("metadata.json")) + Files.size(directory.resolve("checkpoint.json"));
    }
    public long usedBytes() { return segmentBytes + controlBytes; }
    public long sequence() { return sequence; }
    public int size() { return order.size(); }
    public GapSummary gaps() { return gaps; }
    public boolean recoveredUnclean() { return recoveredUnclean; }
    public long previousCheckpointUtc() { return previousCheckpointUtc; }
    private void requireOpen() throws IOException { if (closed) throw new IOException("CLOSED"); }
    /** Failure/crash path deliberately retains cleanClose=false and never marks uncertain writes successful. */
    public void abort() { closed = true; release(); }
    @Override public void close() throws IOException {
        if (closed) return;
        try { checkpoint(true); } finally { closed = true; release(); }
    }
    private void release() {
        try { if (segment != null) segment.close(); } catch (IOException ignored) {}
        try { if (lock != null) lock.release(); } catch (IOException ignored) {}
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) {}
    }
}
