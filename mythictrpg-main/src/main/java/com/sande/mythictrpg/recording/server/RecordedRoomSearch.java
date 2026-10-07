package com.sande.mythictrpg.recording.server;

import com.google.gson.*;
import com.sande.mythictrpg.ai.api.RoomEvidenceReference;
import com.sande.mythictrpg.recording.api.MemoryReadSession.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import com.sande.mythictrpg.recording.api.NativeMemoryEvidence;
import com.sande.mythictrpg.recording.api.NativeInterpretationEvidence;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Internal bounded SQL reader. Archive presence alone never establishes God knowledge. */
final class RecordedRoomSearch {
    record Scope(UUID dataset, String speaker, Set<ActorRef> audience, boolean publicRoom, String recordingPolicy, String memoryMode) {
        Scope { audience = Set.copyOf(audience); new ActorRef(ActorKind.GOD, speaker); }
    }
    record Candidate(Entry entry, List<RoomEvidenceReference> evidence, boolean requiresProjection) {
        Candidate(Entry entry,List<RoomEvidenceReference> evidence){this(entry,evidence,false);}
    }
    /** Internal positions never escape as model-controlled cursor values. */
    record SearchPosition(long indexedBefore, long rawBefore, boolean indexedDone, boolean rawDone,
                          boolean indexedNext, boolean indexedAllowed) {
        static SearchPosition initial() { return new SearchPosition(Long.MAX_VALUE,Long.MAX_VALUE,false,false,true,true); }
        static SearchPosition rawOnly(long before) { return new SearchPosition(0,before,true,before==0,false,false); }
    }
    record Result(List<Candidate> candidates, SearchPosition position, boolean more) {
        long beforeSequence() { return position.rawBefore(); } // Legacy bounded raw-reader tests.
    }
    private record SourceId(UUID id, long sequence, Instant occurred, boolean indexedAtWatermark,ActorRef actor) { }
    private static final class Scan {
        long before; boolean done; List<SourceId> sources; int offset;
        Scan(long before, boolean done) { this.before=before; this.done=done; }
        boolean windowSpent() { return sources!=null && offset>=Math.min(128,sources.size()); }
        void advance(SourceId source) { before=source.sequence(); offset++; if(offset==sources.size())done=true; }
    }
    record Node(UUID id, long sequence, ActorRef speaker, Instant occurred, String body, long bodyBytes,
                        Set<UUID> parents, List<RoomEvidenceReference> evidence) { }
    record SpeechClosure(Map<UUID,Node> nodes,List<RoomEvidenceReference> ownerReferences,boolean requiresProjection) {
        SpeechClosure {nodes=Map.copyOf(nodes);ownerReferences=List.copyOf(ownerReferences);}
    }
    static final class RequestLimit extends Exception { }
    static final class SourceLimit extends Exception { }
    private final Connection db;
    private final Scope scope;
    private final long watermark, deadline;
    private final Map<UUID, Optional<Node>> nodes = new HashMap<>();
    private final Map<UUID,List<RecordedNativeEvidenceStore.Stored>> nativeEvidence=new HashMap<>();
    private final Map<UUID,List<RecordedNativeInterpretationStore.Stored>> interpretationEvidence=new HashMap<>();
    private final Set<UUID> attemptNodes = new HashSet<>();
    private long bytesRead;
    private long attemptBytes;
    RecordedRoomSearch(Connection db, Scope scope, long watermark, long deadline) {
        this.db = db; this.scope = scope; this.watermark = watermark; this.deadline = deadline;
    }
    /** Reuse the raw authority and native DAG checks; interpretations cannot relax these gates. */
    Optional<Node> nativeSource(UUID id) throws Exception {
        attemptNodes.clear(); attemptBytes=0;
        var source=node(id); if(source.isEmpty())return Optional.empty();
        var refs=new HashSet<RoomEvidenceReference>();
        var done=new HashSet<UUID>();
        // Native-only semantic/background consumers do not yet carry projection-dependent proof guards.
        if(!ancestry(source.orElseThrow(),new HashSet<>(),done,refs,0)||!refs.isEmpty()||requiresProjection(done))return Optional.empty();
        return source;
    }
    /** All extraction inputs and their required ancestry must already be recorded dependencies. */
    Optional<Map<UUID,Node>> nativeSources(Set<UUID> ids) throws Exception {
        if(ids.isEmpty()||ids.size()>6)return Optional.empty();
        attemptNodes.clear(); attemptBytes=0;
        var result=new HashMap<UUID,Node>();var done=new HashSet<UUID>();var refs=new HashSet<RoomEvidenceReference>();
        for(UUID id:ids){var source=node(id);if(source.isEmpty())return Optional.empty();
            if(!ancestry(source.orElseThrow(),new HashSet<>(),done,refs,0))return Optional.empty();result.put(id,source.orElseThrow());}
        return refs.isEmpty()&&!requiresProjection(done)&&ids.containsAll(done)?Optional.of(Map.copyOf(result)):Optional.empty();
    }
    /** Seal issuance owns the complete native closure, not just roots selected by the caller. */
    Optional<Map<UUID,Node>> nativeClosure(Set<UUID> roots)throws Exception {
        if(roots.isEmpty()||roots.size()>com.sande.mythictrpg.recording.api.NativeMemoryEvidence.MAX_ROOTS)return Optional.empty();
        attemptNodes.clear();attemptBytes=0;var done=new HashSet<UUID>();var refs=new HashSet<RoomEvidenceReference>();
        for(var id:roots){var source=node(id);if(source.isEmpty()||!ancestry(source.orElseThrow(),new HashSet<>(),done,refs,0))return Optional.empty();}
        if(!refs.isEmpty()||requiresProjection(done)||done.size()>com.sande.mythictrpg.recording.api.NativeMemoryEvidence.MAX_DEPENDENCIES)return Optional.empty();
        return cachedClosure(roots);
    }
    /** Foreground seal only. Content ownership is checked later on the game dispatcher, never by this SQL reader. */
    Optional<SpeechClosure> contentAwareClosure(Set<UUID> roots)throws Exception {
        if(roots.isEmpty()||roots.size()>com.sande.mythictrpg.recording.api.NativeMemoryEvidence.MAX_ROOTS)return Optional.empty();
        attemptNodes.clear();attemptBytes=0;var done=new HashSet<UUID>();var refs=new HashSet<RoomEvidenceReference>();
        for(var id:roots){var source=node(id);if(source.isEmpty()||!ancestry(source.orElseThrow(),new HashSet<>(),done,refs,0))return Optional.empty();}
        if(done.size()>com.sande.mythictrpg.recording.api.NativeMemoryEvidence.MAX_DEPENDENCIES||!contentLeaves(refs))return Optional.empty();
        var closure=cachedClosure(roots);return closure.map(nodes->new SpeechClosure(nodes,refs.stream().sorted(Comparator.comparing(RoomEvidenceReference::kind).thenComparing(RoomEvidenceReference::payload)).toList(),requiresProjection(nodes.keySet())));
    }
    private boolean requiresProjection(Collection<UUID> ids){return ids.stream().anyMatch(id->!interpretationEvidence.getOrDefault(id,List.of()).isEmpty());}
    static boolean nativeProof(RoomEvidenceReference ref){return NativeMemoryEvidence.KIND.equals(ref.kind())||NativeInterpretationEvidence.KIND.equals(ref.kind());}
    static boolean contentLeaves(Collection<RoomEvidenceReference> refs){
        if(refs.size()>64)return false;long bytes=0;
        for(var ref:refs){int limit=switch(ref.kind()){
            case "CONTENT_DISCLOSURE_V1"->16384;
            // Only static authored quest prose. Current assignment/completion/reward authority is not a leaf permission.
            case "QUEST_CONTENT_DISCLOSURE_V1"->4096;
            // Historical dialogue only. The live Story owner still checks fact/cover/definition/event provenance.
            // A permitted Hook description is not an executable Hook token or a newly resolved event.
            case "STORY_DISCLOSURE_V1"->16384;
            default->0;
        };int size=utf8(ref.payload());if(limit==0||size>limit)return false;bytes+=size;if(bytes>65536)return false;}
        return true;
    }
    private Optional<Map<UUID,Node>> cachedClosure(Set<UUID> roots){
        var pending=new ArrayDeque<>(roots);var found=new LinkedHashMap<UUID,Node>();
        while(!pending.isEmpty()){
            var id=pending.removeFirst();if(found.containsKey(id))continue;
            var value=nodes.get(id);if(value==null||value.isEmpty()||found.size()>=com.sande.mythictrpg.recording.api.NativeMemoryEvidence.MAX_DEPENDENCIES)return Optional.empty();
            found.put(id,value.orElseThrow());pending.addAll(value.orElseThrow().parents());
        }
        return Optional.of(Map.copyOf(found));
    }

