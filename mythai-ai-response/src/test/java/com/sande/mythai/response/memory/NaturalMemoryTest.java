package com.sande.mythai.response.memory;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Local raw fixtures only: no model, embedding, Minecraft bootstrap, or live files. */
public final class NaturalMemoryTest {
    static int checks;
    static final long NOW=Instant.parse("2026-09-20T03:00:00Z").toEpochMilli();
    static final UUID PLAYER=UUID.randomUUID(),SESSION=UUID.randomUUID();
    static final MemoryJournal.Key KEY=new MemoryJournal.Key(UUID.randomUUID(),"mythictrpg:fortuna",PLAYER);
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static MemoryJournal.Entry entry(String text,long age) {
        return new MemoryJournal.Entry(UUID.randomUUID(),KEY,SESSION,1,MemoryJournal.Source.PLAYER_STATEMENT,Set.of(PLAYER),NOW-age,text,false);
    }
    static RecallSearch.Result search(List<MemoryJournal.Entry> entries,String question) {
        var scope=new RecallQuery.Scope(KEY,UUID.randomUUID(),Set.of(PLAYER));
        return RecallSearch.search(new MemoryJournal.ReadView(KEY,Set.of(PLAYER),entries,Set.of(),true,false),
                RecallQuery.plan(scope,question,1,NOW,null),new RecallSettings(true,RecallSettings.TimeBasis.REAL_KST),Set.of(),List.of(),NOW,1_000_000_000);
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory(Files.createDirectories(Path.of(args[0])),"natural-memory-");
        var promise=entry("성소를 다시 찾아가기로 약속했어",Duration.ofDays(90).toMillis());
        var casual=entry("성소 근처의 나무가 예뻤어",Duration.ofDays(90).toMillis());
        check(MemorySalience.protectedCandidate(promise),"old promise is salient, not automatically true");
        check(MemorySalience.searchable(promise,NOW,false)&&!MemorySalience.searchable(casual,NOW,false),"casual association fades while promise persists");
        check(MemorySalience.searchable(casual,NOW,true),"explicit old raw recollection still possible");
        check(MemorySalience.adjustment(promise,NOW)>MemorySalience.adjustment(casual,NOW),"age does not erase important salience");
        for(String text:List.of("친구가 내일 간다고 했어","내일 간다면 좋겠어","내일 간다는 건 농담이야","내가 내일 어디간다고 했더라"))
            check(!MemorySalience.protectedCandidate(entry(text,0)),"not a personal commitment: "+text);
        var correction=entry("성소가 아니라 항구로 가기로 정정했어",1000);
        var fulfilled=entry("항구에 찾아가기로 한 약속은 지켰어",500);
        check(MemorySalience.protectedCandidate(correction)&&MemorySalience.protectedCandidate(fulfilled),"correction and reported fulfillment prioritized");
        var repetitive=new ArrayList<MemoryJournal.Entry>();
        repetitive.add(promise);repetitive.add(correction);repetitive.add(fulfilled);
        for(int i=0;i<20;i++)repetitive.add(entry(promise.text(),100+i));
        var result=search(repetitive,"내 약속 뭐라고 했지");
        check(result.selected().size()==3&&result.selected().containsAll(List.of(correction,fulfilled)),"repetition cannot displace correction and fulfillment");
        check(result.status()==RecallSearch.Status.AMBIGUOUS,"no inferred game completion from conflicting/self-reported words");
        check(MemorySalience.daylightCondition("해가 뜨면 찾아갈게").equals("MINECRAFT_SUNRISE"),"sunrise belongs to Minecraft");
        check(MemorySalience.daylightCondition("해 질 때 만나자").equals("MINECRAFT_SUNSET"),"sunset belongs to Minecraft");
        check(RecallSearch.date("해가 뜨면 찾아갈게",NOW,RecallSettings.TimeBasis.REAL_KST)==null,"daylight not converted into real timestamp");
        var daylight=search(List.of(entry("내일 해가 뜨면 찾아갈게",0)),"내일 내 계획 뭐였지");
        String packed=MemoryRecallPolicy.pack(daylight,List.of(),new RecallSettings(true,RecallSettings.TimeBasis.REAL_KST)).prompt();
        check(packed.contains("2026-09-21")&&packed.contains("MINECRAFT_SUNRISE"),"calendar day and daylight are distinct context fields");
        var limits=new MemoryRetentionSettings(1,4,4,65536,2,2);
        Path journalPath=root.resolve("reserved");
        List<MemoryJournal.Entry> saved=new ArrayList<>();
        try(var journal=new MemoryJournal(journalPath,limits)) {
            check(journal.awaitIdle(Duration.ofSeconds(5))&&journal.ready(),"journal ready");
            for(String text:List.of("오늘 바람이 시원하네","꽃이 예쁘네")) {
                var e=entry(text,0);saved.add(e);
                check(journal.append(e).get(5,TimeUnit.SECONDS)==MemoryJournal.Result.STORED,"casual admitted before reserve");
            }
            check(journal.append(entry("돌 색깔이 특이해",0)).get(5,TimeUnit.SECONDS)==MemoryJournal.Result.FULL,"casual cannot consume protected reserve");
            for(var e:List.of(promise,fulfilled)) {
                saved.add(e);check(journal.append(e).get(5,TimeUnit.SECONDS)==MemoryJournal.Result.STORED,"important uses explicit reserve");
            }
            check(journal.append(correction).get(5,TimeUnit.SECONDS)==MemoryJournal.Result.FULL,"hard limit even for important; no eviction");
            check(journal.view().entries().equals(saved),"full never deletes existing raw entries");
        }
        try(var journal=new MemoryJournal(journalPath,limits)) {
            check(journal.awaitIdle(Duration.ofSeconds(5))&&journal.view().entries().equals(saved),"raw and configured reserve survive restart");
        }
        var bytesLimit=new MemoryRetentionSettings(1,1000,1000,65536,0,0);
        Path bounded=root.resolve("bytes");int written=0;
        try(var journal=new MemoryJournal(bounded,bytesLimit)) {
            journal.awaitIdle(Duration.ofSeconds(5));
            for(int i=0;i<1000;i++) {
                var status=journal.append(entry("바위 "+i+" 조각".repeat(200),0)).get(5,TimeUnit.SECONDS);
                if(status==MemoryJournal.Result.FULL)break;
                check(status==MemoryJournal.Result.STORED,"no checkpoint-budget exception after durable append");written++;
            }
            check(written>0&&written<1000&&journal.capacity().usedBytes()<=65536,"peak storage budget respected including snapshot staging");
        }
        try(var journal=new MemoryJournal(bounded,bytesLimit)) {
            check(journal.awaitIdle(Duration.ofSeconds(5))&&journal.ready()&&journal.view().entries().size()==written,"capacity refusal preserves restartable raw history");
        }
        check(MemoryRetentionSettings.load(root.resolve("absent.json")).equals(MemoryRetentionSettings.DEFAULT),"no policy silently enabled");
        Path invalid=root.resolve("invalid.json");Files.writeString(invalid,"{\"schemaVersion\":99}");
        try(var journal=new MemoryJournal(root.resolve("invalid-journal"),invalid)) {
            journal.awaitIdle(Duration.ofSeconds(5));check(journal.failed()&&!journal.ready(),"invalid settings fail closed");
        }
        System.out.println("NaturalMemoryTest: PASS ("+checks+" checks); artifacts="+root);
    }
}
