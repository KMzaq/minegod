package com.sande.mythictrpg.recording.server;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Game-owned storage only: one writer, two bounded readers. No model invocation, world-state execution,
 * archive import, or implicit NPC read authority. All disk/JDBC work is performed off the caller thread.
 */
public final class WorldRecordingService implements GameRecordingPort {
    public static final int QUEUE_ENTRIES = 2048, QUEUE_BYTES = 16 * 1024 * 1024, READ_PAGE_BYTES = 1024 * 1024;
    private static final long PAGE = 4096, STARTUP_RESERVATION = 4 * 1024 * 1024;
    private static final Gson JSON = new Gson();
    public enum State { OFF, STARTING, READY, FULL, UNAVAILABLE, CLOSED }
    public record CutoverBoundary(String buildVersion, Map<String, Long> durableSourceHighWatermarks) {
        public CutoverBoundary {
            if (buildVersion == null || buildVersion.isBlank() || buildVersion.length() > 128) throw new IllegalArgumentException("BUILD_VERSION_REQUIRED");
            durableSourceHighWatermarks = Map.copyOf(durableSourceHighWatermarks);
            if (durableSourceHighWatermarks.size() > 64 || durableSourceHighWatermarks.values().stream().anyMatch(n -> n < 0)
                    || durableSourceHighWatermarks.keySet().stream().anyMatch(key -> !key.matches("[a-z0-9_.:-]{1,128}")))
                throw new IllegalArgumentException("INVALID_CUTOVER_BOUNDARY");
        }
    }
    public record Health(State state, String reasonCode, Optional<UUID> datasetId, UUID runtimeEpoch, long highWatermark,
            boolean possibleGap, String sqliteVersion, int queuedEntries, long queuedBytes, long gapCount) { }
    /** Administrative/test diagnostics, not a public NPC query port. Counts reveal no raw content. */
    public record Statistics(long messages, long deliveries, long workItems, long sources, long invalidations, long coverageGaps) { }
    private record Producer(String id, Set<String> channels, Set<SourceKind> sourceKinds) { }
    private record Queued(long payloadBytes, long growthBytes, long maxDatabaseBytes, WorldRecordingBudget.Reservation reservation, boolean optional) { }
    @FunctionalInterface private interface WriteOperation { WriteReceipt run(long sequence) throws Exception; }
    @FunctionalInterface private interface ReadOperation<T> { T run(Connection db) throws Exception; }
    private static final class Conflict extends Exception { Conflict(String reason) { super(reason); } }

    private final Path worldRoot;
    private final UUID worldId, epoch = UUID.randomUUID();
    private final RecordingSettings settings;
    private final RecordingRetrievalSettings.Policy retrievalPolicy;
    private final CutoverBoundary boundary;
    private final ThreadPoolExecutor writer = executor(1, QUEUE_ENTRIES, "myth-recording-writer");
    private final ThreadPoolExecutor readers = executor(2, 64, "myth-recording-read");
    private final Map<ProducerCapability, Producer> producers = new IdentityHashMap<>();
    private final Map<String, UUID> confirmedSourceLineages = new ConcurrentHashMap<>();
    private final RecordingProjectionStore projections = new RecordingProjectionStore(this);
    private final RecordingLexicalIndex lexicalIndex = new RecordingLexicalIndex(this);
    private final RecordingEmbeddingStore embeddings = new RecordingEmbeddingStore(this);
    private final java.util.concurrent.atomic.AtomicLong authorityGeneration = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger pendingInvalidations = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong projectionGeneration = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger pendingProjectionWrites = new java.util.concurrent.atomic.AtomicInteger();
    private final Map<String, Long> gaps = new LinkedHashMap<>();
    private volatile State state = State.STARTING;
    private volatile String reason = "STARTING", sqliteVersion = "";
    private volatile long highWatermark, databaseBytes, watchKnowledgeWatermark;
    private volatile boolean possibleGap, closing;
    private CompletableFuture<Void> shutdown;
    private long queuedBytes, queuedGrowth;
    private int queuedEntries;
    private DatasetManifest manifest;
    private ManagedStoreRegistry registry;
    private WorldRecordingBudget budget;
    private Path database;
    private Connection connection;
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean quotaInstalled;

