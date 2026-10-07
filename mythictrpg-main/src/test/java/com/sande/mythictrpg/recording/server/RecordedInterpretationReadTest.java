package com.sande.mythictrpg.recording.server;

import com.google.gson.*;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.ProjectionRecords.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Actual SQLite + issued read-session fixtures; no Minecraft process, sockets or model calls. */
public final class RecordedInterpretationReadTest {
    private static int checks;
    private static final Gson JSON = new Gson();
    private static final String GOD = "test:a", OTHER = "test:b";
    private static final ActorRef PLAYER = new ActorRef(ActorKind.PLAYER, UUID.randomUUID().toString());
    private static final ActorRef STRANGER = new ActorRef(ActorKind.PLAYER, UUID.randomUUID().toString());
    private static final RecordingSettings SETTINGS = new RecordingSettings(RecordingSettings.Mode.SHADOW, 128_000_000, 2_000_000, .90, .95);
    private static final WorldRecordingService.CutoverBoundary BOUNDARY = new WorldRecordingService.CutoverBoundary("interpretation-read-test", Map.of());
    private record Fixture(Path root, UUID world, WorldRecordingService store, ProducerCapability producer, UUID conversation) implements AutoCloseable {
        @Override public void close() throws Exception { await(store.closeAsync()); }
    }

