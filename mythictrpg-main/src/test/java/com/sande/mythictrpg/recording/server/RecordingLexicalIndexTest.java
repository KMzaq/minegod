package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Actual contentless FTS5, bounded backfill and optional-failure isolation. Build-directory fixtures only. */
public final class RecordingLexicalIndexTest {
    private static int checks;
    private static final ActorRef PLAYER=new ActorRef(ActorKind.PLAYER,UUID.randomUUID().toString());
    private static final RecordingSettings SETTINGS=new RecordingSettings(RecordingSettings.Mode.SHADOW,128_000_000,2_000_000,.90,.95);
    private static final WorldRecordingService.CutoverBoundary BOUNDARY=new WorldRecordingService.CutoverBoundary("lexical-test",Map.of());
    private record Fixture(Path root,UUID world,WorldRecordingService store,ProducerCapability producer){ }
    public static void main(String[] args)throws Exception{
        Path parent=Path.of(args.length==0?"build/recording-lexical-index-test":args[0]).toAbsolutePath().normalize();
        if(!parent.toString().replace('\\','/').contains("/build/"))throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent);Path root=Files.createTempDirectory(parent,"index-");
        contentless(root.resolve("contentless"));cancellation(root.resolve("cancellation"));atomicFailure(root.resolve("atomic-failure"));commitUncertainty(root.resolve("commit-uncertainty"));timeout(root.resolve("timeout"));busy(root.resolve("busy"));oversized(root.resolve("oversized"));
        quota(root.resolve("quota"));mode(root.resolve("mode"));migration(root.resolve("migration"));
        System.out.println("RecordingLexicalIndexTest: "+checks+" checks passed; fixtures="+root);
    }
    private static void contentless(Path root)throws Exception{
        var f=fixture(root,UUID.randomUUID(),SETTINGS);String original="  Prefix UNICORN 바다의심장 ΣΧΕΔΙΟ 🍎🍐🍌 끝\n";
        UUID first=capture(f,original);for(int i=0;i<19;i++)capture(f,"bounded document "+i);
        long before=f.store().health().highWatermark();
        check(count(f,"SELECT count(*) FROM recording_lexical_manifest")==0,"capture does not depend on optional lexical index");
        var batch=await(f.store().pumpLexicalIndex());
        check(batch.indexed()>0&&batch.indexed()<=16&&!batch.caughtUp(),"one pump processes bounded source prefix only");
        check(f.store().health().highWatermark()>before,"index commit receives new durable archive sequence");
        var caughtUp=drain(f);
        check(caughtUp.caughtUp()&&f.store().lexicalIndexStatus().indexedMessages()==20,"separate bounded pumps report durable exact coverage");
        check(count(f,"SELECT count(*) FROM recording_lexical_manifest WHERE indexed_sequence<=message_sequence")==0,"late index membership not backdated to source capture");
        check(count(f,"SELECT count(*) FROM recording_lexical_fts WHERE body IS NOT NULL")==0,"contentless FTS stores no retrievable second raw body");
        check(matches(f,"UNICORN")==1&&matches(f,"바다의심장")==1&&matches(f,"ΣΧΕΔΙΟ")==1&&matches(f,"🍎🍐🍌")==1,"shared Unicode ROOT normalization and Korean/emoji trigrams work");
        check(await(f.store().inspectMessage(first,4096)).orElseThrow().equals(original),"case/spacing/newline raw text remains byte-equivalent");
        long indexSequence=count(f,"SELECT indexed_sequence FROM recording_lexical_manifest WHERE message_id='"+first+"'");
        long stable=f.store().health().highWatermark();var noOp=await(f.store().pumpLexicalIndex());
        check(noOp.indexed()==0&&noOp.caughtUp()&&f.store().health().highWatermark()==stable,"empty pump creates no fake ingest event or duplicate document");
        UUID late=capture(f,"late unicorn appearance");await(f.store().pumpLexicalIndex());
        check(count(f,"SELECT indexed_sequence FROM recording_lexical_manifest WHERE message_id='"+first+"'")==indexSequence,"existing index membership remains immutable across new indexing");
        check(matches(f,"unicorn")==2&&count(f,"SELECT count(*) FROM recording_lexical_manifest WHERE message_id='"+late+"'")==1,"new record independently gains lexical candidate row");
        UUID world=f.world();await(f.store().closeAsync());var reopened=fixture(root,world,SETTINGS);
        check(reopened.store().lexicalIndexStatus().throughMessageSequence()==0,"restart does not scan archive on game thread to guess coverage");
        var after=await(reopened.store().pumpLexicalIndex());
        check(after.caughtUp()&&after.indexed()==0&&reopened.store().lexicalIndexStatus().indexedMessages()==21,"durable progress/counters restored on bounded async pump");
        check(count(reopened,"SELECT count(*) FROM recording_lexical_manifest")==21&&matches(reopened,"unicorn")==2,"reopen preserves index without implicit rebuild");
        await(reopened.store().closeAsync());
    }
    private static void atomicFailure(Path root)throws Exception{
        var f=fixture(root,UUID.randomUUID(),SETTINGS);UUID first=capture(f,"atomic first needle"),second=capture(f,"atomic second needle");
        sql(f,"CREATE TRIGGER fixture_index_failure BEFORE INSERT ON recording_lexical_manifest WHEN NEW.message_id='"+second+"' BEGIN SELECT RAISE(ABORT,'fixture_index_failure'); END");
        long before=f.store().health().highWatermark();var failure=await(f.store().pumpLexicalIndex());
        check(failure.state().equals("UNAVAILABLE")&&!failure.caughtUp(),"optional SQL failure reports truthful unavailable index");
        check(f.store().health().state()==WorldRecordingService.State.READY,"optional index failure cannot disable authoritative RAW store");
        check(count(f,"SELECT count(*) FROM recording_lexical_manifest")==0&&count(f,"SELECT count(*) FROM recording_lexical_fts")==0
                &&count(f,"SELECT count(*) FROM recording_lexical_progress")==0&&f.store().health().highWatermark()==before,"partial batch failure rolls back postings, manifest, cursor and watermark together");
        UUID third=capture(f,"after optional index failure");
        check(await(f.store().inspectMessage(third,4096)).isPresent()&&await(f.store().inspectMessage(first,4096)).isPresent(),"new and old raw survive failed optional indexing");
        sql(f,"DROP TRIGGER fixture_index_failure");var retry=await(f.store().pumpLexicalIndex());
        check(retry.indexed()==3&&retry.caughtUp()&&matches(f,"atomic")==2,"retry resumes full failed prefix without cursor skip or duplicate partial postings");
        await(f.store().closeAsync());
    }
    private static void cancellation(Path root)throws Exception{
        var f=fixture(root,UUID.randomUUID(),SETTINGS);capture(f,"caller cancellation must not strand pump cleanup");
        var field=WorldRecordingService.class.getDeclaredField("writer");field.setAccessible(true);
        var writer=(ThreadPoolExecutor)field.get(f.store());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        writer.execute(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        check(entered.await(2,TimeUnit.SECONDS),"fixture briefly gates writer before lexical admission");
        try{
            var exposed=f.store().pumpLexicalIndex();check(exposed.cancel(false),"caller may cancel waiting for the admitted index work");
        }finally{release.countDown();}
        long deadline=System.nanoTime()+10_000_000_000L;
        while(f.store().lexicalIndexStatus().active()&&System.nanoTime()<deadline)Thread.sleep(5);
        check(!f.store().lexicalIndexStatus().active(),"unexposed internal completion always clears active flag after caller cancellation");
        check(await(f.store().pumpLexicalIndex()).caughtUp()&&count(f,"SELECT count(*) FROM recording_lexical_manifest")==1,"next pump works and canceled waiter cannot duplicate or strand index work");
        await(f.store().closeAsync());
    }
    private static void commitUncertainty(Path root)throws Exception{
        var f=fixture(root,UUID.randomUUID(),SETTINGS);capture(f,"committed source with lost JDBC acknowledgement");
        var field=WorldRecordingService.class.getDeclaredField("connection");field.setAccessible(true);
        var actual=(org.sqlite.SQLiteConnection)field.get(f.store());
        var installed=new java.util.concurrent.atomic.AtomicBoolean();var failed=new java.util.concurrent.atomic.AtomicBoolean();
        Connection proxy=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(ignored,method,args)->{
            try{Object value=method.invoke(actual,args);
                if(method.getName().equals("commit")&&failed.compareAndSet(false,true))throw new SQLException("FIXTURE_COMMIT_ACK_LOST");
                return value;
            }catch(java.lang.reflect.InvocationTargetException invocation){throw invocation.getCause();}
        });
        // Install only at the final metadata update, AFTER SqlReadBudget has closed its native driver
        // handler. The proxy delegates a real COMMIT and then simulates loss of its acknowledgement.
        org.sqlite.SQLiteUpdateListener listener=(type,database,table,row)->{
            if(table.equals("recording_meta")&&installed.compareAndSet(false,true))try{field.set(f.store(),proxy);}
            catch(IllegalAccessException impossible){throw new AssertionError(impossible);}
        };
        actual.addUpdateListener(listener);
        var result=await(f.store().pumpLexicalIndex());actual.removeUpdateListener(listener);
        check(installed.get()&&failed.get()&&result.state().equals("UNAVAILABLE"),"fixture executes COMMIT before losing its acknowledgement");
        check(f.store().health().state()==WorldRecordingService.State.UNAVAILABLE,"ambiguous optional COMMIT cannot leave stale Java watermark in READY state");
        check(count(f,"SELECT count(*) FROM recording_lexical_manifest")==1&&count(f,"SELECT count(*) FROM recording_lexical_fts")==1,"committed atomic index remains durable despite caller uncertainty");
        UUID world=f.world();await(f.store().closeAsync());var reopened=fixture(root,world,SETTINGS);
        check(await(reopened.store().pumpLexicalIndex()).indexed()==0&&reopened.store().lexicalIndexStatus().indexedMessages()==1,"reopen reconciles committed progress without indexing duplicate rows");
        check(matches(reopened,"acknowledgement")==1,"recovered committed lexical content remains searchable");
        await(reopened.store().closeAsync());
    }
    private static void oversized(Path root)throws Exception{
        var f=fixture(root,UUID.randomUUID(),SETTINGS);UUID small=capture(f,"small searchable source");
        UUID large=capture(f,"x".repeat(RecordingLexicalIndex.MAX_SOURCE_BYTES+1));UUID later=capture(f,"later searchable source");
        var result=drain(f);
        check(result.caughtUp()&&result.state().equals("PARTIAL")&&f.store().lexicalIndexStatus().skippedMessages()==1,"oversized source is explicitly partial coverage, not indexed success");
        check(count(f,"SELECT count(*) FROM recording_lexical_skips WHERE message_id='"+large+"' AND reason_code='SOURCE_TOO_LARGE'")==1,"durable per-source skip diagnostic");
        check(count(f,"SELECT count(*) FROM recording_lexical_manifest WHERE message_id='"+large+"'")==0,"skipped source has no false membership and remains in raw candidate lane");
        check(count(f,"SELECT body_bytes FROM messages WHERE id='"+large+"'")==RecordingLexicalIndex.MAX_SOURCE_BYTES+1L,"oversized original remains stored in full");
        check(matches(f,"searchable")==2&&count(f,"SELECT count(*) FROM recording_lexical_manifest WHERE message_id IN ('"+small+"','"+later+"')")==2,"oversized source cannot starve later eligible source");
        await(f.store().closeAsync());
    }
    private static void timeout(Path root)throws Exception{
        var f=fixture(root,UUID.randomUUID(),SETTINGS);UUID slow=capture(f,"fixture slow lexical source");capture(f,"later source must not starve");
        sql(f,"CREATE TRIGGER fixture_index_timeout BEFORE INSERT ON recording_lexical_manifest WHEN NEW.message_id='"+slow+"' BEGIN "
                +"SELECT sum(n) FROM (WITH RECURSIVE seq(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM seq WHERE n<100000000) SELECT n FROM seq); END");
        var first=await(f.store().pumpLexicalIndex());
        check(first.state().equals("DEFERRED")&&first.reasonCode().equals("LEXICAL_SINGLE_SOURCE_RETRY"),"SQLite VM timeout switches failed batch to bounded single-source retry: "+first+" health="+f.store().health());
        check(f.store().health().state()==WorldRecordingService.State.READY&&count(f,"SELECT count(*) FROM recording_lexical_fts")==0,"timed-out optional transaction rolls back and leaves RAW ready");
        check(count(f,"SELECT cursor FROM consumer_cursors WHERE consumer='lexical-time-budget-v1' AND stream='attempts'")==1,"timeout attempt durably recorded separately from failed index batch");
        UUID world=f.world();await(f.store().closeAsync());f=fixture(root,world,SETTINGS);
        var second=await(f.store().pumpLexicalIndex());check(second.state().equals("DEFERRED")&&count(f,"SELECT cursor FROM consumer_cursors WHERE consumer='lexical-time-budget-v1' AND stream='attempts'")==2,"timeout retry count survives clean restart");
        var third=await(f.store().pumpLexicalIndex());
        check(third.state().equals("PARTIAL")&&third.skipped()==1&&!third.caughtUp(),"persistently slow single source becomes explicit partial coverage after three attempts");
        check(count(f,"SELECT count(*) FROM recording_lexical_manifest WHERE message_id='"+slow+"'")==0
                &&count(f,"SELECT count(*) FROM recording_lexical_skips WHERE message_id='"+slow+"' AND reason_code='INDEX_TIME_BUDGET_EXCEEDED'")==1,"timeout skip never creates false indexed membership");
        var later=await(f.store().pumpLexicalIndex());check(later.indexed()==1&&later.caughtUp()&&later.state().equals("PARTIAL"),"later source proceeds after bounded failure instead of starvation");
        check(matches(f,"starve")==1&&count(f,"SELECT count(*) FROM consumer_cursors WHERE consumer='lexical-time-budget-v1'")==0,"successful cursor advance clears only retry bookkeeping");
        UUID after=capture(f,"raw still accepts messages after index timeout");check(await(f.store().inspectMessage(after,4096)).isPresent(),"raw captures remain independent after repeated SQL VM interruption");
        await(f.store().closeAsync());
    }
    private static void busy(Path root)throws Exception{
        var f=fixture(root,UUID.randomUUID(),SETTINGS);capture(f,"temporary lock is not expensive source content");
        try(var lock=DriverManager.getConnection("jdbc:sqlite:"+database(f));var q=lock.createStatement()){
            q.execute("BEGIN IMMEDIATE");
            try{
                for(int i=0;i<3;i++){
                    var result=await(f.store().pumpLexicalIndex());
                    check(result.state().equals("DEFERRED")&&result.reasonCode().equals("LEXICAL_DATABASE_BUSY"),"database lock contention defers without content penalty: "+result);
                    check(f.store().health().state()==WorldRecordingService.State.READY,"temporary optional lock does not disable RAW archive");
                }
                check(count(f,"SELECT count(*) FROM consumer_cursors WHERE consumer='lexical-time-budget-v1'")==0
                        &&count(f,"SELECT count(*) FROM recording_lexical_skips")==0,"three lock contentions never consume source timeout retries or permanently skip source");
            }finally{q.execute("ROLLBACK");}
        }
        check(await(f.store().pumpLexicalIndex()).indexed()==1&&matches(f,"temporary")==1,"unlocked original is indexed normally without false skip");
        await(f.store().closeAsync());
    }
    private static void quota(Path root)throws Exception{
        var limited=new RecordingSettings(RecordingSettings.Mode.SHADOW,8_000_000,800_000,.90,.95);var f=fixture(root,UUID.randomUUID(),limited);
        for(int i=0;i<3;i++)capture(f,"quota source "+i+" "+"abcd".repeat(25000));
        var result=await(f.store().pumpLexicalIndex());
        check(result.state().equals("DEFERRED")&&!result.caughtUp(),"optional index oversized growth reservation defers under same world quota");
        check(f.store().health().state()==WorldRecordingService.State.READY&&count(f,"SELECT count(*) FROM recording_lexical_manifest")==0,"denied optional reservation does not claim archive is FULL or create index rows");
        UUID after=capture(f,"small RAW must still succeed after optional quota refusal");
        check(await(f.store().inspectMessage(after,4096)).isPresent(),"RAW retains headroom when background indexing cannot fit");
        await(f.store().closeAsync());
    }
    private static void mode(Path root)throws Exception{
        var settings=new RecordingSettings(RecordingSettings.Mode.RECORD_ONLY,128_000_000,2_000_000,.90,.95);var f=fixture(root,UUID.randomUUID(),settings);capture(f,"record only");
        var result=await(f.store().pumpLexicalIndex());
        check(result.state().equals("OFF")&&count(f,"SELECT count(*) FROM recording_lexical_manifest")==0,"record-only capture does not silently enable search-index work");
        await(f.store().closeAsync());
    }
    private static void migration(Path root)throws Exception{
        var f=fixture(root,UUID.randomUUID(),SETTINGS);UUID original=capture(f,"schema six preserved source");await(f.store().closeAsync());
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+database(f));var q=db.createStatement()){
            q.execute("DROP TABLE IF EXISTS native_memory_evidence");
            q.execute("DROP TABLE IF EXISTS projection_input_manifests");
            q.execute("DROP TABLE IF EXISTS native_interpretation_evidence");
            for(String table:List.of("embedding_rows","embedding_jobs","embedding_seed_progress"))q.execute("DROP TABLE "+table);
            q.execute("DROP INDEX embedding_source_seed");
            for(String table:List.of("recording_lexical_fts","recording_lexical_manifest","recording_lexical_progress","recording_lexical_skips"))q.execute("DROP TABLE "+table);
            q.execute("PRAGMA user_version=6");q.execute("UPDATE recording_meta SET schema_version=6");}
        var reopened=fixture(root,f.world(),SETTINGS);
        check(count(reopened,"PRAGMA user_version")==RecordingSchema.VERSION&&count(reopened,"SELECT count(*) FROM recording_lexical_manifest")==0,"schema6 migrates without startup backfill or original re-creation");
        check(await(reopened.store().inspectMessage(original,4096)).orElseThrow().equals("schema six preserved source"),"schema migration preserves exact original");
        check(await(reopened.store().pumpLexicalIndex()).indexed()==1&&matches(reopened,"preserved")==1,"explicit bounded pump indexes only current v2 archive after migration");
        await(reopened.store().closeAsync());
    }
    private static RecordingLexicalIndex.BatchResult drain(Fixture f)throws Exception{RecordingLexicalIndex.BatchResult result=null;for(int i=0;i<20;i++){result=await(f.store().pumpLexicalIndex());if(result.caughtUp())return result;if(!Set.of("READY","PARTIAL").contains(result.state()))throw new AssertionError(result);}throw new AssertionError("bounded fixture failed to catch up: "+result);}
    private static Fixture fixture(Path root,UUID world,RecordingSettings settings)throws Exception{Files.createDirectories(root);var store=await(WorldRecordingService.open(root,world,settings,BOUNDARY));check(store.health().state()==WorldRecordingService.State.READY,"fixture READY "+store.health().reasonCode());return new Fixture(root,world,store,store.registerProducer("lexical-test",Set.of("CHAT"),Set.of()));}
    private static UUID capture(Fixture f,String text)throws Exception{UUID id=UUID.randomUUID();var envelope=new ConversationEnvelope(f.world(),f.store().datasetId().orElseThrow(),UUID.randomUUID(),"CHAT","TEST",1,1,true,false,"fixture");
        var raw=new RawMessage(id,Optional.empty(),0,PLAYER,text,Instant.now(),MessageKind.ACCEPTED_INPUT,id.toString());check(await(f.store().capture(f.producer(),envelope,raw,List.of())).status()==Status.STORED,"raw capture independently committed");return id;}
    private static Path database(Fixture f){return f.root().resolve("mythictrpg-recording-v2").resolve(f.store().datasetId().orElseThrow().toString()).resolve("recording.sqlite");}
    private static long matches(Fixture f,String text)throws Exception{try(var db=DriverManager.getConnection("jdbc:sqlite:"+database(f));var q=db.prepareStatement("SELECT count(*) FROM recording_lexical_fts WHERE recording_lexical_fts MATCH ?")){q.setString(1,"\""+RecordingLexicalIndex.normalize(text).replace("\"","\"\"")+"\"");try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static long count(Fixture f,String query)throws Exception{try(var db=DriverManager.getConnection("jdbc:sqlite:"+database(f));var q=db.createStatement();var row=q.executeQuery(query)){row.next();return row.getLong(1);}}
    private static void sql(Fixture f,String query)throws Exception{try(var db=DriverManager.getConnection("jdbc:sqlite:"+database(f));var q=db.createStatement()){q.execute(query);}}
    private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(20,TimeUnit.SECONDS);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
