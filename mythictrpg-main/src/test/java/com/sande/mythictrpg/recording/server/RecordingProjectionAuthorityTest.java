package com.sande.mythictrpg.recording.server;

import com.sande.mythictrpg.recording.api.*;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Writer lifecycle fences, independent RAW/interpretation generations and cancellation-safe cleanup. */
public final class RecordingProjectionAuthorityTest {
    private static int checks;
    private static final RecordingSettings SETTINGS = new RecordingSettings(RecordingSettings.Mode.SHADOW,128_000_000,2_000_000,.90,.95);
    public static void main(String[] args) throws Exception {
        Path parent=Path.of(args.length==0?"build/recording-projection-authority-test":args[0]).toAbsolutePath().normalize();
        if(!parent.toString().replace('\\','/').contains("/build/"))throw new IllegalArgumentException("TEST_REQUIRES_BUILD_DIRECTORY");
        Files.createDirectories(parent);Path root=Files.createTempDirectory(parent,"authority-");
        var store=await(WorldRecordingService.open(root,UUID.randomUUID(),SETTINGS,new WorldRecordingService.CutoverBoundary("authority-test",Map.of())));
        check(store.health().state()==WorldRecordingService.State.READY,"actual SQLite fixture opens");
        try {
            projectionLifecycle(store);
            invalidationLifecycle(store);
            cancelledPortClaim(store);
        } finally {await(store.closeAsync());}
        long generation=store.projectionGeneration();
        expectStorageFailure(store.projectionTransaction("closed",4096,false,(db,seq)->new RecordingProjectionStore.Mutation<>("unused",true)),ProjectionRecords.Status.UNAVAILABLE);
        check(store.projectionAuthorityStable()&&store.projectionGeneration()==generation,"synchronous closed-store refusal leaves no pending fence or invented commit");
        System.out.println("RecordingProjectionAuthorityTest: "+checks+" checks passed; fixtures="+root);
    }
    private static void projectionLifecycle(WorldRecordingService store)throws Exception {
        long generation=store.projectionGeneration(),rawGeneration=store.authorityGeneration(),watermark=store.health().highWatermark();
        var release=gate(store);
        try {
            var cancelled=store.projectionTransaction("cancelled-view",4096,false,(db,sequence)->new RecordingProjectionStore.Mutation<>("committed",true));
            check(!store.projectionAuthorityStable()&&store.readAuthorityStable(),"queued projection fences interpretations but not RAW leases");
            check(store.projectionGeneration()==generation&&store.authorityGeneration()==rawGeneration,"dispatch does not claim a durable projection change");
            check(cancelled.cancel(false),"caller may abandon waiting without cancelling admitted write");
        } finally {release.countDown();}
        stable(store);
        check(store.projectionGeneration()==generation+1&&store.health().highWatermark()==watermark+1,"cancelled outward view still observes committed mutation for authority fencing");
        check(store.authorityGeneration()==rawGeneration&&store.readAuthorityStable(),"successful optional extraction never retires RAW leases");
        generation=store.projectionGeneration();watermark=store.health().highWatermark();
        check(await(store.projectionTransaction("no-op",4096,false,(db,sequence)->new RecordingProjectionStore.Mutation<>("unchanged",false))).equals("unchanged"),"no-op result returned");
        check(store.projectionGeneration()==generation&&store.health().highWatermark()==watermark&&store.projectionAuthorityStable(),"no-op releases fence without new generation or archive event");
        UUID dataset=store.datasetId().orElseThrow();
        expectStorageFailure(store.projectionTransaction("rollback",4096,false,(db,sequence)->{
            try(var insert=db.prepareStatement("INSERT INTO consumer_cursors VALUES(?,?,?,?)")){
                insert.setString(1,dataset.toString());insert.setString(2,"authority-fixture");insert.setString(3,"rolled-back");insert.setLong(4,sequence);insert.executeUpdate();
            }
            return new RecordingProjectionStore.Mutation<>("not committed",true,()->false);
        }),ProjectionRecords.Status.STALE);
        check(store.projectionAuthorityStable()&&store.projectionGeneration()==generation&&store.health().highWatermark()==watermark,"precommit stale validation rollback releases fence without a successful generation");
        // Resolve the actual managed fixture path without assuming a production/world layout.
        var field=WorldRecordingService.class.getDeclaredField("database");field.setAccessible(true);Path database=(Path)field.get(store);
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+database);var query=db.createStatement();var rows=query.executeQuery("SELECT count(*) FROM consumer_cursors WHERE consumer='authority-fixture'")){
            rows.next();check(rows.getLong(1)==0,"rejected projection's partial SQL was rolled back");
        }
        expectStorageFailure(store.projectionTransaction("timeout",4096,false,(db,sequence)->{throw new SQLException("fixture VM interrupt","",9);}),ProjectionRecords.Status.DEFERRED);
        check(store.projectionAuthorityStable()&&store.projectionGeneration()==generation&&store.health().state()==WorldRecordingService.State.READY,"bounded projection timeout leaves raw recording ready and authority stable");
    }
    private static void invalidationLifecycle(WorldRecordingService store)throws Exception {
        var producer=store.registerProducer("authority-fixture",Set.of(),Set.of(SourceKind.DIALOGUE_DIRECT));
        var source=new SourceRef(store.worldId(),store.datasetId().orElseThrow(),SourceKind.DIALOGUE_DIRECT,"authority-fixture","withdrawn",1,RecordingRecords.sha256("fixture source"));
        var invalidation=new SourceInvalidation(source,1,"FIXTURE_WITHDRAWAL");
        long generation=store.projectionGeneration(),raw=store.authorityGeneration();
        var release=gate(store);
        try {
            var outward=store.invalidate(producer,invalidation).toCompletableFuture();
            check(!store.projectionAuthorityStable()&&!store.readAuthorityStable(),"queued withdrawal fences both interpretations and RAW permissions");
            check(store.projectionGeneration()==generation&&store.authorityGeneration()>raw,"withdrawal immediately retires RAW lease while projection generation awaits commit");
            check(outward.cancel(false),"withdrawal caller can cancel its view only");
        } finally {release.countDown();}
        stable(store);
        check(store.projectionGeneration()==generation+1&&store.authorityGeneration()>raw&&store.readAuthorityStable(),"cancelled withdrawal still commits both authority retirement and pending cleanup");
        generation=store.projectionGeneration();
        check(await(store.invalidate(producer,invalidation)).status()==Status.DUPLICATE,"same withdrawal is idempotent");
        check(store.projectionGeneration()==generation&&store.projectionAuthorityStable(),"duplicate withdrawal is not a new projection generation");
        check(await(store.invalidate(ProducerCapability.unregistered(),invalidation)).status()==Status.UNAVAILABLE&&store.projectionGeneration()==generation,"forged withdrawal cannot change projection generation");
    }
    private static void cancelledPortClaim(WorldRecordingService store)throws Exception {
        var worker=store.registerProjectionWorker("cancellation-fixture","fixture-v1");
        var budget=new ProjectionRecords.WorkBudget(2,4096,45);var release=gate(store);
        try {
            var outward=store.projectionPort().claimWork(worker,budget).toCompletableFuture();
            check(outward.cancel(false),"public port claim waiter can cancel without cancelling internal cleanup");
        } finally {release.countDown();}
        // Single writer barrier: preceding claim's internal completion and worker cleanup have run.
        await(store.projectionTransaction("claim-barrier",4096,false,(db,sequence)->new RecordingProjectionStore.Mutation<>(true,false)));
        check(await(store.projectionPort().claimWork(worker,budget)).status()==ProjectionRecords.Status.EMPTY,"cancelled empty claim never strands worker in WORKER_ALREADY_BUSY");
        check(store.projectionAuthorityStable()&&store.readAuthorityStable(),"public port cancellation leaves both authority fences stable");
    }
    private static CountDownLatch gate(WorldRecordingService store)throws Exception {
        var field=WorldRecordingService.class.getDeclaredField("writer");field.setAccessible(true);var writer=(ThreadPoolExecutor)field.get(store);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        writer.execute(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        check(entered.await(2,TimeUnit.SECONDS),"fixture briefly gates writer before admission");return release;
    }
    private static void stable(WorldRecordingService store)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!store.projectionAuthorityStable()&&System.nanoTime()<deadline)Thread.sleep(2);
        check(store.projectionAuthorityStable(),"internal completion always releases authority fence");
    }
    private static void expectStorageFailure(CompletionStage<?> future,ProjectionRecords.Status expected)throws Exception {
        try{await(future);throw new AssertionError("expected storage failure "+expected);}
        catch(ExecutionException failure){Throwable cause=failure.getCause();while(cause instanceof CompletionException)cause=cause.getCause();
            check(cause instanceof RecordingProjectionStore.StorageFailure storage&&storage.status==expected,"typed storage failure "+expected+": "+cause);}
    }
    private static <T>T await(CompletionStage<T> future)throws Exception{return future.toCompletableFuture().get(20,TimeUnit.SECONDS);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