    static Result query(Connection db, Scope scope, Query query, Budget budget, long watermark, long before) throws Exception {
        return query(db, scope, query, budget, watermark, SearchPosition.rawOnly(before));
    }
    static Result query(Connection db, Scope scope, Query query, Budget budget, long watermark, SearchPosition position) throws Exception {
        return query(db, scope, query, budget, watermark, position, System.nanoTime() + 250_000_000L);
    }
    /** Package-private deadline seam also exercises real SQL interruption without slowing the server. */
    static Result query(Connection db, Scope scope, Query query, Budget budget, long watermark, long before, long deadline) throws Exception {
        return query(db, scope, query, budget, watermark, SearchPosition.rawOnly(before), deadline);
    }
    private static Result query(Connection db, Scope scope, Query query, Budget budget, long watermark, SearchPosition position, long deadline) throws Exception {
        var reader = new RecordedRoomSearch(db, scope, watermark, deadline);
        try (var ignored = new SqlReadBudget(db, reader.deadline)) { return reader.search(query, budget, position); }
    }
    private Result search(Query query, Budget budget, SearchPosition position) throws Exception {
        var tokens = terms(query.text());
        // OR queries containing even one short term need raw matching for ALL messages. Skipping
        // indexed originals there would lose their one/two-character hits.
        boolean indexed = position.indexedAllowed() && !tokens.isEmpty()
                && tokens.stream().allMatch(t->t.codePointCount(0,t.length())>=3 && t.indexOf('\0')<0);
        var indexScan = new Scan(position.indexedBefore(),position.indexedDone() || !indexed);
        var rawScan = new Scan(position.rawBefore(),position.rawDone());
        boolean indexedNext=position.indexedNext();
        if (expired()) return new Result(List.of(),position,true);
        var selected = new ArrayList<Candidate>(); int used = 0, scanned = 0;
        boolean limited = false;
        while (!indexScan.done || !rawScan.done) {
            if(scanned>=128 || expired() || selected.size()>=budget.rows() || budget.utf8Bytes()-used<256) { limited=true; break; }
            boolean useIndex = !indexScan.done && !indexScan.windowSpent()
                    && (indexedNext || rawScan.done || rawScan.windowSpent());
            var scan = useIndex ? indexScan : rawScan;
            if(scan.done || scan.windowSpent())break;
            // Alternate lanes, including across byte/row-limited pages. A deep indexed hit cannot
            // be crowded out by recent unindexed raw rows; raw fallback also gets its next turn.
            indexedNext=!useIndex;
            long previousBefore=scan.before; boolean previousDone=scan.done;
            boolean attemptedSource=false;
            try {
                if(scan.sources==null)scan.sources=sources(useIndex,indexed,scan.before,tokens);
                if(scan.sources.isEmpty()) { scan.done=true; continue; }
                var id=scan.sources.get(scan.offset); scan.advance(id); scanned++;
                attemptedSource=true;
                // Membership is fixed to this read session's archive watermark. Backfill committed
                // later cannot move a row out of its raw lane into the indexed lane mid-pagination.
                if(id.id()==null || indexed && id.indexedAtWatermark()!=useIndex||!query.actorSelection().matches(id.actor()))continue;
                if(query.fromInclusive().filter(t->id.occurred().isBefore(t)).isPresent()
                        || query.untilExclusive().filter(t->!id.occurred().isBefore(t)).isPresent())continue;
                attemptNodes.clear(); attemptBytes=0;
                var node = node(id.id()); if (node.isEmpty()) continue;
                if(!node.get().speaker().equals(id.actor())||!query.actorSelection().matches(node.get().speaker()))continue;
                String text = node.get().body();
                String folded = RecordingLexicalIndex.normalize(text);
                int match = tokens.isEmpty() ? 0 : tokens.stream().mapToInt(folded::indexOf).filter(i -> i >= 0).min().orElse(-1);
                if (match < 0) continue;
                var references = new LinkedHashSet<RoomEvidenceReference>();
                var ancestryNodes=new HashSet<UUID>();
                if (!ancestry(node.get(), new HashSet<>(), ancestryNodes, references, 0)) continue;
                boolean excerpt = false; int remaining = budget.utf8Bytes() - used;
                if (utf8(text) > remaining) {
                    text = excerpt(text, originalOffset(text, folded, match), remaining); excerpt = true;
                }
                if (expired()) throw new RequestLimit();
                used += utf8(text);
                selected.add(new Candidate(new Entry(node.get().id(), node.get().speaker(), node.get().occurred(), text, excerpt), List.copyOf(references),requiresProjection(ancestryNodes)));
            } catch (SourceLimit unsupported) {
                // One source's closure cannot fit even in a fresh request. Skip that source, not
                // the rest of history; coverage remains partial and RAW is never changed.
                limited=true;
            } catch (RequestLimit exhausted) {
                // Prior candidates consumed a shared budget. The current source has NOT been
                // evaluated; retain its position so a fresh page can read it in full.
                scan.before=previousBefore; scan.done=previousDone; indexedNext=useIndex;
                limited=true; break;
            } catch (SQLException failure) {
                if (!budgetInterrupt(failure)) throw failure;
                scan.before=previousBefore; scan.done=previousDone;
                if(attemptedSource)indexedNext=useIndex;
                limited = true; break;
            }
        }
        var next = new SearchPosition(indexScan.before,rawScan.before,indexScan.done,rawScan.done,indexedNext,indexed);
        return new Result(List.copyOf(selected),next,limited || expired() || !indexScan.done || !rawScan.done);
    }
    private List<SourceId> sources(boolean indexedLane, boolean partitioned, long before, List<String> tokens) throws SQLException {
        // LIMIT is inside the materialized candidate window, BEFORE manifest/policy predicates.
        // Otherwise post-watermark indexing or denied audiences could cause an unbounded scan.
        String valid = "CASE WHEN l.dataset_id=m.dataset_id AND l.dataset_id=? AND l.message_id=m.id"
                + " AND l.body_hash=m.body_hash AND l.normalization_version=? AND l.indexed_sequence<=? THEN 1 ELSE 0 END";
        String sql;
        if(indexedLane) {
            sql="WITH hits AS MATERIALIZED (SELECT rowid FROM recording_lexical_fts WHERE recording_lexical_fts MATCH ?"
                    +" AND rowid<=? AND rowid<? ORDER BY rowid DESC LIMIT 129)"
                    +" SELECT h.rowid,m.id,m.occurred_utc,"+valid+",m.actor_kind,m.actor_id FROM hits h"
                    +" LEFT JOIN messages m ON m.ingest_sequence=h.rowid"
                    +" LEFT JOIN recording_lexical_manifest l ON l.message_sequence=h.rowid ORDER BY h.rowid DESC";
        } else {
            sql="WITH hits AS MATERIALIZED (SELECT * FROM messages WHERE ingest_sequence<=? AND ingest_sequence<?"
                    +" ORDER BY ingest_sequence DESC LIMIT 129) SELECT m.ingest_sequence,m.id,m.occurred_utc,"
                    +(partitioned?valid:"0")+",m.actor_kind,m.actor_id FROM hits m"
                    +(partitioned?" LEFT JOIN recording_lexical_manifest l ON l.message_sequence=m.ingest_sequence":"")
                    +" ORDER BY m.ingest_sequence DESC";
        }
        var result=new ArrayList<SourceId>();
        try(var statement=db.prepareStatement(sql)) {
            statement.setQueryTimeout(1); int parameter=1;
            if(indexedLane)statement.setString(parameter++,matchExpression(tokens));
            statement.setLong(parameter++,watermark); statement.setLong(parameter++,before);
            if(partitioned) {
                statement.setString(parameter++,scope.dataset().toString());
                statement.setString(parameter++,RecordingLexicalIndex.NORMALIZATION_VERSION); statement.setLong(parameter,watermark);
            }
            try(var rows=statement.executeQuery()) { while(rows.next()) {
                String id=rows.getString(2);
                ActorRef actor=null;
                try{if(id!=null)actor=new ActorRef(ActorKind.valueOf(rows.getString(5)),rows.getString(6));}catch(RuntimeException malformed){/* Not an admissible speaker. */}
                result.add(new SourceId(id==null?null:UUID.fromString(id),rows.getLong(1),id==null?Instant.EPOCH:Instant.parse(rows.getString(3)),rows.getInt(4)==1,actor));
            } }
        }
        return List.copyOf(result);
    }
    private static String matchExpression(List<String> tokens) {
        var alternatives=new ArrayList<String>();
        for(String term:tokens) {
            int[] points=term.codePoints().toArray();
            if(points.length<=256)alternatives.add(quoted(term));
            else {
                // An 8192-character repeated phrase must not cause thousands of positional joins
                // inside FTS. These grams are a bounded SUPERSET filter; full raw matching below
                // still checks the complete original term and never accepts a gram-only match.
                var grams=new LinkedHashSet<String>();
                for(int start:new int[]{0,(points.length-3)/2,points.length-3})grams.add(quoted(new String(points,start,3)));
                alternatives.add("("+String.join(" AND ",grams)+")");
            }
        }
        return String.join(" OR ",alternatives);
    }
    private static String quoted(String text) { return "\""+text.replace("\"","\"\"")+"\""; }
    private static List<String> terms(String text) {
        // Single Unicode characters are meaningful (for example 신). Symbols-only input must be
        // literal search, not an empty keyword list that silently turns into a recent-history query.
        String folded = RecordingLexicalIndex.normalize(text.strip());
        if(!folded.isEmpty() && folded.codePoints().noneMatch(cp->Character.isLetterOrDigit(cp)
                || Character.getType(cp)==Character.NON_SPACING_MARK || Character.getType(cp)==Character.COMBINING_SPACING_MARK))return List.of(folded);
        var words = Arrays.stream(folded.split("[^\\p{L}\\p{M}\\p{N}_:-]+"))
                .filter(s -> !s.isEmpty()).distinct().limit(8).toList();
        return words.isEmpty() && !folded.isEmpty() ? List.of(folded) : words;
    }
    private boolean budgetInterrupt(SQLException failure) { return failure.getErrorCode() == 9 && expired(); }
    private static int originalOffset(String original, String folded, int offset) {
        if (original.length() == folded.length()) return offset;
        // ROOT lowercase can expand a character (İ -> i + combining dot). Folded indices are not
        // raw UTF-16 offsets in that case, so map the prefix without splitting supplementary chars.
        int raw = 0, lowered = 0;
        while (raw < original.length() && lowered < offset) {
            int end = raw + Character.charCount(original.codePointAt(raw));
            int width = original.substring(raw, end).toLowerCase(Locale.ROOT).length();
            if (lowered + width > offset) break;
            lowered += width; raw = end;
        }
        return raw;
    }
    private boolean ancestry(Node node, Set<UUID> path, Set<UUID> done, Set<RoomEvidenceReference> refs, int depth) throws Exception {
        if (done.contains(node.id())) return true;
        if (expired()) throw new RequestLimit();
        if (depth >= 32 || done.size() + path.size() >= 256 || !path.add(node.id())) return false;
        node.evidence().stream().filter(ref->!nativeProof(ref)).forEach(refs::add);
        if (refs.size() > 64) return false;
        for (UUID parentId : node.parents()) {
            var parent = node(parentId);
            if (parent.isEmpty() || parent.get().sequence() >= node.sequence() || !ancestry(parent.get(), path, done, refs, depth + 1)) return false;
        }
        for(var stored:nativeEvidence.getOrDefault(node.id(),List.of())){
            var closure=cachedClosure(stored.manifest().roots());
            if(closure.isEmpty()||!RecordedNativeEvidenceStore.matches(db,scope,stored.manifest(),closure.orElseThrow(),deadline))return false;
        }
        for(var stored:interpretationEvidence.getOrDefault(node.id(),List.of())){
            var closure=cachedClosure(stored.manifest().sources().roots());
            if(closure.isEmpty()||!RecordedNativeInterpretationStore.matches(db,scope,stored.manifest(),closure.orElseThrow(),deadline))return false;
        }
        path.remove(node.id()); done.add(node.id()); return true;
    }
    private Optional<Node> node(UUID id) throws Exception {
        boolean first=attemptNodes.add(id);
        if(attemptNodes.size()>256)throw new SourceLimit();
        if (nodes.containsKey(id)) {
            var cached=nodes.get(id);
            if(first && cached.isPresent())countSourceBytes(cached.orElseThrow().bodyBytes());
            return cached;
        }
        if (expired() || nodes.size() >= 256) throw new RequestLimit();
        Optional<Node> result = readNode(id); nodes.put(id, result); return result;
    }
    private void countSourceBytes(long bytes) throws SourceLimit {
        attemptBytes+=bytes;if(attemptBytes>2_097_152)throw new SourceLimit();
    }
    private Optional<Node> readNode(UUID id) throws Exception {
        String sql = "SELECT m.ingest_sequence,m.actor_kind,m.actor_id,m.occurred_utc,m.body_hash,m.body_bytes,c.channel,c.policy,x.context_json"
                + " FROM messages m JOIN conversations c ON c.id=m.conversation_id JOIN message_contexts x ON x.message_id=m.id"
                + " WHERE m.id=? AND m.dataset_id=? AND m.producer='room-publication-v2' AND m.ingest_sequence<=?"
                + " AND length(x.context_json)<=262144"
                + " AND EXISTS(SELECT 1 FROM deliveries d WHERE d.message_id=m.id AND d.actor_kind='GOD' AND d.actor_id=? AND d.kind='GAME_HEARD' AND d.status='SERVER_DISPATCHED')";
        long sequence, size; ActorRef actor; Instant occurred; String hash; JsonObject context;boolean privateSource;
        try (var statement = db.prepareStatement(sql)) {
            statement.setQueryTimeout(1); statement.setString(1, id.toString()); statement.setString(2, scope.dataset().toString()); statement.setLong(3, watermark);
            statement.setString(4, scope.speaker());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                sequence = rows.getLong(1); actor = new ActorRef(ActorKind.valueOf(rows.getString(2)), rows.getString(3)); occurred = Instant.parse(rows.getString(4));
                hash = rows.getString(5); size = rows.getLong(6); context = JsonParser.parseString(rows.getString(9)).getAsJsonObject();
                boolean publicSource = rows.getString(7).equals("ROOM_PUBLIC") && rows.getString(8).equals("PUBLIC_SPEECH");
                privateSource = rows.getString(7).equals("ROOM_PRIVATE") && rows.getString(8).equals("ACTUAL_LISTENERS_ONLY");
                if (!publicSource && !privateSource || scope.publicRoom() && !publicSource
                        || !scope.recordingPolicy().equals(context.get("recordingPolicy").getAsString())
                        || !context.has("memoryMode") || !scope.memoryMode().equals(context.get("memoryMode").getAsString())) return Optional.empty();
                var full = strings(context.getAsJsonArray("fullAudience"));
                for (var recipient : scope.audience()) if ((recipient.kind() == ActorKind.GOD || !publicSource) && !full.contains(recipient.key())) return Optional.empty();
            }
        }
        // Require the actual committed receipt, not merely the planned fullAudience metadata.
        for (var recipient : scope.audience()) {
            if (recipient.kind() != ActorKind.GOD && scope.publicRoom()) continue;
            // Private-source PLAYER scope also requires a durable complete receipt. A follow-up batch may have failed.
            if (recipient.kind() == ActorKind.PLAYER && !strings(context.getAsJsonArray("fullAudience")).contains(recipient.key())) continue;
            try (var statement = db.prepareStatement("SELECT 1 FROM deliveries WHERE message_id=? AND actor_kind=? AND actor_id=? AND status='SERVER_DISPATCHED'"
                    + (recipient.kind() == ActorKind.GOD ? " AND kind='GAME_HEARD'" : "")
                    + (recipient.kind()==ActorKind.PLAYER&&privateSource ? " AND EXISTS(SELECT 1 FROM work_items w WHERE w.dataset_id=deliveries.dataset_id AND w.message_id=deliveries.message_id"
                        +" AND w.receipt_id=deliveries.id AND w.kind='DELIVERY_CAPTURED' AND w.source_version=deliveries.id||':'||deliveries.receipt_hash AND w.created_sequence<=? AND w.state<>'INVALIDATED')":""))) {
                statement.setQueryTimeout(1); statement.setString(1, id.toString()); statement.setString(2, recipient.kind().name()); statement.setString(3, recipient.id());
                if(recipient.kind()==ActorKind.PLAYER&&privateSource)statement.setLong(4,watermark);
                try (var rows = statement.executeQuery()) { if (!rows.next()) return Optional.empty(); }
            }
        }
        if (size < 0) return Optional.empty();
        if (size > 1_048_576) throw new SourceLimit();
        countSourceBytes(size);
        if (bytesRead + size > 2_097_152) throw new RequestLimit();
        bytesRead += size; var body = new StringBuilder(); int index = 0;
        try (var statement = db.prepareStatement("SELECT part_index,body FROM message_parts WHERE message_id=? ORDER BY part_index")) {
            statement.setQueryTimeout(1); statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) { while (rows.next()) {
                if (rows.getInt(1) != index++) return Optional.empty(); body.append(rows.getString(2));
                if (body.length() > size) return Optional.empty();
                if (expired()) throw new RequestLimit();
            } }
        }
        String text = body.toString();
        if (utf8(text) != size || !com.sande.mythictrpg.recording.api.RecordingRecords.sha256(text).equals(hash)) return Optional.empty();
        // Native KnowledgeReceipt stores a view pointer, never a second copy of the original speech.
        for (var recipient : scope.audience()) if (recipient.kind() == ActorKind.GOD) {
            try (var statement = db.prepareStatement("SELECT k.projection FROM knowledge_receipts k JOIN source_refs s ON s.id=k.source_ref"
                    + " JOIN deliveries d ON d.id=json_extract(k.projection,'$.deliveryReceiptId') AND d.message_id=s.source_id AND d.actor_id=k.god_id"
                    + " WHERE s.dataset_id=? AND s.owner='room-publication-v2' AND s.source_id=? AND s.source_revision=1 AND s.revoked=0 AND k.god_id=? AND k.acquisition='DIRECT_HEARD'"
                    + " AND s.source_hash=? AND d.actor_kind='GOD' AND d.kind='GAME_HEARD' AND d.status='SERVER_DISPATCHED'"
                    + " AND NOT EXISTS(SELECT 1 FROM knowledge_invalidations ki WHERE ki.dataset_id=k.dataset_id AND ki.receipt_id=k.id)"
                    + " AND NOT EXISTS(SELECT 1 FROM invalidations i WHERE i.dataset_id=s.dataset_id AND i.kind=s.kind AND i.owner=s.owner"
                    + " AND i.source_id=s.source_id AND i.source_revision=s.source_revision)"
                    + " AND NOT EXISTS(SELECT 1 FROM source_refs newer WHERE newer.dataset_id=s.dataset_id AND newer.kind=s.kind"
                    + " AND newer.owner=s.owner AND newer.source_id=s.source_id AND newer.source_revision>s.source_revision)"
                    + " AND d.view_hash=json_extract(k.projection,'$.viewHash')"
                    + " AND EXISTS(SELECT 1 FROM work_items w WHERE w.receipt_id=d.id AND w.source_ref=s.id AND w.kind='ROOM_KNOWLEDGE_CAPTURED' AND w.created_sequence<=?)")) {
                statement.setQueryTimeout(1); statement.setString(1, scope.dataset().toString()); statement.setString(2, id.toString()); statement.setString(3, recipient.id());
                statement.setString(4, com.sande.mythictrpg.recording.api.RecordingRecords.sha256(hash + ":" + new Gson().toJson(context)));
                statement.setLong(5, watermark);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    var projection = JsonParser.parseString(rows.getString(1)).getAsJsonObject();
                    if (!id.toString().equals(projection.get("messageId").getAsString())
                            || !new DeliveryView(text, List.of(text)).hash().equals(projection.get("viewHash").getAsString())) return Optional.empty();
                }
            }
        }
        var evidence = new ArrayList<RoomEvidenceReference>();
        for (var value : context.getAsJsonArray("evidence")) { var e = value.getAsJsonObject(); evidence.add(new RoomEvidenceReference(e.get("kind").getAsString(), e.get("payload").getAsString())); }
        var parents = new HashSet<UUID>(); strings(context.getAsJsonArray("sourceMessages")).forEach(s -> parents.add(UUID.fromString(s)));
        if (parents.size() > 256 || evidence.size() > 64) return Optional.empty();
        var nativeRefs=new ArrayList<RecordedNativeEvidenceStore.Stored>();
        var interpretationRefs=new ArrayList<RecordedNativeInterpretationStore.Stored>();
        for(var reference:evidence)if(com.sande.mythictrpg.recording.api.NativeMemoryEvidence.KIND.equals(reference.kind())){
            try {
                var decoded=com.sande.mythictrpg.recording.api.NativeMemoryEvidence.decode(reference);
                var stored=RecordedNativeEvidenceStore.load(db,decoded.worldId(),scope,decoded,watermark,sequence,deadline);
                if(stored.isEmpty())return Optional.empty();nativeRefs.add(stored.orElseThrow());parents.addAll(stored.orElseThrow().manifest().roots());
            }catch(RuntimeException malformedNativeDescriptor){return Optional.empty();} // A bad source is denied, not an empty proof or a failed whole archive page.
        }
        for(var reference:evidence)if(NativeInterpretationEvidence.KIND.equals(reference.kind())){
            try {
                var decoded=NativeInterpretationEvidence.decode(reference);
                var stored=RecordedNativeInterpretationStore.load(db,decoded.worldId(),scope,decoded,watermark,sequence,deadline);
                if(stored.isEmpty())return Optional.empty();interpretationRefs.add(stored.orElseThrow());
                parents.addAll(stored.orElseThrow().manifest().sources().roots());
            }catch(RuntimeException malformedNativeDescriptor){return Optional.empty();}
        }
        if(parents.size()>256)return Optional.empty();nativeEvidence.put(id,List.copyOf(nativeRefs));interpretationEvidence.put(id,List.copyOf(interpretationRefs));
        return Optional.of(new Node(id, sequence, actor, occurred, text, size, Set.copyOf(parents), List.copyOf(evidence)));
    }
    private boolean expired() { return System.nanoTime() > deadline || Thread.currentThread().isInterrupted(); }
    private static Set<String> strings(JsonArray values) { var result = new HashSet<String>(); values.forEach(v -> result.add(v.getAsString())); return result; }
    private static int utf8(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    private static String excerpt(String text, int match, int budget) {
        int start = Math.max(0, match - 64);
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start--;
        int end = start, bytes = 0;
        while (end < text.length()) { int cp = text.codePointAt(end), width = cp <= 127 ? 1 : cp <= 2047 ? 2 : cp <= 65535 ? 3 : 4;
            if (bytes + width > budget) break; bytes += width; end += Character.charCount(cp); }
        return text.substring(start, end);
    }
}
