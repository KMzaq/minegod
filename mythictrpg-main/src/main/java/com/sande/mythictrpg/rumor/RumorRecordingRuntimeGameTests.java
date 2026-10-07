package com.sande.mythictrpg.rumor;

import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.recording.server.RecordingRuntime;
import com.sande.mythictrpg.recording.server.WorldRecordingService;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.*;

/** Actual runtime tick -> frozen SavedData commit -> source registration/capture. No entity observer or LLM is invented. */
@GameTestHolder("mythictrpg_recording_rumor")
@PrefixGameTestTemplate(false)
public final class RumorRecordingRuntimeGameTests {
    private RumorRecordingRuntimeGameTests() { }
    @GameTest(templateNamespace = "mythictrpg_recording_rumor", template = "empty", timeoutTicks = 30_000)
    public static void runtimeConfirmsLateReceiptsAndRevocation(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        helper.onEachTick(() -> {
            if (fixture.done) return;
            LockSupport.parkNanos(1_000_000); // GameTest's unpaced ticks must leave time for the actual I/O workers.
            try { fixture.tick(); }
            catch (Exception | AssertionError failure) { fixture.close(); helper.fail("Rumor runtime capture: " + failure); }
        });
    }
    private record SqlSnapshot(boolean registered, long cutoff, long latest, int sources, int revokedSources,
            int sourceInvalidations, Set<String> receivers, Set<Long> origins, boolean noInventedAssessment) { }
    private static final class Fixture {
        static final String OWNER = "rumor-saved-data-v1", G = "mythictrpg:demeter", H = "mythictrpg:fortuna";
        final GameTestHelper helper;
        final MinecraftServer server;
        final UUID player = UUID.randomUUID(), bird = UUID.randomUUID(), root = UUID.randomUUID();
        final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "rumor-runtime-fixture-read"); thread.setDaemon(true); return thread;
        });
        RumorSavedData data;
        Path database;
        UUID dataset;
        CompletableFuture<SqlSnapshot> pending;
        long phaseStarted = System.nanoTime(), nextPoll, firstAcquired, secondAcquired;
        int phase;
        boolean done;
        Fixture(GameTestHelper helper) { this.helper = helper; server = helper.getLevel().getServer(); }
        void tick() throws Exception {
            require(System.nanoTime() - phaseStarted < TimeUnit.SECONDS.toNanos(12), "Timed out phase " + phase);
            if (phase == 0) {
                String world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString().replace('\\', '/');
                require(world.contains("/build/"), "Build fixture world required");
                require(MemoryFoundationSettings.mode() == MemoryFoundationSettings.Mode.RUMOR_TEST, "RUMOR_TEST fixture config required");
                var store = RecordingRuntime.current(server).orElse(null);
                if (store == null || store.health().state() != WorldRecordingService.State.READY) return;
                data = RumorSavedData.get(server);
                var baseline = data.recordingSnapshot().orElse(null);
                if (baseline == null) return;
                require(baseline.metadata().cursor() == 0 && baseline.metadata().roots().isEmpty(), "Fresh recording baseline required");
                dataset = store.datasetId().orElseThrow();
                database = server.getWorldPath(LevelResource.ROOT).resolve("mythictrpg-recording-v2")
                        .resolve(dataset.toString()).resolve("recording.sqlite");
                pending = inspect(); nextPhase(1); return;
            }
            if (!pending.isDone() || System.nanoTime() < nextPoll) return;
            SqlSnapshot sql = pending.join();
            if (phase == 1) {
                if (!sql.registered()) { pending = inspect(); return; }
                require(sql.cutoff() == 0 && sql.latest() == 0, "Runtime did not register the confirmed baseline");
                var before = data.recordingSnapshot().orElseThrow();
                // Synthetic, explicitly game-owned witness only for this plumbing fixture. No NPC or observation is replayed.
                String excerpt = "runtime-fixture witnessed event";
                CourierProof proof = new CourierProof(UUID.randomUUID(), 1, "test:runtime_capture", CourierSettings.hash("fixture-policy"),
                        "test:runtime_event", CourierSettings.Source.GAME_EVENT, System.currentTimeMillis(), server.getTickCount(),
                        "minecraft:overworld", 0, 64, 0, CourierSettings.hash(excerpt));
                data.access(server, ledger -> {
                    require(ledger.bindCourier(player, bird), "Fixture courier binding rejected");
                    require(ledger.observe(root, player, bird, Set.of(player), excerpt, Set.of(G, H), Set.of(player), proof), "Fixture proven source rejected");
                    require(ledger.publish(root, "검증용으로 전달된 소문", ""), "Fixture publication rejected");
                    require(ledger.deliver(ledger.pending().stream().filter(d -> d.rootId().equals(root) && d.godId().equals(G)).findFirst().orElseThrow()), "First recipient not delivered");
                    return true;
                });
                require(data.recordingSnapshot().orElseThrow().equals(before), "Live operation fabricated a disk acknowledgment");
                pending = inspect(); nextPhase(2);
            } else if (phase == 2) {
                if (sql.receivers().isEmpty()) { pending = inspect(); return; }
                require(sql.sources() == 1 && sql.receivers().equals(Set.of(G)), "Pending second God gained knowledge before delivery");
                require(sql.sourceInvalidations() == 0 && sql.revokedSources() == 0 && sql.noInventedAssessment(), "Initial source state or assessment fabricated");
                var committed = data.recordingSnapshot().orElseThrow();
                firstAcquired = committed.receipt(root, G, 1).orElseThrow().acquiredCursor();
                require(committed.root(root).orElseThrow().bornCursor() > sql.cutoff() && sql.origins().equals(Set.of(firstAcquired)), "SQL receipt origin does not match actual committed grant");
                require(committed.receipt(root, H, 1).isEmpty(), "Unheard recipient got recording metadata");
                data.access(server, ledger -> {
                    require(ledger.deliver(ledger.pending().stream().filter(d -> d.rootId().equals(root) && d.godId().equals(H)).findFirst().orElseThrow()), "Late recipient not delivered");
                    return true;
                });
                require(data.recordingSnapshot().orElseThrow().equals(committed), "Late live delivery prematurely acknowledged");
                pending = inspect(); nextPhase(3);
            } else if (phase == 3) {
                if (sql.receivers().size() < 2) { pending = inspect(); return; }
                require(sql.sources() == 1 && sql.receivers().equals(Set.of(G, H)) && sql.noInventedAssessment(), "Late grant duplicated source or inherited another God's belief");
                var committed = data.recordingSnapshot().orElseThrow();
                secondAcquired = committed.receipt(root, H, 1).orElseThrow().acquiredCursor();
                require(secondAcquired > firstAcquired && sql.origins().equals(Set.of(firstAcquired, secondAcquired)), "Late receipt lost its independent acquisition cursor");
                data.access(server, ledger -> { require(ledger.revoke(root), "Game revoke rejected"); return true; });
                require(data.recordingSnapshot().orElseThrow().equals(committed), "Live revocation was mistaken for a committed update");
                pending = inspect(); nextPhase(4);
            } else {
                if (sql.sourceInvalidations() == 0) { pending = inspect(); return; }
                require(sql.sources() == 1 && sql.revokedSources() == 1 && sql.sourceInvalidations() == 1,
                        "Runtime failed to mirror exactly one authoritative source revocation");
                require(sql.receivers().equals(Set.of(G, H)), "Revocation deleted historical actual receipt facts");
                var committed = data.recordingSnapshot().orElseThrow();
                require(committed.root(root).orElseThrow().claimRevision() == 2 && sql.latest() == committed.metadata().cursor(),
                        "Source revocation did not follow exact durable cursor registration");
                close(); helper.succeed();
            }
        }
        void nextPhase(int next) { phase = next; phaseStarted = System.nanoTime(); }
        /** This unrestricted diagnostic exists only in the isolated GameTest, never in an AI read port. */
        CompletableFuture<SqlSnapshot> inspect() {
            final Path file = database; final UUID datasetId = dataset;
            nextPoll = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20);
            return CompletableFuture.supplyAsync(() -> {
                Properties properties = new Properties(); properties.setProperty("open_mode", "1");
                try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + file, properties)) {
                    try (var settings = db.createStatement()) { settings.execute("PRAGMA query_only=ON"); settings.execute("PRAGMA busy_timeout=250"); }
                    db.setAutoCommit(false);
                    boolean registered = false; long cutoff = -1, latest = -1;
                    try (var query = db.prepareStatement("SELECT cutoff,latest_confirmed FROM source_cutovers WHERE dataset_id=? AND owner=?")) {
                        query.setQueryTimeout(1); query.setString(1, datasetId.toString()); query.setString(2, OWNER);
                        try (var rows = query.executeQuery()) { if (rows.next()) { registered = true; cutoff = rows.getLong(1); latest = rows.getLong(2); } }
                    }
                    int sources = 0, revoked = 0, invalidations = 0;
                    try (var query = db.prepareStatement("SELECT revoked FROM source_refs WHERE dataset_id=? AND owner=? AND source_id=?")) {
                        bind(query, datasetId); try (var rows = query.executeQuery()) { while (rows.next()) { sources++; revoked += rows.getInt(1); } }
                    }
                    try (var query = db.prepareStatement("SELECT count(*) FROM invalidations WHERE dataset_id=? AND owner=? AND source_id=?")) {
                        bind(query, datasetId); try (var rows = query.executeQuery()) { rows.next(); invalidations = rows.getInt(1); }
                    }
                    Set<String> gods = new HashSet<>(); Set<Long> origins = new HashSet<>(); boolean noInventedAssessment = true;
                    try (var query = db.prepareStatement("SELECT k.god_id,k.projection,o.acquired_cursor FROM knowledge_receipts k JOIN source_refs s ON s.id=k.source_ref JOIN knowledge_origins o ON o.receipt_id=k.id WHERE s.dataset_id=? AND s.owner=? AND s.source_id=?")) {
                        bind(query, datasetId); try (var rows = query.executeQuery()) { while (rows.next()) {
                            require(gods.add(rows.getString(1)), "Duplicate actual God receipt"); origins.add(rows.getLong(3));
                            String projection = rows.getString(2);
                            noInventedAssessment &= projection.contains("CURRENT_GAME_LOOKUP_REQUIRED") && !projection.contains("ACCEPTED") && !projection.contains("UNASSESSED");
                        } }
                    }
                    db.rollback(); return new SqlSnapshot(registered, cutoff, latest, sources, revoked, invalidations,
                            Set.copyOf(gods), Set.copyOf(origins), noInventedAssessment);
                } catch (SQLException failure) { throw new CompletionException("Fixture read-only SQL failed", failure); }
            }, reader).orTimeout(2, TimeUnit.SECONDS);
        }
        void bind(PreparedStatement statement, UUID datasetId) throws SQLException {
            statement.setQueryTimeout(1); statement.setString(1, datasetId.toString()); statement.setString(2, OWNER); statement.setString(3, root.toString());
        }
        void close() { done = true; reader.shutdownNow(); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
