package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.gameplay.ledger.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Deterministic authority, persistence and inference checks. Not a satisfaction/LLM/UI evaluation. */
public final class PlayerUnderstandingTest {
    private static final UUID W = UUID.randomUUID(), P = UUID.randomUUID(), OTHER = UUID.randomUUID(), SESSION = UUID.randomUUID();
    private static int checks;
    private static final ActionLedgerStore.Limits LIMITS = new ActionLedgerStore.Limits(16 * 1024 * 1024, 65536, 10000);
    private static ActionRecord.Draft draft(long n, ActionRecord.Type type, UUID actor, Map<String,String> payload, UUID run, Set<UUID> participants) {
        String kind = switch(type) {
            case MATURE_CROP_REMOVED, BLOCK_REMOVED -> "BLOCK";
            case BATTLE_RESULT, RAID_CHOICE -> "BATTLE";
            default -> "ACTIVITY";
        };
        String id = kind.equals("ACTIVITY") ? "mythictrpg:player_understanding" : kind.equals("BATTLE") ? "mythictrpg:raid_one" : "minecraft:wheat";
        String source = type == ActionRecord.Type.MATURE_CROP_REMOVED ? "mythictrpg:crop_remove_commit" : "mythictrpg:detail/" + type.name().toLowerCase(Locale.ROOT);
        return new ActionRecord.Draft(UUID.randomUUID(), SESSION, n, source, 1, actor, new ActionRecord.Subject(kind, id, null),
                1800000000000L + n, n * 12000, n, "minecraft:overworld", new ActionRecord.Position(1,64,2), type, "COMPLETED", payload,
                "mythictrpg:admin_only_unprojected", new ActionRecord.Details(null, "AT_TRANSITION", run, participants));
    }
    private static ActionRecord record(long n, ActionRecord.Type type, UUID actor, Map<String,String> payload) {
        return new ActionRecord(1, W, n, draft(n,type,actor,payload,null,Set.of(actor)));
    }
    private static ActionRecord start(long n) { return record(n,ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,P,Map.of("operation","START")); }
    private static PlayerUnderstanding.View project(ActionRecord control,List<ActionRecord> events,List<ActionRecord> feedback, boolean gap) {
        return PlayerUnderstanding.project(W,P,new PlayerUnderstanding.Slice(control,events,feedback,10000,false),gap);
    }
    private static void check(boolean condition,String message) { checks++; if(!condition)throw new AssertionError(message); }
    private static PlayerUnderstanding.Card card(PlayerUnderstanding.View view,String key) { return view.cards().stream().filter(c->c.key().equals(key)).findFirst().orElseThrow(); }
    public static void main(String[] args) throws Exception {
        check(!project(null,List.of(),List.of(),false).active(),"no implicit old-data import");
        check(!PlayerUnderstanding.project(W,P,new PlayerUnderstanding.Slice(start(2),List.of(),List.of(),1,false),false).active(),"future boundary cannot authorize old snapshot");
        var old=record(1,ActionRecord.Type.MATURE_CROP_REMOVED,P,Map.of());
        var view=project(start(2),List.of(old),List.of(),false);
        check(card(view,"FARMING").assessment().equals("근거 부족"),"pre-boundary excluded");
        var crops=new ArrayList<ActionRecord>();
        for(int i=3;i<6;i++)crops.add(record(i,ActionRecord.Type.MATURE_CROP_REMOVED,P,Map.of()));
        view=project(start(2),crops,List.of(),false);
        check(card(view,"FARMING").assessment().startsWith("반복 관측"),"independent episodes");
        check(card(view,"FARMING").evidence().size()==3,"bounded self evidence");
        check(card(view,"COMBAT").assessment().equals("근거 부족"),"absence not negative preference");
        check(card(view,"RAID_MECHANIC").assessment().contains("근거 부족"),"no fake raid personality");
        var duplicate=project(start(2),List.of(crops.getFirst(),crops.getFirst(),crops.getFirst()),List.of(),false);
        check(card(duplicate,"FARMING").explanation().contains("기록 1건"),"occurrence dedup");
        check(card(duplicate,"FARMING").assessment().startsWith("단일"),"no inflation by duplicate");
        var foreign=record(6,ActionRecord.Type.MATURE_CROP_REMOVED,OTHER,Map.of());
        check(card(project(start(2),List.of(foreign),List.of(),false),"FARMING").evidence().isEmpty(),"other player exclusion");
        var otherWorld=new ActionRecord(1,UUID.randomUUID(),7,crops.getFirst().event());
        check(card(project(start(2),List.of(otherWorld),List.of(),false),"FARMING").evidence().isEmpty(),"other world exclusion");
        var test=record(7,ActionRecord.Type.MATURE_CROP_REMOVED,P,Map.of("test","true"));
        check(card(project(start(2),List.of(test),List.of(),false),"FARMING").evidence().isEmpty(),"test exclusion");
        var dispute=record(8,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,P,Map.of("key","FARMING","operation","DISPUTE"));
        view=project(start(2),crops,List.of(dispute),true);
        check(card(view,"FARMING").disputed(),"correction visible");
        check(card(view,"FARMING").evidence().size()==3,"correction retains facts");
        check(view.notice().contains("누락"),"coverage warning");
        var restore=record(9,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,P,Map.of("key","FARMING","operation","RESTORE"));
        check(!card(project(start(2),crops,List.of(restore,dispute),false),"FARMING").disputed(),"newest correction regardless order");
        check(!card(project(start(9),crops,List.of(dispute),false),"FARMING").disputed(),"restart boundary does not import old correction");
        var purpose=record(10,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,P,Map.of("key","purpose","operation","DECLARE","text","OOO 만나기"));
        check(card(project(start(2),crops,List.of(purpose),false),"purpose").assessment().contains("진술"),"statement not proven intent");
        var retract=record(11,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,P,Map.of("key","purpose","operation","RETRACT","text",""));
        check(project(start(2),crops,List.of(retract,purpose),false).cards().stream().noneMatch(c->c.key().equals("purpose")),"withdrawn purpose not active");
        var battles=new ArrayList<ActionRecord>();
        for(int i=12;i<15;i++)battles.add(new ActionRecord(1,W,i,draft(i,ActionRecord.Type.BATTLE_RESULT,OTHER,
                Map.of("battle_result","DEFEAT"),UUID.randomUUID(),Set.of(P,OTHER))));
        view=project(start(2),battles,List.of(),false);
        check(card(view,"RAID").evidence().size()==3,"roster participant not just recorder");
        check(card(view,"RAID_INTEREST").explanation().contains("알 수 없습니다"),"competing motives remain unresolved");
        check(card(view,"COMBAT").evidence().isEmpty(),"raid result not personal damage");
        var aborted=new ActionRecord(1,W,20,draft(20,ActionRecord.Type.BATTLE_RESULT,P,Map.of("battle_result","ABORTED"),UUID.randomUUID(),Set.of(P)));
        check(card(project(start(2),List.of(aborted),List.of(),false),"RAID").evidence().isEmpty(),"server abort not character evidence");
        var pause=record(21,ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,P,Map.of("operation","PAUSE"));
        check(!project(pause,crops,List.of(purpose),false).active(),"pause disables projection");
        check(!project(record(22,ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,OTHER,Map.of("operation","START")),crops,List.of(),false).active(),"foreign boundary denied");
        check(!PlayerUnderstanding.validFeedbackKey("RAID_SECRET")&&!PlayerUnderstanding.validFeedbackKey(null),"allowlisted correction keys");
        try { record(23,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,P,Map.of("key","purpose","operation","DISPUTE")); throw new AssertionError("malformed note accepted"); }
        catch(IllegalArgumentException expected) { checks++; }
        Path parent=Path.of(args[0]);Files.createDirectories(parent);Path root=Files.createTempDirectory(parent,"understanding-");
        persistence(root);bounded(root.resolve("bounded"));capacity(root.resolve("capacity"));choices();
        System.out.println("PlayerUnderstandingTest: PASS ("+checks+" checks); no LLM, production server or client UI executed; artifacts="+root);
    }
    private static void persistence(Path root)throws Exception {
        UUID occurrence;
        try(var store=new ActionLedgerStore(root,W,LIMITS)) {
            store.append(draft(1,ActionRecord.Type.MATURE_CROP_REMOVED,P,Map.of(),null,Set.of(P)));
            store.append(draft(2,ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,P,Map.of("operation","START"),null,Set.of(P)));
            var evidence=store.append(draft(3,ActionRecord.Type.MATURE_CROP_REMOVED,P,Map.of(),null,Set.of(P)));occurrence=evidence.event().occurrenceId();
            store.append(draft(4,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,P,Map.of("operation","DISPUTE","key","FARMING"),null,Set.of(P)));
            var slice=store.understanding(P);check(slice.events().size()==2,"index bounded after enrollment");
        }
        try(var ledger=new AsyncActionLedger(root,W,LIMITS,8)) {
            ledger.ready().get(5,TimeUnit.SECONDS);var slice=ledger.understanding(P).get(5,TimeUnit.SECONDS);
            check(slice.events().stream().anyMatch(r->r.event().occurrenceId().equals(occurrence)),"durable evidence after restart");
            check(card(PlayerUnderstanding.project(W,P,slice,false),"FARMING").disputed(),"durable correction after restart");
            check(ledger.understanding(OTHER).get(5,TimeUnit.SECONDS).control()==null,"other player no enrollment");
        }
    }
    private static void bounded(Path root)throws Exception {
        try(var store=new ActionLedgerStore(root,W,LIMITS)) {
            store.append(draft(1,ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,P,Map.of("operation","START"),null,Set.of(P)));
            store.append(draft(2,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,P,Map.of("operation","DISPUTE","key","FARMING"),null,Set.of(P)));
            for(int n=3;n<410;n++)store.append(draft(n,ActionRecord.Type.MATURE_CROP_REMOVED,P,Map.of(),null,Set.of(P)));
            var slice=store.understanding(P);
            check(slice.truncated()&&slice.events().size()==400,"bounded window reports truncation");
            check(card(PlayerUnderstanding.project(W,P,slice,false),"FARMING").disputed(),"correction survives outside recent window");
        }
    }
    private static void capacity(Path root)throws Exception {
        try(var store=new ActionLedgerStore(root,W,new ActionLedgerStore.Limits(131072,65536,1))) {
            store.append(draft(1,ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL,P,Map.of("operation","START"),null,Set.of(P)));
            try {
                store.append(draft(2,ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK,P,Map.of("operation","DISPUTE","key","FARMING"),null,Set.of(P)));
                throw new AssertionError("capacity rejected correction appeared durable");
            } catch(java.io.IOException expected) { checks++; }
            check(store.understanding(P).feedback().isEmpty()&&store.size()==1,"rejected correction neither persisted nor replaced original control");
        }
    }
    private static void choices() {
        var rows=new ArrayList<ActionRecord>();
        long seq=30;
        for(int run=0;run<3;run++) {
            UUID id=UUID.randomUUID();
            for(int i=0;i<3;i++) {
                var choice=new RaidChoiceEvidence(UUID.randomUUID(),RaidChoiceEvidence.Kind.MECHANIC,
                        i==2?RaidChoiceEvidence.Result.NOT_CHOSEN:RaidChoiceEvidence.Result.CHOSEN,"seal","v1",false);
                rows.add(new ActionRecord(1,W,seq,draft(seq++,ActionRecord.Type.RAID_CHOICE,P,choice.payload(),id,Set.of(P))));
            }
        }
        var view=project(start(2),rows,List.of(),false);
        check(card(view,"RAID_MECHANIC").assessment().contains("반복 선택"),"independent runs and opportunities");
        check(card(view,"RAID_MECHANIC").explanation().contains("선택 6 / 유효 기회 9"),"choices distinct from outcomes");
        check(card(view,"RAID_SUPPORT").assessment().contains("근거 부족"),"absent support opportunity not uncooperative");
        var test=new RaidChoiceEvidence(UUID.randomUUID(),RaidChoiceEvidence.Kind.MECHANIC,RaidChoiceEvidence.Result.CHOSEN,"seal","v1",true);
        var trial=new ActionRecord(1,W,seq,draft(seq++,ActionRecord.Type.RAID_CHOICE,P,test.payload(),UUID.randomUUID(),Set.of(P)));
        check(card(project(start(2),List.of(trial),List.of(),false),"RAID_MECHANIC").evidence().isEmpty(),"trial choice excluded");
        var unavailable=new RaidChoiceEvidence(UUID.randomUUID(),RaidChoiceEvidence.Kind.MECHANIC,RaidChoiceEvidence.Result.UNAVAILABLE,"seal","v1",false);
        var lost=new ActionRecord(1,W,seq,draft(seq++,ActionRecord.Type.RAID_CHOICE,P,unavailable.payload(),UUID.randomUUID(),Set.of(P)));
        check(card(project(start(2),List.of(lost),List.of(),false),"RAID_MECHANIC").evidence().isEmpty(),"unavailable not denominator");
        var repeated=project(start(2),List.of(rows.getFirst(),rows.getFirst()),List.of(),false);
        check(card(repeated,"RAID_MECHANIC").explanation().contains("유효 기회 1"),"duplicate choice not repeated evidence");
    }
}
