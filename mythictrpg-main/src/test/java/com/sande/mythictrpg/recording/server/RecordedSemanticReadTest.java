package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real SQLite and game-issued native work/read capabilities, with deterministic vectors and no model calls. */
public final class RecordedSemanticReadTest {
    private static int checks;
    private static final String GOD = "test:a", OTHER = "test:b";
    private static final ActorRef PLAYER = new ActorRef(ActorKind.PLAYER, UUID.randomUUID().toString());
    private static final ActorRef STRANGER = new ActorRef(ActorKind.PLAYER, UUID.randomUUID().toString());
    private static final Instant WHEN = Instant.parse("2026-09-01T12:00:00Z");
    private static final EmbeddingRecords.ModelSpace SPACE = space("fixture-embed", "a".repeat(64), 3);
    private static final float[] SAME = {1, 0, 0}, OTHER_DIRECTION = {0, 1, 0};
    private static final String QUERY = "abandonedpromise";
    private static final RecordingSettings SETTINGS = new RecordingSettings(RecordingSettings.Mode.SHADOW, 128_000_000, 2_000_000, .90, .95);
    private static final WorldRecordingService.CutoverBoundary BOUNDARY = new WorldRecordingService.CutoverBoundary("semantic-read-test", Map.of());
    private record Fixture(Path root, UUID world, WorldRecordingService store, ProducerCapability producer, UUID conversation,
                           EmbeddingWorkerCapability worker) implements AutoCloseable {
        @Override public void close() throws Exception { await(store.closeAsync()); }
    }