    public static void main(String[] args) throws Exception {
        Path parent = Path.of(args.length == 0 ? "build/recorded-interpretation-read-test" : args[0]).toAbsolutePath().normalize();
        if (!parent.toString().replace('\\', '/').contains("/build/")) throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent); Path root = Files.createTempDirectory(parent, "interpretations-");
        linkedClaims(root.resolve("linked")); unquotedWithdrawal(root.resolve("unquoted"));
        prefixCoverage(root.resolve("prefix")); missingDependency(root.resolve("missing-dependency"));
        for(String corruption:List.of("unquoted-delete","coverage","alias","sibling-delete","metadata","manifest-delete","manifest-hash","prefix-hash"))
            inputIntegrity(root.resolve(corruption),corruption);
        integrityMigration(root.resolve("schema9"));
        audience(root.resolve("audience")); versions(root.resolve("versions")); pagination(root.resolve("pagination"));
        System.out.println("RecordedInterpretationReadTest: " + checks + " checks passed; fixtures=" + root);
    }

    private static void linkedClaims(Path root) throws Exception {
        try (var f = fixture(root)) {
            var worker = f.store().registerProjectionWorker("fixture", "v1");
            UUID oldMessage = capture(f, "seedmarker 내일 돌아오겠다고 약속할게.", Set.of());
            Work old = claim(f, worker); commit(f, old, three(old));
            UUID recent = capture(f, "그 약속은 취소할게.", Set.of(oldMessage));
            Work cancellation = claim(f, worker);
            Evidence previous = cancellation.evidence().stream().filter(e -> e.messageId().equals(oldMessage)).findFirst().orElseThrow();
            var event = new Candidate(Layer.EVENT, ClaimKind.CORRECTION_OR_EXPLANATION,
                    List.of(new Quote(cancellation.targetAlias(), target(cancellation).text()), new Quote(previous.alias(), previous.text())),
                    List.of(new Link(cancellation.targetAlias(), previous.alias(), Relation.CANCELS)));
            var cards = new ArrayList<>(three(cancellation)); cards.set(0, event); commit(f, cancellation, cards);
            var session = session(f, GOD, Set.of(PLAYER), false); var raw = raw(session, "seedmarker");
            check(raw.entries().size() == 1 && raw.entries().getFirst().messageId().equals(oldMessage), "issued seed is the original promise only");
            var page = interpretations(session, raw, Optional.empty(), 8);
            boolean pageCurrent = session.current(page);
            check(page.status() == MemoryReadSession.Status.PARTIAL && page.entries().size() == 6 && pageCurrent,
                    "old raw seed retrieves its own and later linked native candidate layers: status=" + page.status()
                            + ", cards=" + page.entries().size() + ", current=" + pageCurrent + ", continuation=" + page.next().isPresent());
            check(page.entries().stream().mapToInt(RecordedInterpretationSearch::wireByteSize).sum() <= 32768,
                    "aggregate returned complete-card wire bytes stay within request budget, including Instant metadata");
            var linked = page.entries().stream().filter(e -> !e.links().isEmpty()).findFirst().orElseThrow();
            check(linked.authority() == InterpretationReadRecords.Authority.CANDIDATE && linked.kind() == ClaimKind.CORRECTION_OR_EXPLANATION,
                    "cancellation remains a non-authoritative interpretation");
            var relation = linked.links().getFirst();
            var older = linked.quotes().stream().filter(q -> q.sourceAlias().equals(relation.olderAlias())).findFirst().orElseThrow();
            var newer = linked.quotes().stream().filter(q -> q.sourceAlias().equals(relation.newerAlias())).findFirst().orElseThrow();
            check(relation.relation() == Relation.CANCELS && older.messageId().equals(oldMessage) && newer.messageId().equals(recent)
                            && older.actualActor().equals(PLAYER) && newer.actualActor().equals(PLAYER), "link preserves exact endpoint actors and source direction");
            check(older.text().equals(target(old).text()) && newer.text().equals(target(cancellation).text()) && linked.inputs().size() == 2,
                    "both verbatim quotes and all input coverage survive typed lookup");
            check(await(f.store().inspectMessage(oldMessage, 4096)).orElseThrow().equals(target(old).text()), "derived lookup does not rewrite RAW");
        }
    }

    private static void unquotedWithdrawal(Path root) throws Exception {
        try (var f = fixture(root)) {
            var worker = f.store().registerProjectionWorker("fixture", "v1");
            capture(f, "앞에서 참고했던 말.", Set.of()); Work old = claim(f, worker); commit(f, old, three(old));
            UUID message = capture(f, "targetmarker 지금 하는 말.", Set.of()); Work work = claim(f, worker); commit(f, work, three(work));
            var session = session(f, GOD, Set.of(PLAYER), false); var seed = raw(session, "targetmarker");
            var issued = interpretations(session, seed, Optional.empty(), 8);
            check(issued.entries().size() == 3 && issued.entries().stream().allMatch(e -> e.quotes().size() == 1 && e.inputs().size() == 2),
                    "all-input lineage includes an unquoted older context source");
            Evidence previous = target(old);
            var revoke = f.store().invalidateKnowledge(f.producer(), new KnowledgeInvalidation(previous.source(), previous.knowledgeReceiptId(), 1, "WITHDRAW_UNQUOTED_INPUT"));
            check(!session.current(issued), "withdrawal dispatch immediately cancels issued interpretation page");
            check(await(revoke).status() == RecordingRecords.Status.STORED, "unquoted input receipt withdrawn durably");
            var fresh = session(f, GOD, Set.of(PLAYER), false); var freshSeed = raw(fresh, "targetmarker");
            check(freshSeed.entries().size() == 1 && freshSeed.entries().getFirst().messageId().equals(message), "target RAW remains authorized independently");
            check(interpretations(fresh, freshSeed, Optional.empty(), 8).entries().isEmpty(), "unquoted withdrawal denies every affected candidate, not just selected quotes");
        }
    }

    private static void prefixCoverage(Path root) throws Exception {
        try (var f = fixture(root)) {
            var worker = f.store().registerProjectionWorker("fixture", "v1");
            String text = "prefixmarker " + "x".repeat(400) + " hidden_tail";
            capture(f, text, Set.of());
            var claimed = await(f.store().projectionPort().claimWork(worker, new WorkBudget(1, 256, 45)));
            check(claimed.status() == ProjectionRecords.Status.CLAIMED, "small input prefix is leased"); Work work = claimed.work().orElseThrow();
            check(target(work).excerpt() && !target(work).text().contains("hidden_tail"), "fixture tail was never supplied for interpretation");
            commit(f, work, three(work));
            var good = session(f, GOD, Set.of(PLAYER), false);
            check(interpretations(good, raw(good, "prefixmarker"), Optional.empty(), 8).entries().size() == 3, "valid prefix-grounded cards are readable");
            // Corruption fixture only: recompute payload hash so coverage, not merely hashing, must reject it.
            try (var db = connection(f); var q = db.prepareStatement("SELECT id,payload_json FROM memories"); var rows = q.executeQuery()) {
                var updates = new ArrayList<String[]>();
                while (rows.next()) { var payload = JsonParser.parseString(rows.getString(2)).getAsJsonObject();
                    payload.getAsJsonArray("quotes").get(0).getAsJsonObject().addProperty("text", "hidden_tail");
                    updates.add(new String[]{rows.getString(1), JSON.toJson(payload)}); }
                for (var update : updates) try (var change = db.prepareStatement("UPDATE memories SET payload_json=?,projection_hash=? WHERE id=?")) {
                    change.setString(1, update[1]); change.setString(2, RecordingRecords.sha256(update[1])); change.setString(3, update[0]); change.executeUpdate(); }
            }
            var rejected = session(f, GOD, Set.of(PLAYER), false);
            check(interpretations(rejected, raw(rejected, "prefixmarker"), Optional.empty(), 8).entries().isEmpty(),
                    "quote present only outside persisted UTF-16 prefix coverage is rejected even with matching payload hash");
        }
    }

    private static void missingDependency(Path root) throws Exception {
        try (var f = fixture(root)) {
            var worker = f.store().registerProjectionWorker("fixture", "v1");
            UUID parent = capture(f, "부모가 된 발언.", Set.of()); Work old = claim(f, worker); commit(f, old, three(old));
            UUID child = capture(f, "childmarker 부모를 참고한 발언.", Set.of(parent)); Work work = claim(f, worker); commit(f, work, three(work));
            try (var db = connection(f); var q = db.prepareStatement("DELETE FROM memory_sources WHERE message_id=? AND memory_id IN"
                    + " (SELECT m.id FROM memories m JOIN work_items w ON w.id=m.job_id WHERE w.message_id=?)")) {
                q.setString(1, parent.toString()); q.setString(2, child.toString()); check(q.executeUpdate() == 3, "fixture removes only mandatory parent derivation rows"); }
            var session = session(f, GOD, Set.of(PLAYER), false); var seed = raw(session, "childmarker");
            check(seed.entries().size() == 1, "underlying complete RAW parent proof still exists");
            check(interpretations(session, seed, Optional.empty(), 8).entries().isEmpty(),
                    "reader cannot repair missing derivation lineage by silently fetching extra parents");
        }
    }

    private static void audience(Path root) throws Exception {
        try (var f = fixture(root)) {
            var worker = f.store().registerProjectionWorker("fixture", "v1");
            capture(f, "privatemarker 이 방에서만 한 말.", Set.of()); Work work = claim(f, worker); commit(f, work, three(work));
            var owner = session(f, GOD, Set.of(PLAYER), false); var seed = raw(owner, "privatemarker");
            check(interpretations(owner, seed, Optional.empty(), 8).entries().size() == 3, "original observer can read its own candidate");
            for (var denied : List.of(session(f, OTHER, Set.of(PLAYER), false), session(f, GOD, Set.of(PLAYER, STRANGER), false), session(f, GOD, Set.of(PLAYER), true))) {
                var hidden = raw(denied, "privatemarker");
                check(hidden.entries().isEmpty() && interpretations(denied, hidden, Optional.empty(), 8).entries().isEmpty(),
                        "unheard God, added private listener, or public destination receives no original or interpretation");
                check(interpretations(denied, seed, Optional.empty(), 8).status() == MemoryReadSession.Status.STALE,
                        "foreign issued raw seed cannot manufacture access");
            }
        }
    }

    /** The removed source is OPTIONAL context: parent closure/quotes alone cannot detect its loss. */
    private static void inputIntegrity(Path root,String corruption)throws Exception {
        try(var f=fixture(root)) {
            var worker=f.store().registerProjectionWorker("fixture","v1");
            UUID old=capture(f,"참고만 했던 입력이다.",Set.of());Work earlier=claim(f,worker);commit(f,earlier,three(earlier));
            UUID target=capture(f,"integritymarker 지금 답한 내용.",Set.of());Work work=claim(f,worker);commit(f,work,three(work));
            check(work.evidence().size()==2&&work.evidence().stream().anyMatch(e->e.messageId().equals(old)),"fixture includes unquoted optional input, not a mandatory ancestor");
            var valid=session(f,GOD,Set.of(PLAYER),false);
            check(interpretations(valid,raw(valid,"integritymarker"),Optional.empty(),8).entries().size()==3,"intact commit-time input manifest allows all layers");
            String job;
            try(var db=connection(f);var q=db.prepareStatement("SELECT job_id FROM memories WHERE job_id IN (SELECT id FROM work_items WHERE message_id=?) LIMIT 1")){
                q.setString(1,target.toString());try(var rows=q.executeQuery()){check(rows.next(),"target projection job exists");job=rows.getString(1);}}
            try(var db=connection(f)) {
                String predicate=" WHERE memory_id IN (SELECT id FROM memories WHERE job_id='"+job+"') AND message_id='"+old+"'";
                switch(corruption) {
                    case "unquoted-delete" -> {try(var q=db.createStatement()){check(q.executeUpdate("DELETE FROM memory_sources"+predicate)==3,"corruption deletes every unquoted context row while quoted target stays intact");}}
                    case "coverage" -> {try(var q=db.createStatement()){q.executeUpdate("UPDATE memory_sources SET covered_characters=covered_characters-1,excerpt=1"+predicate);}}
                    case "alias" -> {try(var q=db.createStatement()){q.executeUpdate("UPDATE memory_sources SET source_alias='e5'"+predicate);}}
                    case "sibling-delete" -> {try(var q=db.createStatement()){q.executeUpdate("DELETE FROM memories WHERE job_id='"+job+"' AND layer='SUMMARY'");}}
                    case "metadata" -> {try(var q=db.createStatement()){q.executeUpdate("UPDATE memories SET created_sequence=created_sequence+1 WHERE job_id='"+job+"'");}}
                    case "manifest-delete" -> {try(var q=db.prepareStatement("DELETE FROM projection_input_manifests WHERE job_id=?")){q.setString(1,job);q.executeUpdate();}}
                    case "manifest-hash" -> {try(var q=db.prepareStatement("UPDATE projection_input_manifests SET manifest_hash=? WHERE job_id=?")){q.setString(1,"0".repeat(64));q.setString(2,job);q.executeUpdate();}}
                    case "prefix-hash" -> {
                        String json;try(var q=db.prepareStatement("SELECT manifest_json FROM projection_input_manifests WHERE job_id=?")){q.setString(1,job);try(var rows=q.executeQuery()){rows.next();json=rows.getString(1);}}
                        var object=JsonParser.parseString(json).getAsJsonObject();object.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("prefixHash","0".repeat(64));
                        String changed=JSON.toJson(object);
                        try(var q=db.prepareStatement("UPDATE projection_input_manifests SET manifest_json=?,manifest_hash=? WHERE job_id=?")){
                            q.setString(1,changed);q.setString(2,RecordingRecords.sha256(changed));q.setString(3,job);q.executeUpdate();}
                    }
                    default -> throw new AssertionError(corruption);
                }
            }
            var denied=session(f,GOD,Set.of(PLAYER),false);var seed=raw(denied,"integritymarker");
            check(seed.entries().size()==1&&seed.entries().getFirst().messageId().equals(target),"integrity corruption leaves authorized RAW untouched: "+corruption);
            check(interpretations(denied,seed,Optional.empty(),8).entries().isEmpty(),"entire candidate group fails closed for exact input/output mismatch: "+corruption);
            check(f.store().health().state()==WorldRecordingService.State.READY,"bad optional candidate never disables native archive: "+corruption);
        }
    }

    private static void integrityMigration(Path root)throws Exception {
        var original=fixture(root);var worker=original.store().registerProjectionWorker("fixture","v1");
        UUID message=capture(original,"migrationmarker 과거 원문은 보존한다.",Set.of());Work first=claim(original,worker);commit(original,first,three(first));
        UUID dataset=original.store().datasetId().orElseThrow();original.close();
        try(var db=connection(original);var q=db.createStatement()){
            q.execute("DROP TABLE projection_input_manifests");q.execute("DROP TABLE native_interpretation_evidence");q.execute("PRAGMA user_version=9");q.execute("UPDATE recording_meta SET schema_version=9");
        }
        var reopened=await(WorldRecordingService.open(root,original.world(),SETTINGS,BOUNDARY));
        try(var f=new Fixture(root,original.world(),reopened,null,original.conversation())) {
            check(reopened.health().state()==WorldRecordingService.State.READY&&reopened.datasetId().orElseThrow().equals(dataset),"schema9 migrates in place with same dataset");
            try(var db=connection(f);var q=db.createStatement()){
                try(var rows=q.executeQuery("PRAGMA user_version")){rows.next();check(rows.getInt(1)==RecordingSchema.VERSION,"schema10 migration records current version");}
                try(var rows=q.executeQuery("SELECT (SELECT count(*) FROM projection_input_manifests),(SELECT count(*) FROM memories)")){rows.next();
                    check(rows.getInt(1)==0&&rows.getInt(2)==3,"legacy candidates preserved with NO retrospective integrity manifest");}
            }
            var before=session(f,GOD,Set.of(PLAYER),false);var seed=raw(before,"migrationmarker");
            check(seed.entries().size()==1&&seed.entries().getFirst().messageId().equals(message),"migration preserves original source authority");
            check(interpretations(before,seed,Optional.empty(),8).entries().isEmpty(),"unverified legacy candidate is not issued");
            var oldWorker=reopened.registerProjectionWorker("fixture","v1");
            check(await(reopened.projectionPort().claimWork(oldWorker,new WorkBudget(6,16384,45))).status()==ProjectionRecords.Status.EMPTY,
                    "same extractor version does not silently re-extract or certify old DONE rows");
            Work replacement=claim(f,reopened.registerProjectionWorker("fixture","v2-explicit"));commit(f,replacement,three(replacement));
            var fresh=session(f,GOD,Set.of(PLAYER),false);var cards=interpretations(fresh,raw(fresh,"migrationmarker"),Optional.empty(),8);
            check(cards.entries().size()==3&&cards.entries().stream().allMatch(e->e.extractorVersion().equals("v2-explicit")),"only explicit new-version real lease/commit certifies new interpretations");
            try(var db=connection(f);var q=db.createStatement();var rows=q.executeQuery("SELECT (SELECT count(*) FROM projection_input_manifests),(SELECT count(*) FROM memories)")){
                rows.next();check(rows.getInt(1)==1&&rows.getInt(2)==6,"new integrity row coexists with preserved old candidates");}
        }
        try(var f=new Fixture(root,original.world(),await(WorldRecordingService.open(root,original.world(),SETTINGS,BOUNDARY)),null,original.conversation())){
            var session=session(f,GOD,Set.of(PLAYER),false);
            check(interpretations(session,raw(session,"migrationmarker"),Optional.empty(),8).entries().size()==3,"commit-time input manifest validates after another clean restart");
        }
    }

    private static void versions(Path root) throws Exception {
        try (var f = fixture(root)) {
            var worker = f.store().registerProjectionWorker("fixture", "v1");
            capture(f, "versionmarker 같은 원문.", Set.of()); Work work = claim(f, worker); commit(f, work, three(work));
            var old = session(f, GOD, Set.of(PLAYER), false); var seed = raw(old, "versionmarker");
            var before = interpretations(old, seed, Optional.empty(), 8); check(before.entries().size() == 3, "first successful extractor version readable");
            var nextWorker = f.store().registerProjectionWorker("fixture", "v2"); Work next = claim(f, nextWorker);
            check(old.current(seed) && !old.current(before), "projection mutation stales typed interpretation but not unchanged RAW page");
            check(interpretations(old, seed, Optional.empty(), 8).status() == MemoryReadSession.Status.STALE, "old projection generation cannot issue new cards");
            var pending = session(f, GOD, Set.of(PLAYER), false);
            check(interpretations(pending, raw(pending, "versionmarker"), Optional.empty(), 8).entries().isEmpty(), "old interpretation is not resurrected while current extractor job is LEASED");
            commit(f, next, three(next));
            var fresh = session(f, GOD, Set.of(PLAYER), false); var latest = interpretations(fresh, raw(fresh, "versionmarker"), Optional.empty(), 8);
            check(latest.entries().size() == 3 && latest.entries().stream().allMatch(e -> e.extractorVersion().equals("v2")),
                    "current DONE extractor provides exactly one set of layers, never mixed old/new duplicates");
            check(old.current(seed), "background candidate replacement never revokes unchanged raw knowledge");
        }
    }

    private static void pagination(Path root) throws Exception {
        try (var f = fixture(root)) {
            var worker = f.store().registerProjectionWorker("fixture", "v1");
            capture(f, "pagemarker 페이지를 나눌 원문.", Set.of()); Work work = claim(f, worker); commit(f, work, three(work));
            var session = session(f, GOD, Set.of(PLAYER), false); var seed = raw(session, "pagemarker");
            var page = interpretations(session, seed, Optional.empty(), 1); var ids = new HashSet<UUID>();
            var firstCursor = page.next().orElseThrow();
            for (int i = 0; i < 3; i++) {
                check(page.entries().size() == 1 && session.current(page) && ids.add(page.entries().getFirst().memoryId()), "bounded page returns a distinct complete card");
                if (i < 2) page = interpretations(session, seed, page.next(), 1);
            }
            check(ids.size() == 3, "three layers survive bounded keyset continuation");
            var foreign = session(f, GOD, Set.of(PLAYER), false); var foreignSeed = raw(foreign, "pagemarker");
            check(interpretations(foreign, foreignSeed, Optional.of(firstCursor), 1).status() == MemoryReadSession.Status.STALE,
                    "cursor belongs to its issuing session and exact raw page");
            check(interpretations(session, seed, Optional.of(InterpretationReadRecords.Cursor.unregistered()), 1).status() == MemoryReadSession.Status.STALE,
                    "unregistered cursor rejected");
            var forgedSeed = new MemoryReadSession.Page(seed.status(), seed.entries(), seed.next());
            check(interpretations(session, forgedSeed, Optional.empty(), 1).status() == MemoryReadSession.Status.STALE,
                    "structurally equal copied page is not a registered raw capability");
            var forged = new InterpretationReadRecords.Page(page.status(), page.entries(), page.next());
            check(!session.current(forged), "copied interpretation page never receives current authority");
            var tiny = session(f, GOD, Set.of(PLAYER), false); var tinySeed = raw(tiny, "pagemarker");
            var noFit = await(tiny.interpretations(tinySeed, Optional.empty(), new MemoryReadSession.Budget(8, 256)));
            check(noFit.status() == MemoryReadSession.Status.PARTIAL && noFit.entries().isEmpty() && tiny.current(noFit),
                    "byte budget includes complete actor/time/source metadata, not only short quote text");
            var fitting = session(f, GOD, Set.of(PLAYER), false);
            check(interpretations(fitting, raw(fitting, "pagemarker"), Optional.empty(), 8).entries().size() == 3,
                    "explicit wire-byte accounting handles Instant values and preserves intact cards under sufficient budget");
        }
    }

    private static Fixture fixture(Path root) throws Exception {
        Files.createDirectories(root); UUID world = UUID.randomUUID(); var store = await(WorldRecordingService.open(root, world, SETTINGS, BOUNDARY));
        check(store.health().state() == WorldRecordingService.State.READY, "fixture READY " + store.health().reasonCode());
        return new Fixture(root, world, store, store.registerProducer("room-publication-v2", Set.of("ROOM_PRIVATE"), Set.of(SourceKind.DIALOGUE_DIRECT, SourceKind.DERIVED_SPEECH)), UUID.randomUUID());
    }
    private static UUID capture(Fixture f, String text, Set<UUID> parents) throws Exception {
        UUID id = UUID.randomUUID(); Instant now = Instant.now(); var audience = Set.of(PLAYER, new ActorRef(ActorKind.GOD, GOD));
        var context = new PublicationContext(f.store().runtimeEpoch(), 1, "STANDARD", audience, audience, Map.of(), List.of(), parents, Map.of(), "UNKNOWN", "PERSONAL");
        var envelope = new ConversationEnvelope(f.world(), f.store().datasetId().orElseThrow(), f.conversation(), "ROOM_PRIVATE", "ACTUAL_LISTENERS_ONLY", 1, 1, true, false, "fixture");
        var raw = new RawMessage(id, Optional.empty(), 0, PLAYER, text, now, MessageKind.ACCEPTED_INPUT, id.toString(), context);
        var view = new DeliveryView(text, List.of(text)); var deliveries = new ArrayList<DeliveryReceipt>();
        for (var actor : audience) deliveries.add(new DeliveryReceipt(UUID.randomUUID(), actor, actor.kind() == ActorKind.GOD ? "GAME_HEARD" : "CHAT", now, 1, DeliveryStatus.SERVER_DISPATCHED, view, Set.of(0)));
        check(await(f.store().capture(f.producer(), envelope, raw, deliveries)).status() == RecordingRecords.Status.STORED, "native full-audience capture committed"); return id;
    }
    private static Work claim(Fixture f, ProjectionWorkerCapability worker) throws Exception {
        var result = await(f.store().projectionPort().claimWork(worker, new WorkBudget(6, 16384, 45)));
        check(result.status() == ProjectionRecords.Status.CLAIMED, "claim succeeded: " + result); return result.work().orElseThrow();
    }
    private static Evidence target(Work work) { return work.evidence().stream().filter(e -> e.alias().equals(work.targetAlias())).findFirst().orElseThrow(); }
    private static List<Candidate> three(Work work) {
        var quote = new Quote(work.targetAlias(), target(work).text());
        return List.of(new Candidate(Layer.EVENT, ClaimKind.DIALOGUE_EPISODE, List.of(quote), List.of()),
                new Candidate(Layer.RELATIONSHIP, ClaimKind.DIALOGUE_EPISODE, List.of(quote), List.of()),
                new Candidate(Layer.SUMMARY, ClaimKind.DIALOGUE_EPISODE, List.of(quote), List.of()));
    }
    private static void commit(Fixture f, Work work, List<Candidate> candidates) throws Exception {
        check(await(f.store().projectionPort().commitProjection(work.token(), candidates)).status() == ProjectionRecords.Status.STORED, "grounded candidates durably committed");
    }
    private static RecordedMemoryAccess.Session session(Fixture f, String god, Set<ActorRef> players, boolean publicRoom) {
        var audience = new HashSet<>(players); audience.add(new ActorRef(ActorKind.GOD, god));
        var scope = new RecordedRoomSearch.Scope(f.store().datasetId().orElseThrow(), god, audience, publicRoom, "STANDARD", "PERSONAL");
        return new RecordedMemoryAccess.Session(f.store(), scope, () -> true, Runnable::run, () -> true,
                refs -> CompletableFuture.completedFuture(refs.isEmpty()), List::isEmpty);
    }
    private static MemoryReadSession.Page raw(MemoryReadSession session, String text) throws Exception {
        var page = await(session.query(new MemoryReadSession.Query(text, Optional.empty(), Optional.empty()), Optional.empty(), new MemoryReadSession.Budget(8, 8192)));
        check(page.status() == MemoryReadSession.Status.PARTIAL && session.current(page), "actual raw page issued and current: " + page.status()); return page;
    }
    private static InterpretationReadRecords.Page interpretations(MemoryReadSession session, MemoryReadSession.Page seed, Optional<InterpretationReadRecords.Cursor> cursor, int rows) throws Exception {
        return await(session.interpretations(seed, cursor, new MemoryReadSession.Budget(rows, 32768)));
    }
    private static Connection connection(Fixture f) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + f.root().resolve("mythictrpg-recording-v2").resolve(f.store().datasetId().orElseThrow().toString()).resolve("recording.sqlite"));
    }
    private static <T> T await(CompletionStage<T> value) throws Exception { return value.toCompletableFuture().get(20, TimeUnit.SECONDS); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
