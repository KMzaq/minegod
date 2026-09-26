package com.sande.mythictrpg.ai.experiencecontract;

import com.sande.mythai.response.memory.*;
import com.sande.mythictrpg.ai.memorycontract.ConversationMemoryContext;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.watch.*;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Package-private game lease constructor is used ONLY by this offline authority fixture, never AI production code. */
public final class ExperienceDialogueTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static <T> T get(CompletableFuture<T> f) throws Exception { return f.get(10,TimeUnit.SECONDS); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of(args[0])), "experience-dialogue-");
        UUID world=UUID.randomUUID(), player=UUID.randomUUID(), boot=UUID.randomUUID(); String god="mythictrpg:fortuna";
        Ref disclosure=new Ref("test:owner",1);
        var rawLimits = new ActionLedgerStore.Limits(4_000_000,128_000,5000);
        var watchLimits = new AsyncGodWatch.Limits(4_000_000,5000,64);
        var context = new ConversationMemoryContext(world,UUID.randomUUID(),UUID.randomUUID(),player,god,Set.of(player),false);
        var audience = new Audience(world,new Key(god,player),Set.of(player),new Ref(context.generation().toString(),1));
        UUID eventId = UUID.randomUUID();
        var battle=new ExperienceView.Event(UUID.randomUUID(),UUID.randomUUID(),1,"DIRECT_WATCH","BATTLE_RESULT","test:raid","DEFEAT","tick=40");
        var mining=new ExperienceView.Event(UUID.randomUUID(),UUID.randomUUID(),1,"DIRECT_WATCH","OBSERVED_ACTIVITY_SUMMARY","minecraft:stone","VISIBLE_SAMPLES=2;ACTIVITY=BLOCK_REMOVED","tick=40");
        var typed=new ExperienceView(1,true,"READY",List.of(battle,mining),ExperienceView.Relationship.UNKNOWN);
        check(ExperienceMemory.select(typed,"레이드 전투 봤어?",List.of()).events().equals(List.of(battle)),"named battle outcome selected without promoting defeat to victory");
        check(ExperienceMemory.select(typed,"내 채굴 봤어?",List.of()).events().equals(List.of(mining)),"only visible mining samples selected");
        check(ExperienceMemory.select(typed,"내 농사 봤어?",List.of()).events().isEmpty(),"unrelated mining summary is not farming evidence");
        check(ExperienceMemory.prompt(typed,new ExperienceMemory.Selection(List.of(mining),true)).contains("not all player activity or hidden gaps"),"summary never becomes total stats");
        try (var raw = new AsyncActionLedger(root.resolve("raw"),world,rawLimits,64)) {
            get(raw.ready());
            try (var watch = new AsyncGodWatch(root.resolve("watch"),world,boot,raw,watchLimits)) {
                get(watch.ready());
                get(watch.disclose(new Disclosure(disclosure,Set.of(player))));
                get(watch.start(UUID.randomUUID(),new Approval(world,new Key(god,player),new Ref("test:eligible",1),new Ref("test:start",1),
                        new Policy(new Ref("test:policy",1),god,"test:sight","test:crop",List.of(new Area("minecraft:overworld",-1,0,-1,1,100,1)),Set.of(Field.values())))));
                var draft=new ActionRecord.Draft(eventId,boot,1,"mythictrpg:crop_remove_commit",1,player,
                        new ActionRecord.Subject("BLOCK","minecraft:wheat",null),12345,40,6000,"minecraft:overworld",new ActionRecord.Position(0,64,0),
                        ActionRecord.Type.MATURE_CROP_REMOVED,"COMPLETED",Map.of("hidden","NEVER_LEAK"),"mythictrpg:admin_only_unprojected");
                Map<Field,Visibility> fields=new EnumMap<>(Field.class);
                for(Field f:List.of(Field.ACTOR,Field.ACTION,Field.SUBJECT_TYPE,Field.OUTCOME,Field.TIME)) fields.put(f,new Visibility(true,Map.of(player,disclosure)));
                get(watch.capture(draft,new Scene(eventId,boot,1,new Ref("test:scene",1),Set.of("test:sight"),Set.of("test:crop"),false,false,fields)).observed());
            }
        }
        // A different process-like lifetime and conversation generation reads persisted observations, not old dialogue state.
        try (var raw = new AsyncActionLedger(root.resolve("raw"),world,rawLimits,64)) {
            get(raw.ready());
            try (var watch = new AsyncGodWatch(root.resolve("watch"),world,UUID.randomUUID(),raw,watchLimits)) {
                get(watch.ready());
                var snapshot=get(watch.read(audience,16));
                var view=ExperienceProjection.project(snapshot.view(),player,new ExperienceView.Relationship(true,275));
                AtomicBoolean sameSession=new AtomicBoolean(true);
                var lease=new ExperienceLease(view,ids -> sameSession.get() && watch.current(snapshot,audience));
                var personal=new DialogueMemoryBridge.Turn(context,null,List.of(),List.of(),"",1);
                var combined=DialogueMemoryBridge.combine(personal,lease,"내가 밀 수확하는 걸 봤어?",List.of());
                check(combined.hasExperiences() && combined.recalling(),"persisted crop selected after new conversation");
                check(combined.prompt().contains("DIRECT_OBSERVATION") && combined.prompt().contains("MATURE_CROP_REMOVED"),"actual typed prompt-data source and event");
                check(combined.prompt().contains("BLOCK_REMOVED_NOT_ITEM_ACQUISITION"),"no fake loot/reward completion");
                check(combined.prompt().contains("275") && combined.prompt().contains("UNDEFINED"),"actual affinity, no invented tier");
                check(!combined.prompt().contains("NEVER_LEAK") && !combined.prompt().contains(eventId.toString()) && !combined.prompt().contains(player.toString()),"no hidden fields/internal identities in prompt");
                check(combined.prompt().length() < 1300,"small bounded observed context");
                check(combined.experience().current(combined.observations().ids()),"selected lease current before generation");
                var history = new ExperienceHistory();
                history.record("네가 밀을 거두는 걸 봤지.",combined.evidence());
                var inherited = DialogueMemoryBridge.inherit(personal,history.references(Set.of("네가 밀을 거두는 걸 봤지.")));
                check(!inherited.hasExperiences() && inherited.hasGuardedEvidence(),"follow-up carries provenance even without fresh retrieval");
                history.record("그 장면이 인상적이었거든.",inherited.evidence());
                check(history.excluded(List.of("네가 밀을 거두는 걸 봤지.","그 장면이 인상적이었거든.")).isEmpty(),"valid paraphrases retain short-term conversation continuity");
                check(!DialogueMemoryBridge.combine(personal,lease,"심심해",List.of()).hasExperiences(),"irrelevant small talk does not drag in farming");
                check(!DialogueMemoryBridge.combine(personal,lease,"그 비밀 기억나?",List.of()).hasExperiences(),"secret does not substring-match wheat in Korean");
                check(!DialogueMemoryBridge.combine(personal,lease,"친밀해지고 싶어",List.of()).hasExperiences(),"intimacy does not substring-match wheat in Korean");
                check(!DialogueMemoryBridge.combine(personal,lease,"수영 못한다고 말했던 거 기억나?",List.of()).hasExperiences(),"personal recall is not arbitrary action recall");
                check(!DialogueMemoryBridge.combine(personal,lease,"당근 수확을 봤어?",List.of()).hasExperiences(),"different crop not substituted");
                check(DialogueMemoryBridge.combine(personal,lease,"그거 기억나?",List.of("밀 수확했는데")).hasExperiences(),"dependent follow-up uses player's topic");
                var noGod=get(watch.read(new Audience(world,new Key("mythictrpg:amphitrite",player),Set.of(player),new Ref("test:other",1)),16));
                check(ExperienceMemory.select(ExperienceProjection.project(noGod.view(),player,ExperienceView.Relationship.UNKNOWN),"밀 수확 봤어?",List.of()).events().isEmpty(),"god B cannot recall unobserved event");
                var noAudience=get(watch.read(new Audience(world,new Key(god,player),Set.of(player,UUID.randomUUID()),new Ref("test:group",1)),16));
                check(ExperienceProjection.project(noAudience.view(),player,ExperienceView.Relationship.UNKNOWN).events().isEmpty(),"changed audience excludes event before AI sees it");
                var key=new MemoryJournal.Key(world,god,player); List<MemoryJournal.Entry> words=new ArrayList<>();
                for(int i=0;i<3;i++) words.add(new MemoryJournal.Entry(UUID.randomUUID(),key,context.generation(),i+1,MemoryJournal.Source.PLAYER_STATEMENT,Set.of(player),i+1,"내일 밀 수확하겠다는 계획"+i,false));
                var withWords=DialogueMemoryBridge.combine(new DialogueMemoryBridge.Turn(context,null,words,List.of(),DialogueMemoryBridge.prompt(words,List.of()),4),lease,"밀 수확 기억나?",List.of());
                check(withWords.selected().size()+withWords.rumors().size()+withWords.observations().events().size()<=3,"one shared record budget");
                check(withWords.prompt().contains("PLAYER_STATEMENT") && withWords.prompt().contains("DIRECT_OBSERVATION"),"claim vs direct observation remain distinct");
                check(withWords.prompt().contains("reward payout are NOT established"),"past observation grants no action authority");
                sameSession.set(false); check(!combined.experience().current(combined.observations().ids()),"new session rejects late generated answer"); sameSession.set(true);
                get(watch.revokeEvent(eventId)); check(!combined.experience().current(combined.observations().ids()),"revocation rejects generated answer and derived transcript");
                check(inherited.evidence().stream().noneMatch(ExperienceHistory.Reference::current),"multi-hop paraphrase retains revocation guard");
                check(history.excluded(List.of("네가 밀을 거두는 걸 봤지.","그 장면이 인상적이었거든.")).size()==2,"revoked original and paraphrase removed together");
                check(!get(watch.read(audience,16)).view().proofs().contains(snapshot.view().proofs().getFirst()),"revocation visible to next retrieval");
                check(DialogueMemoryBridge.combine(personal,ExperienceLease.unavailable("OFF"),"밀 수확 봤어?",List.of())==personal,"OFF preserves prior memory path");
            }
        }
        String source=Files.readString(Path.of(args[1]));
        check(source.contains("session.memoryTurn.referenceContext() + examples") && source.contains("new StringBuilder(session.memoryTurn.referenceContext())"),"same context in compiled classification and generation attachment");
        check(source.contains("DialogueMemoryBridge.current(player, session.memoryTurn)") && source.contains("session.pending = false"),"final response/proposal path revalidates and releases pending");
        check(source.contains("Observed experience is not authorized for another speaker"),"primary god evidence cannot become another speaker's reply");
        check(source.contains("DialogueMemoryBridge.excludedHistory(player, god,") && source.contains("pruneExperienceHistory(player, session, session.speaker())"),"revoked derived transcript filtered before context collection");
        check(source.split("DialogueMemoryBridge.beginTriggered",-1).length==3 && source.split("pruneExperienceHistory\\(player, session, godId\\)",-1).length==3,"both quest acknowledgements start fresh scoped turn and prune provenance");
        check(source.contains("Only OBSERVED_EXPERIENCE_DATA supplies past observed action facts"),"no contradictory no-world-facts instruction");
        check(source.contains("session.turn != turn") && source.contains("DialogueMemoryBridge.accept(player, memory)"),"session/turn gates preserved");
        System.out.println("ExperienceDialogueTest: PASS ("+checks+" checks); artifacts="+root+"; actual LLM/HUD not run");
    }
}
