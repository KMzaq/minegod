package com.sande.mythai.response.memory;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.*;
import com.sande.mythictrpg.recording.api.MemoryReadSession;
import com.sande.mythictrpg.recording.api.RecordingRecords.*;
import net.minecraft.resources.ResourceLocation;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Shared diagnostic boundary never adds prompt content or invokes a second legacy reader. */
public final class RecordedRetrievalShadowTest {
    private static int checks;
    private static final UUID MESSAGE=UUID.randomUUID();
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static Request request(){
        var player=UUID.randomUUID();var god=ResourceLocation.parse("test:athena");
        return new Request(UUID.randomUUID(),1,UUID.randomUUID(),player,"Fixture",List.of(god),god,"안녕",
                List.of(),false,true,false,List.of(new GodState(god,"R_NEUTRAL","E_NEUTRAL","",null)),false,Set.of(player));
    }
    private static final class Session implements MemoryReadSession{
        final List<Page> pages=new ArrayList<>();boolean current=true,async;CompletableFuture<Page> pending;
        @Override public CompletableFuture<Page> query(Query query,Optional<Cursor> cursor,Budget budget){
            var page=new Page(Status.FOUND,List.of(new Entry(MESSAGE,new ActorRef(ActorKind.GOD,"test:athena"),
                    Instant.EPOCH,"quoted fixture",false)),Optional.empty());pages.add(page);
            if(async){pending=new CompletableFuture<>();return pending;}
            return CompletableFuture.completedFuture(page);
        }
        @Override public boolean current(Page page){return current&&pages.stream().anyMatch(p->p==page);}
    }
    private static RecordedRetrievalCoordinator.Options options(){return new RecordedRetrievalCoordinator.Options(8,32768,4,2,1000,false,false);}
    private static RecordedRetrievalShadow.Selection selection(){return new RecordedRetrievalShadow.Selection(Set.of(MESSAGE),Set.of(),Set.of());}
    private static void compare(Session session,Request request,AtomicInteger opens){
        RecordedRetrievalShadow.compare(()->{opens.incrementAndGet();return Optional.of(session);},request,Optional.empty(),selection(),
                prepared->{throw new AssertionError("ordinary chat queried embedding backend");},options(),Runnable::run);
    }
    public static void main(String[] args){
        var before=RecordedRetrievalShadow.diagnostics();var opens=new AtomicInteger();var request=request();
        var session=new Session();compare(session,request,opens);var after=RecordedRetrievalShadow.diagnostics();
        check(opens.get()==1&&session.pages.size()==1,"one game-issued session and one raw lane, no duplicate comparisons");
        check(after.get("boundedCompleted")==before.get("boundedCompleted")+1,"bounded diagnostic can complete with unavailable optional lanes");
        check(after.get("rawMatches")==before.get("rawMatches")+1,"selected legacy message compared without fetching legacy again");
        check(after.get("unavailableLanes")>before.get("unavailableLanes"),"unsupported lanes do not become false empty results");
        check(request.history().isEmpty()&&request.currentText().equals("안녕"),"request and foreground history remain unchanged");
        var a=new Session();a.async=true;var b=new Session();b.async=true;before=RecordedRetrievalShadow.diagnostics();
        compare(a,request(),new AtomicInteger());compare(b,request(),new AtomicInteger());a.current=false;
        a.pending.complete(a.pages.getFirst());b.pending.complete(b.pages.getFirst());after=RecordedRetrievalShadow.diagnostics();
        check(after.get("unavailable")==before.get("unavailable")+1,"revoked session drops its diagnostics");
        check(after.get("boundedCompleted")==before.get("boundedCompleted")+1,"independent session continues");
        before=RecordedRetrievalShadow.diagnostics();
        RecordedRetrievalShadow.compare(Optional::empty,request,Optional.empty(),selection(),p->{throw new AssertionError();},options(),Runnable::run);
        check(before.equals(RecordedRetrievalShadow.diagnostics()),"missing authority is a no-op, no model work");
        RecordedRetrievalShadow.compare(()->{throw new IllegalStateException();},request,Optional.empty(),selection(),
                p->CompletableFuture.completedFuture(Optional.empty()),options(),Runnable::run);
        check(RecordedRetrievalShadow.diagnostics().get("unavailable")==before.get("unavailable")+1,"opening failure isolated");
        before=RecordedRetrievalShadow.diagnostics();
        RecordedRetrievalShadow.compare(()->Optional.of(new Session()),request,Optional.empty(),selection(),
                p->CompletableFuture.completedFuture(Optional.empty()),options(),task->{throw new RejectedExecutionException();});
        check(RecordedRetrievalShadow.diagnostics().get("unavailable")==before.get("unavailable")+1,"dispatcher failure isolated");
        var s=new Session();var plan=RecordedRecallQuery.prepare(request,Optional.empty()).orElseThrow();
        var bundle=RecordedRetrievalCoordinator.collect(s,request,plan,Optional.empty(),options(),Runnable::run).join();
        before=RecordedRetrievalShadow.diagnostics();
        RecordedRetrievalShadow.observe(bundle,RecordedRetrievalBundle.Scope.of(request()),selection());
        check(RecordedRetrievalShadow.diagnostics().get("unavailable")==before.get("unavailable")+1,"foreign room scope rejected");
        s.current=false;before=RecordedRetrievalShadow.diagnostics();
        RecordedRetrievalShadow.observe(bundle,RecordedRetrievalBundle.Scope.of(request),selection());
        check(RecordedRetrievalShadow.diagnostics().get("unavailable")==before.get("unavailable")+1,"previously returned bundle revalidated");
        check(RecordedRetrievalShadow.diagnostics().values().stream().allMatch(v->v>=0),"diagnostics contain counts only");
        System.out.println("RecordedRetrievalShadowTest: "+checks+" checks passed");
    }
}
