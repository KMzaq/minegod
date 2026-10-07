package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.ai.room.*;
import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.MemoryReadSession.*;
import com.sande.mythictrpg.recording.api.MemoryReadSession.Status;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real SQLite, authority/cursor identity, receipt/DAG/mode isolation; no live server or model. */
public final class RecordedRoomReadTest {
    private static int checks;
    private static final String G = "test:athena", H = "test:hermes";
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), WORLD = UUID.randomUUID();
    private static final Instant DATE = Instant.parse("2026-09-29T12:00:00Z");
    public static void main(String[] args) throws Exception {
        var base = Path.of(args.length == 0 ? "build/recorded-room-read-test" : args[0]).toAbsolutePath().normalize();
        if (!base.toString().replace('\\','/').contains("/build/")) throw new IllegalArgumentException("BUILD_ONLY");
        Files.createDirectories(base); var root = Files.createTempDirectory(base, "read-");
        var store = open(root, RecordingSettings.Mode.SHADOW);
        check(RecordedMemoryAccess.readableDataset(null).isEmpty(),"absent recording runtime cannot issue a read dataset");
        check(RecordedMemoryAccess.readableDataset(store).equals(store.datasetId()),"ready SHADOW runtime exposes its exact dataset for admission");
        var capturedMode = new java.util.concurrent.atomic.AtomicReference<>("PERSONAL");
        var standard = new RoomRecordingCapture(store, capturedMode::get);
        var privateMessage = event("needle private", false, Set.of(G), Set.of(A), List.of(), Set.of(), RecordingScope.STANDARD);
        captured(standard, privateMessage);
        var scope = scope(store, G, Set.of(G), Set.of(A), false, "STANDARD", "PERSONAL");
        var result = read(store, scope, "needle");
        check(result.candidates().size() == 1 && result.candidates().getFirst().entry().text().equals("needle private"), "direct actual listener reads private original");
        check(read(store, scope(store,H,Set.of(H),Set.of(A),false,"STANDARD","PERSONAL"), "needle").candidates().isEmpty(), "unheard God cannot search archive");
        check(read(store, scope(store,G,Set.of(G),Set.of(A,B),false,"STANDARD","PERSONAL"), "needle").candidates().isEmpty(), "new private player audience denied");
        check(read(store, scope(store,G,Set.of(G,H),Set.of(A),false,"STANDARD","PERSONAL"), "needle").candidates().isEmpty(), "unheard secondary God blocks disclosure");
        check(read(store, scope(store,G,Set.of(G),Set.of(A),true,"STANDARD","PERSONAL"), "needle").candidates().isEmpty(), "private cannot become public");
        capturedMode.set("RUMOR_TEST");
        captured(standard,event("rumortest isolated",false,Set.of(G),Set.of(A),List.of(),Set.of(),RecordingScope.STANDARD));
        capturedMode.set("UNKNOWN");
        captured(standard,event("oldmode unknown",false,Set.of(G),Set.of(A),List.of(),Set.of(),RecordingScope.STANDARD));
        capturedMode.set("PERSONAL");
        check(read(store,scope,"rumortest").candidates().isEmpty() && read(store,scope,"oldmode").candidates().isEmpty(), "different and unknown memory modes excluded");
        captured(standard,event("testpartition isolated",false,Set.of(G),Set.of(A),List.of(),Set.of(),RecordingScope.TEST_RECORDING));
        check(read(store,scope,"testpartition").candidates().isEmpty(), "test and standard recording remain separate");
        captured(standard,event("shared public",true,Set.of(G,H),Set.of(A),List.of(),Set.of(),RecordingScope.STANDARD));
        check(read(store,scope(store,G,Set.of(G,H),Set.of(B),true,"STANDARD","PERSONAL"),"shared").candidates().size()==1, "public disclosure to new player does not grant unheard Gods knowledge");
        var privateParent = event("ancestor private",false,Set.of(G),Set.of(A),List.of(),Set.of(),RecordingScope.STANDARD); captured(standard,privateParent);
        captured(standard,event("laundered public",true,Set.of(G,H),Set.of(A,B),List.of(),Set.of(privateParent.messageId()),RecordingScope.STANDARD));
        check(read(store,scope(store,G,Set.of(G,H),Set.of(A,B),true,"STANDARD","PERSONAL"),"laundered").candidates().isEmpty(), "public child cannot launder private ancestry");
        captured(standard,event("missing ancestor",false,Set.of(G),Set.of(A),List.of(),Set.of(UUID.randomUUID()),RecordingScope.STANDARD));
        check(read(store,scope,"missing").candidates().isEmpty(), "missing parent fails closed");
        var rootEvidence = new RoomEvidenceReference("test-proof","source1");
        var parent = event("proof parent",false,Set.of(G),Set.of(A),List.of(rootEvidence),Set.of(),RecordingScope.STANDARD); captured(standard,parent);
        captured(standard,event("proofchild statement",false,Set.of(G),Set.of(A),List.of(),Set.of(parent.messageId()),RecordingScope.STANDARD));
        check(read(store,scope,"proofchild").candidates().getFirst().evidence().equals(List.of(rootEvidence)), "all parent source evidence travels to live validation");
        String longText = "첫머리 "+"가나다라 ".repeat(5000)+"tailmarker 끝 정정";
        captured(standard,event(longText,false,Set.of(G),Set.of(A),List.of(),Set.of(),RecordingScope.STANDARD));
        var tail = await(store.readRoom(scope,query("tailmarker"),new Budget(2,512),store.health().highWatermark(),Long.MAX_VALUE)).candidates().getFirst().entry();
        check(tail.excerpt() && tail.text().contains("tailmarker") && tail.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=512,"long raw suffix searchable and UTF-8 bounded excerpt labelled");
        check(await(store.readRoom(scope,new Query("needle",Optional.of(DATE.plusSeconds(1)),Optional.empty()),new Budget(2,512),store.health().highWatermark(),Long.MAX_VALUE)).candidates().isEmpty(), "time range excludes wrong date");
        check(await(store.readRoom(scope,new Query("needle",Optional.of(DATE),Optional.of(DATE.plusSeconds(1))),new Budget(2,512),store.health().highWatermark(),Long.MAX_VALUE)).candidates().size()==1, "inclusive start exclusive end time query");
        sessions(store,scope);
        asynchronousSessionRecovery(store,scope);
        chunkBoundarySearch(store,standard,scope);
        privatePageShapes(store,standard);
        byteBudgetPagination(store,standard,scope);
        var oldSession = session(store,scope,new AtomicBoolean(true),new AtomicBoolean(true));
        var oldPage = await(oldSession.query(query("needle"),Optional.empty(),new Budget(2,512)));
        await(store.closeAsync()); check(!oldSession.current(oldPage), "closed runtime invalidates capability");
        check(RecordedMemoryAccess.readableDataset(store).isEmpty(),"closed SHADOW runtime cannot issue new sessions");
        var reopened = open(root,RecordingSettings.Mode.SHADOW);
        check(readAcrossWindows(reopened,scope(reopened,G,Set.of(G),Set.of(A),false,"STANDARD","PERSONAL"),"needle").size()==1,"actual reopen reuses original receipt across bounded windows without importing legacy");
        check(!oldSession.current(oldPage),"restart cannot revive old page authority"); await(reopened.closeAsync());
        // A rejected restore/world identity returns an unavailable service with no manifest assigned.
        var failed=await(WorldRecordingService.open(root,UUID.randomUUID(),
                new RecordingSettings(RecordingSettings.Mode.SHADOW,256_000_000,2_000_000,.9,.95),
                new WorldRecordingService.CutoverBoundary("read-fixture",Map.of())));
        check(failed.health().state()==WorldRecordingService.State.UNAVAILABLE&&failed.datasetId().isEmpty()
                &&RecordedMemoryAccess.readableDataset(failed).isEmpty(),"initialization failure without dataset is an absent optional read, not an exception");
        await(failed.closeAsync());
        var recordOnly = open(root.resolve("record-only"),RecordingSettings.Mode.RECORD_ONLY);
        check(RecordedMemoryAccess.readableDataset(recordOnly).isEmpty(),"healthy RECORD_ONLY runtime does not issue read sessions");
        try { read(recordOnly,scope(recordOnly,G,Set.of(G),Set.of(A),false,"STANDARD","PERSONAL"),""); throw new AssertionError("record-only read permitted"); }
        catch(ExecutionException expected) { checks++; } finally { await(recordOnly.closeAsync()); }
        nativeKnowledge(root.resolve("native-knowledge"));
        lexicalPrecision(root.resolve("lexical-precision"));
        indexedPagination(root.resolve("indexed-pagination"));
        budgetContinuation(root.resolve("budget-continuation"));
        sqlVmBudget();
        System.out.println("RecordedRoomReadTest: "+checks+" checks passed; fixtures="+root);
    }
    private static void sqlVmBudget() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            long started = System.nanoTime(); boolean interrupted = false;
            try (var budget = new SqlReadBudget(db, started); var query = db.createStatement()) {
                query.executeQuery("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<1000000000) SELECT sum(x) FROM n");
            } catch (SQLException expected) { interrupted = expected.getErrorCode() == 9; }
            check(interrupted && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "expired budget interrupts SQLite execution instead of scanning billions of rows");
            try (var query = db.createStatement(); var rows = query.executeQuery("SELECT 42")) {
                check(rows.next() && rows.getInt(1) == 42, "progress handler removed after interruption");
            }
        }
    }
    private static void lexicalPrecision(Path root) throws Exception {
        var store = open(root, RecordingSettings.Mode.SHADOW);
        try {
            var producer = store.registerProducer(RoomRecordingCapture.PRODUCER, Set.of("ROOM_PRIVATE"), Set.of());
            var scope = scope(store,G,Set.of(G),Set.of(A),false,"STANDARD","PERSONAL");
            var fixtures = List.of(Map.entry("신", "작은 신 이야기"), Map.entry("%", "확률 50%"),
                    Map.entry("_", "under_score"), Map.entry("!", "놀람!"), Map.entry("🌊", "바다 🌊 파도"),
                    Map.entry("ÉCOLE", "école 방문"));
            var ids = new HashMap<String,UUID>();
            for (var fixture : fixtures) ids.put(fixture.getKey(), storeNative(store, producer, fixture.getValue(), DATE));
            storeNative(store,producer,"검색과 무관한 최근 기록",DATE);
            for (var fixture : fixtures) {
                var found = read(store,scope,fixture.getKey()).candidates();
                check(found.size()==1 && found.getFirst().entry().messageId().equals(ids.get(fixture.getKey())),
                        "one-character, literal punctuation/wildcard and Unicode folded query only returns matching authorized speech: "
                                +fixture.getKey()+" returned "+found.size());
            }
            check(read(store,scope,"?").candidates().isEmpty() && read(store,scope,"🐉").candidates().isEmpty()
                            && read(store,scope,"x").candidates().isEmpty(),
                    "absent symbol, emoji and one-letter query cannot silently become recent-history retrieval");
            check(!read(store,scope,"").candidates().isEmpty(),"only an intentionally empty query requests recent authorized history");
            String expanding = "İ".repeat(2000) + " 경계표시 마무리";
            UUID expandedId = storeNative(store,producer,expanding,DATE);
            var expanded = await(store.readRoom(scope,query("경계표시"),new Budget(1,512),store.health().highWatermark(),Long.MAX_VALUE));
            check(expanded.candidates().size()==1 && expanded.candidates().getFirst().entry().messageId().equals(expandedId)
                            && expanded.candidates().getFirst().entry().excerpt() && expanded.candidates().getFirst().entry().text().contains("경계표시"),
                    "case-fold expansion before a hit cannot shift the excerpt past the actual Unicode match");
            Instant tick = DATE.plusNanos(123_456_700);
            UUID exact = storeNative(store,producer,"nanotime exact",tick);
            storeNative(store,producer,"nanotime before",tick.minusNanos(1));
            storeNative(store,producer,"nanotime after",tick.plusNanos(1));
            var timed = await(store.readRoom(scope,new Query("nanotime",Optional.of(tick),Optional.of(tick.plusNanos(1))),
                    new Budget(8,8192),store.health().highWatermark(),Long.MAX_VALUE));
            check(timed.candidates().size()==1 && timed.candidates().getFirst().entry().messageId().equals(exact),
                    "Instant range is exact to nanoseconds without SQLite julianday rounding");
            UUID old = storeNative(store,producer,"longtailmarker oldest relevant speech",DATE);
            for (int i=0;i<130;i++) storeNative(store,producer,"무관한 새 대화 "+i,DATE);
            long watermark = store.health().highWatermark();
            var first = await(store.readRoom(scope,query("longtailmarker"),new Budget(8,8192),watermark,Long.MAX_VALUE));
            check(first.candidates().isEmpty() && first.more() && first.beforeSequence()>0 && first.beforeSequence()<Long.MAX_VALUE,
                    "rare old keyword returns a bounded partial window and a progressing internal cursor, not an unbounded SQL scan");
            var second = await(store.readRoom(scope,query("longtailmarker"),new Budget(8,8192),watermark,first.beforeSequence()));
            check(second.candidates().size()==1 && second.candidates().getFirst().entry().messageId().equals(old)
                            && second.beforeSequence()<first.beforeSequence(),
                    "bounded continuation reaches old matching speech after a nonmatching first window");
            var capability = session(store,scope,new AtomicBoolean(true),new AtomicBoolean(true));
            var page = await(capability.query(query("longtailmarker"),Optional.empty(),new Budget(8,8192)));
            check(page.status()==Status.PARTIAL && page.entries().isEmpty() && page.next().isPresent() && capability.current(page),
                    "public read API truthfully labels a nonexhaustive empty scan PARTIAL rather than EMPTY");
            var next = await(capability.query(query("longtailmarker"),page.next(),new Budget(8,8192)));
            check(next.status()==Status.PARTIAL && next.entries().size()==1 && next.entries().getFirst().messageId().equals(old),
                    "opaque session cursor preserves raw-window forward progress");
            try (var db = diagnosticDb(root,store)) {
                var expired = RecordedRoomSearch.query(db,scope,query("longtailmarker"),new Budget(8,8192),watermark,Long.MAX_VALUE,System.nanoTime()-1);
                check(expired.candidates().isEmpty() && expired.more(),"already-expired bounded search never claims exhaustive absence");
                check(RecordedRoomSearch.query(db,scope,query("longtailmarker"),new Budget(8,8192),watermark,first.beforeSequence()).candidates().size()==1,
                        "expired search clears its SQLite interruption handler for the next bounded query");
            }
            UUID small = storeNative(store,producer,"oversizemark older permitted speech",DATE);
            UUID oversized = storeNative(store,producer,"oversizemark "+"a".repeat(1_048_576),DATE);
            var bounded = await(store.readRoom(scope,query("oversizemark"),new Budget(1,512),store.health().highWatermark(),Long.MAX_VALUE));
            check(bounded.candidates().size()==1 && bounded.candidates().getFirst().entry().messageId().equals(small)
                            && bounded.candidates().stream().noneMatch(c->c.entry().messageId().equals(oversized)),
                    "oversized archived source is skipped fail-closed without starving an older authorized matching source");
        } finally { await(store.closeAsync()); }
    }
    private static UUID storeNative(WorldRecordingService store, ProducerCapability producer, String text, Instant occurred) throws Exception {
        var message = nativeMessage(store,text,occurred);
        check(await(store.capture(producer,message.envelope(),message.raw(),List.of(nativePlayerReceipt(text),nativeReceipt(G,text,false)))).status()==RecordingRecords.Status.STORED,
                "lexical fixture source and complete native receipt commit");
        return message.raw().messageId();
    }
    private static void indexAll(WorldRecordingService store) throws Exception {
        for(int batch=0;batch<128;batch++) {
            var result=await(store.pumpLexicalIndex());
            if(result.caughtUp())return;
            check(result.indexed()+result.skipped()>0,"bounded lexical backfill advances: "+result.state()+" "+result.reasonCode());
        }
        throw new AssertionError("lexical fixture backfill exceeded bounded test limit");
    }
    private static void budgetContinuation(Path root) throws Exception {
        var store=open(root,RecordingSettings.Mode.SHADOW);
        try {
            var producer=store.registerProducer(RoomRecordingCapture.PRODUCER,Set.of("ROOM_PRIVATE"),Set.of());
            var scope=scope(store,G,Set.of(G),Set.of(A),false,"STANDARD","PERSONAL");
            UUID wanted=storeNative(store,producer,"budgetmarker old small speech",DATE);
            UUID a=storeNative(store,producer,"a".repeat(1_048_576),DATE);
            UUID b=storeNative(store,producer,"b".repeat(1_048_576),DATE);
            long wantedSequence;
            try(var db=diagnosticDb(root,store)) { wantedSequence=scalar(db,"SELECT ingest_sequence FROM messages WHERE id=?",wanted); }
            long watermark=store.health().highWatermark();
            var first=await(store.readRoom(scope,query("budgetmarker"),new Budget(1,512),watermark,Long.MAX_VALUE));
            check(first.candidates().isEmpty() && first.more() && first.beforeSequence()>wantedSequence,
                    "request-wide byte/deadline exhaustion preserves the unexamined small matching source in the continuation");
            var recovered=new ArrayList<RecordedRoomSearch.Candidate>();long before=first.beforeSequence();
            for(int page=1;page<8;page++) {
                var next=await(store.readRoom(scope,query("budgetmarker"),new Budget(1,512),watermark,before));
                recovered.addAll(next.candidates());if(!next.more()||!recovered.isEmpty())break;before=next.beforeSequence();
            }
            check(recovered.size()==1 && recovered.getFirst().entry().messageId().equals(wanted),
                    "fresh page finds the small authorized match after two one-MiB nonmatches consumed the earlier page budget");

            var child=nativeMessage(store,"budgetmarker intrinsically oversized ancestor closure");
            var prior=child.raw().publicationContext();
            var context=new PublicationContext(prior.runtimeEpoch(),prior.membershipRevision(),prior.recordingPolicy(),
                    prior.participants(),prior.fullAudience(),prior.participantNames(),prior.evidence(),Set.of(a,b),prior.transportMessageIds(),prior.gameTimeStatus(),prior.memoryMode());
            var raw=child.raw();
            var dependent=new RawMessage(raw.messageId(),raw.turnId(),raw.turnSequence(),raw.speaker(),raw.body(),raw.occurredAt(),raw.kind(),raw.occurrenceKey(),context);
            check(await(store.capture(producer,child.envelope(),dependent,List.of(nativePlayerReceipt(raw.body()),nativeReceipt(G,raw.body(),false)))).status()==RecordingRecords.Status.STORED,
                    "large-ancestry fixture remains stored without pretending it fits one read budget");
            recovered.clear();before=Long.MAX_VALUE;watermark=store.health().highWatermark();
            for(int page=0;page<8;page++) {
                var next=await(store.readRoom(scope,query("budgetmarker"),new Budget(1,512),watermark,before));
                recovered.addAll(next.candidates());if(!next.more()||!recovered.isEmpty())break;before=next.beforeSequence();
            }
            check(recovered.size()==1 && recovered.getFirst().entry().messageId().equals(wanted)
                            && recovered.stream().noneMatch(c->c.entry().messageId().equals(raw.messageId())),
                    "intrinsic over-budget ancestry is explicitly skipped instead of retrying forever and starving older valid speech");
        } finally { await(store.closeAsync()); }
    }
    private static void indexedPagination(Path root) throws Exception {
        var store=open(root,RecordingSettings.Mode.SHADOW);
        try {
            var producer=store.registerProducer(RoomRecordingCapture.PRODUCER,Set.of("ROOM_PRIVATE"),Set.of(SourceKind.DIALOGUE_DIRECT));
            var scope=scope(store,G,Set.of(G),Set.of(A),false,"STANDARD","PERSONAL");
            UUID deep=storeNative(store,producer,"rareindexmarker old known speech",DATE);
            indexAll(store);
            for(int i=0;i<130;i++)storeNative(store,producer,"최근의 무관한 발화 "+i,DATE);
            var expected=new HashSet<UUID>();expected.add(deep);
            for(int i=0;i<3;i++)expected.add(storeNative(store,producer,"betweenmark initially unindexed "+i,DATE));
            var capability=session(store,scope,new AtomicBoolean(true),new AtomicBoolean(true));
            var combined=query("rareindexmarker betweenmark");
            var first=await(capability.query(combined,Optional.empty(),new Budget(1,512)));
            check(first.status()==Status.PARTIAL && first.entries().size()==1 && first.entries().getFirst().messageId().equals(deep),
                    "real trigram index reaches a rare old match past the entire recent raw window on the first page");
            var received=new HashSet<UUID>();received.add(deep);
            UUID tooLate=storeNative(store,producer,"betweenmark published after read watermark",DATE);
            indexAll(store); // This changes manifest membership only for FUTURE sessions.
            check(capability.current(first),"non-authoritative indexing does not revoke an otherwise-current read capability");
            var cursor=first.next();
            for(int pageNumber=2;pageNumber<=8 && cursor.isPresent();pageNumber++) {
                var page=await(capability.query(combined,cursor,new Budget(1,512)));
                check(page.status()==Status.PARTIAL && capability.current(page),"continued indexed/raw read keeps its original authority and partial coverage");
                for(var entry:page.entries())check(received.add(entry.messageId()),"indexing between pages cannot return an already-issued raw/indexed source twice");
                cursor=page.next();
            }
            check(received.equals(expected) && !received.contains(tooLate),
                    "late indexing cannot skip formerly-unindexed matches or import originals newer than the session watermark");
            var fresh=session(store,scope,new AtomicBoolean(true),new AtomicBoolean(true));
            var rare=await(fresh.query(query("rareindexmarker"),Optional.empty(),new Budget(1,512)));
            check(rare.entries().size()==1 && rare.entries().getFirst().messageId().equals(deep),"fresh indexed query still reaches the deep authorized original");
            for(var deniedScope:List.of(scope(store,H,Set.of(H),Set.of(A),false,"STANDARD","PERSONAL"),
                    scope(store,G,Set.of(G),Set.of(A,B),false,"STANDARD","PERSONAL"),scope(store,G,Set.of(G),Set.of(A),true,"STANDARD","PERSONAL"))) {
                var denied=session(store,deniedScope,new AtomicBoolean(true),new AtomicBoolean(true));
                check(await(denied.query(query("rareindexmarker"),Optional.empty(),new Budget(1,512))).entries().isEmpty(),
                        "trigram presence never substitutes for actual God receipt, private audience or public disclosure ACL");
            }
            UUID shortMatch=storeNative(store,producer,"신 한글 발화",DATE);
            indexAll(store);
            var mixed=session(store,scope,new AtomicBoolean(true),new AtomicBoolean(true));
            var mixedPage=await(mixed.query(query("rareindexmarker 신"),Optional.empty(),new Budget(1,512)));
            check(mixedPage.entries().size()==1 && mixedPage.entries().getFirst().messageId().equals(shortMatch),
                    "OR query containing a one-character term raw-checks indexed messages too");
            for(var fixture:List.of(Map.entry("%%%___","정확한 기호 %%%___"),Map.entry("\"\"\"","정확한 따옴표 \"\"\""),
                    Map.entry("🌀🌀🌀","소용돌이 🌀🌀🌀"),Map.entry("ÉCOLE","école 방문"))) {
                UUID id=storeNative(store,producer,fixture.getValue(),DATE);indexAll(store);
                var reader=session(store,scope,new AtomicBoolean(true),new AtomicBoolean(true));
                var found=await(reader.query(query(fixture.getKey()),Optional.empty(),new Budget(1,512)));
                check(found.status()==Status.PARTIAL && found.entries().size()==1 && found.entries().getFirst().messageId().equals(id),
                        "FTS grammar quoting preserves literal punctuation, SQL wildcard, emoji and Unicode queries: "+fixture.getKey());
            }
            String longest="q".repeat(4095)+"𠀀"+"r".repeat(4095);
            String acrossParts="가".repeat(4095)+longest;
            UUID maximum=storeNative(store,producer,acrossParts,DATE);
            UUID gramsOnly=storeNative(store,producer,"qqq separate q𠀀r separate rrr",DATE);
            indexAll(store);
            var maximumReader=session(store,scope,new AtomicBoolean(true),new AtomicBoolean(true));
            var maximumPage=await(maximumReader.query(query(longest),Optional.empty(),new Budget(2,32768)));
            check(maximumPage.status()==Status.PARTIAL && maximumPage.entries().size()==1
                            && maximumPage.entries().getFirst().messageId().equals(maximum)
                            && maximumPage.entries().getFirst().text().equals(acrossParts)
                            && maximumPage.entries().stream().noneMatch(e->e.messageId().equals(gramsOnly)),
                    "8192-unit cross-part term uses bounded trigram candidates but still verifies the entire literal raw term");
            SourceRef source;UUID receipt;
            try(var db=diagnosticDb(root,store);var statement=db.prepareStatement("SELECT s.source_hash,k.id FROM source_refs s JOIN knowledge_receipts k ON k.source_ref=s.id WHERE s.source_id=? AND k.god_id=?")) {
                statement.setString(1,deep.toString());statement.setString(2,G);
                try(var rows=statement.executeQuery()) {
                    check(rows.next(),"indexed original has exact native knowledge proof for revocation");
                    source=new SourceRef(WORLD,store.datasetId().orElseThrow(),SourceKind.DIALOGUE_DIRECT,RoomRecordingCapture.PRODUCER,deep.toString(),1,rows.getString(1));
                    receipt=UUID.fromString(rows.getString(2));
                }
            }
            var revoked=store.invalidateKnowledge(producer,new KnowledgeInvalidation(source,receipt,1,"GAME_PROOF_REVOKED"));
            check(!fresh.current(rare),"index lookup page loses authority immediately when its game proof is withdrawn");
            check(await(revoked).status()==RecordingRecords.Status.STORED,"indexed source proof withdrawal commits");
            var denied=session(store,scope,new AtomicBoolean(true),new AtomicBoolean(true));
            check(await(denied.query(query("rareindexmarker"),Optional.empty(),new Budget(1,512))).entries().isEmpty(),
                    "stale contentless FTS posting cannot revive revoked knowledge");
        } finally { await(store.closeAsync()); }
    }
    private static List<RecordedRoomSearch.Candidate> readAcrossWindows(WorldRecordingService store, RecordedRoomSearch.Scope scope, String text) throws Exception {
        var found = new ArrayList<RecordedRoomSearch.Candidate>(); long before = Long.MAX_VALUE;
        for (int i=0;i<8;i++) {
            var page=await(store.readRoom(scope,query(text),new Budget(8,8192),store.health().highWatermark(),before));
            found.addAll(page.candidates()); if (!page.more()) break;
            check(page.beforeSequence()<before,"bounded archive continuation advances"); before=page.beforeSequence();
        }
        return List.copyOf(found);
    }
    private static void sessions(WorldRecordingService store,RecordedRoomSearch.Scope scope) throws Exception {
        var live=new AtomicBoolean(true);var proof=new AtomicBoolean(true);var session=session(store,scope,live,proof);
        var page=await(session.query(query("proofchild"),Optional.empty(),new Budget(2,1024)));
        check(page.entries().size()==1&&session.current(page)&&page.status()==Status.PARTIAL,"authorized page requires live proof and declares incomplete coverage");
        check(!session.current(new Page(page.status(),page.entries(),page.next())),"copied page does not carry authority");
        proof.set(false);check(!session.current(page),"proof revoked after read prevents use");
        var rejected=await(session.query(query("proofchild"),Optional.empty(),new Budget(2,1024)));
        check(rejected.entries().isEmpty(),"invalid evidence never leaves query as content");proof.set(true);
        live.set(false);check(!session.current(page)&&await(session.query(query("needle"),Optional.empty(),new Budget(2,512))).status()==Status.STALE,"superseded game turn denies read and previous page");
        var other=session(store,scope,new AtomicBoolean(true),proof);
        check(!other.current(page),"another session cannot reuse page");
        check(await(other.query(query(""),Optional.of(Cursor.unregistered()),new Budget(1,512))).status()==Status.STALE,"fabricated cursor rejected");
        var paged=session(store,scope,new AtomicBoolean(true),proof);
        var first=await(paged.query(query(""),Optional.empty(),new Budget(1,512)));
        check(first.next().isPresent(),"bounded page cursor issued");
        check(await(other.query(query(""),first.next(),new Budget(1,512))).status()==Status.STALE,"foreign session cursor rejected");
        check(await(paged.query(query("needle"),first.next(),new Budget(1,512))).status()==Status.STALE,"cursor binds query");
        var second=await(paged.query(query(""),first.next(),new Budget(1,512)));
        check(second.entries().stream().noneMatch(e->first.entries().stream().anyMatch(f->f.messageId().equals(e.messageId()))),"next page does not repeat earlier row");
    }
    private record PageShape(Status status, int entries, boolean next) { }
    private static void asynchronousSessionRecovery(WorldRecordingService store,RecordedRoomSearch.Scope scope) throws Exception {
        for (int rejectedAt : List.of(1, 2)) {
            var dispatches = new java.util.concurrent.atomic.AtomicInteger();
            var session = new RecordedMemoryAccess.Session(store,scope,()->true,task->{
                if (dispatches.incrementAndGet() == rejectedAt) throw new RejectedExecutionException("synthetic dispatch unavailable");
                task.run();
            },()->true,refs->CompletableFuture.completedFuture(true),refs->true);
            var failed = await(session.query(query("proofchild"),Optional.empty(),new Budget(1,512)));
            check(failed.status()==Status.UNAVAILABLE && failed.entries().isEmpty() && !session.current(failed),
                    "dispatch rejection at read/prepare callback fails closed without page authority");
            var retry = await(session.query(query("proofchild"),Optional.empty(),new Budget(1,512)));
            check(retry.status()==Status.PARTIAL && retry.entries().size()==1 && session.current(retry),
                    "same session recovers after one dispatch rejection");
        }
        var firstPreparation = new CompletableFuture<Boolean>();
        var secondPreparation = new CompletableFuture<Boolean>();
        var preparingSecond = new CountDownLatch(1);
        var preparations = new java.util.concurrent.atomic.AtomicInteger();
        var session = new RecordedMemoryAccess.Session(store,scope,()->true,Runnable::run,()->true,refs->{
            int attempt = preparations.incrementAndGet();
            if (attempt == 1) return firstPreparation;
            if (attempt == 2) { preparingSecond.countDown(); return secondPreparation; }
            return CompletableFuture.completedFuture(true);
        },refs->true);
        var timedOut = await(session.query(query("proofchild"),Optional.empty(),new Budget(1,512)));
        check(timedOut.status()==Status.UNAVAILABLE && timedOut.entries().isEmpty() && !session.current(timedOut)
                        && !firstPreparation.isDone(), "pending proof preparation times out without inventing evidence or canceling provider work");
        var next = session.query(query("proofchild"),Optional.empty(),new Budget(1,512));
        check(preparingSecond.await(1,TimeUnit.SECONDS), "timeout releases admission for a new query in the same valid session");
        firstPreparation.complete(true);
        check(!next.isDone(), "late old preparation cannot complete the new active query");
        check(await(session.query(query("needle"),Optional.empty(),new Budget(1,512))).status()==Status.UNAVAILABLE,
                "late old callback cannot unlock a still-running new query");
        secondPreparation.complete(true);
        var recovered = await(next);
        check(recovered.status()==Status.PARTIAL && recovered.entries().size()==1 && session.current(recovered),
                "new query keeps its own result and authority after late old callback");
        for (int call=3;call<=8;call++) check(await(session.query(query("needle"),Optional.empty(),new Budget(1,512))).status()==Status.PARTIAL,
                "timeout counts once and concurrent rejection does not consume the existing eight-call budget");
        check(await(session.query(query("needle"),Optional.empty(),new Budget(1,512))).status()==Status.UNAVAILABLE,
                "recovery does not extend the existing eight-call budget");
    }
    private static void chunkBoundarySearch(WorldRecordingService store,RoomRecordingCapture capture,
            RecordedRoomSearch.Scope scope) throws Exception {
        for(var fixture:List.of(Map.entry("boundarytoken","x".repeat(4092)+" boundarytoken suffix"),
                Map.entry("푸른경계표식","가".repeat(4092)+" 푸른경계표식 마무리"),
                Map.entry("나𠀀다경계","가".repeat(4094)+"나𠀀다경계 마무리"))) {
            var message=event(fixture.getValue(),false,Set.of(G),Set.of(A),List.of(),Set.of(),RecordingScope.STANDARD);
            captured(capture,message);
            var found=await(store.readRoom(scope,query(fixture.getKey()),new Budget(2,512),store.health().highWatermark(),Long.MAX_VALUE));
            check(found.candidates().size()==1&&found.candidates().getFirst().entry().messageId().equals(message.messageId()),
                    "keyword across a raw storage part boundary remains searchable");
            var projection=found.candidates().getFirst().entry();
            check(projection.excerpt()&&projection.text().contains(fixture.getKey())
                    &&projection.text().equals(new String(projection.text().getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8)),
                    "cross-part ASCII/Korean/supplementary-letter excerpt preserves valid Unicode and matched text");
        }
        String longest="q".repeat(4095)+"𠀀"+"r".repeat(4095);
        check(longest.length()==8192,"fixture uses full supported query length");
        var message=event("가".repeat(4095)+longest,false,Set.of(G),Set.of(A),List.of(),Set.of(),RecordingScope.STANDARD);
        captured(capture,message);
        var found=await(store.readRoom(scope,query(longest),new Budget(2,32768),store.health().highWatermark(),Long.MAX_VALUE));
        check(found.candidates().size()==1&&found.candidates().getFirst().entry().messageId().equals(message.messageId())
                &&found.candidates().getFirst().entry().text().equals(message.text()),"maximum-length keyword spans multiple adjacent raw parts without whole-archive aggregation");
    }
    private static void privatePageShapes(WorldRecordingService store, RoomRecordingCapture capture) throws Exception {
        // These are heard by G but remain private from the current player B, including their existence.
        for (int i=0;i<129;i++) captured(capture,event("hiddenonlymarker mixedmarker private "+i,false,
                Set.of(G),Set.of(A),List.of(),Set.of(),RecordingScope.STANDARD));
        // Newest visible rows make the first page identical with and without denied trailing hits.
        captured(capture,event("mixedmarker visible",false,Set.of(G),Set.of(A,B),List.of(),Set.of(),RecordingScope.STANDARD));
        captured(capture,event("visibleonlymarker visible",false,Set.of(G),Set.of(A,B),List.of(),Set.of(),RecordingScope.STANDARD));
        var currentAudience=scope(store,G,Set.of(G),Set.of(B),false,"STANDARD","PERSONAL");
        var hidden=pageShapes(store,currentAudience,"hiddenonlymarker",0);
        var missing=pageShapes(store,currentAudience,"nonexistentpagemarker",0);
        check(hidden.equals(missing),"129 denied-only hits and no hits expose identical page shape");
        var mixed=pageShapes(store,currentAudience,"mixedmarker",1);
        var visible=pageShapes(store,currentAudience,"visibleonlymarker",1);
        check(mixed.equals(visible),"one visible hit with 129 hidden trailing hits and one visible-only hit expose identical page shape");
    }
    private static List<PageShape> pageShapes(WorldRecordingService store,RecordedRoomSearch.Scope scope,String text,
            int firstEntries) throws Exception {
        var session=session(store,scope,new AtomicBoolean(true),new AtomicBoolean(true));
        var query=query(text);Optional<Cursor> cursor=Optional.empty();var shapes=new ArrayList<PageShape>();
        for(int pageNumber=1;pageNumber<=8;pageNumber++) {
            var page=await(session.query(query,cursor,new Budget(1,512)));
            check(session.current(page)&&page.status()==Status.PARTIAL,"every bounded page retains live session authority");
            check(page.entries().size()==(pageNumber==1?firstEntries:0),"hidden/no-hit continuation never returns private content or repeats visible row");
            check(page.next().isPresent()==(pageNumber<8),"opaque continuation exposes fixed eight-call budget, not result existence");
            shapes.add(new PageShape(page.status(),page.entries().size(),page.next().isPresent()));cursor=page.next();
        }
        check(await(session.query(query,Optional.empty(),new Budget(1,512))).status()==Status.UNAVAILABLE,
                "fixed continuation budget cannot be reset by starting over");
        return List.copyOf(shapes);
    }
    private static void byteBudgetPagination(WorldRecordingService store,RoomRecordingCapture capture,
            RecordedRoomSearch.Scope scope) throws Exception {
        var older=event("byteboundarymarker second row",false,Set.of(G),Set.of(A),List.of(),Set.of(),RecordingScope.STANDARD);
        captured(capture,older);
        var newest=event("byteboundarymarker "+"x".repeat(1024),false,Set.of(G),Set.of(A),List.of(),Set.of(),RecordingScope.STANDARD);
        captured(capture,newest);
        var session=session(store,scope,new AtomicBoolean(true),new AtomicBoolean(true));var query=query("byteboundarymarker");
        var first=await(session.query(query,Optional.empty(),new Budget(2,512)));
        check(first.entries().size()==1&&first.entries().getFirst().messageId().equals(newest.messageId())
                &&first.entries().getFirst().excerpt()&&first.entries().getFirst().text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length==512,
                "first row consumes byte budget before the row-count budget");
        check(first.next().isPresent(),"byte-budget page retains opaque continuation");
        var second=await(session.query(query,first.next(),new Budget(2,512)));
        check(second.entries().size()==1&&second.entries().getFirst().messageId().equals(older.messageId())
                &&second.entries().getFirst().text().equals(older.text()),"unreturned second row is not skipped by byte-budget cursor");
    }
    private record NativeMessage(ConversationEnvelope envelope,RawMessage raw) { }
    private static void nativeKnowledge(Path root) throws Exception {
        var store=open(root,RecordingSettings.Mode.SHADOW);
        var producer=store.registerProducer(RoomRecordingCapture.PRODUCER,Set.of("ROOM_PRIVATE"),Set.of());
        var original=nativeMessage(store,"nativepointer marker "+"가🌌".repeat(1600));
        var g=nativeReceipt(G,original.raw().body(),false);var h=nativeReceipt(H,original.raw().body(),false);
        var player=nativePlayerReceipt(original.raw().body());
        var stored=await(store.capture(producer,original.envelope(),original.raw(),List.of(player,g,h)));
        check(stored.status()==RecordingRecords.Status.STORED,"native source and actual complete God receipts commit");
        List<String> pointers;
        try(var db=diagnosticDb(root,store)) {
            pointers=nativePointers(db,original.raw().messageId());
            check(scalar(db,"SELECT count(*) FROM messages")==1&&scalar(db,"SELECT count(*) FROM source_refs")==1
                    &&pointers.size()==2,"one original/native source supports multiple independent God knowledge receipts");
            check(scalar(db,"SELECT count(*) FROM message_parts WHERE message_id=?",original.raw().messageId())==2
                    &&scalar(db,"SELECT count(*) FROM delivery_views WHERE message_id=?",original.raw().messageId())==1,
                    "God knowledge does not duplicate raw message parts or the shared delivery view");
            check(scalar(db,"SELECT count(*) FROM delivery_parts")==0
                    &&scalar(db,"SELECT count(*) FROM delivery_part_refs")==3
                    &&scalar(db,"SELECT count(*) FROM delivery_parts_resolved WHERE body=?",original.raw().body())==3,
                    "player and God receipts reference shared text; exact three delivery projections remain resolvable");
            for(String pointer:pointers) {
                var projection=JsonParser.parseString(pointer).getAsJsonObject();
                check(projection.keySet().equals(Set.of("messageId","deliveryReceiptId","viewHash","projectionKind"))
                        &&projection.get("messageId").getAsString().equals(original.raw().messageId().toString())
                        &&projection.get("viewHash").getAsString().equals(g.view().hash())
                        &&projection.get("projectionKind").getAsString().equals("DIRECT_HEARD_POINTER")
                        &&pointer.length()<512&&!pointer.contains("nativepointer marker"),"knowledge projection is an immutable bounded receipt pointer, not copied speech");
            }
            long sequence=stored.ingestSequence().orElseThrow();
            check(scalar(db,"SELECT count(*) FROM source_refs WHERE source_id=? AND kind='DIALOGUE_DIRECT' AND source_revision=1 AND ingest_sequence=?",
                    original.raw().messageId(),sequence)==1
                    &&scalar(db,"SELECT count(*) FROM work_items WHERE message_id=? AND kind='ROOM_KNOWLEDGE_CAPTURED' AND created_sequence=? AND source_ref IS NOT NULL AND receipt_id IS NOT NULL",
                    original.raw().messageId(),sequence)==2
                    &&scalar(db,"SELECT cursor FROM consumer_cursors WHERE consumer='room-knowledge' AND stream='archive-deliveries'")==sequence,
                    "native source, knowledge work and capture cursor use the same committed sequence");
        }
        check(await(store.capture(producer,original.envelope(),original.raw(),List.of(player,g,h))).status()==RecordingRecords.Status.DUPLICATE,
                "retry does not mint another source or God knowledge pointer");
        var changed=new DeliveryReceipt(g.receiptId(),g.recipient(),g.deliveryKind(),g.dispatchedAt(),g.audienceRevision(),
                DeliveryStatus.SERVER_DISPATCHED,new DeliveryView("changed view",List.of("changed view")),Set.of(0));
        check(await(store.recordDeliveries(producer,new DeliveryBatch(UUID.randomUUID(),original.raw().messageId(),"pointer-conflict",List.of(changed)))).status()==RecordingRecords.Status.CONFLICT,
                "existing actual receipt and its knowledge pointer cannot be overwritten");
        try(var db=diagnosticDb(root,store)) {check(nativePointers(db,original.raw().messageId()).equals(pointers)
                &&scalar(db,"SELECT count(*) FROM source_refs WHERE source_id=?",original.raw().messageId())==1,"conflicting retry preserves exact source and pointer identities");}

        var unknownEnvelope=nativeMessage(store,"unknownnative marker original raw");var unknownOriginal=unknownEnvelope.raw();
        var unknown=new RawMessage(unknownOriginal.messageId(),unknownOriginal.turnId(),unknownOriginal.turnSequence(),unknownOriginal.speaker(),
                unknownOriginal.body(),unknownOriginal.occurredAt(),unknownOriginal.kind(),unknownOriginal.occurrenceKey());
        var unknownReceipts=List.of(nativePlayerReceipt(unknown.body()),nativeReceipt(G,unknown.body(),false));
        check(await(store.capture(producer,unknownEnvelope.envelope(),unknown,unknownReceipts)).status()==RecordingRecords.Status.STORED,
                "old no-context raw remains archival data without invented known mode");
        try(var db=diagnosticDb(root,store)) {check(nativePointers(db,unknown.messageId()).isEmpty()
                &&scalar(db,"SELECT count(*) FROM message_contexts WHERE message_id=?",unknown.messageId())==0,
                "unknown native input is neither given knowledge nor silently rewritten with context");}

        var partial=nativeMessage(store,"partialknowmarker original full speech");
        check(await(store.capture(producer,partial.envelope(),partial.raw(),List.of(nativePlayerReceipt(partial.raw().body()),
                nativeReceipt(G,partial.raw().body(),true)))).status()==RecordingRecords.Status.STORED,"partial actual delivery may be archived without granting knowledge");
        var missing=nativeMessage(store,"missingknowmarker original full speech");
        var missingStored=await(store.capture(producer,missing.envelope(),missing.raw(),List.of(nativePlayerReceipt(missing.raw().body()))));
        check(missingStored.status()==RecordingRecords.Status.STORED,"planned God audience without actual God receipt still archives raw");
        try(var db=diagnosticDb(root,store)) {
            check(scalar(db,"SELECT count(*) FROM source_refs WHERE source_id IN (?,?)",partial.raw().messageId(),missing.raw().messageId())==0,
                    "partial or missing GAME_HEARD never creates native knowledge, despite fullAudience metadata");
        }
        var single=scope(store,G,Set.of(G),Set.of(A),false,"STANDARD","PERSONAL");
        check(read(store,single,"partialknowmarker").candidates().isEmpty()&&read(store,single,"missingknowmarker").candidates().isEmpty(),
                "archive and planned audience alone cannot be searched as God knowledge");
        var late=await(store.recordDeliveries(producer,new DeliveryBatch(UUID.randomUUID(),missing.raw().messageId(),"actual-late-heard",
                List.of(nativeReceipt(G,missing.raw().body(),false)))));
        check(late.status()==RecordingRecords.Status.STORED&&read(store,single,"missingknowmarker").candidates().size()==1,
                "committed late complete receipt enables only the actual receiving God");
        check(read(store,scope(store,H,Set.of(H),Set.of(A),false,"STANDARD","PERSONAL"),"missingknowmarker").candidates().isEmpty(),
                "another God in planned fullAudience does not inherit late knowledge");
        check(await(store.readRoom(single,query("missingknowmarker"),new Budget(2,512),missingStored.ingestSequence().orElseThrow(),Long.MAX_VALUE)).candidates().isEmpty(),
                "session watermark before late knowledge commit cannot use the later receipt");
        try(var db=diagnosticDb(root,store)) {check(nativePointers(db,missing.raw().messageId()).size()==1
                &&scalar(db,"SELECT count(*) FROM work_items WHERE message_id=? AND kind='ROOM_KNOWLEDGE_CAPTURED' AND created_sequence=?",
                missing.raw().messageId(),late.ingestSequence().orElseThrow())==1,"late delivery commits one native pointer and matching work atomically");}
        await(store.closeAsync());
        var reopened=open(root,RecordingSettings.Mode.SHADOW);
        try(var db=diagnosticDb(root,reopened)) {check(RecordingSchema.version(db)==RecordingSchema.VERSION
                &&nativePointers(db,original.raw().messageId()).equals(pointers)
                &&scalar(db,"SELECT count(*) FROM source_refs WHERE source_id=?",original.raw().messageId())==1,"schema reopen retains one native source and exact immutable knowledge pointers");}
        check(read(reopened,scope(reopened,G,Set.of(G,H),Set.of(A),false,"STANDARD","PERSONAL"),"nativepointer").candidates().size()==1,
                "reopened native knowledge still requires and accepts both actual God receipts");
        check(read(reopened,scope(reopened,G,Set.of(G),Set.of(A),false,"STANDARD","PERSONAL"),"partialknowmarker").candidates().isEmpty(),
                "restart cannot promote partial receipt to knowledge");
        var freshProducer=reopened.registerProducer(RoomRecordingCapture.PRODUCER,Set.of("ROOM_PRIVATE"),Set.of(SourceKind.DIALOGUE_DIRECT));
        check(await(reopened.capture(freshProducer,unknownEnvelope.envelope(),unknown,unknownReceipts)).status()==RecordingRecords.Status.DUPLICATE
                &&read(reopened,scope(reopened,G,Set.of(G),Set.of(A),false,"STANDARD","PERSONAL"),"unknownnative").candidates().isEmpty(),
                "restart raw retry remains duplicate without importing unknown-context speech as knowledge");
        check(await(reopened.capture(freshProducer,original.envelope(),original.raw(),List.of(player,g,h))).status()==RecordingRecords.Status.UNAVAILABLE,
                "restart cannot replay a stale publication epoch to mint fresh contextual authority");
        var lateAfterRestart=new DeliveryBatch(UUID.randomUUID(),missing.raw().messageId(),"second-god-after-restart",
                List.of(nativeReceipt(H,missing.raw().body(),false)));
        check(await(reopened.recordDeliveries(freshProducer,lateAfterRestart)).status()==RecordingRecords.Status.STORED
                &&await(reopened.recordDeliveries(freshProducer,lateAfterRestart)).status()==RecordingRecords.Status.DUPLICATE,
                "reopened canonical context permits an actual second God receipt and idempotent batch retry");
        try(var db=diagnosticDb(root,reopened)) {check(scalar(db,"SELECT count(*) FROM messages")==4
                &&scalar(db,"SELECT count(*) FROM source_refs WHERE source_id=?",missing.raw().messageId())==1
                &&nativePointers(db,missing.raw().messageId()).size()==2&&nativePointers(db,unknown.messageId()).isEmpty(),
                "restart late receipt reuses the same source instead of copying raw or importing unknown knowledge");}
        check(read(reopened,scope(reopened,G,Set.of(G,H),Set.of(A),false,"STANDARD","PERSONAL"),"missingknowmarker").candidates().size()==1,
                "both actual receipts remain searchable after restart without replacing original context");
        var gScope=scope(reopened,G,Set.of(G),Set.of(A),false,"STANDARD","PERSONAL");
        var hScope=scope(reopened,H,Set.of(H),Set.of(A),false,"STANDARD","PERSONAL");
        var issued=session(reopened,gScope,new AtomicBoolean(true),new AtomicBoolean(true));
        var page=await(issued.query(query("missingknowmarker"),Optional.empty(),new Budget(1,512)));
        check(page.entries().size()==1&&issued.current(page),"native page valid before scoped receipt revocation");
        SourceRef revokedSource; UUID revokedReceipt;
        try(var db=diagnosticDb(root,reopened);var query=db.prepareStatement("SELECT s.source_hash,k.id FROM source_refs s JOIN knowledge_receipts k ON k.source_ref=s.id WHERE s.source_id=? AND k.god_id=?")) {
            query.setString(1,missing.raw().messageId().toString());query.setString(2,G);
            try(var rows=query.executeQuery()) {
                check(rows.next(),"native receipt selected for exact game revocation");
                revokedSource=new SourceRef(WORLD,reopened.datasetId().orElseThrow(),SourceKind.DIALOGUE_DIRECT,RoomRecordingCapture.PRODUCER,
                        missing.raw().messageId().toString(),1,rows.getString(1));
                revokedReceipt=UUID.fromString(rows.getString(2));
            }
        }
        var revoke=reopened.invalidateKnowledge(freshProducer,new KnowledgeInvalidation(revokedSource,revokedReceipt,1,"GAME_PROOF_REVOKED"));
        check(!issued.current(page),"accepted knowledge revocation immediately cancels the already-issued read page");
        check(await(revoke).status()==RecordingRecords.Status.STORED,"native receipt tombstone is durable");
        check(read(reopened,gScope,"missingknowmarker").candidates().isEmpty()
                        &&read(reopened,hScope,"missingknowmarker").candidates().size()==1,
                "one God receipt revocation does not erase another God's native knowledge");
        check(read(reopened,scope(reopened,H,Set.of(G,H),Set.of(A),false,"STANDARD","PERSONAL"),"missingknowmarker").candidates().isEmpty(),
                "shared audience cannot launder a revoked God receipt through another God");
        await(reopened.closeAsync());
    }
    private static NativeMessage nativeMessage(WorldRecordingService store,String text) {
        return nativeMessage(store,text,DATE);
    }
    private static NativeMessage nativeMessage(WorldRecordingService store,String text,Instant occurred) {
        var actors=Set.of(new ActorRef(ActorKind.PLAYER,A.toString()),new ActorRef(ActorKind.GOD,G),new ActorRef(ActorKind.GOD,H));
        var context=new PublicationContext(store.runtimeEpoch(),1,"STANDARD",actors,actors,Map.of(A.toString(),"player"),
                List.of(),Set.of(),Map.of(),"UNKNOWN_ORIGINAL_GAME_TIME","PERSONAL");
        var envelope=new ConversationEnvelope(WORLD,store.datasetId().orElseThrow(),UUID.randomUUID(),"ROOM_PRIVATE",
                "ACTUAL_LISTENERS_ONLY",1,1,true,false,RoomRecordingCapture.PRODUCER);
        UUID id=UUID.randomUUID();
        return new NativeMessage(envelope,new RawMessage(id,Optional.empty(),0,new ActorRef(ActorKind.PLAYER,A.toString()),text,
                occurred,MessageKind.ACCEPTED_INPUT,id.toString(),context));
    }
    private static DeliveryReceipt nativeReceipt(String god,String text,boolean partial) {
        var parts=partial?List.of(text.substring(0,text.length()/2),text.substring(text.length()/2)):List.of(text);
        return new DeliveryReceipt(UUID.randomUUID(),new ActorRef(ActorKind.GOD,god),"GAME_HEARD",DATE,1,
                partial?DeliveryStatus.PARTIAL_DISPATCH:DeliveryStatus.SERVER_DISPATCHED,new DeliveryView(text,parts),Set.of(0));
    }
    private static DeliveryReceipt nativePlayerReceipt(String text) {
        return new DeliveryReceipt(UUID.randomUUID(),new ActorRef(ActorKind.PLAYER,A.toString()),"CHAT",DATE,1,
                DeliveryStatus.SERVER_DISPATCHED,new DeliveryView(text,List.of(text)),Set.of(0));
    }
    private static Connection diagnosticDb(Path root,WorldRecordingService store) throws Exception {
        var properties=new Properties();properties.setProperty("open_mode","1");
        var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("mythictrpg-recording-v2").resolve(store.datasetId().orElseThrow().toString()).resolve("recording.sqlite"),properties);
        try(var statement=db.createStatement()){statement.execute("PRAGMA query_only=ON");}return db;
    }
    private static List<String> nativePointers(Connection db,UUID message) throws Exception {
        var result=new ArrayList<String>();
        try(var statement=db.prepareStatement("SELECT k.projection FROM knowledge_receipts k JOIN source_refs s ON s.id=k.source_ref WHERE s.source_id=? ORDER BY k.god_id")) {
            statement.setString(1,message.toString());try(var rows=statement.executeQuery()){while(rows.next())result.add(rows.getString(1));}
        }
        return List.copyOf(result);
    }
    private static long scalar(Connection db,String sql,Object... parameters) throws Exception {
        try(var statement=db.prepareStatement(sql)){for(int i=0;i<parameters.length;i++)statement.setString(i+1,parameters[i].toString());
            try(var rows=statement.executeQuery()){check(rows.next(),"diagnostic SQL returned a row");return rows.getLong(1);}}
    }
    private static RecordedMemoryAccess.Session session(WorldRecordingService store,RecordedRoomSearch.Scope scope,AtomicBoolean live,AtomicBoolean proof) {
        return new RecordedMemoryAccess.Session(store,scope,()->true,Runnable::run,live::get,
                refs->CompletableFuture.completedFuture(refs.isEmpty()||proof.get()),refs->refs.isEmpty()||proof.get());
    }
    private static Query query(String text){return new Query(text,Optional.empty(),Optional.empty());}
    private static RecordedRoomSearch.Result read(WorldRecordingService s,RecordedRoomSearch.Scope scope,String text)throws Exception{return await(s.readRoom(scope,query(text),new Budget(8,8192),s.health().highWatermark(),Long.MAX_VALUE));}
    private static RecordedRoomSearch.Scope scope(WorldRecordingService s,String god,Set<String> gods,Set<UUID> players,boolean pub,String policy,String mode){
        var audience=new HashSet<ActorRef>();gods.forEach(g->audience.add(new ActorRef(ActorKind.GOD,g)));players.forEach(p->audience.add(new ActorRef(ActorKind.PLAYER,p.toString())));
        return new RecordedRoomSearch.Scope(s.datasetId().orElseThrow(),god,audience,pub,policy,mode);
    }
    private static RoomDialogueEvent event(String text,boolean pub,Set<String> gods,Set<UUID> players,List<RoomEvidenceReference> refs,Set<UUID> parents,RecordingScope recording){
        var names=new HashMap<UUID,String>();var deliveries=new HashMap<UUID,RoomDialogueEvent.Delivery>();
        for(var p:players){names.put(p,"player");deliveries.put(p,new RoomDialogueEvent.Delivery("player",true,0,Optional.of(new RoomDialogueEvent.DispatchView(text,List.of(text),Set.of(0),Map.of())),Optional.empty()));}
        return new RoomDialogueEvent(UUID.randomUUID(),UUID.randomUUID(),1,Optional.empty(),pub?RoomType.PUBLIC_MOBILE:RoomType.PRIVATE,
                recording,"PLAYER",A.toString(),text,gods,names,deliveries,DATE.toEpochMilli()).withWorld(WORLD).withHeardGods(gods).withEvidence(refs,parents);
    }
    private static void captured(RoomRecordingCapture c,RoomDialogueEvent e)throws Exception{check(await(c.capture(e,true)).complete(),"capture committed");}
    private static WorldRecordingService open(Path root,RecordingSettings.Mode mode)throws Exception{
        Files.createDirectories(root);var s=await(WorldRecordingService.open(root,WORLD,new RecordingSettings(mode,256_000_000,2_000_000,.9,.95),new WorldRecordingService.CutoverBoundary("read-fixture",Map.of())));
        check(s.health().state()==WorldRecordingService.State.READY,"store ready");return s;
    }
    private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(15,TimeUnit.SECONDS);}
    private static void check(boolean okay,String message){checks++;if(!okay)throw new AssertionError(message);}
}
