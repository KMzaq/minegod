package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.CompoundTag;

/** Metadata/source lifecycle fixtures; real atomic-save confirmation is covered by RumorRecordingGameTests. */
public final class RumorRecordingStateTest {
    private static final Gson JSON = new Gson();
    private static final UUID PLAYER = new UUID(0, 1), BIRD = new UUID(1, 1);
    private static final String G = "mythictrpg:fortuna", H = "mythictrpg:demeter";
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void rejects(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); } catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        legacyAndPartition(); corruptionAndDowngrade(); boundsAndOverflow(); ownershipAndCanonicalization();
        System.out.println("RumorRecordingStateTest: " + checks + " checks passed");
    }
    private static RumorLedger ledger(RumorSavedData data) throws Exception {
        var field = RumorSavedData.class.getDeclaredField("ledger"); field.setAccessible(true); return (RumorLedger) field.get(data);
    }
    private static RumorRecordingState.State metadata(RumorSavedData data) {
        var nbt = data.save(new CompoundTag(), null);
        return RumorRecordingState.decode(nbt.getString(RumorRecordingState.NBT_KEY), data.snapshot());
    }
    private static boolean sameStoredFacts(CompoundTag before, CompoundTag after) {
        return before.getInt("dataVersion") == after.getInt("dataVersion")
                && Objects.equals(before.get(RumorRecordingState.NBT_KEY), after.get(RumorRecordingState.NBT_KEY))
                && JSON.fromJson(before.getString("state"), RumorLedger.Snapshot.class)
                    .equals(JSON.fromJson(after.getString("state"), RumorLedger.Snapshot.class));
    }
    private static UUID observe(RumorLedger ledger) {
        UUID root = UUID.randomUUID(); check(ledger.observe(root, PLAYER, BIRD, Set.of(PLAYER), "실제 관찰 내용", Set.of(G, H), Set.of(PLAYER)), "observed");
        return root;
    }
    private static void deliver(RumorLedger ledger, UUID root, String god) {
        check(ledger.deliver(ledger.pending().stream().filter(d -> d.rootId().equals(root) && d.godId().equals(god)).findFirst().orElseThrow()), "actual delivery");
    }
    private static void legacyAndPartition() throws Exception {
        var data = new RumorSavedData(); var ledger = ledger(data); ledger.bindCourier(PLAYER, BIRD);
        UUID old = observe(ledger); ledger.publish(old, "기존 소문", ""); deliver(ledger, old, G);
        CompoundTag original = data.save(new CompoundTag(), null);
        check(!original.contains(RumorRecordingState.NBT_KEY), "OFF legacy state has no new metadata");
        var legacyLoaded = RumorSavedData.load(original, null); var legacyResaved = legacyLoaded.save(new CompoundTag(), null);
        check(legacyResaved.getInt("dataVersion") == original.getInt("dataVersion")
                && !legacyResaved.contains(RumorRecordingState.NBT_KEY)
                && JSON.fromJson(legacyResaved.getString("state"), RumorLedger.Snapshot.class)
                    .equals(JSON.fromJson(original.getString("state"), RumorLedger.Snapshot.class)), "legacy schema and facts preserved");
        data.recordingEnabled(true);
        check(metadata(data).cursor() == 0 && metadata(data).roots().isEmpty(), "enabled old roots begin as origin zero");
        check(data.recordingSnapshot().isEmpty(), "enable and serialization are not durable acknowledgment");
        check(data.recordingStatus() == RumorSavedData.RecordingStatus.WAITING_COMMIT, "initial commit wait is diagnosable");
        var lineage = metadata(data).lineage();
        deliver(ledger, old, H);
        check(metadata(data).roots().isEmpty() && metadata(data).receipts().isEmpty(), "late legacy delivery cannot import old root");
        UUID first = observe(ledger); var origin = metadata(data).roots().get(first).bornCursor();
        check(origin > 0 && metadata(data).roots().get(first).claimCursor() == 0, "new evidence cursor before publication");
        ledger.publish(first, "새 소문", ""); long claimCursor = metadata(data).roots().get(first).claimCursor();
        deliver(ledger, first, G); long receiptCursor = metadata(data).receipts().getFirst().acquiredCursor();
        check(origin < claimCursor && claimCursor < receiptCursor, "origin, claim and actual receipt cursors are separate");
        var saved = data.save(new CompoundTag(), null); var reloaded = RumorSavedData.load(saved, null);
        check(reloaded.recordingSnapshot().isPresent(), "valid disk load confirms exact snapshot");
        var confirmed = reloaded.recordingSnapshot().orElseThrow();
        check(confirmed.metadata().lineage().equals(lineage) && confirmed.metadata().cursor() == metadata(data).cursor(), "persistent cursor and lineage survive restart");
        check(confirmed.root(first).orElseThrow().bornCursor() == origin && confirmed.receipt(first, G, 1).isPresent(), "receipt lookup matches exact revision");
        check(confirmed.root(old).isEmpty() && confirmed.receipt(first, H, 1).isEmpty(), "legacy root and merely pending god remain unknown");
        var afterRestart = ledger(reloaded); reloaded.recordingEnabled(false);
        deliver(afterRestart, first, H); UUID off = observe(afterRestart); afterRestart.publish(off, "OFF 동안 소문", ""); deliver(afterRestart, off, G);
        check(!metadata(reloaded).roots().containsKey(off) && metadata(reloaded).receipts().size() == 1, "OFF-born roots and OFF-acquired receipts stay origin zero");
        check(reloaded.recordingSnapshot().orElseThrow().equals(confirmed), "live OFF changes do not mutate confirmed checkpoint");
        reloaded.recordingEnabled(true); deliver(afterRestart, off, H);
        check(!metadata(reloaded).roots().containsKey(off) && metadata(reloaded).receipts().size() == 1, "re-enable never relabels OFF data");
        reloaded.recordingEnabled(false); afterRestart.revoke(first);
        check(metadata(reloaded).roots().get(first).claimRevision() == 2
                && metadata(reloaded).roots().get(first).claimCursor() > receiptCursor, "OFF revocation of tracked source is persisted");
        var revoked = RumorSavedData.load(reloaded.save(new CompoundTag(), null), null).recordingSnapshot().orElseThrow();
        check(revoked.snapshot().claims().stream().anyMatch(c -> c.rootId().equals(first) && c.revoked()), "revoked snapshot survives restart");
        long before = metadata(reloaded).cursor(); afterRestart.courierDied(BIRD);
        check(metadata(reloaded).cursor() > before && metadata(reloaded).receipts().size() == 1, "courier death does not revoke previous receipt");
        check(!afterRestart.revoke(first) && metadata(reloaded).cursor() == before + 1, "failed mutation does not advance cursor");
    }
    private static void corruptionAndDowngrade() throws Exception {
        var data = new RumorSavedData(); var ledger = ledger(data); ledger.bindCourier(PLAYER, BIRD); data.recordingEnabled(true);
        UUID root = observe(ledger); ledger.publish(root, "허용된 소문", ""); deliver(ledger, root, G);
        CompoundTag tag = data.save(new CompoundTag(), null);
        for (String bad : List.of("{", tag.getString(RumorRecordingState.NBT_KEY).replace("\"version\":1", "\"version\":99"))) {
            var damaged = tag.copy(); damaged.putString(RumorRecordingState.NBT_KEY, bad);
            var loaded = RumorSavedData.load(damaged, null); loaded.recordingEnabled(true);
            check(loaded.ready() && loaded.snapshot().evidence().size() == 1 && loaded.recordingSnapshot().isEmpty(), "bad metadata quarantines recording only");
            check(sameStoredFacts(loaded.save(new CompoundTag(), null), damaged), "corrupt original metadata and source facts are preserved without reinitializing lineage");
            check(loaded.recordingStatus() == RumorSavedData.RecordingStatus.UNAVAILABLE_METADATA, "metadata failure is content-free diagnosable");
        }
        var changed = tag.copy(); changed.putString("state", tag.getString("state").replace("허용된 소문", "변경된 소문"));
        var loaded = RumorSavedData.load(changed, null);
        check(loaded.ready() && loaded.recordingSnapshot().isEmpty(), "snapshot hash mismatch fails only recording closed");
        check(sameStoredFacts(loaded.save(new CompoundTag(), null), changed), "game snapshot and original mismatched metadata retained");
        var downgrade = tag.copy(); downgrade.remove(RumorRecordingState.NBT_KEY);
        var missing = RumorSavedData.load(downgrade, null); missing.recordingEnabled(true);
        check(!metadata(missing).lineage().equals(metadata(data).lineage()) && metadata(missing).roots().isEmpty(),
                "missing metadata generates new baseline lineage for DB reset rejection, not old import");
        var wrongType = tag.copy(); wrongType.putInt(RumorRecordingState.NBT_KEY, 7);
        var wrong = RumorSavedData.load(wrongType, null);
        check(wrong.ready() && wrong.recordingSnapshot().isEmpty() && sameStoredFacts(wrong.save(new CompoundTag(), null), wrongType), "non-string original metadata retained");
    }
    private static void boundsAndOverflow() {
        check(RumorRecordingState.supported("{}", "x".repeat(RumorRecordingState.MAX_METADATA_BYTES)), "metadata boundary accepted");
        check(!RumorRecordingState.supported("{}", "x".repeat(RumorRecordingState.MAX_METADATA_BYTES + 1)), "metadata bound enforced");
        check(!RumorRecordingState.supported("{}", "한".repeat(20_000)), "metadata modified UTF-8, not character count");
        check(!RumorRecordingState.supported("한".repeat(22_000), "{}"), "legacy game NBT string bound retained");
        RumorLedger ledger = new RumorLedger(); var initial = RumorRecordingState.begin(ledger.snapshot());
        var exhausted = new RumorRecordingState.State(1, initial.worldId(), initial.lineage(), Long.MAX_VALUE,
                RumorRecordingState.Status.ACTIVE, Map.of(), List.of(), initial.stateHash());
        var before = ledger.snapshot(); ledger.bindCourier(PLAYER, BIRD);
        var failed = RumorRecordingState.advance(exhausted, before, ledger.snapshot(), true);
        check(failed.status() == RumorRecordingState.Status.UNAVAILABLE_CURSOR && failed.cursor() == Long.MAX_VALUE, "overflow fails recording closed without wrapping");
        var snapshot = ledger.snapshot(); var unavailable = RumorRecordingState.unavailable(initial, RumorRecordingState.Status.UNAVAILABLE_LIMIT, snapshot);
        check(RumorRecordingState.advance(unavailable, snapshot, snapshot, true).status() == RumorRecordingState.Status.UNAVAILABLE_LIMIT,
                "unavailable lineage cannot silently reactivate");
        rejects(() -> new RumorRecordingState.DurableView(unavailable, snapshot), "unavailable cannot publish durable evidence");
        var state = RumorRecordingState.begin(snapshot); var roots = new HashMap<UUID, RumorRecordingState.RootStamp>();
        roots.put(UUID.randomUUID(), new RumorRecordingState.RootStamp(UUID.randomUUID(), 1, 0, 0));
        rejects(() -> new RumorRecordingState.State(1, state.worldId(), state.lineage(), 1,
                RumorRecordingState.Status.ACTIVE, roots, List.of(), state.stateHash()), "root key mismatch rejected");
        boolean reachedMetadataLimit = false;
        Set<String> recipients = new HashSet<>();
        for (int i = 0; i < 64; i++) recipients.add("test:g" + i);
        for (int count = 1; count <= 12 && !reachedMetadataLimit; count++) {
            var evidence = new ArrayList<RumorLedger.Evidence>(); var claims = new ArrayList<RumorLedger.Claim>();
            var receipts = new ArrayList<RumorLedger.Receipt>();
            for (int i = 0; i < count; i++) {
                UUID id = new UUID(99, i);
                evidence.add(new RumorLedger.Evidence(id, PLAYER, BIRD, ledger.courier(PLAYER).epoch(), "x", recipients, Set.of(PLAYER)));
                claims.add(new RumorLedger.Claim(id, 1, "x", "", false));
                for (String god : recipients) receipts.add(new RumorLedger.Receipt(id, 1, god));
            }
            var many = new RumorLedger.Snapshot(2, snapshot.worldId(), snapshot.couriers(), evidence, claims, List.of(), receipts);
            if (!com.sande.mythictrpg.recording.server.LegacyRecordingQuota.SavedDataGate.supportedJson(JSON.toJson(many))) break;
            var bounded = RumorRecordingState.advance(state, snapshot, many, true);
            if (bounded.status() == RumorRecordingState.Status.UNAVAILABLE_LIMIT) {
                reachedMetadataLimit = true;
                check(bounded.roots().isEmpty() && bounded.receipts().isEmpty() && bounded.lineage().equals(state.lineage()),
                        "metadata-only capacity fault keeps lineage but exposes no partial provenance");
            }
        }
        check(reachedMetadataLimit, "fixture reaches metadata bound before the independent game NBT bound");
    }
    private static void ownershipAndCanonicalization() throws Exception {
        var data = new RumorSavedData(); var ledger = ledger(data); ledger.bindCourier(PLAYER, BIRD); data.recordingEnabled(true);
        UUID root = observe(ledger); ledger.publish(root, "순서와 무관한 원본", ""); deliver(ledger, root, H); deliver(ledger, root, G);
        var state = metadata(data); var s = data.snapshot();
        var reordered = new RumorLedger.Snapshot(1, s.worldId(), s.couriers().reversed(), s.evidence().reversed(),
                s.claims().reversed(), s.pending().reversed(), s.receipts().reversed());
        check(RumorRecordingState.fingerprint(s).equals(RumorRecordingState.fingerprint(reordered)), "canonical binding ignores set/list order and supported v1/v2 wrapper");
        check(new RumorRecordingState.DurableView(state, reordered).metadata().equals(state), "canonical restart can validate identical facts");
        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread thread = new Thread(() -> { try { data.recordingEnabled(false); } catch (Throwable ex) { caught.set(ex); } });
        thread.start(); thread.join(); check(caught.get() instanceof IllegalStateException, "archive policy mutation game-thread boundary");
        var loaded = RumorSavedData.load(data.save(new CompoundTag(), null), null);
        AtomicReference<RumorRecordingState.DurableView> workerView = new AtomicReference<>();
        Thread reader = new Thread(() -> workerView.set(loaded.recordingSnapshot().orElseThrow())); reader.start(); reader.join();
        check(workerView.get().equals(loaded.recordingSnapshot().orElseThrow()), "shutdown worker reads only immutable confirmed checkpoint");
        var json = JSON.toJson(state); check(RumorRecordingState.decode(json, s).equals(state), "metadata JSON roundtrip");
        var mutable = new ArrayList<>(s.receipts());
        var view = new RumorRecordingState.DurableView(state, new RumorLedger.Snapshot(s.version(), s.worldId(), s.couriers(), s.evidence(), s.claims(), s.pending(), mutable));
        mutable.clear(); check(view.snapshot().receipts().size() == 2, "confirmed source snapshot list defensively copied");
    }
}