    public static void main(String[] args) throws Exception {
        Path parent = Path.of(args.length == 0 ? "build/recorded-semantic-read-test" : args[0]).toAbsolutePath().normalize();
        if (!parent.toString().replace('\\', '/').contains("/build/")) throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent); Path root = Files.createTempDirectory(parent, "semantics-");
        independentLane(root.resolve("independent")); oldLaterPage(root.resolve("old-page"));
        timeRange(root.resolve("time")); modelSpace(root.resolve("model")); audience(root.resolve("audience"));
        prefixAndBudget(root.resolve("prefix")); frozenWatermark(root.resolve("watermark"));
        withdrawals(root.resolve("receipt"), true); withdrawals(root.resolve("source"), false);
        cursorsAndSessions(root.resolve("cursors")); runtimeGate(root.resolve("gate"));
        corruptRows(root.resolve("corruption")); reopen(root.resolve("reopen")); surrogatePrefix(root.resolve("surrogate"));
        System.out.println("RecordedSemanticReadTest: " + checks + " checks passed; fixtures=" + root);
    }

    private static void independentLane(Path root) throws Exception {
        try (var f = fixture(root)) {
            String original = "나는 다시는 이곳에 돌아오지 않겠어.";
            UUID id = capture(f, original, WHEN); index(f, SAME);
            check(await(f.store().pumpLexicalIndex()).indexed() == 1, "fixture also has a real FTS row");
            var lexical = session(f, GOD, Set.of(PLAYER), false);
            var absent = await(lexical.query(query(QUERY), Optional.empty(), budget(8, 8192)));
            check(absent.entries().isEmpty(), "query has zero lexical overlap with the source");
            var semantic = session(f, GOD, Set.of(PLAYER), false); var page = read(semantic, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192);
            check(page.entries().size() == 1 && semantic.current(page), "independent vector lane retrieves a nonlexical source: " + page.status());
            var entry = page.entries().getFirst();
            check(entry.messageId().equals(id) && entry.speaker().equals(PLAYER) && entry.occurredAt().equals(WHEN)
                    && entry.text().equals(original) && Math.abs(entry.similarity() - 1) < .000001,
                    "semantic result retains original actor/time/text and exact cosine rather than a fabricated summary");
            check(entry.coveredCharacters() == original.length() && entry.totalCharacters() == original.length(), "complete source coverage is explicit");
            check(await(f.store().inspectMessage(id, 4096)).orElseThrow().equals(original), "optional vector search never rewrites RAW");
        }
    }

    private static void oldLaterPage(Path root) throws Exception {
        try (var f = fixture(root)) {
            UUID old = capture(f, "오래전에 남긴 특별한 약속.", WHEN); index(f, SAME);
            for (int i = 0; i < 130; i++) { capture(f, "recent unrelated " + i, WHEN.plusSeconds(i + 1)); index(f, OTHER_DIRECTION); }
            var s = session(f, GOD, Set.of(PLAYER), false);
            var first = read(s, query(QUERY), vector(QUERY, SAME), Optional.empty(), 1, 8192);
            check(first.entries().size() == 1 && !first.entries().getFirst().messageId().equals(old), "first ranked window is bounded rather than a full-history scan");
            var later = first;
            for (int i = 0; i < 7 && later.entries().stream().noneMatch(e -> e.messageId().equals(old)) && later.next().isPresent(); i++)
                later = read(s, query(QUERY), vector(QUERY, SAME), later.next(), 1, 8192);
            check(later.entries().size() == 1 && later.entries().getFirst().messageId().equals(old)
                    && later.entries().getFirst().similarity() > first.entries().getFirst().similarity(),
                    "chronological continuation can find an older stronger semantic hit without FTS overlap");
            check(first.status() == MemoryReadSession.Status.PARTIAL && later.status() == MemoryReadSession.Status.PARTIAL,
                    "bounded window ranking never claims global semantic top-K completeness");
        }
    }

    private static void timeRange(Path root) throws Exception {
        try (var f = fixture(root)) {
            capture(f, "before", WHEN.minusSeconds(1)); index(f, SAME);
            UUID exact = capture(f, "included", WHEN); index(f, SAME);
            capture(f, "excluded end", WHEN.plusSeconds(1)); index(f, SAME);
            var q = new MemoryReadSession.Query(QUERY, Optional.of(WHEN), Optional.of(WHEN.plusSeconds(1)));
            var s = session(f, GOD, Set.of(PLAYER), false); var page = read(s, q, vector(QUERY, SAME), Optional.empty(), 8, 8192);
            check(page.entries().size() == 1 && page.entries().getFirst().messageId().equals(exact), "semantic time bounds are inclusive start and exclusive end");
        }
    }

    private static void modelSpace(Path root) throws Exception {
        try (var f = fixture(root)) {
            capture(f, "정확한 모델 공간에서만 비교한다.", WHEN); index(f, SAME);
            for (var alternate : List.of(space("other-model", "a".repeat(64), 3), space("fixture-embed", "b".repeat(64), 3),
                    space("fixture-embed", "a".repeat(64), 2))) {
                float[] value = new float[alternate.dimensions()]; value[0] = 1;
                var s = session(f, GOD, Set.of(PLAYER), false);
                var wrong = new EmbeddingRecords.QueryVector(alternate, sha256(QUERY), value);
                check(read(s, query(QUERY), wrong, Optional.empty(), 8, 8192).entries().isEmpty(), "model name, pinned digest and dimensions define separate vector spaces");
            }
            var mismatch = session(f, GOD, Set.of(PLAYER), false);
            check(read(mismatch, query(QUERY), vector("different query", SAME), Optional.empty(), 8, 8192).entries().isEmpty(), "query hash must match exact supplied text");
            expectIllegal(() -> new EmbeddingRecords.QueryVector(SPACE, sha256(QUERY), new float[]{1, 0}), "query dimension mismatch rejected before search");
            expectIllegal(() -> new EmbeddingRecords.QueryVector(SPACE, sha256(QUERY), new float[]{Float.NaN, 0, 1}), "query NaN rejected before search");
            expectIllegal(() -> new EmbeddingRecords.QueryVector(SPACE, sha256(QUERY), new float[]{Float.POSITIVE_INFINITY, 0, 1}), "query infinity rejected before search");
            expectIllegal(() -> new EmbeddingRecords.QueryVector(SPACE, sha256(QUERY), new float[]{0, 0, 0}), "query zero norm rejected before search");
            float[] mutable = SAME.clone(); var protectedVector = vector(QUERY, mutable); mutable[0] = 0;
            float[] accessor = protectedVector.values(); accessor[0] = 0;
            check(protectedVector.values()[0] == 1, "query vectors defensively copy caller and accessor arrays");
        }
    }

    private static void audience(Path root) throws Exception {
        try (var f = fixture(root)) {
            capture(f, "둘만 아는 비밀 이야기.", WHEN); index(f, SAME);
            var owner = session(f, GOD, Set.of(PLAYER), false);
            check(read(owner, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192).entries().size() == 1, "original God and private audience can read");
            for (var denied : List.of(session(f, OTHER, Set.of(PLAYER), false), session(f, GOD, Set.of(PLAYER, STRANGER), false),
                    session(f, GOD, Set.of(PLAYER), true), session(f, GOD, Set.of(STRANGER), false))) {
                var result = read(denied, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192);
                check(result.entries().isEmpty(), "unknown God, extra LISTENER, public destination and another session player never receive private vectors' original text");
            }
            UUID publicMessage = capture(f, "공개적으로 남긴 말도 듣지 않은 신의 기억은 아니다.", WHEN.plusSeconds(1), true); index(f, SAME);
            var publicKnown = session(f, GOD, Set.of(STRANGER), true);
            var publicPage = read(publicKnown, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192);
            check(publicPage.entries().size() == 1 && publicPage.entries().getFirst().messageId().equals(publicMessage),
                    "public original can be disclosed to a new player while private original remains hidden");
            var publicUnknown = session(f, OTHER, Set.of(STRANGER), true);
            check(read(publicUnknown, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192).entries().isEmpty(),
                    "public archive presence does not invent knowledge for a God who never heard it");
        }
    }

    private static void prefixAndBudget(Path root) throws Exception {
        try (var f = fixture(root)) {
            String body = "한".repeat(1600) + " index_never_saw_this_tail";
            capture(f, body, WHEN); var work = index(f, SAME);
            check(work.excerpt() && work.coveredCharacters() == 1600 && work.totalCharacters() == body.length(), "native embedding work contains only its declared original prefix");
            var s = session(f, GOD, Set.of(PLAYER), false); var full = read(s, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192);
            check(full.entries().size() == 1 && full.entries().getFirst().text().equals(body.substring(0, 1600))
                    && full.entries().getFirst().coveredCharacters() == 1600 && full.entries().getFirst().totalCharacters() == body.length(),
                    "search returns exact embedded prefix with truthful full length, never unembedded tail");
            int bytes = RecordedSemanticSearch.wireByteSize(full.entries().getFirst());
            check(bytes > full.entries().getFirst().text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length && bytes <= 8192,
                    "semantic byte budget includes actor/time/similarity/coverage metadata and handles Instant");
            var shortBudget = session(f, GOD, Set.of(PLAYER), false);
            check(read(shortBudget, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, bytes - 1).entries().isEmpty(), "one complete prefix card is not silently trimmed to fit the budget");
            var exactBudget = session(f, GOD, Set.of(PLAYER), false);
            check(read(exactBudget, query(QUERY), vector(QUERY, SAME), Optional.empty(), 1, bytes).entries().size() == 1, "exact complete wire-byte budget admits the card");
            capture(f, body, WHEN.plusSeconds(1)); index(f, SAME);
            var aggregate = session(f, GOD, Set.of(PLAYER), false);
            var bounded = read(aggregate, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, bytes * 2 - 1);
            check(bounded.entries().size() == 1 && bounded.entries().stream().mapToInt(RecordedSemanticSearch::wireByteSize).sum() <= bytes * 2 - 1,
                    "aggregate full-card byte budget prevents two independently fitting cards from overflowing one page");
            var rowLimited = session(f, GOD, Set.of(PLAYER), false);
            check(read(rowLimited, query(QUERY), vector(QUERY, SAME), Optional.empty(), 1, bytes * 2).entries().size() == 1, "row budget applies independently of sufficient aggregate byte capacity");
        }
    }

    private static void frozenWatermark(Path root) throws Exception {
        try (var f = fixture(root)) {
            capture(f, "늦게 색인되는 원문.", WHEN);
            var old = session(f, GOD, Set.of(PLAYER), false);
            var absent = read(old, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192);
            check(absent.entries().isEmpty() && old.current(absent), "pre-index read has no candidate");
            index(f, SAME);
            check(old.current(absent), "optional embedding writes do not revoke unchanged raw authority");
            check(read(old, query(QUERY), vector(QUERY, SAME), absent.next(), 8, 8192).entries().isEmpty(), "late index cannot enter an already frozen session watermark");
            var fresh = session(f, GOD, Set.of(PLAYER), false);
            check(read(fresh, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192).entries().size() == 1, "new session can see newly committed vector coverage");
        }
    }

    private static void withdrawals(Path root, boolean receiptOnly) throws Exception {
        try (var f = fixture(root)) {
            capture(f, "나중에 철회할 기억의 원문.", WHEN); var work = index(f, SAME);
            var s = session(f, GOD, Set.of(PLAYER), false); var issued = read(s, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192);
            check(issued.entries().size() == 1 && s.current(issued), "vector result begins with current native receipt authority");
            var withdrawal = receiptOnly
                    ? f.store().invalidateKnowledge(f.producer(), new KnowledgeInvalidation(work.source(), work.knowledgeReceiptId(), 1, "FIXTURE_WITHDRAW_RECEIPT"))
                    : f.store().invalidate(f.producer(), new SourceInvalidation(work.source(), 1, "FIXTURE_WITHDRAW_SOURCE"));
            check(!s.current(issued), "revocation dispatch immediately invalidates issued semantic page");
            check(await(withdrawal).status() == RecordingRecords.Status.STORED, "native revocation committed");
            var fresh = session(f, GOD, Set.of(PLAYER), false);
            check(read(fresh, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192).entries().isEmpty(), "durable receipt/source withdrawal gates vector lookup even while derived row exists");
        }
    }

    private static void cursorsAndSessions(Path root) throws Exception {
        try (var f = fixture(root)) {
            capture(f, "cursor fixture", WHEN); index(f, SAME);
            var a = session(f, GOD, Set.of(PLAYER), false); var b = session(f, GOD, Set.of(PLAYER), false);
            var page = read(a, query(QUERY), vector(QUERY, SAME), Optional.empty(), 1, 8192); var cursor = page.next();
            check(read(b, query(QUERY), vector(QUERY, SAME), cursor, 1, 8192).status() == MemoryReadSession.Status.STALE, "Session B cannot reuse Session A cursor");
            check(read(a, query("anotherquery"), vector("anotherquery", SAME), cursor, 1, 8192).status() == MemoryReadSession.Status.STALE, "cursor cannot change query text");
            check(read(a, query(QUERY), vector(QUERY, OTHER_DIRECTION), cursor, 1, 8192).status() == MemoryReadSession.Status.STALE, "cursor binds exact vector values, not merely input hash");
            var timed = new MemoryReadSession.Query(QUERY, Optional.of(WHEN), Optional.empty());
            check(read(a, timed, vector(QUERY, SAME), cursor, 1, 8192).status() == MemoryReadSession.Status.STALE, "cursor cannot change time scope");
            check(read(a, query(QUERY), vector(QUERY, SAME), Optional.of(SemanticReadRecords.Cursor.unregistered()), 1, 8192).status() == MemoryReadSession.Status.STALE, "unregistered semantic cursor cannot be forged");
            check(!b.current(page) && !a.current(new SemanticReadRecords.Page(page.status(), page.entries(), page.next())), "only exact issued page identity carries current authority");
            check(a.current(page), "foreign attempts do not mutate Session A's issued page");
        }
    }

    private static void runtimeGate(Path root) throws Exception {
        try (var f = fixture(root)) {
            capture(f, "gate fixture", WHEN); index(f, SAME);
            var enabled = new AtomicBoolean(false); var s = session(f, GOD, Set.of(PLAYER), false, enabled);
            check(read(s, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192).entries().isEmpty(), "disabled semantic capability cannot query existing vector rows");
            enabled.set(true); var active = session(f, GOD, Set.of(PLAYER), false, enabled);
            check(read(s, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192).entries().isEmpty(), "an initially disabled issued session cannot gain permission retroactively");
            var page = read(active, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192);
            check(page.entries().size() == 1 && active.current(page), "explicit enabled test capability can issue semantic page");
            enabled.set(false); check(!active.current(page), "turning feature gate off revokes already issued semantic output");
        }
    }

    private static void corruptRows(Path root) throws Exception {
        try (var f = fixture(root)) {
            capture(f, "검증된 벡터 원문.", WHEN); var work = index(f, SAME);
            var legitimate = session(f, GOD, Set.of(PLAYER), false);
            check(read(legitimate, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192).entries().size() == 1, "corruption fixture starts readable");
            // Synthetic build-directory fixture only; each malformed field is restored before the next case.
            var original = new LinkedHashMap<String, Object>();
            try (var db = connection(f); var q = db.prepareStatement("SELECT * FROM embedding_rows"); var row = q.executeQuery()) {
                check(row.next(), "one committed embedding row exists");
                for (String column : List.of("model_digest", "dimensions", "input_hash", "source_hash", "receipt_hash", "covered_characters", "total_characters", "vector", "vector_hash"))
                    original.put(column, column.equals("vector") ? row.getBytes(column) : row.getObject(column));
            }
            for (var mutation : List.<Map.Entry<String, Object>>of(Map.entry("model_digest", "b".repeat(64)), Map.entry("dimensions", 2),
                    Map.entry("input_hash", "0".repeat(64)), Map.entry("source_hash", "0".repeat(64)), Map.entry("receipt_hash", "0".repeat(64)),
                    Map.entry("covered_characters", work.coveredCharacters() - 1), Map.entry("total_characters", work.totalCharacters() + 1),
                    Map.entry("vector_hash", "0".repeat(64)))) {
                change(f, mutation.getKey(), mutation.getValue());
                var s = session(f, GOD, Set.of(PLAYER), false);
                check(read(s, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192).entries().isEmpty(), "stored " + mutation.getKey() + " mismatch cannot disclose or rank RAW");
                change(f, mutation.getKey(), original.get(mutation.getKey()));
            }
            byte[] nonfinite = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).putFloat(0).putFloat(1).array();
            change(f, "vector", nonfinite); change(f, "vector_hash", EmbeddingRecords.vectorHash(nonfinite));
            var nan = session(f, GOD, Set.of(PLAYER), false);
            check(read(nan, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192).entries().isEmpty(), "stored NaN is rejected even with a matching vector blob hash");
            change(f, "vector", new byte[8]); change(f, "vector_hash", EmbeddingRecords.vectorHash(new byte[8]));
            var shortBlob = session(f, GOD, Set.of(PLAYER), false);
            check(read(shortBlob, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192).entries().isEmpty(), "blob length must exactly match declared vector dimension");
            change(f, "vector", original.get("vector")); change(f, "vector_hash", original.get("vector_hash"));
            var restored = session(f, GOD, Set.of(PLAYER), false);
            check(read(restored, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192).entries().size() == 1, "restoring the synthetic row restores lookup without rewriting source records");
        }
    }

    private static void reopen(Path root) throws Exception {
        UUID world = UUID.randomUUID(), message;
        try (var f = fixture(root, world)) { message = capture(f, "재시작 뒤에도 남아야 하는 원문.", WHEN); index(f, SAME); }
        try (var f = fixture(root, world)) {
            var s = session(f, GOD, Set.of(PLAYER), false); var page = read(s, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192);
            check(page.entries().size() == 1 && page.entries().getFirst().messageId().equals(message), "clean reopen reads durable vectors with the original receipt proof");
            check(await(f.store().embeddingPort().claimWork(f.worker(), 45)).status() == EmbeddingRecords.Status.EMPTY, "completed same-space source is not embedded again after restart");
            try (var db = connection(f); var q = db.prepareStatement("SELECT count(*) FROM embedding_rows"); var rows = q.executeQuery()) {
                rows.next(); check(rows.getLong(1) == 1, "restart creates no duplicate vector rows");
            }
        }
    }

    private static void surrogatePrefix(Path root) throws Exception {
        try (var f = fixture(root)) {
            String text = "x".repeat(1599) + "🍎" + "never indexed tail";
            capture(f, text, WHEN); var work = index(f, SAME);
            check(work.coveredCharacters() == 1599 && work.text().equals("x".repeat(1599)), "embedding prefix never splits a UTF-16 surrogate pair at character1600");
            var s = session(f, GOD, Set.of(PLAYER), false); var page = read(s, query(QUERY), vector(QUERY, SAME), Optional.empty(), 8, 8192);
            check(page.entries().size() == 1 && page.entries().getFirst().text().equals(work.text()) && page.entries().getFirst().excerpt()
                    && page.entries().getFirst().totalCharacters() == text.length(), "reader retains exact safe prefix rather than adding unseen surrogate or tail characters");
        }
    }

    private static Fixture fixture(Path root) throws Exception {
        return fixture(root, UUID.randomUUID());
    }
    private static Fixture fixture(Path root, UUID world) throws Exception {
        Files.createDirectories(root); var store = await(WorldRecordingService.open(root, world, SETTINGS, BOUNDARY));
        check(store.health().state() == WorldRecordingService.State.READY, "fixture READY " + store.health().reasonCode());
        var producer = store.registerProducer("room-publication-v2", Set.of("ROOM_PRIVATE", "ROOM_PUBLIC"), Set.of(SourceKind.DIALOGUE_DIRECT, SourceKind.DERIVED_SPEECH));
        return new Fixture(root, world, store, producer, UUID.randomUUID(), store.registerEmbeddingWorker("fixture", SPACE));
    }
    private static UUID capture(Fixture f, String text, Instant occurred) throws Exception {
        return capture(f, text, occurred, false);
    }
    private static UUID capture(Fixture f, String text, Instant occurred, boolean publicRoom) throws Exception {
        UUID id = UUID.randomUUID(); var audience = Set.of(PLAYER, new ActorRef(ActorKind.GOD, GOD));
        var context = new PublicationContext(f.store().runtimeEpoch(), 1, "STANDARD", audience, audience, Map.of(), List.of(), Set.of(), Map.of(), "UNKNOWN", "PERSONAL");
        var envelope = new ConversationEnvelope(f.world(), f.store().datasetId().orElseThrow(), publicRoom ? UUID.randomUUID() : f.conversation(),
                publicRoom ? "ROOM_PUBLIC" : "ROOM_PRIVATE", publicRoom ? "PUBLIC_SPEECH" : "ACTUAL_LISTENERS_ONLY", 1, 1, true, false, "fixture");
        var raw = new RawMessage(id, Optional.empty(), 0, PLAYER, text, occurred, MessageKind.ACCEPTED_INPUT, id.toString(), context);
        var view = new DeliveryView(text, List.of(text)); var deliveries = new ArrayList<DeliveryReceipt>();
        for (var actor : audience) deliveries.add(new DeliveryReceipt(UUID.randomUUID(), actor, actor.kind() == ActorKind.GOD ? "GAME_HEARD" : "CHAT", occurred, 1, DeliveryStatus.SERVER_DISPATCHED, view, Set.of(0)));
        check(await(f.store().capture(f.producer(), envelope, raw, deliveries)).status() == RecordingRecords.Status.STORED, "native full-audience capture committed"); return id;
    }
    private static EmbeddingRecords.Work index(Fixture f, float[] vector) throws Exception {
        var claimed = await(f.store().embeddingPort().claimWork(f.worker(), 45));
        check(claimed.status() == EmbeddingRecords.Status.CLAIMED, "embedding claim succeeded: " + claimed.status() + "/" + claimed.reasonCode());
        var work = claimed.work().orElseThrow();
        check(await(f.store().embeddingPort().commitEmbedding(work.token(), vector)).status() == EmbeddingRecords.Status.STORED, "deterministic vector committed against exact game-issued native work"); return work;
    }
    private static RecordedMemoryAccess.Session session(Fixture f, String god, Set<ActorRef> players, boolean publicRoom) {
        return session(f, god, players, publicRoom, new AtomicBoolean(true));
    }
    private static RecordedMemoryAccess.Session session(Fixture f, String god, Set<ActorRef> players, boolean publicRoom, AtomicBoolean enabled) {
        var audience = new HashSet<>(players); audience.add(new ActorRef(ActorKind.GOD, god));
        var scope = new RecordedRoomSearch.Scope(f.store().datasetId().orElseThrow(), god, audience, publicRoom, "STANDARD", "PERSONAL");
        return new RecordedMemoryAccess.Session(f.store(), scope, () -> true, Runnable::run, () -> true,
                refs -> CompletableFuture.completedFuture(refs.isEmpty()), List::isEmpty, enabled::get);
    }
    private static SemanticReadRecords.Page read(MemoryReadSession session, MemoryReadSession.Query query, EmbeddingRecords.QueryVector vector,
            Optional<SemanticReadRecords.Cursor> cursor, int rows, int bytes) throws Exception {
        return await(session.semantic(query, vector, cursor, budget(rows, bytes)));
    }
    private static MemoryReadSession.Query query(String text) { return new MemoryReadSession.Query(text, Optional.empty(), Optional.empty()); }
    private static MemoryReadSession.Budget budget(int rows, int bytes) { return new MemoryReadSession.Budget(rows, bytes); }
    private static EmbeddingRecords.ModelSpace space(String model, String digest, int dimensions) { return new EmbeddingRecords.ModelSpace(model, digest, dimensions, EmbeddingRecords.ENCODER_VERSION); }
    private static EmbeddingRecords.QueryVector vector(String text, float[] values) { return new EmbeddingRecords.QueryVector(SPACE, sha256(text), values); }
    private static String sha256(String text) { return RecordingRecords.sha256(text); }
    private static Connection connection(Fixture f) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + f.root().resolve("mythictrpg-recording-v2").resolve(f.store().datasetId().orElseThrow().toString()).resolve("recording.sqlite"));
    }
    private static void change(Fixture f, String column, Object value) throws Exception {
        if (!Set.of("model_digest", "dimensions", "input_hash", "source_hash", "receipt_hash", "covered_characters", "total_characters", "vector", "vector_hash").contains(column))
            throw new IllegalArgumentException("FIXTURE_COLUMN");
        try (var db = connection(f); var q = db.prepareStatement("UPDATE embedding_rows SET " + column + "=?")) {
            if (value instanceof byte[] bytes) q.setBytes(1, bytes); else q.setObject(1, value);
            check(q.executeUpdate() == 1, "synthetic corruption/restoration affects exactly one test row");
        }
    }
    private static void expectIllegal(Runnable action, String message) { boolean rejected = false; try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; } check(rejected, message); }
    private static <T> T await(CompletionStage<T> value) throws Exception { return value.toCompletableFuture().get(20, TimeUnit.SECONDS); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
