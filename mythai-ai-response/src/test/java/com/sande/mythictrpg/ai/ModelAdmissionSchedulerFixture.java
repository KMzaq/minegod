package com.sande.mythictrpg.ai;

import com.sande.mythai.response.memory.ModelAdmission;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs the production patched scheduler with fake suppliers, not LocalOllamaClient or a server. */
public final class ModelAdmissionSchedulerFixture {
    public static void run()throws Exception {
        var settings=new AiDialogueConfig.Settings(URI.create("http://127.0.0.1:11434/api/chat"),"fixture",2,
                12000,600,12,256,100,false,128,4,2,16,1,3,3,10,4,false);
        var active=new AtomicInteger();var maximum=new AtomicInteger();var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        try(var a=new LocalLlmRequestScheduler();var b=new LocalLlmRequestScheduler()){
            var futures=new ArrayList<CompletableFuture<LocalLlmRequestScheduler.ScheduledResult<Integer>>>();
            for(int i=0;i<6;i++)futures.add((i%2==0?a:b).submit(UUID.randomUUID(),settings,()->{
                int current=active.incrementAndGet();maximum.accumulateAndGet(current,Math::max);entered.countDown();
                try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("release timeout");return current;}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}finally{active.decrementAndGet();}
            }).completion());
            if(!entered.await(2,TimeUnit.SECONDS)||ModelAdmission.status().foregroundPending()!=6||ModelAdmission.optional(false)!=null)throw new AssertionError("six queued foreground jobs protect admission");
            release.countDown();for(var f:futures)f.get(4,TimeUnit.SECONDS);
            if(maximum.get()>2)throw new AssertionError("global configured concurrency across schedulers");
        } finally {release.countDown();}
        if(ModelAdmission.status().foregroundPending()!=0||ModelAdmission.status().foregroundActive()!=0)throw new AssertionError("scheduler tickets drained");
        var blocker=ModelAdmission.optional(false);var executed=new AtomicBoolean();
        try(var scheduler=new LocalLlmRequestScheduler()) {var future=scheduler.submit(UUID.randomUUID(),settings,()->{executed.set(true);return 1;}).completion();future.cancel(false);}
        finally {blocker.close();}
        if(executed.get()||ModelAdmission.status().foregroundPending()!=0)throw new AssertionError("cancelled request must not invoke supplier or leak ticket");
        System.out.println("ModelAdmissionSchedulerFixture: PASS (4 checks, six fake foreground jobs across two real schedulers)");
    }
}
