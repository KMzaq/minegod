package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.AiDialogueConfig;
import com.sande.mythictrpg.ai.SocialPersona;
import com.sande.mythictrpg.rumor.SocialReview;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.util.concurrent.*;

/** Nonblocking post-dialogue client registered at mod initialization. OFF never schedules or contacts Ollama. */
public final class SocialReviewProvider implements SocialReview.Provider {
    public static final SocialReviewProvider INSTANCE=new SocialReviewProvider();
    private ThreadPoolExecutor worker;
    public CompletableFuture<SocialReview.Answer> review(SocialReview.Request request) {
        var server=ServerLifecycleHooks.getCurrentServer();
        if(server==null||!server.isSameThread())return CompletableFuture.failedFuture(new IllegalStateException("social snapshot requires server thread"));
        var settings=OllamaSocialReview.Settings.load(server.getServerDirectory().resolve("config/mythictrpg/ai-social-review.json"));
        if(!settings.enabled())return CompletableFuture.failedFuture(new IllegalStateException("social model OFF"));
        var persona=SocialPersona.read(request.godId());var dialogue=AiDialogueConfig.INSTANCE.settings();
        var result=new CompletableFuture<SocialReview.Answer>();
        if(worker==null||worker.isShutdown())worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"mythai-social-review");t.setDaemon(true);return t;});
        try{worker.execute(()->{
            try(var lease=ModelAdmission.followup()) {
                if(result.isCancelled()||lease==null)throw new SocialReview.Busy();
                var answer=OllamaSocialReview.local().review(request,persona.text(),dialogue.ollamaChatUrl(),dialogue.ollamaModel(),settings);
                server.execute(()->{
                    try {if(ServerLifecycleHooks.getCurrentServer()!=server||!SocialPersona.current(persona))throw new IllegalStateException("content reloaded/server changed");result.complete(answer);}
                    catch(RuntimeException stale){result.completeExceptionally(stale);}
                });
            }catch(Exception unavailable){result.completeExceptionally(unavailable instanceof InterruptedException?new SocialReview.Busy():unavailable);}
            finally{Thread.interrupted();} // interruption belongs to this job, not the next queued review
        });}catch(RejectedExecutionException full){result.completeExceptionally(new SocialReview.Busy());}
        return result;
    }
    public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        if(INSTANCE.worker!=null){INSTANCE.worker.shutdownNow();INSTANCE.worker=null;}
    }
    private SocialReviewProvider() { }
}
