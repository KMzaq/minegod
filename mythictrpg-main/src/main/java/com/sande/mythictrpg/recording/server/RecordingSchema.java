package com.sande.mythictrpg.recording.server;

import java.sql.*;
import java.util.List;

/** M1 schema: future projection/search migrations may add tables, never reinterpret legacy journal rows. */
final class RecordingSchema {
    static final int VERSION = 11;
    static final List<String> REQUIRED = List.of("recording_meta", "conversations", "turns", "messages", "message_parts",
            "delivery_batches", "deliveries", "delivery_views", "delivery_parts", "source_refs", "knowledge_receipts",
            "work_items", "consumer_cursors", "invalidations", "coverage_gaps", "search_documents");
    private static final List<String> DDL = List.of(
        "CREATE TABLE recording_meta(singleton INTEGER PRIMARY KEY CHECK(singleton=1),world_id TEXT NOT NULL,dataset_id TEXT NOT NULL,schema_version INTEGER NOT NULL,clean_shutdown INTEGER NOT NULL,high_watermark INTEGER NOT NULL,runtime_epoch TEXT NOT NULL)",
        "CREATE TABLE conversations(id TEXT PRIMARY KEY,dataset_id TEXT NOT NULL,channel TEXT NOT NULL,policy TEXT NOT NULL,policy_revision INTEGER NOT NULL,membership_revision INTEGER NOT NULL,closed INTEGER NOT NULL,adapter TEXT NOT NULL)",
        "CREATE TABLE turns(id TEXT PRIMARY KEY,dataset_id TEXT NOT NULL,conversation_id TEXT NOT NULL REFERENCES conversations(id),sequence INTEGER NOT NULL,UNIQUE(conversation_id,sequence))",
        "CREATE TABLE messages(id TEXT PRIMARY KEY,dataset_id TEXT NOT NULL,conversation_id TEXT NOT NULL REFERENCES conversations(id),turn_id TEXT REFERENCES turns(id),producer TEXT NOT NULL,occurrence_key TEXT NOT NULL,body_hash TEXT NOT NULL,body_bytes INTEGER NOT NULL,actor_kind TEXT NOT NULL,actor_id TEXT NOT NULL,occurred_utc TEXT NOT NULL,recorded_utc TEXT NOT NULL,kind TEXT NOT NULL,request_hash TEXT NOT NULL,ingest_sequence INTEGER NOT NULL UNIQUE,UNIQUE(dataset_id,producer,occurrence_key))",
        "CREATE TABLE message_parts(message_id TEXT NOT NULL REFERENCES messages(id),part_index INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(message_id,part_index)) WITHOUT ROWID",
        "CREATE TABLE delivery_batches(id TEXT PRIMARY KEY,dataset_id TEXT NOT NULL,message_id TEXT NOT NULL REFERENCES messages(id),producer TEXT NOT NULL,occurrence_key TEXT NOT NULL,request_hash TEXT NOT NULL,ingest_sequence INTEGER NOT NULL,UNIQUE(dataset_id,producer,occurrence_key))",
        "CREATE TABLE delivery_views(message_id TEXT NOT NULL REFERENCES messages(id),view_hash TEXT NOT NULL,plain_text TEXT NOT NULL,parts_json TEXT NOT NULL,PRIMARY KEY(message_id,view_hash)) WITHOUT ROWID",
        "CREATE TABLE deliveries(id TEXT PRIMARY KEY,dataset_id TEXT NOT NULL,message_id TEXT NOT NULL REFERENCES messages(id),actor_kind TEXT NOT NULL,actor_id TEXT NOT NULL,kind TEXT NOT NULL,dispatched_utc TEXT NOT NULL,audience_revision INTEGER NOT NULL,status TEXT NOT NULL,view_hash TEXT NOT NULL,receipt_hash TEXT NOT NULL,UNIQUE(message_id,actor_kind,actor_id,kind),FOREIGN KEY(message_id,view_hash) REFERENCES delivery_views(message_id,view_hash))",
        "CREATE TABLE delivery_parts(receipt_id TEXT NOT NULL REFERENCES deliveries(id),part_index INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(receipt_id,part_index)) WITHOUT ROWID",
        "CREATE TABLE source_refs(id INTEGER PRIMARY KEY,dataset_id TEXT NOT NULL,kind TEXT NOT NULL,owner TEXT NOT NULL,source_id TEXT NOT NULL,source_revision INTEGER NOT NULL,source_hash TEXT NOT NULL,revoked INTEGER NOT NULL DEFAULT 0,request_hash TEXT NOT NULL,ingest_sequence INTEGER NOT NULL,UNIQUE(dataset_id,kind,owner,source_id,source_revision))",
        "CREATE TABLE knowledge_receipts(id TEXT PRIMARY KEY,dataset_id TEXT NOT NULL,source_ref INTEGER NOT NULL REFERENCES source_refs(id),god_id TEXT NOT NULL,acquisition TEXT NOT NULL,projection TEXT NOT NULL,audience_json TEXT NOT NULL,policy_revision INTEGER NOT NULL,receipt_hash TEXT NOT NULL)",
        "CREATE INDEX knowledge_scope ON knowledge_receipts(dataset_id,god_id,source_ref)",
        "CREATE TABLE work_items(id TEXT PRIMARY KEY,dataset_id TEXT NOT NULL,message_id TEXT REFERENCES messages(id),receipt_id TEXT REFERENCES deliveries(id),source_ref INTEGER REFERENCES source_refs(id),source_version TEXT NOT NULL,kind TEXT NOT NULL,state TEXT NOT NULL,created_sequence INTEGER NOT NULL,lease_epoch TEXT,lease_deadline TEXT,UNIQUE(dataset_id,kind,source_version))",
        "CREATE INDEX work_pending ON work_items(dataset_id,state,created_sequence)",
        "CREATE TABLE consumer_cursors(dataset_id TEXT NOT NULL,consumer TEXT NOT NULL,stream TEXT NOT NULL,cursor INTEGER NOT NULL,PRIMARY KEY(dataset_id,consumer,stream)) WITHOUT ROWID",
        "CREATE TABLE invalidations(dataset_id TEXT NOT NULL,kind TEXT NOT NULL,owner TEXT NOT NULL,source_id TEXT NOT NULL,source_revision INTEGER NOT NULL,state_version INTEGER NOT NULL,reason_code TEXT NOT NULL,ingest_sequence INTEGER NOT NULL,PRIMARY KEY(dataset_id,kind,owner,source_id,source_revision)) WITHOUT ROWID",
        "CREATE TABLE coverage_gaps(dataset_id TEXT NOT NULL,stream TEXT NOT NULL,reason_code TEXT NOT NULL,after_sequence INTEGER NOT NULL,count INTEGER NOT NULL,PRIMARY KEY(dataset_id,stream,reason_code,after_sequence)) WITHOUT ROWID",
        "CREATE VIRTUAL TABLE search_documents USING fts5(dataset_id UNINDEXED,source_id UNINDEXED,body,tokenize='unicode61')"
    );
    private static final String PUBLICATION_CONTEXT_DDL = "CREATE TABLE message_contexts(message_id TEXT PRIMARY KEY REFERENCES messages(id),context_json TEXT NOT NULL)";
    private static final String PART_REFS_DDL = "CREATE TABLE delivery_part_refs(receipt_id TEXT NOT NULL REFERENCES deliveries(id),part_index INTEGER NOT NULL CHECK(part_index>=0),PRIMARY KEY(receipt_id,part_index)) WITHOUT ROWID";
    private static final String RESOLVED_PARTS_DDL = "CREATE VIEW delivery_parts_resolved AS SELECT receipt_id,part_index,body FROM delivery_parts UNION ALL SELECT r.receipt_id,r.part_index,json_extract(v.parts_json,'$['||r.part_index||']') AS body FROM delivery_part_refs r JOIN deliveries d ON d.id=r.receipt_id JOIN delivery_views v ON v.message_id=d.message_id AND v.view_hash=d.view_hash";
    private static final List<String> KNOWLEDGE_DDL = List.of(
        "CREATE TABLE source_cutovers(dataset_id TEXT NOT NULL,owner TEXT NOT NULL,lineage_id TEXT NOT NULL,cutoff INTEGER NOT NULL CHECK(cutoff>=0),latest_confirmed INTEGER NOT NULL CHECK(latest_confirmed>=cutoff),PRIMARY KEY(dataset_id,owner)) WITHOUT ROWID",
        "CREATE TABLE source_origins(source_ref INTEGER PRIMARY KEY REFERENCES source_refs(id),lineage_id TEXT NOT NULL,origin_cursor INTEGER NOT NULL CHECK(origin_cursor>0),state_cursor INTEGER NOT NULL CHECK(state_cursor>=origin_cursor))",
        "CREATE TABLE knowledge_origins(receipt_id TEXT PRIMARY KEY REFERENCES knowledge_receipts(id),acquired_cursor INTEGER NOT NULL CHECK(acquired_cursor>0))",
        "CREATE TABLE knowledge_invalidations(dataset_id TEXT NOT NULL,receipt_id TEXT NOT NULL,kind TEXT NOT NULL,owner TEXT NOT NULL,source_id TEXT NOT NULL,source_revision INTEGER NOT NULL,state_version INTEGER NOT NULL CHECK(state_version>0),reason_code TEXT NOT NULL,ingest_sequence INTEGER NOT NULL,PRIMARY KEY(dataset_id,receipt_id)) WITHOUT ROWID",
        "CREATE INDEX knowledge_invalidation_source ON knowledge_invalidations(dataset_id,kind,owner,source_id,source_revision)",
        "CREATE INDEX IF NOT EXISTS source_owner_sequence ON source_refs(owner,ingest_sequence)"
    );
    private static final List<String> PROJECTION_DDL = List.of(
        "ALTER TABLE work_items ADD COLUMN extractor_version TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE work_items ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE work_items ADD COLUMN next_attempt_utc TEXT",
        "ALTER TABLE work_items ADD COLUMN last_failure TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE work_items ADD COLUMN lease_nonce TEXT",
        "CREATE TABLE memories(id TEXT PRIMARY KEY,dataset_id TEXT NOT NULL,job_id TEXT NOT NULL REFERENCES work_items(id),observer_god TEXT NOT NULL,layer TEXT NOT NULL,kind TEXT NOT NULL,status TEXT NOT NULL,disclosure_hash TEXT NOT NULL,extractor_version TEXT NOT NULL,payload_json TEXT NOT NULL,projection_hash TEXT NOT NULL,created_sequence INTEGER NOT NULL,UNIQUE(dataset_id,job_id,extractor_version,layer))",
        "CREATE TABLE memory_sources(memory_id TEXT NOT NULL REFERENCES memories(id),knowledge_receipt_id TEXT NOT NULL REFERENCES knowledge_receipts(id),source_ref INTEGER NOT NULL REFERENCES source_refs(id),source_hash TEXT NOT NULL,receipt_hash TEXT NOT NULL,message_id TEXT NOT NULL REFERENCES messages(id),source_alias TEXT NOT NULL,actual_actor_kind TEXT NOT NULL,actual_actor_id TEXT NOT NULL,occurred_utc TEXT NOT NULL,excerpt INTEGER NOT NULL,total_characters INTEGER NOT NULL,covered_characters INTEGER NOT NULL,PRIMARY KEY(memory_id,knowledge_receipt_id)) WITHOUT ROWID",
        "CREATE INDEX memory_source_withdrawal ON memory_sources(source_ref,knowledge_receipt_id,memory_id)",
        "CREATE INDEX memory_receipt_withdrawal ON memory_sources(knowledge_receipt_id,memory_id)",
        "CREATE TABLE memory_subjects(memory_id TEXT NOT NULL REFERENCES memories(id),actor_kind TEXT NOT NULL,actor_id TEXT NOT NULL,PRIMARY KEY(memory_id,actor_kind,actor_id)) WITHOUT ROWID",
        "CREATE TABLE memory_links(memory_id TEXT NOT NULL REFERENCES memories(id),newer_receipt_id TEXT NOT NULL REFERENCES knowledge_receipts(id),older_receipt_id TEXT NOT NULL REFERENCES knowledge_receipts(id),relation TEXT NOT NULL,status TEXT NOT NULL,PRIMARY KEY(memory_id,newer_receipt_id,older_receipt_id,relation)) WITHOUT ROWID"
    );
    private static final List<String> LEXICAL_DDL = List.of(
        "CREATE VIRTUAL TABLE recording_lexical_fts USING fts5(body,content='',contentless_delete=1,tokenize='trigram case_sensitive 1')",
        "CREATE TABLE recording_lexical_manifest(message_sequence INTEGER PRIMARY KEY REFERENCES messages(ingest_sequence),dataset_id TEXT NOT NULL,message_id TEXT NOT NULL UNIQUE REFERENCES messages(id),body_hash TEXT NOT NULL,normalization_version TEXT NOT NULL,indexed_sequence INTEGER NOT NULL CHECK(indexed_sequence>=message_sequence))",
        "CREATE INDEX recording_lexical_snapshot ON recording_lexical_manifest(dataset_id,indexed_sequence,message_sequence)",
        "CREATE TABLE recording_lexical_progress(dataset_id TEXT PRIMARY KEY,normalization_version TEXT NOT NULL,after_message_sequence INTEGER NOT NULL CHECK(after_message_sequence>=0),indexed_messages INTEGER NOT NULL CHECK(indexed_messages>=0),skipped_messages INTEGER NOT NULL CHECK(skipped_messages>=0),reason_code TEXT NOT NULL)",
        "CREATE TABLE recording_lexical_skips(message_sequence INTEGER PRIMARY KEY REFERENCES messages(ingest_sequence),dataset_id TEXT NOT NULL,message_id TEXT NOT NULL UNIQUE REFERENCES messages(id),body_hash TEXT NOT NULL,reason_code TEXT NOT NULL)"
    );
    private static final List<String> EMBEDDING_DDL = List.of(
        "CREATE TABLE embedding_jobs(id TEXT PRIMARY KEY,dataset_id TEXT NOT NULL,model_fingerprint TEXT NOT NULL,room_work_id TEXT NOT NULL REFERENCES work_items(id),state TEXT NOT NULL,created_sequence INTEGER NOT NULL,attempt_count INTEGER NOT NULL DEFAULT 0,next_attempt_millis INTEGER NOT NULL DEFAULT 0,last_failure TEXT NOT NULL DEFAULT '',lease_epoch TEXT,lease_nonce TEXT,lease_deadline_millis INTEGER,UNIQUE(dataset_id,model_fingerprint,room_work_id))",
        "CREATE INDEX embedding_pending ON embedding_jobs(dataset_id,model_fingerprint,state,next_attempt_millis,created_sequence,id)",
        "CREATE INDEX IF NOT EXISTS embedding_source_seed ON work_items(dataset_id,kind,created_sequence,id)",
        "CREATE TABLE embedding_seed_progress(dataset_id TEXT NOT NULL,model_fingerprint TEXT NOT NULL,after_sequence INTEGER NOT NULL,after_work_id TEXT NOT NULL,PRIMARY KEY(dataset_id,model_fingerprint)) WITHOUT ROWID",
        "CREATE TABLE embedding_rows(id TEXT PRIMARY KEY,dataset_id TEXT NOT NULL,job_id TEXT NOT NULL REFERENCES embedding_jobs(id),room_work_id TEXT NOT NULL REFERENCES work_items(id),message_id TEXT NOT NULL REFERENCES messages(id),source_ref INTEGER NOT NULL REFERENCES source_refs(id),source_hash TEXT NOT NULL,knowledge_receipt_id TEXT NOT NULL REFERENCES knowledge_receipts(id),receipt_hash TEXT NOT NULL,observer_god TEXT NOT NULL,actual_actor_kind TEXT NOT NULL,actual_actor_id TEXT NOT NULL,disclosure_hash TEXT NOT NULL,model_fingerprint TEXT NOT NULL,model_name TEXT NOT NULL,model_digest TEXT NOT NULL,dimensions INTEGER NOT NULL,encoder_version TEXT NOT NULL,input_hash TEXT NOT NULL,covered_characters INTEGER NOT NULL,total_characters INTEGER NOT NULL,vector BLOB NOT NULL,vector_hash TEXT NOT NULL,created_sequence INTEGER NOT NULL,UNIQUE(dataset_id,knowledge_receipt_id,model_fingerprint))",
        "CREATE INDEX embedding_scope ON embedding_rows(dataset_id,observer_god,model_fingerprint,created_sequence,id)"
    );
    private static final String NATIVE_EVIDENCE_DDL="CREATE TABLE native_memory_evidence(seal_id TEXT PRIMARY KEY,dataset_id TEXT NOT NULL,world_id TEXT NOT NULL,manifest_hash TEXT NOT NULL,manifest_json TEXT NOT NULL,original_watermark INTEGER NOT NULL CHECK(original_watermark>0),issued_sequence INTEGER NOT NULL CHECK(issued_sequence>original_watermark))";
    private static final String PROJECTION_INPUT_DDL="CREATE TABLE projection_input_manifests(dataset_id TEXT NOT NULL,job_id TEXT NOT NULL REFERENCES work_items(id),extractor_version TEXT NOT NULL,manifest_hash TEXT NOT NULL,manifest_json TEXT NOT NULL,created_sequence INTEGER NOT NULL CHECK(created_sequence>0),PRIMARY KEY(dataset_id,job_id,extractor_version)) WITHOUT ROWID";
    private static final String INTERPRETATION_EVIDENCE_DDL="CREATE TABLE native_interpretation_evidence(seal_id TEXT PRIMARY KEY,dataset_id TEXT NOT NULL,world_id TEXT NOT NULL,manifest_hash TEXT NOT NULL,manifest_json TEXT NOT NULL,original_watermark INTEGER NOT NULL CHECK(original_watermark>0),issued_sequence INTEGER NOT NULL CHECK(issued_sequence>original_watermark))";
    static void create(Connection db) throws SQLException {
        try (var statement = db.createStatement()) { for (String ddl : DDL) statement.execute(ddl); statement.execute(PUBLICATION_CONTEXT_DDL);
            statement.execute(PART_REFS_DDL); statement.execute(RESOLVED_PARTS_DDL);
            for (String ddl : KNOWLEDGE_DDL) statement.execute(ddl);
            for (String ddl : PROJECTION_DDL) statement.execute(ddl);
            for (String ddl : LEXICAL_DDL) statement.execute(ddl);
            for (String ddl : EMBEDDING_DDL) statement.execute(ddl);
            statement.execute(NATIVE_EVIDENCE_DDL);
            statement.execute(PROJECTION_INPUT_DDL);
            statement.execute(INTERPRETATION_EVIDENCE_DDL);
            statement.execute("PRAGMA user_version=11"); }
    }
    static int version(Connection db) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery("PRAGMA user_version")) { if (!rows.next()) throw new SQLException("MISSING_SCHEMA_VERSION"); return rows.getInt(1); }
    }
    /** Called only inside the reserved startup transaction, after identity/old schema checks. Old rows remain explicitly without context. */
    static void upgradePublicationContext(Connection db) throws SQLException {
        try (var statement = db.createStatement()) {
            if (version(db) == 2) {
                statement.execute(PUBLICATION_CONTEXT_DDL);
                statement.execute("UPDATE recording_meta SET schema_version=3 WHERE singleton=1");
                statement.execute("PRAGMA user_version=3");
            }
            if (version(db) == 3) {
                // Old bodies are preserved; only subsequent receipts share immutable display projections.
                statement.execute(PART_REFS_DDL); statement.execute(RESOLVED_PARTS_DDL);
                statement.execute("UPDATE recording_meta SET schema_version=4 WHERE singleton=1");
                statement.execute("PRAGMA user_version=4");
            }
            if (version(db) == 4) {
                for (String ddl : KNOWLEDGE_DDL) statement.execute(ddl);
                statement.execute("UPDATE recording_meta SET schema_version=5 WHERE singleton=1");
                statement.execute("PRAGMA user_version=5");
            }
            if (version(db) == 5) {
                for (String ddl : PROJECTION_DDL) statement.execute(ddl);
                statement.execute("UPDATE recording_meta SET schema_version=6 WHERE singleton=1");
                statement.execute("PRAGMA user_version=6");
            }
            if(version(db)==6){
                for(String ddl:LEXICAL_DDL)statement.execute(ddl);
                statement.execute("UPDATE recording_meta SET schema_version=7 WHERE singleton=1");
                statement.execute("PRAGMA user_version=7");
            }
            if(version(db)==7){
                for(String ddl:EMBEDDING_DDL)statement.execute(ddl);
                statement.execute("UPDATE recording_meta SET schema_version=8 WHERE singleton=1");
                statement.execute("PRAGMA user_version=8");
            }
            if(version(db)==8){
                statement.execute(NATIVE_EVIDENCE_DDL);
                statement.execute("UPDATE recording_meta SET schema_version=9 WHERE singleton=1");
                statement.execute("PRAGMA user_version=9");
            }
            if(version(db)==9){
                // Old candidates survive for diagnostics. Never certify their remaining rows by backfill.
                statement.execute(PROJECTION_INPUT_DDL);
                statement.execute("UPDATE recording_meta SET schema_version=10 WHERE singleton=1");
                statement.execute("PRAGMA user_version=10");
            }
            if(version(db)==10){
                statement.execute(INTERPRETATION_EVIDENCE_DDL);
                statement.execute("UPDATE recording_meta SET schema_version=11 WHERE singleton=1");
                statement.execute("PRAGMA user_version=11");
            }
            // Additive index only: early development schema5 snapshots keep identical row semantics.
            if(version(db)>=5)statement.execute("CREATE INDEX IF NOT EXISTS source_owner_sequence ON source_refs(owner,ingest_sequence)");
            if(version(db)>=6)statement.execute("CREATE INDEX IF NOT EXISTS memory_receipt_withdrawal ON memory_sources(knowledge_receipt_id,memory_id)");
        }
    }
    static void verify(Connection db) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery("PRAGMA quick_check")) {
            if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next()) throw new SQLException("CORRUPT_DATABASE");
        }
        int version = version(db);
        if (version < 2 || version > VERSION) throw new SQLException("UNSUPPORTED_SCHEMA");
        try (var statement = db.prepareStatement("SELECT 1 FROM sqlite_master WHERE name=?")) {
            for (String name : REQUIRED) { statement.setString(1, name); try (var rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("INCOMPLETE_SCHEMA"); } }
            if (version >= 3) { statement.setString(1, "message_contexts"); try (var rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("INCOMPLETE_SCHEMA"); } }
            if (version >= 4) for (String name : List.of("delivery_part_refs", "delivery_parts_resolved")) {
                statement.setString(1, name); try (var rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("INCOMPLETE_SCHEMA"); }
            }
            if (version >= 5) for (String name : List.of("source_cutovers", "source_origins", "knowledge_origins", "knowledge_invalidations")) {
                statement.setString(1, name); try (var rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("INCOMPLETE_SCHEMA"); }
            }
            if (version >= 6) for (String name : List.of("memories", "memory_sources", "memory_subjects", "memory_links")) {
                statement.setString(1, name); try (var rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("INCOMPLETE_SCHEMA"); }
            }
            if(version>=7)for(String name:List.of("recording_lexical_fts","recording_lexical_manifest","recording_lexical_progress","recording_lexical_skips")){
                statement.setString(1,name);try(var rows=statement.executeQuery()){if(!rows.next())throw new SQLException("INCOMPLETE_SCHEMA");}
            }
            if(version>=8)for(String name:List.of("embedding_jobs","embedding_rows","embedding_seed_progress")){
                statement.setString(1,name);try(var rows=statement.executeQuery()){if(!rows.next())throw new SQLException("INCOMPLETE_SCHEMA");}
            }
            if(version>=9){statement.setString(1,"native_memory_evidence");try(var rows=statement.executeQuery()){if(!rows.next())throw new SQLException("INCOMPLETE_SCHEMA");}}
            if(version>=10){statement.setString(1,"projection_input_manifests");try(var rows=statement.executeQuery()){if(!rows.next())throw new SQLException("INCOMPLETE_SCHEMA");}}
            if(version>=11){statement.setString(1,"native_interpretation_evidence");try(var rows=statement.executeQuery()){if(!rows.next())throw new SQLException("INCOMPLETE_SCHEMA");}}
        }
        if (version >= 6) try (var statement = db.createStatement(); var ignored = statement.executeQuery("SELECT extractor_version,attempt_count,next_attempt_utc,last_failure,lease_nonce FROM work_items LIMIT 0")) { }
        try (var statement = db.createStatement(); var ignored = statement.executeQuery("SELECT rowid FROM search_documents WHERE search_documents MATCH 'native_fts5_probe' LIMIT 1")) { /* Real FTS5 execution, no persisted test document. */ }
        if(version>=7)try(var statement=db.createStatement();var ignored=statement.executeQuery("SELECT rowid FROM recording_lexical_fts WHERE recording_lexical_fts MATCH 'trigram_probe' LIMIT 1")){ }
    }
}
