package com.sande.mythai.response.memory;

import com.sande.mythictrpg.recording.api.EmbeddingRecords;
import com.sande.mythictrpg.recording.api.RecordingRecords;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Queue/admission/cancellation checks without creating another model pool or invoking a model. */
public final class RecordedQueryEmbeddingTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static RecordedRetrievalCoordinator.SemanticQuery vector(){
        var space=new EmbeddingRecords.ModelSpace("fixture","a".repeat(64),2,EmbeddingRecords.ENCODER_VERSION);
        return new RecordedRetrievalCoordinator.SemanticQuery(new EmbeddingRecords.QueryVector(
                space,RecordingRecords.sha256("fixture"),new float[]{1,0}),.25);
    }
    public static void main(String[] args){
        var occupied=new AtomicBoolean(); var calls=new AtomicInteger();var queue=new ArrayDeque<Runnable>();
        var delivery=new ArrayDeque<Runnable>();var current=new AtomicBoolean(true);var available=new AtomicBoolean(true);
        var first=RecordedEmbeddingService.submitQuery(queue::add,occupied,available::get,current::get,
                ()->{calls.incrementAndGet();return Optional.of(vector());},delivery::add);
        check(occupied.get()&&!first.isDone()&&queue.size()==1,"one bounded work submission");
        var overlap=RecordedEmbeddingService.submitQuery(queue::add,occupied,available::get,current::get,
                ()->{throw new AssertionError("overlap reached backend");},delivery::add);
        check(overlap.join().isEmpty()&&queue.size()==1,"overlap rejected without another model call");
        queue.remove().run();
        check(calls.get()==1&&!occupied.get()&&!first.isDone(),"worker releases admission only after actual backend completion");
        current.set(false);delivery.remove().run();
        check(first.join().isEmpty(),"game-thread policy revocation rejects late vector");
        current.set(true);
        var success=RecordedEmbeddingService.submitQuery(Runnable::run,occupied,available::get,current::get,
                ()->Optional.of(vector()),Runnable::run);
        check(success.join().isPresent()&&!occupied.get(),"valid vector returned once");
        var cancelled=RecordedEmbeddingService.submitQuery(queue::add,occupied,available::get,current::get,
                ()->{calls.incrementAndGet();return Optional.of(vector());},delivery::add);
        cancelled.cancel(false);
        check(occupied.get(),"cancellation does not prematurely clear queued-worker gate");
        queue.remove().run();
        check(!occupied.get()&&calls.get()==1&&delivery.isEmpty(),"cancelled queued call skips backend and dispatch");
        var holder=new AtomicReference<CompletableFuture<Optional<RecordedRetrievalCoordinator.SemanticQuery>>>();
        var running=RecordedEmbeddingService.submitQuery(queue::add,occupied,available::get,current::get,()->{
            check(occupied.get(),"running backend owns gate");holder.get().cancel(false);
            check(occupied.get(),"running cancellation keeps gate until backend returns");return Optional.of(vector());
        },delivery::add);holder.set(running);queue.remove().run();
        check(running.isCancelled()&&!occupied.get()&&delivery.isEmpty(),"running late result is discarded");
        available.set(false);
        check(RecordedEmbeddingService.submitQuery(Runnable::run,occupied,available::get,current::get,
                ()->{throw new AssertionError("closed backend invoked");},Runnable::run).join().isEmpty(),"closed worker skipped");
        available.set(true);
        check(RecordedEmbeddingService.submitQuery(Runnable::run,occupied,available::get,current::get,
                ()->{throw new IllegalStateException("backend unavailable");},Runnable::run).join().isEmpty()&&!occupied.get(),"backend failure contained");
        check(RecordedEmbeddingService.submitQuery(task->{throw new RejectedExecutionException();},occupied,available::get,current::get,
                ()->Optional.of(vector()),Runnable::run).join().isEmpty()&&!occupied.get(),"executor rejection releases gate");
        check(RecordedEmbeddingService.submitQuery(Runnable::run,occupied,available::get,current::get,
                ()->Optional.of(vector()),task->{throw new RejectedExecutionException();}).join().isEmpty()&&!occupied.get(),"dispatcher rejection contained");
        check(RecordedEmbeddingService.submitQuery(Runnable::run,occupied,available::get,()->{throw new IllegalStateException();},
                ()->Optional.of(vector()),Runnable::run).join().isEmpty(),"game-thread recheck failure contained");
        check(RecordedEmbeddingService.submitQuery(Runnable::run,occupied,available::get,current::get,
                ()->null,Runnable::run).join().isEmpty(),"null backend contract rejected");
        check(RecordedEmbeddingService.submitQuery(Runnable::run,occupied,available::get,current::get,
                Optional::empty,Runnable::run).join().isEmpty(),"admission denial optional empty result");
        System.out.println("RecordedQueryEmbeddingTest: "+checks+" checks passed");
    }
}