    private WorldRecordingService(Path root, UUID world, RecordingSettings settings, CutoverBoundary boundary,
            RecordingRetrievalSettings.Policy retrievalPolicy) {
        this.worldRoot = root.toAbsolutePath().normalize(); this.worldId = Objects.requireNonNull(world);
        this.settings = Objects.requireNonNull(settings); this.boundary = Objects.requireNonNull(boundary);
        this.retrievalPolicy = Objects.requireNonNull(retrievalPolicy);
    }
    public static CompletableFuture<WorldRecordingService> open(Path root, UUID gameWorldId, RecordingSettings settings, CutoverBoundary boundary) {
        return open(root,gameWorldId,settings,boundary,RecordingRetrievalSettings.resolve(settings.archiveMode(),Optional.empty()));
    }
    public static CompletableFuture<WorldRecordingService> open(Path root, UUID gameWorldId, RecordingSettings settings,
            CutoverBoundary boundary, RecordingRetrievalSettings.Policy retrievalPolicy) {
        var service = new WorldRecordingService(root, gameWorldId, settings, boundary,retrievalPolicy);
        if (settings.archiveMode() == RecordingSettings.Mode.OFF) {
            service.state = State.OFF; service.reason = "ARCHIVE_OFF"; service.writer.shutdown(); service.readers.shutdown();
            return CompletableFuture.completedFuture(service); // Not even a directory, lock file, driver or world identity is created.
        }
        var result = new CompletableFuture<WorldRecordingService>();
        service.writer.execute(() -> { service.initialize(); result.complete(service); });
        return result;
    }
    private void initialize() {
        WorldRecordingBudget.Reservation reservation = null;
        try {
            registry = ManagedStoreRegistry.open(worldRoot);
            var fileStore = Files.getFileStore(worldRoot);
            String filesystem = fileStore.type().toLowerCase(Locale.ROOT);
            if (worldRoot.toString().startsWith("\\\\") || fileStore.name().startsWith("\\\\")
                    || filesystem.matches(".*(nfs|smb|cifs|sshfs|webdav).*")) throw new IOException("UNSUPPORTED_NETWORK_FILESYSTEM");
            budget = new WorldRecordingBudget(registry, settings.worldRecordingLimitBytes(), settings.maintenanceHeadroomBytes(), settings.warningRatio(), settings.deferBackgroundRatio());
            Path root = registry.root(ManagedStoreRegistry.RECORDING), manifestFile = root.resolve("manifest.json");
            boolean existing = Files.exists(manifestFile);
            if (!existing && Files.exists(root)) try (var files = Files.list(root)) {
                if (files.findAny().isPresent()) throw new IOException("MISSING_MANIFEST_WITH_EXISTING_FILES");
            }
            if (existing) manifest = DatasetManifest.read(manifestFile, worldId, boundary);
            else manifest = DatasetManifest.create(worldId, boundary);
            database = root.resolve(manifest.datasetId().toString()).resolve("recording.sqlite");
            registry.validate(database);
            Path oldWal = database.resolveSibling("recording.sqlite-wal"); registry.validate(oldWal);
            long recoveryGrowth = Files.exists(oldWal) ? Files.size(oldWal) : 0;
            // Recovery/checkpoint can temporarily retain WAL and grow the main DB by all WAL-backed new pages.
            reservation = budget.reserve(ManagedStoreRegistry.RECORDING, Math.addExact(STARTUP_RESERVATION, recoveryGrowth), existing);
            LegacyRecordingQuota.install(worldRoot, budget);
            quotaInstalled = true;
            Files.createDirectories(root);
            lockChannel = FileChannel.open(root.resolve("writer.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("WRITER_LOCKED");
            if (existing && !Files.isRegularFile(database)) throw new IOException("MISSING_DATASET_DATABASE");
            if (!existing) { Files.createDirectories(database.getParent()); manifest.writeNew(manifestFile); }
            Class.forName("org.sqlite.JDBC"); // Server RECORD_ONLY worker only; never a client/static initializer.
            connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            if (existing) {
                sql("PRAGMA query_only=ON"); RecordingSchema.verify(connection);
                if (scalar("PRAGMA page_size") != PAGE) throw new IOException("UNSUPPORTED_PAGE_SIZE");
                try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT * FROM recording_meta WHERE singleton=1")) {
                    if (!row.next() || !worldId.toString().equals(row.getString("world_id"))
                            || !manifest.datasetId().toString().equals(row.getString("dataset_id")) || row.getInt("schema_version") != RecordingSchema.version(connection))
                        throw new IOException("DATABASE_IDENTITY_MISMATCH");
                    highWatermark = row.getLong("high_watermark"); possibleGap = row.getInt("clean_shutdown") != 1;
                    if (highWatermark < 0) throw new IOException("INVALID_DURABLE_CURSOR");
                }
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT stream,cursor FROM consumer_cursors WHERE consumer='capture'")) {
                    while (rows.next()) if (boundary.durableSourceHighWatermarks().getOrDefault(rows.getString(1), -1L) < rows.getLong(2))
                        throw new IOException("SOURCE_CURSOR_REGRESSED_AFTER_CAPTURE");
                }
                sql("PRAGMA query_only=OFF");
            }
            sql("PRAGMA page_size=4096"); sql("PRAGMA foreign_keys=ON"); sql("PRAGMA busy_timeout=250");
            sql("PRAGMA temp_store=MEMORY"); sql("PRAGMA cache_size=-16384"); sql("PRAGMA cache_spill=OFF");
            sql("PRAGMA synchronous=FULL"); sql("PRAGMA wal_autocheckpoint=0");
            try (var statement = connection.createStatement(); var row = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!row.next() || !"wal".equalsIgnoreCase(row.getString(1))) throw new IOException("WAL_UNAVAILABLE");
            }
            try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT sqlite_version()")) { row.next(); sqliteVersion = row.getString(1); }
            if (!"3.53.4".equals(sqliteVersion)) throw new IOException("UNVERIFIED_SQLITE_VERSION");
            connection.setAutoCommit(false);
            if (!existing) {
                RecordingSchema.create(connection);
                update("INSERT INTO recording_meta VALUES(1,?,?,?,0,0,?)", worldId, manifest.datasetId(), RecordingSchema.VERSION, epoch);
            } else {
                RecordingSchema.upgradePublicationContext(connection);
                update("UPDATE recording_meta SET clean_shutdown=0,runtime_epoch=? WHERE singleton=1", epoch);
                update("UPDATE work_items SET state='PENDING',lease_epoch=NULL,lease_deadline=NULL,lease_nonce=NULL WHERE state='LEASED'");
                update("UPDATE embedding_jobs SET state='PENDING',lease_epoch=NULL,lease_deadline_millis=NULL,lease_nonce=NULL WHERE state='LEASED'");
                if (possibleGap) update("INSERT INTO coverage_gaps VALUES(?,?,?,?,1) ON CONFLICT DO UPDATE SET count=count+1",
                        manifest.datasetId(), "runtime", "POSSIBLE_GAP", highWatermark);
            }
            connection.commit(); connection.setAutoCommit(true);
            RecordingSchema.verify(connection); checkpoint();
            watchKnowledgeWatermark=scalar("SELECT coalesce(max(ingest_sequence),0) FROM source_refs WHERE owner='action-ledger-v1'");
            databaseBytes = Math.multiplyExact(scalar("PRAGMA page_count"), PAGE);
            budget.reconcile(reservation); reservation = null;
            state = State.READY; reason = possibleGap ? "READY_WITH_POSSIBLE_GAP" : "READY";
        } catch (Throwable failure) {
            state = State.UNAVAILABLE; reason = safeReason(failure, "INITIALIZATION_FAILED");
            // Administrator diagnostics only. Initialization has not accepted any dialogue body;
            // keep the public health code bounded while retaining packaging/native-driver causes.
            System.getLogger(WorldRecordingService.class.getName()).log(System.Logger.Level.ERROR,
                    "Recording storage initialization failed: " + reason, failure);
            closeConnection(); releaseLock();
            if (quotaInstalled) try { LegacyRecordingQuota.uninstall(worldRoot, budget); quotaInstalled = false; } catch (IOException ignored) { }
        } finally {
            if (reservation != null) try { budget.reconcile(reservation); } catch (Exception ignored) { /* uncertainty retains fail-closed accounting */ }
        }
    }
    /** Internal game registration; callers receive no authority to forge another producer, dataset or epoch. */
    public synchronized ProducerCapability registerProducer(String id, Set<String> channels, Set<SourceKind> sourceKinds) {
        if (state != State.READY || closing || id == null || !id.matches("[a-z0-9_.:-]{1,128}")
                || channels.size() > 64 || sourceKinds.size() > 16) throw new IllegalStateException("PRODUCER_REGISTRATION_UNAVAILABLE");
        if (producers.values().stream().anyMatch(p -> p.id().equals(id))) throw new IllegalArgumentException("DUPLICATE_PRODUCER");
        var handle = ProducerCapability.unregistered(); producers.put(handle, new Producer(id, Set.copyOf(channels), Set.copyOf(sourceKinds))); return handle;
    }
    private synchronized Producer producer(ProducerCapability handle) { return producers.get(handle); }
    public synchronized Health health() {
        return new Health(state, reason, Optional.ofNullable(manifest).map(DatasetManifest::datasetId), epoch, highWatermark,
                possibleGap, sqliteVersion, queuedEntries, queuedBytes, gaps.values().stream().mapToLong(Long::longValue).sum());
    }
    public Optional<UUID> datasetId() { return Optional.ofNullable(manifest).map(DatasetManifest::datasetId); }
    public UUID runtimeEpoch() { return epoch; }
    public UUID worldId() { return worldId; }
    long watchKnowledgeWatermark() { return watchKnowledgeWatermark; }
    /** Existing read leases must fail closed when a source/receipt withdrawal is dispatched or committed. */
    public long authorityGeneration() { return authorityGeneration.get(); }
    public boolean readAuthorityStable() { return pendingInvalidations.get() == 0; }
    /** Interpretation leases change independently of immutable RAW read leases. */
    public long projectionGeneration() { return projectionGeneration.get(); }
    public boolean projectionAuthorityStable() { return pendingProjectionWrites.get() == 0 && readAuthorityStable(); }
    public RecordingRetrievalSettings.Policy retrievalPolicy() { return retrievalPolicy; }
    boolean shadowReadsEnabled() { return settings.archiveMode() != RecordingSettings.Mode.OFF && retrievalPolicy.shadowReadsAllowed(); }
    boolean lexicalMaintenanceEnabled() { return settings.archiveMode() == RecordingSettings.Mode.SHADOW; }
    CompletableFuture<RecordedRoomSearch.Result> readRoom(RecordedRoomSearch.Scope scope,
            MemoryReadSession.Query query, MemoryReadSession.Budget readBudget, long watermark, long before) {
        return readRoom(scope,query,readBudget,watermark,RecordedRoomSearch.SearchPosition.rawOnly(before));
    }
    CompletableFuture<Optional<RecordedNativeEvidenceStore.IssuedNative>> issueNativeEvidence(RecordedRoomSearch.Scope scope,Request request,
            long watermark,Set<UUID> roots) {
        if(!shadowReadsEnabled()||!projectionAdmission()||!datasetId().filter(scope.dataset()::equals).isPresent())
            return CompletableFuture.completedFuture(Optional.empty());
        UUID sealId=UUID.randomUUID();var saved=new java.util.concurrent.atomic.AtomicReference<RecordedNativeEvidenceStore.IssuedNative>();
        // Optional metadata uses the same writer/quota/rollback as capture; never a second database writer.
        return submit("native-memory-evidence",sealId.toString(),NativeMemoryEvidence.MAX_MANIFEST_BYTES+16384L,false,true,sequence->{
            try(var ignored=new SqlReadBudget(connection,System.nanoTime()+250_000_000L)) {
                var value=RecordedNativeEvidenceStore.issue(connection,worldId,scope,request,watermark,roots,sealId,sequence,System.nanoTime()+200_000_000L);
                if(value.isEmpty())return new WriteReceipt(Status.DUPLICATE,OptionalLong.of(highWatermark),sealId.toString(),"NATIVE_EVIDENCE_UNSUPPORTED");
                saved.set(value.orElseThrow());return stored(sequence,sealId.toString());
            }catch(IllegalArgumentException unsupported){throw new Conflict("NATIVE_EVIDENCE_UNSUPPORTED");}
        }).handle((receipt,failure)->failure==null&&receipt.status()==Status.STORED?Optional.ofNullable(saved.get()):Optional.<RecordedNativeEvidenceStore.IssuedNative>empty()).toCompletableFuture();
    }
    CompletableFuture<Optional<RecordedNativeEvidenceStore.ValidatedNative>> validateNativeEvidence(RecordedRoomSearch.Scope scope,NativeMemoryEvidence.Reference reference) {
        if(!shadowReadsEnabled()||!datasetId().filter(scope.dataset()::equals).isPresent())return CompletableFuture.completedFuture(Optional.empty());
        return read(db->{db.setAutoCommit(false);
            try(var ignored=new SqlReadBudget(db,System.nanoTime()+250_000_000L)) {
                return RecordedNativeEvidenceStore.validate(db,worldId,scope,reference,highWatermark,System.nanoTime()+200_000_000L);
            }finally{db.rollback();}
        }).exceptionally(failure->Optional.empty());
    }
    CompletableFuture<Optional<RecordedNativeInterpretationStore.IssuedInterpretation>> issueNativeInterpretationEvidence(
            RecordedRoomSearch.Scope scope,Request request,long watermark,Set<UUID> speechRoots,List<InterpretationReadRecords.Entry> actualEntries) {
        if(!shadowReadsEnabled()||!projectionAdmission()||!projectionAuthorityStable()||!datasetId().filter(scope.dataset()::equals).isPresent())
            return CompletableFuture.completedFuture(Optional.empty());
        Set<UUID> roots=Set.copyOf(speechRoots);List<InterpretationReadRecords.Entry> entries=List.copyOf(actualEntries);
        UUID sealId=UUID.randomUUID();var saved=new java.util.concurrent.atomic.AtomicReference<RecordedNativeInterpretationStore.IssuedInterpretation>();
        // Proof issuance does NOT mutate projections or increment projectionGeneration. Ordinary shared quota writer only.
        return submit("native-interpretation-evidence",sealId.toString(),NativeInterpretationEvidence.MAX_MANIFEST_BYTES+16384L,false,true,sequence->{
            try(var ignored=new SqlReadBudget(connection,System.nanoTime()+250_000_000L)) {
                var value=RecordedNativeInterpretationStore.issue(connection,worldId,scope,request,watermark,roots,entries,sealId,sequence,System.nanoTime()+200_000_000L);
                if(value.isEmpty())return new WriteReceipt(Status.DUPLICATE,OptionalLong.of(highWatermark),sealId.toString(),"NATIVE_INTERPRETATION_UNSUPPORTED");
                saved.set(value.orElseThrow());return stored(sequence,sealId.toString());
            }catch(IllegalArgumentException unsupported){throw new Conflict("NATIVE_INTERPRETATION_UNSUPPORTED");}
        }).handle((receipt,failure)->failure==null&&receipt.status()==Status.STORED?Optional.ofNullable(saved.get()):Optional.<RecordedNativeInterpretationStore.IssuedInterpretation>empty()).toCompletableFuture();
    }
    CompletableFuture<Optional<RecordedNativeInterpretationStore.ValidatedInterpretation>> validateNativeInterpretationEvidence(
            RecordedRoomSearch.Scope scope,NativeInterpretationEvidence.Reference reference) {
        if(!shadowReadsEnabled()||!datasetId().filter(scope.dataset()::equals).isPresent())return CompletableFuture.completedFuture(Optional.empty());
        return read(db->{db.setAutoCommit(false);
            try(var ignored=new SqlReadBudget(db,System.nanoTime()+250_000_000L)) {
                return RecordedNativeInterpretationStore.validate(db,worldId,scope,reference,highWatermark,System.nanoTime()+200_000_000L);
            }finally{db.rollback();}
        }).exceptionally(failure->Optional.empty());
    }
    CompletableFuture<RecordedRoomSearch.Result> readRoom(RecordedRoomSearch.Scope scope,
            MemoryReadSession.Query query, MemoryReadSession.Budget readBudget, long watermark, RecordedRoomSearch.SearchPosition position) {
        if (!shadowReadsEnabled() || !datasetId().filter(scope.dataset()::equals).isPresent())
            return CompletableFuture.failedFuture(new IllegalStateException("MEMORY_READ_DISABLED"));
        return read(db -> {
            // One SQLite snapshot for message, receipt, native knowledge and ancestry checks.
            // A late delivery/invalidated source may not be combined with an earlier query snapshot.
            db.setAutoCommit(false);
            try { return RecordedRoomSearch.query(db, scope, query, readBudget, watermark, position); }
            finally { db.rollback(); }
        });
    }
    CompletableFuture<RecordedInterpretationSearch.Result> readInterpretations(RecordedRoomSearch.Scope scope,
            List<UUID> seedMessageIds, MemoryReadSession.Budget readBudget, long watermark, RecordedInterpretationSearch.Position position) {
        if (!shadowReadsEnabled() || !datasetId().filter(scope.dataset()::equals).isPresent())
            return CompletableFuture.failedFuture(new IllegalStateException("MEMORY_READ_DISABLED"));
        return read(db -> {
            // Quotes, current work version and every derivation dependency share one SQLite snapshot.
            db.setAutoCommit(false);
            try { return RecordedInterpretationSearch.query(db, scope, seedMessageIds, readBudget, watermark, position); }
            finally { db.rollback(); }
        });
    }
    CompletableFuture<RecordedSemanticSearch.Result> readSemantic(RecordedRoomSearch.Scope scope,
            MemoryReadSession.Query query,EmbeddingRecords.QueryVector vector,MemoryReadSession.Budget readBudget,
            long watermark,RecordedSemanticSearch.Position position) {
        if(!shadowReadsEnabled()||!datasetId().filter(scope.dataset()::equals).isPresent())
            return CompletableFuture.failedFuture(new IllegalStateException("MEMORY_READ_DISABLED"));
        return read(db->{db.setAutoCommit(false);
            try{return RecordedSemanticSearch.query(db,scope,query,vector,readBudget,watermark,position);}
            finally{db.rollback();}});
    }
    CompletableFuture<RecordedObservationSearch.Result> readObservations(RecordedRoomSearch.Scope scope,
            MemoryReadSession.Query query,MemoryReadSession.Budget readBudget,long watermark,
            RecordedObservationSearch.Position position) {
        if(!shadowReadsEnabled()||!datasetId().filter(scope.dataset()::equals).isPresent())
            return CompletableFuture.failedFuture(new IllegalStateException("MEMORY_READ_DISABLED"));
        return read(db->{db.setAutoCommit(false);
            // Source identity, exact acquisition work and current tombstones share one archive snapshot.
            // The caller separately prepares and revalidates the game-owned observation proof.
            try{return RecordedObservationSearch.query(db,worldId,scope,query,readBudget,watermark,position);}
            finally{db.rollback();}});
    }
    public Optional<WorldRecordingBudget.Snapshot> quotaSnapshot() { return budget == null ? Optional.empty() : Optional.of(budget.snapshot()); }
    CompletableFuture<RecordedRumorSearch.Result> readRumors(RecordedRoomSearch.Scope scope,MemoryReadSession.Query query,
            MemoryReadSession.Budget readBudget,long watermark,RecordedRumorSearch.Position position) {
        if(!shadowReadsEnabled()||!datasetId().filter(scope.dataset()::equals).isPresent())
            return CompletableFuture.failedFuture(new IllegalStateException("MEMORY_READ_DISABLED"));
        return read(db->{db.setAutoCommit(false);
            try{return RecordedRumorSearch.query(db,worldId,scope,query,readBudget,watermark,position);}
            finally{db.rollback();}});
    }
    /** Optional index work only; no model, game-thread I/O, RAW rewrite, or legacy journal import. */
    public CompletableFuture<RecordingLexicalIndex.BatchResult> pumpLexicalIndex() { return lexicalIndex.pump(); }
    public RecordingLexicalIndex.IndexStatus lexicalIndexStatus() { return lexicalIndex.status(); }
    CompletableFuture<RecordingLexicalIndex.Plan> lexicalPlan() {
        return read(db -> {try(var ignored=new SqlReadBudget(db,System.nanoTime()+250_000_000L)){
            return RecordingLexicalIndex.plan(db,manifest.datasetId());
        }});
    }
    CompletableFuture<RecordingLexicalIndex.Applied> applyLexicalPlan(RecordingLexicalIndex.Plan plan) {
        return applyLexicalPlan(plan,false);
    }
    CompletableFuture<RecordingLexicalIndex.Applied> recordLexicalTimeout(RecordingLexicalIndex.Plan plan) {
        return applyLexicalPlan(plan,true);
    }
    private CompletableFuture<RecordingLexicalIndex.Applied> applyLexicalPlan(RecordingLexicalIndex.Plan plan,boolean timeout) {
        var applied=new java.util.concurrent.atomic.AtomicReference<RecordingLexicalIndex.Applied>();
        return submit("lexical-index","bounded-batch",timeout?4096:Math.addExact(plan.rawBytes(),16384),false,true,sequence->{
            try(var ignored=new SqlReadBudget(connection,System.nanoTime()+250_000_000L)) {
                var result=timeout?RecordingLexicalIndex.recordTimeout(connection,manifest.datasetId(),plan,sequence)
                        :RecordingLexicalIndex.apply(connection,manifest.datasetId(),plan,sequence);applied.set(result);
                return result.changed()?stored(sequence,"lexical-batch")
                        :new WriteReceipt(Status.DUPLICATE,OptionalLong.of(highWatermark),"lexical-batch","LEXICAL_NO_CHANGE");
            }
        }).thenApply(receipt->{
            if(receipt.status()!=Status.STORED&&receipt.status()!=Status.DUPLICATE)
                throw new RecordingLexicalIndex.IndexFailure(receipt.status()==Status.FULL?"DEFERRED":"UNAVAILABLE",receipt.reasonCode());
            return applied.get();
        }).toCompletableFuture();
    }
    /** Registered by the game-owned runtime, never from a model-supplied identifier. */
    public ProjectionWorkerCapability registerProjectionWorker(String id, String extractorVersion) {
        return projections.register(id, extractorVersion);
    }
    public MemoryProjectionPort projectionPort() { return projections; }
    public EmbeddingWorkerCapability registerEmbeddingWorker(String id,EmbeddingRecords.ModelSpace space){return embeddings.register(id,space);}
    public MemoryEmbeddingPort embeddingPort(){return embeddings;}
    RecordingProjectionStore.EmbeddingSource embeddingSource(Connection db,String roomWorkId)throws Exception {
        return projections.embeddingSource(db,roomWorkId);
    }
    @FunctionalInterface interface EmbeddingOperation<T>{RecordingEmbeddingStore.Mutation<T> run(Connection db,long sequence)throws Exception;}
    <T> CompletableFuture<T> embeddingTransaction(String id,long bytes,boolean maintenance,EmbeddingOperation<T> operation){
        var value=new java.util.concurrent.atomic.AtomicReference<T>();
        return submit("native-embedding",id,bytes,maintenance,true,sequence->{
            final RecordingEmbeddingStore.Mutation<T> mutation;
            try(var ignored=new SqlReadBudget(connection,System.nanoTime()+250_000_000L)){mutation=operation.run(connection,sequence);}
            catch(SQLException interrupted){if(interrupted.getErrorCode()==9)throw new Conflict("EMBEDDING_VM_BUDGET");
                if(Set.of(5,6).contains(interrupted.getErrorCode()))throw new Conflict("EMBEDDING_DATABASE_BUSY");throw interrupted;}
            if(!mutation.valid().getAsBoolean())throw new Conflict("STALE_EMBEDDING_AUTHORITY");
            value.set(mutation.value());return mutation.changed()?stored(sequence,id):new WriteReceipt(Status.DUPLICATE,OptionalLong.of(highWatermark),id,"EMBEDDING_NO_CHANGE");
        }).thenApply(receipt->{
            if(receipt.status()!=Status.STORED&&receipt.status()!=Status.DUPLICATE)
                throw new RecordingEmbeddingStore.StorageFailure(receipt.status()==Status.FULL?EmbeddingRecords.Status.FULL
                        :receipt.reasonCode().equals("STALE_EMBEDDING_AUTHORITY")?EmbeddingRecords.Status.STALE
                        :Set.of("EMBEDDING_VM_BUDGET","EMBEDDING_DATABASE_BUSY","LEXICAL_DATABASE_BUSY").contains(receipt.reasonCode())?EmbeddingRecords.Status.DEFERRED
                        :EmbeddingRecords.Status.UNAVAILABLE,receipt.reasonCode());
            return value.get();
        }).toCompletableFuture();
    }
    boolean projectionAdmission() {
        return !closing && state == State.READY && readAuthorityStable() && budget != null
                && Set.of("READY", "WARNING").contains(budget.snapshot().state());
    }
    @FunctionalInterface interface ProjectionOperation<T> { RecordingProjectionStore.Mutation<T> run(Connection db, long sequence) throws Exception; }
    <T> CompletableFuture<T> projectionTransaction(String id, long bytes, boolean maintenance, ProjectionOperation<T> operation) {
        var value = new java.util.concurrent.atomic.AtomicReference<T>();
        // Callers may cancel their view, but cannot cancel an admitted writer or its authority cleanup.
        var exposed = new CompletableFuture<T>();
        pendingProjectionWrites.incrementAndGet();
        final CompletionStage<WriteReceipt> committed;
        try { committed = submit("memory-projection", id, bytes, maintenance, sequence -> {
            final RecordingProjectionStore.Mutation<T> mutation;
            try (var ignored = new SqlReadBudget(connection, System.nanoTime() + 250_000_000L)) {
                mutation = operation.run(connection, sequence);
            }
            catch(SQLException interrupted) {
                if(Set.of(5,6,9).contains(interrupted.getErrorCode()))throw new Conflict("PROJECTION_READ_TIMEOUT");
                throw interrupted;
            }
            value.set(mutation.value());
            if(!mutation.valid().getAsBoolean())throw new Conflict("STALE_PROJECTION_AUTHORITY");
            return mutation.changed() ? stored(sequence, id)
                    : new WriteReceipt(Status.DUPLICATE, OptionalLong.of(highWatermark), id, "PROJECTION_NO_CHANGE");
        }); } catch (RuntimeException failure) {
            pendingProjectionWrites.decrementAndGet();
            exposed.completeExceptionally(failure); return exposed;
        }
        committed.whenComplete((receipt, failure) -> {
            if (failure == null && receipt.status() == Status.STORED) projectionGeneration.incrementAndGet();
            pendingProjectionWrites.decrementAndGet();
            if (failure != null) exposed.completeExceptionally(failure);
            else if (receipt.status() != Status.STORED && receipt.status() != Status.DUPLICATE)
                exposed.completeExceptionally(new RecordingProjectionStore.StorageFailure(receipt.status() == Status.FULL
                        ? ProjectionRecords.Status.FULL : receipt.reasonCode().equals("PROJECTION_READ_TIMEOUT")
                        ? ProjectionRecords.Status.DEFERRED : receipt.reasonCode().equals("STALE_PROJECTION_AUTHORITY")
                        ? ProjectionRecords.Status.STALE : ProjectionRecords.Status.UNAVAILABLE, receipt.reasonCode()));
            else exposed.complete(value.get());
        });
        return exposed;
    }

    private boolean watchRepairAllowed(ProducerCapability capability) {
        var registered=producer(capability);
        return registered!=null && registered.id().equals(WatchRecordingCapture.PRODUCER)
                && registered.sourceKinds().containsAll(Set.of(SourceKind.ACTION_OBSERVED,SourceKind.ACTIVITY_OBSERVED));
    }
    CompletableFuture<WatchKnowledgeReconciler.Page> watchKnowledgePage(ProducerCapability capability,long watermark,String after,int limit) {
        if(!watchRepairAllowed(capability)||watermark>highWatermark)
            return CompletableFuture.failedFuture(new IllegalArgumentException("INVALID_WATCH_REPAIR_SCOPE"));
        return read(db->WatchRecordingArchive.readPage(db,worldId,manifest.datasetId(),watermark,after,limit));
    }
    CompletableFuture<Optional<WatchKnowledgeReconciler.Checkpoint>> watchCheckpoint(ProducerCapability capability) {
        if(!watchRepairAllowed(capability))return CompletableFuture.failedFuture(new IllegalArgumentException("INVALID_WATCH_REPAIR_SCOPE"));
        return read(db->WatchRecordingArchive.readCheckpoint(db,manifest.datasetId()));
    }
    CompletableFuture<Boolean> checkpointWatch(ProducerCapability capability,WatchKnowledgeReconciler.Checkpoint checkpoint) {
        if(!watchRepairAllowed(capability))return CompletableFuture.completedFuture(false);
        return submit(WatchRecordingCapture.PRODUCER,"reconciliation-checkpoint",4096,true,sequence->{
            if(checkpoint.archiveWatermark()>highWatermark)throw new Conflict("UNCONFIRMED_WATCH_CHECKPOINT");
            var previous=WatchRecordingArchive.readCheckpoint(connection,manifest.datasetId());
            if(previous.isPresent()&&(checkpoint.watchRevision()<previous.get().watchRevision()
                    ||checkpoint.archiveWatermark()<previous.get().archiveWatermark()))throw new Conflict("WATCH_CHECKPOINT_REGRESSED");
            update("INSERT INTO consumer_cursors VALUES(?,?,?,?) ON CONFLICT DO UPDATE SET cursor=excluded.cursor",
                    manifest.datasetId(),"watch-reconcile-v1","watch-journal",checkpoint.watchRevision());
            update("INSERT INTO consumer_cursors VALUES(?,?,?,?) ON CONFLICT DO UPDATE SET cursor=excluded.cursor",
                    manifest.datasetId(),"watch-reconcile-v1","archive-watermark",checkpoint.archiveWatermark());
            // Housekeeping is committed but creates no new content event and must not continuously trigger itself.
            return new WriteReceipt(Status.DUPLICATE,OptionalLong.of(highWatermark),"reconciliation-checkpoint","WATCH_CHECKPOINT_CONFIRMED");
        }).thenApply(result->result.status()==Status.DUPLICATE).toCompletableFuture();
    }

    @Override public CompletionStage<WriteReceipt> capture(ProducerCapability capability, ConversationEnvelope envelope, RawMessage raw, List<DeliveryReceipt> initial) {
        var producer = producer(capability);
        var deliveries = RecordingRecords.receipts(initial);
        if (producer == null || !identity(envelope.worldId(), envelope.datasetId()) || !producer.channels().contains(envelope.channel()))
            return refused(raw.messageId().toString(), "INVALID_PRODUCER_SCOPE");
        if (!envelope.recordingAllowed()) return refused(raw.messageId().toString(), "RECORDING_DISABLED");
        if (envelope.closed()) return refused(raw.messageId().toString(), "CONVERSATION_CLOSED");
        if (raw.publicationContext() != null && (!epoch.equals(raw.publicationContext().runtimeEpoch())
                || raw.publicationContext().membershipRevision() != envelope.membershipRevision())) return refused(raw.messageId().toString(), "STALE_PUBLICATION_CONTEXT");
        String context = raw.publicationContext() == null ? "" : JSON.toJson(contextData(raw.publicationContext()));
        long bytes = byteSize(raw.body()) + byteSize(context) + deliveries.stream().mapToLong(d -> deliveryGrowth(d)).sum() + 4096;
        if (bytes > QUEUE_BYTES) { gap(producer.id(), "QUEUE_FULL"); return refused(raw.messageId().toString(), "QUEUE_FULL"); }
        String fingerprint = RecordingRecords.sha256(JSON.toJson(List.of(envelope, rawData(raw), deliveries.stream().map(WorldRecordingService::deliveryData).toList())));
        return submit(producer.id(), raw.messageId().toString(), bytes, false, sequence -> {
            try (var query = prepare("SELECT id,request_hash,ingest_sequence FROM messages WHERE dataset_id=? AND producer=? AND occurrence_key=?",
                    manifest.datasetId(), producer.id(), raw.occurrenceKey()); var rows = query.executeQuery()) {
                if (rows.next()) return duplicate(rows, fingerprint, raw.messageId().toString());
            }
            ensureConversation(envelope, raw);
            update("INSERT INTO messages VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", raw.messageId(), manifest.datasetId(), envelope.conversationId(),
                    raw.turnId().orElse(null), producer.id(), raw.occurrenceKey(), raw.bodyHash(), byteSize(raw.body()), raw.speaker().kind(),
                    raw.speaker().id(), raw.occurredAt(), Instant.now(), raw.kind(), fingerprint, sequence);
            var parts = chunks(raw.body());
            for (int i = 0; i < parts.size(); i++) update("INSERT INTO message_parts VALUES(?,?,?)", raw.messageId(), i, parts.get(i));
            if (!context.isEmpty()) update("INSERT INTO message_contexts VALUES(?,?)", raw.messageId(), context);
            for (var receipt : deliveries) insertDelivery(raw.messageId(), receipt, sequence);
            enqueueWork(raw.messageId(), null, null, raw.bodyHash() + ":" + raw.messageId(), "MESSAGE_CAPTURED", sequence);
            return stored(sequence, raw.messageId().toString());
        });
    }
    @Override public CompletionStage<WriteReceipt> recordDeliveries(ProducerCapability capability, DeliveryBatch batch) {
        var producer = producer(capability);
        if (producer == null) return refused(batch.batchId().toString(), "INVALID_CAPABILITY");
        String fingerprint = RecordingRecords.sha256(JSON.toJson(List.of(batch.messageId(), batch.occurrenceKey(), batch.deliveries().stream().map(WorldRecordingService::deliveryData).toList())));
        long bytes = batch.deliveries().stream().mapToLong(WorldRecordingService::deliveryGrowth).sum() + 4096;
        return submit(producer.id(), batch.batchId().toString(), bytes, false, sequence -> {
            try (var query = prepare("SELECT id,request_hash,ingest_sequence FROM delivery_batches WHERE dataset_id=? AND producer=? AND occurrence_key=?",
                    manifest.datasetId(), producer.id(), batch.occurrenceKey()); var rows = query.executeQuery()) {
                if (rows.next()) return duplicate(rows, fingerprint, rows.getString("id"));
            }
            try (var query = prepare("SELECT producer FROM messages WHERE id=? AND dataset_id=?", batch.messageId(), manifest.datasetId()); var rows = query.executeQuery()) {
                if (!rows.next() || !producer.id().equals(rows.getString(1))) throw new Conflict("MISSING_OR_FOREIGN_MESSAGE");
            }
            update("INSERT INTO delivery_batches VALUES(?,?,?,?,?,?,?)", batch.batchId(), manifest.datasetId(), batch.messageId(), producer.id(), batch.occurrenceKey(), fingerprint, sequence);
            for (var receipt : batch.deliveries()) insertDelivery(batch.messageId(), receipt, sequence);
            return stored(sequence, batch.batchId().toString());
        });
    }
    @Override public CompletionStage<WriteReceipt> captureSource(ProducerCapability capability, SourceCapture capture) {
        var producer = producer(capability); var source = capture.source();
        if (producer == null || !sourceAllowed(producer, source)) return refused(source.sourceId(), "INVALID_SOURCE_SCOPE");
        Long cutoff = manifest.sourceHighWatermarks().get(producer.id());
        if (cutoff == null || capture.durableSourceCursor() <= cutoff) return refused(source.sourceId(), "BEFORE_CUTOVER_OR_ORIGIN_UNKNOWN");
        String fingerprint = RecordingRecords.sha256(JSON.toJson(List.of(source, capture.durableSourceCursor(), capture.knowledge().stream().map(WorldRecordingService::knowledgeData).toList())));
        long bytes = capture.knowledge().stream().mapToLong(k -> byteSize(k.permittedProjection()) + 4096).sum() + 4096;
        return submitSourceMutation(producer.id(), source, bytes, sequence -> {
            if (invalidationVersion(source) > 0) throw new Conflict("SOURCE_REVOKED");
            try (var query = sourceQuery("SELECT id,request_hash,ingest_sequence FROM source_refs WHERE", source); var rows = query.executeQuery()) {
                if (rows.next()) return duplicate(rows, fingerprint, source.sourceId());
            }
            update("INSERT INTO source_refs(dataset_id,kind,owner,source_id,source_revision,source_hash,request_hash,ingest_sequence) VALUES(?,?,?,?,?,?,?,?)",
                    source.datasetId(), source.kind(), source.owner(), source.sourceId(), source.revision(), source.hash(), fingerprint, sequence);
            long id = scalar("SELECT last_insert_rowid()");
            for (var receipt : capture.knowledge()) {
                if (knowledgeInvalidationVersion(source.datasetId(), receipt.receiptId()) > 0) throw new Conflict("KNOWLEDGE_REVOKED");
                update("INSERT INTO knowledge_receipts VALUES(?,?,?,?,?,?,?,?,?)", receipt.receiptId(), manifest.datasetId(), id,
                        receipt.godId(), receipt.acquisition(), receipt.permittedProjection(), JSON.toJson(receipt.permittedAudience().stream().map(ActorRef::key).sorted().toList()),
                        receipt.policyRevision(), RecordingRecords.sha256(JSON.toJson(knowledgeData(receipt))));
            }
            enqueueWork(null, null, id, source.hash() + ":" + id, "SOURCE_CAPTURED", sequence);
            update("INSERT INTO consumer_cursors VALUES(?,?,?,?) ON CONFLICT DO UPDATE SET cursor=MAX(cursor,excluded.cursor)",
                    manifest.datasetId(), "capture", producer.id(), capture.durableSourceCursor());
            return stored(sequence, source.sourceId());
        });
    }
    @Override public CompletionStage<WriteReceipt> invalidate(ProducerCapability capability, SourceInvalidation invalidation) {
        var producer = producer(capability); var source = invalidation.source();
        if (producer == null || !sourceAllowed(producer, source)) return refused(source.sourceId(), "INVALID_SOURCE_SCOPE");
        return submitInvalidation(producer.id(), source.sourceId(), sequence -> {
            long previous = invalidationVersion(source);
            if (previous >= invalidation.stateVersion()) return new WriteReceipt(Status.DUPLICATE, OptionalLong.of(highWatermark), source.sourceId(), "ALREADY_INVALIDATED");
            update("INSERT INTO invalidations VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO UPDATE SET state_version=excluded.state_version,reason_code=excluded.reason_code,ingest_sequence=excluded.ingest_sequence",
                    source.datasetId(), source.kind(), source.owner(), source.sourceId(), source.revision(), invalidation.stateVersion(), invalidation.reasonCode(), sequence);
            update("UPDATE source_refs SET revoked=1 WHERE dataset_id=? AND kind=? AND owner=? AND source_id=? AND source_revision=?",
                    source.datasetId(), source.kind(), source.owner(), source.sourceId(), source.revision());
            update("UPDATE work_items SET state='INVALIDATED',lease_epoch=NULL,lease_deadline=NULL WHERE state!='INVALIDATED' AND source_ref IN "
                            + "(SELECT id FROM source_refs WHERE dataset_id=? AND kind=? AND owner=? AND source_id=? AND source_revision=?)",
                    source.datasetId(), source.kind(), source.owner(), source.sourceId(), source.revision());
            RecordingProjectionStore.invalidateSources(connection, source);
            return stored(sequence, source.sourceId());
        });
    }

    @Override public CompletionStage<SourceRegistrationReceipt> registerSource(ProducerCapability capability, SourceRegistration registration) {
        var producer = producer(capability);
        if (producer == null || producer.sourceKinds().isEmpty() || !identity(registration.worldId(), registration.datasetId())
                || !producer.id().equals(registration.owner()) || manifest.sourceHighWatermarks().containsKey(registration.owner()))
            return CompletableFuture.completedFuture(new SourceRegistrationReceipt(WriteReceipt.failed(Status.UNAVAILABLE,
                    registration.owner(), "INVALID_SOURCE_REGISTRATION_SCOPE"), OptionalLong.empty()));
        var cutoff = new java.util.concurrent.atomic.AtomicLong(-1);
        return submit(producer.id(), registration.owner(), 4096, true, sequence -> {
            try (var query = prepare("SELECT lineage_id,cutoff,latest_confirmed FROM source_cutovers WHERE dataset_id=? AND owner=?",
                    registration.datasetId(), registration.owner()); var row = query.executeQuery()) {
                if (row.next()) {
                    if (!registration.lineageId().toString().equals(row.getString(1))) throw new Conflict("SOURCE_LINEAGE_MISMATCH");
                    if (registration.durableCursor() < row.getLong(3)) throw new Conflict("SOURCE_CURSOR_REGRESSED");
                    cutoff.set(row.getLong(2));
                    if (registration.durableCursor() == row.getLong(3)) return new WriteReceipt(Status.DUPLICATE,
                            OptionalLong.of(highWatermark), registration.owner(), "SOURCE_ALREADY_CONFIRMED");
                    update("UPDATE source_cutovers SET latest_confirmed=? WHERE dataset_id=? AND owner=?",
                            registration.durableCursor(), registration.datasetId(), registration.owner());
                    return stored(sequence, registration.owner());
                }
            }
            // Never attach missing metadata to already ingested material from a different capture path.
            try (var query = prepare("SELECT 1 FROM source_refs WHERE dataset_id=? AND owner=? LIMIT 1", registration.datasetId(), registration.owner()); var row = query.executeQuery()) {
                if (row.next()) throw new Conflict("SOURCE_REGISTRATION_MISSING_FOR_EXISTING_DATA");
            }
            cutoff.set(registration.durableCursor());
            update("INSERT INTO source_cutovers VALUES(?,?,?,?,?)", registration.datasetId(), registration.owner(),
                    registration.lineageId(), cutoff.get(), registration.durableCursor());
            return stored(sequence, registration.owner());
        }).thenApply(receipt -> {
            boolean accepted = receipt.status() == Status.STORED || receipt.status() == Status.DUPLICATE;
            if (accepted) confirmedSourceLineages.put(registration.owner(), registration.lineageId());
            else confirmedSourceLineages.remove(registration.owner());
            return new SourceRegistrationReceipt(receipt, accepted ? OptionalLong.of(cutoff.get()) : OptionalLong.empty());
        });
    }

    @Override public CompletionStage<WriteReceipt> appendKnowledge(ProducerCapability capability, SourceKnowledgeCapture capture) {
        var producer = producer(capability); var source = capture.source();
        if (producer == null || !sourceAllowed(producer, source)) return refused(source.sourceId(), "INVALID_SOURCE_SCOPE");
        String fingerprint = RecordingRecords.sha256(JSON.toJson(List.of(source, capture.lineageId(), capture.originCursor(), capture.sourceStateCursor())));
        long bytes = capture.knowledge().stream().mapToLong(k -> byteSize(JSON.toJson(knowledgeData(k.receipt()))) + 4096).sum() + 4096;
        return submitSourceMutation(producer.id(), source, bytes, sequence -> {
            if (!capture.lineageId().equals(confirmedSourceLineages.get(source.owner()))) throw new Conflict("SOURCE_NOT_CONFIRMED_THIS_RUNTIME");
            try (var query = prepare("SELECT lineage_id,cutoff,latest_confirmed FROM source_cutovers WHERE dataset_id=? AND owner=?", source.datasetId(), source.owner()); var row = query.executeQuery()) {
                if (!row.next() || !capture.lineageId().toString().equals(row.getString(1))) throw new Conflict("SOURCE_LINEAGE_MISMATCH");
                if (capture.originCursor() <= row.getLong(2)) throw new Conflict("BEFORE_CUTOVER_OR_ORIGIN_UNKNOWN");
                long confirmed = row.getLong(3);
                if (capture.sourceStateCursor() > confirmed || capture.knowledge().stream().anyMatch(k -> k.acquiredCursor() > confirmed))
                    throw new Conflict("SOURCE_NOT_DURABLY_CONFIRMED");
            }
            if (invalidationVersion(source) > 0) throw new Conflict("SOURCE_REVOKED");
            long sourceKey = 0, originalSequence = sequence;
            boolean changed = false;
            try (var query = sourceQuery("SELECT id,request_hash,ingest_sequence,revoked FROM source_refs WHERE", source); var row = query.executeQuery()) {
                if (row.next()) {
                    if (!fingerprint.equals(row.getString(2)) || row.getInt(4) != 0) throw new Conflict("SOURCE_PROVENANCE_CONFLICT");
                    sourceKey = row.getLong(1); originalSequence = row.getLong(3);
                }
            }
            if (sourceKey == 0) {
                update("INSERT INTO source_refs(dataset_id,kind,owner,source_id,source_revision,source_hash,request_hash,ingest_sequence) VALUES(?,?,?,?,?,?,?,?)",
                        source.datasetId(), source.kind(), source.owner(), source.sourceId(), source.revision(), source.hash(), fingerprint, sequence);
                sourceKey = scalar("SELECT last_insert_rowid()"); changed = true;
                update("INSERT INTO source_origins VALUES(?,?,?,?)", sourceKey, capture.lineageId(), capture.originCursor(), capture.sourceStateCursor());
                enqueueWork(null, null, sourceKey, source.hash() + ":" + sourceKey, "SOURCE_CAPTURED", sequence);
            }
            for (var acquired : capture.knowledge()) {
                var receipt = acquired.receipt();
                if (knowledgeInvalidationVersion(source.datasetId(), receipt.receiptId()) > 0) throw new Conflict("KNOWLEDGE_REVOKED");
                String hash = RecordingRecords.sha256(JSON.toJson(knowledgeData(receipt)));
                try (var query = prepare("SELECT k.source_ref,k.receipt_hash,o.acquired_cursor FROM knowledge_receipts k LEFT JOIN knowledge_origins o ON o.receipt_id=k.id WHERE k.id=?", receipt.receiptId()); var row = query.executeQuery()) {
                    if (row.next()) {
                        if (row.getLong(1) != sourceKey || !hash.equals(row.getString(2)) || row.getLong(3) != acquired.acquiredCursor()) throw new Conflict("KNOWLEDGE_RECEIPT_CONFLICT");
                        continue;
                    }
                }
                update("INSERT INTO knowledge_receipts VALUES(?,?,?,?,?,?,?,?,?)", receipt.receiptId(), source.datasetId(), sourceKey,
                        receipt.godId(), receipt.acquisition(), receipt.permittedProjection(), JSON.toJson(receipt.permittedAudience().stream().map(ActorRef::key).sorted().toList()), receipt.policyRevision(), hash);
                update("INSERT INTO knowledge_origins VALUES(?,?)", receipt.receiptId(), acquired.acquiredCursor());
                enqueueWork(null, null, sourceKey, receipt.receiptId() + ":" + hash, "KNOWLEDGE_ACQUIRED", sequence);
                changed = true;
            }
            // No MAX consumer checkpoint: every source/receipt has its own durable idempotency key.
            return changed ? stored(sequence, source.sourceId()) : new WriteReceipt(Status.DUPLICATE, OptionalLong.of(originalSequence), source.sourceId(), "KNOWLEDGE_ALREADY_CAPTURED");
        });
    }

    @Override public CompletionStage<WriteReceipt> invalidateKnowledge(ProducerCapability capability, KnowledgeInvalidation invalidation) {
        var producer = producer(capability); var source = invalidation.source();
        String receiptId = invalidation.receiptId().toString();
        if (producer == null || !sourceAllowed(producer, source)) return refused(receiptId, "INVALID_SOURCE_SCOPE");
        return submitInvalidation(producer.id(), receiptId, sequence -> {
            try (var query = prepare("SELECT s.kind,s.owner,s.source_id,s.source_revision FROM knowledge_receipts k JOIN source_refs s ON s.id=k.source_ref WHERE k.id=? AND k.dataset_id=?", receiptId, source.datasetId()); var row = query.executeQuery()) {
                if (row.next() && !sameSourceKey(row, source)) throw new Conflict("FOREIGN_KNOWLEDGE_RECEIPT");
            }
            try (var query = prepare("SELECT kind,owner,source_id,source_revision,state_version FROM knowledge_invalidations WHERE dataset_id=? AND receipt_id=?", source.datasetId(), receiptId); var row = query.executeQuery()) {
                if (row.next()) {
                    if (!sameSourceKey(row, source)) throw new Conflict("FOREIGN_KNOWLEDGE_TOMBSTONE");
                    if (row.getLong(5) >= invalidation.stateVersion()) return new WriteReceipt(Status.DUPLICATE, OptionalLong.of(highWatermark), receiptId, "ALREADY_INVALIDATED");
                }
            }
            update("INSERT INTO knowledge_invalidations VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT DO UPDATE SET state_version=excluded.state_version,reason_code=excluded.reason_code,ingest_sequence=excluded.ingest_sequence",
                    source.datasetId(), receiptId, source.kind(), source.owner(), source.sourceId(), source.revision(), invalidation.stateVersion(), invalidation.reasonCode(), sequence);
            update("UPDATE work_items SET state='INVALIDATED',lease_epoch=NULL,lease_deadline=NULL WHERE dataset_id=? AND kind IN ('KNOWLEDGE_ACQUIRED','ROOM_KNOWLEDGE_CAPTURED') AND source_version IN (SELECT id||':'||receipt_hash FROM knowledge_receipts WHERE id=? AND dataset_id=?)",
                    source.datasetId(), receiptId, source.datasetId());
            RecordingProjectionStore.invalidateReceipt(connection, receiptId);
            return stored(sequence, receiptId);
        });
    }

    private CompletionStage<WriteReceipt> submitInvalidation(String stream, String id, WriteOperation operation) {
        pendingInvalidations.incrementAndGet(); authorityGeneration.incrementAndGet();
        var exposed = new CompletableFuture<WriteReceipt>();
        try {
            submit(stream, id, 4096, true, operation).whenComplete((receipt, failure) -> {
                if (failure == null && receipt.status() == Status.STORED) projectionGeneration.incrementAndGet();
                authorityGeneration.incrementAndGet(); pendingInvalidations.decrementAndGet();
                if (failure != null) exposed.completeExceptionally(failure); else exposed.complete(receipt);
            });
            return exposed;
        } catch (RuntimeException failure) {
            authorityGeneration.incrementAndGet(); pendingInvalidations.decrementAndGet();
            throw failure;
        }
    }

    /** A new revision withdraws older proof authority; unrelated sources and late receipts do not. */
    private CompletionStage<WriteReceipt> submitSourceMutation(String stream,SourceRef source,long bytes,WriteOperation operation) {
        var fenced=new java.util.concurrent.atomic.AtomicBoolean();
        var exposed=new CompletableFuture<WriteReceipt>();
        try {
            submit(stream,source.sourceId(),bytes,false,sequence->{
                // Existing unique source index bounds this lookup. Detect on the single writer,
                // before a replacement can become visible to any reader snapshot.
                try(var query=prepare("SELECT 1 FROM source_refs old WHERE old.dataset_id=? AND old.kind=? AND old.owner=?"
                        +" AND old.source_id=? AND old.source_revision<? AND NOT EXISTS(SELECT 1 FROM source_refs same"
                        +" WHERE same.dataset_id=old.dataset_id AND same.kind=old.kind AND same.owner=old.owner"
                        +" AND same.source_id=old.source_id AND same.source_revision=?) LIMIT 1",
                        source.datasetId(),source.kind(),source.owner(),source.sourceId(),source.revision(),source.revision());var rows=query.executeQuery()) {
                    if(rows.next()) {
                        pendingInvalidations.incrementAndGet();authorityGeneration.incrementAndGet();fenced.set(true);
                    }
                }
                return operation.run(sequence);
            }).whenComplete((receipt,failure)->{
                // Detached internal completion is not cancelled with the public caller's future.
                if(fenced.getAndSet(false)) {
                    if(failure==null&&receipt.status()==Status.STORED)projectionGeneration.incrementAndGet();
                    authorityGeneration.incrementAndGet();pendingInvalidations.decrementAndGet();
                }
                if(failure!=null)exposed.completeExceptionally(failure);else exposed.complete(receipt);
            });
            return exposed;
        }catch(RuntimeException failure) {
            if(fenced.getAndSet(false)){authorityGeneration.incrementAndGet();pendingInvalidations.decrementAndGet();}
            throw failure;
        }
    }

    private static boolean sameSourceKey(ResultSet row, SourceRef source) throws SQLException {
        return source.kind().name().equals(row.getString(1)) && source.owner().equals(row.getString(2))
                && source.sourceId().equals(row.getString(3)) && source.revision() == row.getLong(4);
    }
    private long knowledgeInvalidationVersion(UUID dataset, UUID receipt) throws SQLException {
        try (var query = prepare("SELECT state_version FROM knowledge_invalidations WHERE dataset_id=? AND receipt_id=?", dataset, receipt); var row = query.executeQuery()) {
            return row.next() ? row.getLong(1) : 0;
        }
    }

    private boolean identity(UUID world, UUID dataset) { return manifest != null && worldId.equals(world) && manifest.datasetId().equals(dataset); }
    private boolean sourceAllowed(Producer producer, SourceRef source) {
        return identity(source.worldId(), source.datasetId()) && producer.sourceKinds().contains(source.kind()) && producer.id().equals(source.owner());
    }
    private void ensureConversation(ConversationEnvelope envelope, RawMessage raw) throws Exception {
        try (var query = prepare("SELECT channel,policy,policy_revision,membership_revision,closed FROM conversations WHERE id=?", envelope.conversationId()); var rows = query.executeQuery()) {
            if (rows.next()) {
                if (rows.getInt("closed") != 0 || !envelope.channel().equals(rows.getString("channel")) || !envelope.privacyPolicy().equals(rows.getString("policy"))
                        || envelope.policyRevision() < rows.getLong("policy_revision") || envelope.membershipRevision() < rows.getLong("membership_revision"))
                    throw new Conflict("CONVERSATION_REVISION_CONFLICT");
                update("UPDATE conversations SET policy_revision=?,membership_revision=? WHERE id=?", envelope.policyRevision(), envelope.membershipRevision(), envelope.conversationId());
            } else update("INSERT INTO conversations VALUES(?,?,?,?,?,?,?,?)", envelope.conversationId(), manifest.datasetId(), envelope.channel(),
                    envelope.privacyPolicy(), envelope.policyRevision(), envelope.membershipRevision(), 0, envelope.adapterVersion());
        }
        if (raw.turnId().isPresent()) {
            try (var query = prepare("SELECT conversation_id,sequence FROM turns WHERE id=?", raw.turnId().get()); var rows = query.executeQuery()) {
                if (rows.next()) {
                    if (!envelope.conversationId().toString().equals(rows.getString(1)) || raw.turnSequence() != rows.getLong(2)) throw new Conflict("TURN_IDENTITY_CONFLICT");
                } else update("INSERT INTO turns VALUES(?,?,?,?)", raw.turnId().get(), manifest.datasetId(), envelope.conversationId(), raw.turnSequence());
            }
        }
    }
    private void insertDelivery(UUID message, DeliveryReceipt receipt, long sequence) throws Exception {
        String hash = RecordingRecords.sha256(JSON.toJson(deliveryData(receipt)));
        try (var query = prepare("SELECT receipt_hash,message_id FROM deliveries WHERE id=? OR (message_id=? AND actor_kind=? AND actor_id=? AND kind=?)",
                receipt.receiptId(), message, receipt.recipient().kind(), receipt.recipient().id(), receipt.deliveryKind()); var rows = query.executeQuery()) {
            if (rows.next()) { if (!hash.equals(rows.getString(1)) || !message.toString().equals(rows.getString(2))) throw new Conflict("DELIVERY_CONFLICT"); return; }
        }
        String parts = JSON.toJson(receipt.view().parts());
        try (var query = prepare("SELECT parts_json FROM delivery_views WHERE message_id=? AND view_hash=?", message, receipt.view().hash()); var rows = query.executeQuery()) {
            if (rows.next()) { if (!parts.equals(rows.getString(1))) throw new Conflict("DELIVERY_VIEW_CONFLICT"); }
            else update("INSERT INTO delivery_views VALUES(?,?,?,?)", message, receipt.view().hash(), receipt.view().plainText(), parts);
        }
        update("INSERT INTO deliveries VALUES(?,?,?,?,?,?,?,?,?,?,?)", receipt.receiptId(), manifest.datasetId(), message, receipt.recipient().kind(),
                receipt.recipient().id(), receipt.deliveryKind(), receipt.dispatchedAt(), receipt.audienceRevision(), receipt.status(), receipt.view().hash(), hash);
        // Recipient receipt records only which immutable shared-view parts were actually dispatched.
        for (int index : new TreeSet<>(receipt.deliveredParts())) update("INSERT INTO delivery_part_refs VALUES(?,?)", receipt.receiptId(), index);
        enqueueWork(message, receipt.receiptId(), null, receipt.receiptId() + ":" + hash, "DELIVERY_CAPTURED", sequence);
        insertRoomKnowledge(message, receipt, sequence);
    }
    /** Native dialogue source: receipt + knowledge pointer + work/cursor commit in the SAME transaction. No copied raw per God. */
    private void insertRoomKnowledge(UUID message, DeliveryReceipt receipt, long sequence) throws Exception {
        if (receipt.recipient().kind() != ActorKind.GOD || !receipt.deliveryKind().equals("GAME_HEARD")
                || receipt.status() != DeliveryStatus.SERVER_DISPATCHED) return;
        String bodyHash, actorKind; com.google.gson.JsonObject context;
        try (var query = prepare("SELECT m.body_hash,m.actor_kind,x.context_json FROM messages m JOIN message_contexts x ON x.message_id=m.id WHERE m.id=? AND m.producer='room-publication-v2'", message);
                var rows = query.executeQuery()) {
            if (!rows.next()) return; bodyHash = rows.getString(1); actorKind = rows.getString(2);
            context = com.google.gson.JsonParser.parseString(rows.getString(3)).getAsJsonObject();
        }
        if (!context.has("memoryMode") || !Set.of("PERSONAL", "RUMOR_TEST").contains(context.get("memoryMode").getAsString())) return;
        var audience = context.getAsJsonArray("fullAudience");
        if (audience.size() > 256 || !audience.asList().stream().anyMatch(v -> v.getAsString().equals(receipt.recipient().key()))) return;
        String kind = actorKind.equals("PLAYER") ? SourceKind.DIALOGUE_DIRECT.name() : SourceKind.DERIVED_SPEECH.name();
        String sourceHash = RecordingRecords.sha256(bodyHash + ":" + JSON.toJson(context));
        if (invalidationVersion(new SourceRef(worldId, manifest.datasetId(), SourceKind.valueOf(kind),
                RoomRecordingCapture.PRODUCER, message.toString(), 1, sourceHash)) > 0) return;
        update("INSERT OR IGNORE INTO source_refs(dataset_id,kind,owner,source_id,source_revision,source_hash,request_hash,ingest_sequence) VALUES(?,?,?, ?,1,?,?,?)",
                manifest.datasetId(), kind, RoomRecordingCapture.PRODUCER, message, sourceHash, sourceHash, sequence);
        long sourceId;
        try (var query = prepare("SELECT id,source_hash,revoked FROM source_refs WHERE dataset_id=? AND kind=? AND owner=? AND source_id=? AND source_revision=1",
                manifest.datasetId(), kind, RoomRecordingCapture.PRODUCER, message); var rows = query.executeQuery()) {
            if (!rows.next() || !sourceHash.equals(rows.getString(2)) || rows.getInt(3) != 0) throw new Conflict("ROOM_SOURCE_CONFLICT"); sourceId = rows.getLong(1);
        }
        UUID knowledgeId = UUID.nameUUIDFromBytes((receipt.receiptId() + "/knowledge-v1").getBytes(StandardCharsets.UTF_8));
        // Preserve the actual publication/delivery, but never recreate a withdrawn God proof.
        if (knowledgeInvalidationVersion(manifest.datasetId(), knowledgeId) > 0) return;
        String projection = JSON.toJson(Map.of("messageId", message.toString(), "deliveryReceiptId", receipt.receiptId().toString(),
                "viewHash", receipt.view().hash(), "projectionKind", "DIRECT_HEARD_POINTER"));
        String receiptHash = RecordingRecords.sha256(sourceHash + ":" + JSON.toJson(deliveryData(receipt)));
        update("INSERT INTO knowledge_receipts VALUES(?,?,?,?,?,?,?,?,?)", knowledgeId, manifest.datasetId(), sourceId,
                receipt.recipient().id(), "DIRECT_HEARD", projection, JSON.toJson(audience), receipt.audienceRevision(), receiptHash);
        enqueueWork(message, receipt.receiptId(), sourceId, knowledgeId + ":" + receiptHash, "ROOM_KNOWLEDGE_CAPTURED", sequence);
        update("INSERT INTO consumer_cursors VALUES(?,?,?,?) ON CONFLICT DO UPDATE SET cursor=MAX(cursor,excluded.cursor)",
                manifest.datasetId(), "room-knowledge", "archive-deliveries", sequence);
    }
    private static long deliveryGrowth(DeliveryReceipt receipt) {
        // Bounded 256-actor knowledge scope plus projection/work, including UTF-8 and JSON escaping.
        return byteSize(receipt.view().plainText()) * 2 + 1024
                + (receipt.recipient().kind() == ActorKind.GOD && receipt.deliveryKind().equals("GAME_HEARD") ? 400_000 : 0);
    }
    private void enqueueWork(UUID message, UUID receipt, Long source, String version, String kind, long sequence) throws SQLException {
        update("INSERT INTO work_items(id,dataset_id,message_id,receipt_id,source_ref,source_version,kind,state,created_sequence,lease_epoch,lease_deadline) VALUES(?,?,?,?,?,?,?,'PENDING',?,NULL,NULL)", UUID.randomUUID(), manifest.datasetId(), message, receipt, source, version, kind, sequence);
    }
    private long invalidationVersion(SourceRef source) throws SQLException {
        try (var query = sourceQuery("SELECT state_version FROM invalidations WHERE", source); var rows = query.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
    }
    private PreparedStatement sourceQuery(String prefix, SourceRef source) throws SQLException {
        return prepare(prefix + " dataset_id=? AND kind=? AND owner=? AND source_id=? AND source_revision=?", source.datasetId(), source.kind(), source.owner(), source.sourceId(), source.revision());
    }
    private WriteReceipt duplicate(ResultSet row, String hash, String id) throws SQLException, Conflict {
        if (!hash.equals(row.getString("request_hash"))) throw new Conflict("OCCURRENCE_CONFLICT");
        return new WriteReceipt(Status.DUPLICATE, OptionalLong.of(row.getLong("ingest_sequence")), id, "DUPLICATE");
    }
    private static WriteReceipt stored(long sequence, String id) { return new WriteReceipt(Status.STORED, OptionalLong.of(sequence), id, "DURABLE_COMMIT"); }
    private static CompletionStage<WriteReceipt> refused(String id, String reason) { return CompletableFuture.completedFuture(WriteReceipt.failed(Status.UNAVAILABLE, id, reason)); }

    private CompletionStage<WriteReceipt> submit(String stream, String id, long bytes, boolean maintenance, WriteOperation operation) {
        return submit(stream,id,bytes,maintenance,false,operation);
    }
    private synchronized CompletionStage<WriteReceipt> submit(String stream, String id, long bytes, boolean maintenance, boolean optional, WriteOperation operation) {
        if (closing || !(state == State.READY || maintenance && state == State.FULL)) return refused(id, state == State.OFF ? "ARCHIVE_OFF" : reason);
        if (bytes < 0 || bytes > QUEUE_BYTES || queuedEntries >= QUEUE_ENTRIES || queuedBytes > QUEUE_BYTES - bytes) {
            gap(stream, "QUEUE_FULL"); return refused(id, "QUEUE_FULL");
        }
        final Queued task;
        try {
            // Bound allocation with max_page_count, and WAL with no cache spill + at most every database page.
            // This is intentionally conservative for a growing database; underestimates fail SQLITE_FULL, never overwrite RAW.
            long growth = Math.multiplyExact(Math.addExact(64, Math.floorDiv(Math.multiplyExact(bytes, 16), PAGE)), PAGE);
            long maximum = Math.addExact(Math.addExact(databaseBytes, queuedGrowth), growth);
            long wal = Math.addExact(32, Math.multiplyExact(Math.floorDiv(maximum, PAGE), PAGE + 24));
            long shm = Math.multiplyExact(32768, Math.addExact(2, Math.ceilDiv(maximum / PAGE, 4096)));
            long reservationBytes = Math.addExact(Math.addExact(wal, growth), shm);
            var reservation = budget.tryReserve(ManagedStoreRegistry.RECORDING, reservationBytes, maintenance);
            if (reservation.isEmpty()) {
                if(!optional){state = State.FULL; reason = "RECORDING_QUOTA_FULL"; gap(stream, reason);}
                return CompletableFuture.completedFuture(WriteReceipt.failed(Status.FULL, id, "RECORDING_QUOTA_FULL"));
            }
            task = new Queued(bytes, growth, maximum, reservation.orElseThrow(),optional);
            queuedEntries++; queuedBytes += bytes; queuedGrowth = Math.addExact(queuedGrowth, growth);
        } catch (ArithmeticException invalid) { gap(stream, "GROWTH_OVERFLOW"); return refused(id, "GROWTH_OVERFLOW"); }
        var future = new CompletableFuture<WriteReceipt>();
        try { writer.execute(() -> runWrite(stream, id, task, operation, future)); }
        catch (RejectedExecutionException stopped) { release(task); future.complete(WriteReceipt.failed(Status.UNAVAILABLE, id, "QUEUE_FULL")); }
        return future;
    }
    private void runWrite(String stream, String id, Queued task, WriteOperation operation, CompletableFuture<WriteReceipt> future) {
        WriteReceipt receipt;
        boolean commitAttempted=false;
        try {
            if (state == State.UNAVAILABLE || connection == null) throw new IOException("STORE_UNAVAILABLE");
            budget.refresh();
            var accounted = budget.snapshot();
            if (Math.addExact(accounted.usedPhysicalBytes(), accounted.outstandingReservations()) > accounted.limitBytes())
                throw new IOException("QUOTA_CHANGED_AFTER_ADMISSION");
            if (Files.getFileStore(worldRoot).getUsableSpace() < task.reservation().bytes()) throw new IOException("DISK_HEADROOM_UNAVAILABLE");
            checkpoint();
            long current = scalar("PRAGMA page_count");
            if (Math.multiplyExact(current, PAGE) > task.maxDatabaseBytes()) throw new IOException("UNRESERVED_DATABASE_GROWTH");
            sql("PRAGMA max_page_count=" + task.maxDatabaseBytes() / PAGE);
            connection.setAutoCommit(false);
            receipt = operation.run(Math.addExact(highWatermark, 1));
            if (receipt.status() == Status.STORED) update("UPDATE recording_meta SET high_watermark=? WHERE singleton=1", receipt.ingestSequence().orElseThrow());
            commitAttempted=true;
            connection.commit(); connection.setAutoCommit(true);
            if (receipt.status() == Status.STORED) highWatermark = receipt.ingestSequence().orElseThrow();
            if(receipt.status()==Status.STORED&&stream.equals(WatchRecordingCapture.PRODUCER))
                watchKnowledgeWatermark=scalar("SELECT coalesce(max(ingest_sequence),0) FROM source_refs WHERE owner='action-ledger-v1'");
            databaseBytes = Math.multiplyExact(scalar("PRAGMA page_count"), PAGE);
        } catch (Conflict conflict) {
            rollback(); gap(stream, conflict.getMessage()); receipt = WriteReceipt.failed(Status.CONFLICT, id, conflict.getMessage());
        } catch (Exception failure) {
            rollback();
            if(task.optional() && !commitAttempted && state!=State.UNAVAILABLE && !(failure instanceof SQLException sql && Set.of(11,26).contains(sql.getErrorCode()))) {
                String code=failure instanceof SQLException sql&&sql.getErrorCode()==9?"LEXICAL_TIME_BUDGET"
                        :failure instanceof SQLException sql&&Set.of(5,6).contains(sql.getErrorCode())||"CHECKPOINT_BUSY".equals(failure.getMessage())
                        ?"LEXICAL_DATABASE_BUSY":safeReason(failure,"OPTIONAL_INDEX_FAILED");
                receipt=WriteReceipt.failed(failure instanceof SQLException sql&&sql.getErrorCode()==13?Status.FULL:Status.UNAVAILABLE,id,code);
            }
            else if (failure instanceof SQLException sql && sql.getErrorCode() == 19) receipt = WriteReceipt.failed(Status.CONFLICT, id, "CONSTRAINT_CONFLICT");
            else {
                boolean full = failure instanceof SQLException sql && sql.getErrorCode() == 13;
                state = full ? State.FULL : State.UNAVAILABLE; reason = full ? "SQLITE_ALLOCATION_BOUND" : safeReason(failure, "WRITE_FAILED");
                receipt = WriteReceipt.failed(full ? Status.FULL : Status.UNAVAILABLE, id, reason);
            }
            if(!task.optional())gap(stream, receipt.reasonCode());
        }
        try { budget.reconcile(task.reservation()); }
        catch (Exception failure) { state = State.UNAVAILABLE; reason = "QUOTA_ACCOUNTING_UNCERTAIN"; }
        synchronized (this) { queuedEntries--; queuedBytes -= task.payloadBytes(); queuedGrowth -= task.growthBytes(); }
        future.complete(receipt); // Durable success only after commit; never a claim that gameplay or delivery occurred.
    }
    private void release(Queued task) {
        budget.cancelUnstarted(task.reservation());
        queuedEntries--; queuedBytes -= task.payloadBytes(); queuedGrowth -= task.growthBytes();
    }
    private synchronized void gap(String stream, String code) {
        gap(stream, code, 1);
    }
    private synchronized void gap(String stream, String code, long count) {
        String key = stream + "|" + code;
        if (!gaps.containsKey(key) && gaps.size() >= 128) key = "other|COALESCED_GAPS";
        gaps.merge(key, count, (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b);
    }
    /** Game capture diagnostics only; no text, source identity or model-provided reason enters coverage. */
    public void captureGap(ProducerCapability capability, String code) {
        captureGap(capability, code, 1);
    }
    public void captureGap(ProducerCapability capability, String code, long count) {
        var registered = producer(capability);
        if (registered != null && code != null && code.matches("[A-Z0-9_]{1,64}") && count > 0) gap(registered.id(), code, count);
    }

    /** Game-internal diagnostic read; no AI/API code receives this unrestricted archive read port. */
    public CompletableFuture<Optional<String>> inspectMessage(UUID messageId, int maxUtf8Bytes) {
        if (maxUtf8Bytes <= 0 || maxUtf8Bytes > READ_PAGE_BYTES) return CompletableFuture.failedFuture(new IllegalArgumentException("READ_BUDGET"));
        return read(db -> {
            String hash; long bytes;
            try (var query = db.prepareStatement("SELECT body_hash,body_bytes FROM messages WHERE id=? AND dataset_id=?")) {
                query.setString(1, messageId.toString()); query.setString(2, manifest.datasetId().toString()); query.setQueryTimeout(1);
                try (var rows = query.executeQuery()) { if (!rows.next()) return Optional.empty(); hash = rows.getString(1); bytes = rows.getLong(2); }
            }
            if (bytes > maxUtf8Bytes) throw new IOException("READ_BUDGET");
            var text = new StringBuilder(); int index = 0;
            try (var query = db.prepareStatement("SELECT part_index,body FROM message_parts WHERE message_id=? ORDER BY part_index")) {
                query.setString(1, messageId.toString()); query.setQueryTimeout(1);
                try (var rows = query.executeQuery()) { while (rows.next()) { if (rows.getInt(1) != index++) throw new IOException("INCOMPLETE_MESSAGE"); text.append(rows.getString(2)); } }
            }
            if (byteSize(text.toString()) != bytes || !RecordingRecords.sha256(text.toString()).equals(hash)) throw new IOException("MESSAGE_HASH_MISMATCH");
            return Optional.of(text.toString());
        });
    }
    public CompletableFuture<Statistics> statistics() {
        return read(db -> { long[] counts = new long[6]; int i = 0;
            for (String table : List.of("messages", "deliveries", "work_items", "source_refs", "invalidations", "coverage_gaps"))
                try (var query = db.createStatement()) { query.setQueryTimeout(1); try (var row = query.executeQuery("SELECT count(*) FROM " + table)) { row.next(); counts[i++] = row.getLong(1); } }
            return new Statistics(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]);
        });
    }
    private <T> CompletableFuture<T> read(ReadOperation<T> operation) {
        var result = new CompletableFuture<T>();
        if (closing || !(state == State.READY || state == State.FULL)) return CompletableFuture.failedFuture(new IllegalStateException(reason));
        try { readers.execute(() -> {
            var properties = new Properties(); properties.setProperty("open_mode", "1");
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + database, properties)) {
                try (var statement = db.createStatement()) { statement.execute("PRAGMA query_only=ON"); statement.execute("PRAGMA busy_timeout=250"); statement.execute("PRAGMA temp_store=MEMORY"); statement.execute("PRAGMA cache_size=-8192"); }
                result.complete(operation.run(db));
            } catch (Exception failure) { result.completeExceptionally(new IOException("READ_UNAVAILABLE", failure)); }
        }); } catch (RejectedExecutionException full) { result.completeExceptionally(new IOException("READ_QUEUE_FULL")); }
        return result;
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (shutdown != null) return shutdown;
        closing = true; producers.clear(); projections.close(); embeddings.close(); readers.shutdown();
        shutdown = new CompletableFuture<Void>();
        if (state == State.OFF) { state = State.CLOSED; shutdown.complete(null); return shutdown; }
        var result = shutdown;
        try { writer.execute(() -> {
            Exception shutdownFailure = null;
            try {
                if (!readers.awaitTermination(3, TimeUnit.SECONDS)) throw new IOException("READERS_NOT_STOPPED");
                if (connection != null && (state == State.READY || state == State.FULL)) {
                    LegacyRecordingQuota.requireDrained(worldRoot, budget);
                    var reservation = budget.reserve(ManagedStoreRegistry.RECORDING, Math.addExact(databaseBytes, 1024 * 1024), true);
                    try {
                        checkpoint(); connection.setAutoCommit(false);
                        synchronized (this) { for (var gap : gaps.entrySet()) {
                            int separator = gap.getKey().indexOf('|');
                            update("INSERT INTO coverage_gaps VALUES(?,?,?,?,?) ON CONFLICT DO UPDATE SET count=count+excluded.count",
                                    manifest.datasetId(), gap.getKey().substring(0, separator), gap.getKey().substring(separator + 1), highWatermark, gap.getValue());
                        } }
                        update("UPDATE recording_meta SET clean_shutdown=1 WHERE singleton=1"); connection.commit(); connection.setAutoCommit(true); checkpoint();
                    } finally { budget.reconcile(reservation); }
                }
            } catch (Exception failure) { rollback(); possibleGap = true; reason = "UNCLEAN_SHUTDOWN"; shutdownFailure = failure; }
            finally {
                closeConnection(); releaseLock();
                if (quotaInstalled) try { LegacyRecordingQuota.uninstall(worldRoot, budget); quotaInstalled = false; }
                catch (IOException failure) { reason = "UNCLEAN_QUOTA_SHUTDOWN"; possibleGap = true; if (shutdownFailure == null) shutdownFailure = failure; }
                state = State.CLOSED; writer.shutdown();
                if (shutdownFailure == null) result.complete(null); else result.completeExceptionally(shutdownFailure);
            }
        }); } catch (RejectedExecutionException rejected) { state = State.CLOSED; result.complete(null); }
        return result;
    }
    private void checkpoint() throws SQLException, IOException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!row.next() || row.getInt(1) != 0) throw new IOException("CHECKPOINT_BUSY");
        }
    }
    private void rollback() {
        try { if (connection != null && !connection.getAutoCommit()) { connection.rollback(); connection.setAutoCommit(true); } }
        catch (SQLException rollbackFailure) {
            try {
                // SQLite INTERRUPT may already have rolled back the native write transaction while
                // JDBC still reports autoCommit=false. BEGIN can succeed only when no old transaction
                // remains. Never commit an uncertain old transaction just to reset the JDBC flag.
                sql("BEGIN IMMEDIATE");
                if(connection.getAutoCommit())sql("COMMIT");
                else connection.setAutoCommit(true); // commits only the proven new, empty recovery transaction
                if((state==State.READY||state==State.FULL)
                        &&scalar("SELECT high_watermark FROM recording_meta WHERE singleton=1")!=highWatermark)
                    throw new SQLException("ROLLBACK_HIGH_WATERMARK_UNCERTAIN");
            } catch (SQLException uncertain) { state = State.UNAVAILABLE; }
        }
    }
    private void closeConnection() { rollback(); try { if (connection != null) connection.close(); } catch (SQLException ignored) { } connection = null; }
    private void releaseLock() { try { if (lock != null) lock.release(); } catch (IOException ignored) { } try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { } }
    private void sql(String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    private long scalar(String sql) throws SQLException { try (var statement = connection.createStatement(); var row = statement.executeQuery(sql)) { if (!row.next()) throw new SQLException("MISSING_SCALAR"); return row.getLong(1); } }
    private PreparedStatement prepare(String sql, Object... values) throws SQLException {
        var statement = connection.prepareStatement(sql);
        for (int i = 0; i < values.length; i++) { Object value = values[i]; if (value instanceof Number) statement.setObject(i + 1, value); else statement.setString(i + 1, value == null ? null : value.toString()); }
        return statement;
    }
    private int update(String sql, Object... values) throws SQLException { try (var statement = prepare(sql, values)) { return statement.executeUpdate(); } }
    private static ThreadPoolExecutor executor(int count, int queue, String name) {
        return new ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queue), task -> { var thread = new Thread(task, name); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    }
    private static long byteSize(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static List<String> chunks(String body) {
        var parts = new ArrayList<String>();
        for (int start = 0; start < body.length();) {
            int end = Math.min(body.length(), start + 4096);
            if (end < body.length() && Character.isHighSurrogate(body.charAt(end - 1))) end--;
            parts.add(body.substring(start, end)); start = end;
        }
        return parts;
    }
    private static Object rawData(RawMessage r) {
        var fields = new ArrayList<Object>(List.of(r.messageId(), r.turnId().map(Object::toString).orElse(""), r.turnSequence(), r.speaker(), r.body(), r.occurredAt().toString(), r.kind(), r.occurrenceKey()));
        if (r.publicationContext() != null) fields.add(contextData(r.publicationContext())); // Preserve v2 no-context retry hashes.
        return fields;
    }
    private static Object contextData(PublicationContext context) {
        var transports = new TreeMap<String, Map<Integer, UUID>>(); context.transportMessageIds().forEach((key, ids) -> transports.put(key, new TreeMap<>(ids)));
        var data = new TreeMap<String,Object>(Map.of("runtimeEpoch", context.runtimeEpoch(), "membershipRevision", context.membershipRevision(), "recordingPolicy", context.recordingPolicy(),
                "participants", context.participants().stream().map(ActorRef::key).sorted().toList(), "fullAudience", context.fullAudience().stream().map(ActorRef::key).sorted().toList(),
                "participantNames", new TreeMap<>(context.participantNames()), "evidence", context.evidence(), "sourceMessages", context.sourceMessages().stream().map(Object::toString).sorted().toList(),
                "transportMessageIds", transports, "gameTimeStatus", context.gameTimeStatus()));
        if (!context.memoryMode().equals("UNKNOWN")) data.put("memoryMode", context.memoryMode());
        return data;
    }
    private static Object deliveryData(DeliveryReceipt r) { return List.of(r.receiptId(), r.recipient(), r.deliveryKind(), r.dispatchedAt().toString(), r.audienceRevision(), r.status(), r.view(), new TreeSet<>(r.deliveredParts())); }
    private static Object knowledgeData(KnowledgeReceipt r) { return List.of(r.receiptId(), r.godId(), r.acquisition(), r.permittedProjection(), r.permittedAudience().stream().map(ActorRef::key).sorted().toList(), r.policyRevision()); }
    private static String safeReason(Throwable failure, String fallback) {
        String value = failure.getMessage(); return value != null && value.matches("[A-Z0-9_]{1,64}") ? value : fallback;
    }
}
