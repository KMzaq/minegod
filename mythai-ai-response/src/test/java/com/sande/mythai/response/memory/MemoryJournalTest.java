package com.sande.mythai.response.memory;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class MemoryJournalTest {
    private static int checks;
    private static final UUID WORLD = UUID.randomUUID(), PLAYER = UUID.randomUUID();
    private static final MemoryJournal.Key KEY = new MemoryJournal.Key(WORLD, "mythictrpg:demeter", PLAYER);
    private static final long NOW = System.currentTimeMillis();
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static MemoryJournal.Entry entry(String text, boolean important, long time) {
        return new MemoryJournal.Entry(UUID.randomUUID(), KEY, UUID.randomUUID(), 1, MemoryJournal.Source.PLAYER_STATEMENT, Set.of(PLAYER), time, text, important);
    }
    private static MemoryJournal.Result waitFor(CompletableFuture<MemoryJournal.Result> future) throws Exception { return future.get(15, TimeUnit.SECONDS); }
    private static List<MemoryJournal.Entry> search(MemoryJournal journal, String query) { return journal.search(KEY, Set.of(PLAYER), query, Set.of(), NOW, 3, 20_000_000); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "memory-test-");
        Path main = root.resolve("main");
        MemoryJournal.Entry first = entry("나는 깊은 강에서 수영하는 것이 무서워", false, NOW);
        try (var journal = new MemoryJournal(main)) {
            check(waitFor(journal.append(first)) == MemoryJournal.Result.STORED, "first write queued behind async load");
            check(journal.ready(), "ready after load");
            try (var duplicate = new MemoryJournal(main)) {
                check(!duplicate.awaitIdle(Duration.ofSeconds(5)) && duplicate.failed(), "second writer rejected");
            }
            check(waitFor(journal.append(first)) == MemoryJournal.Result.DUPLICATE, "duplicate turn id");
            check(search(journal, "깊은 강에서 수영").size() == 1, "Korean lexical retrieval");
            check(journal.search(new MemoryJournal.Key(WORLD,"mythictrpg:fortuna",PLAYER),Set.of(PLAYER),"깊은 강 수영",Set.of(),NOW,3,20_000_000).isEmpty(), "god isolation");
            check(journal.search(new MemoryJournal.Key(UUID.randomUUID(),KEY.god(),PLAYER),Set.of(PLAYER),"깊은 강 수영",Set.of(),NOW,3,20_000_000).isEmpty(), "world isolation");
            UUID stranger = UUID.randomUUID();
            check(journal.search(new MemoryJournal.Key(WORLD,KEY.god(),stranger),Set.of(stranger),"깊은 강 수영",Set.of(),NOW,3,20_000_000).isEmpty(), "player isolation");
            check(journal.search(KEY,Set.of(PLAYER,stranger),"깊은 강 수영",Set.of(),NOW,3,20_000_000).isEmpty(), "audience privacy");
            check(journal.search(KEY,Set.of(PLAYER),"깊은 강 수영",Set.of(first.text()),NOW,3,20_000_000).isEmpty(), "recent history dedup");
            check(search(journal,"응").isEmpty(), "short response does not force memory");
            check(search(journal,"무기 제작 재료").isEmpty(), "unrelated importance not injected");
            check(journal.search(KEY,Set.of(PLAYER),"깊은 강 수영",Set.of(),NOW,3,0).isEmpty(), "deadline fallback");
            var expired = entry("나는 겨울 축제 잡담을 했어", false, NOW-Duration.ofDays(60).toMillis());
            var promise = entry("겨울 축제 전에 귀중한 약속을 지키기로 했어", true, expired.occurredAt());
            waitFor(journal.append(expired)); waitFor(journal.append(promise));
            check(search(journal,"겨울 축제").equals(List.of(promise)), "important promise survives casual aging");
            long revision = journal.view().revision();
            var corrected = entry("이제 깊은 강에서도 수영을 배웠다고 말했다", false, NOW);
            check(waitFor(journal.supersede(first.id(), corrected, revision-1)) == MemoryJournal.Result.STALE, "late correction rejected");
            check(waitFor(journal.supersede(first.id(), corrected, revision)) == MemoryJournal.Result.STORED, "explicit correction");
            check(!journal.stillCurrent(List.of(first)), "in-flight result invalidated by correction");
            check(waitFor(journal.append(first)) == MemoryJournal.Result.DUPLICATE, "old extraction cannot resurrect replaced memory");
            check(waitFor(journal.pin(corrected.id(),journal.view().revision(),true)) == MemoryJournal.Result.STORED, "pin important evidence");
            check(waitFor(journal.delete(corrected.id(),journal.view().revision())) == MemoryJournal.Result.STORED, "explicit delete");
            check(search(journal,"깊은 강 수영").isEmpty(), "deleted entry removed from index");
            String prompt = DialogueMemoryBridge.prompt(List.of(promise),List.of());
            check(prompt.contains("PLAYER_STATEMENT") && prompt.contains("quote"), "statement not a confirmed fact");
            var primaryMemory = new DialogueMemoryBridge.Turn(null, null, List.of(promise), List.of(), prompt, 0);
            var speakers = Set.of("mythictrpg:demeter", "mythictrpg:fortuna");
            check(DialogueMemoryBridge.preparedSpeakers(KEY.god(), speakers, primaryMemory).equals(Set.of(KEY.god())), "private memory cannot author another God's speech");
            check(DialogueMemoryBridge.preparedSpeakers(KEY.god(), speakers, DialogueMemoryBridge.EMPTY).equals(speakers), "OFF retains original prepared speaker policy");
            for (int i=0;i<130;i++) check(waitFor(journal.append(entry("무기 제작 계획 번호 " + i,false,NOW))) == MemoryJournal.Result.STORED, "checkpoint write");
            check(search(journal,"무기 제작 계획").size()==3, "top 3 cap");
            check(DialogueMemoryBridge.prompt(journal.view().entries().subList(0,3),List.of()).length()<=800, "total prompt character budget");
            var rumor = new com.sande.mythictrpg.rumor.RumorLedger.HeardRumor(UUID.randomUUID(),1,"드래곤로드와 친우라는 소문","드래곤로드의 친우");
            check(DialogueMemoryBridge.selectRumors(List.of(rumor),"안녕",true).size()==1,"opening hearsay");
            check(DialogueMemoryBridge.selectRumors(List.of(rumor),"오늘은 채광하러 가려고",false).isEmpty(),"unrelated turn excludes rumor");
            check(DialogueMemoryBridge.selectRumors(List.of(rumor),"드래곤로드와 친우",false).size()==1,"related hearsay retrieval");
            check(DialogueMemoryBridge.selectRumors(List.of(rumor),"내 소문을 들었어?",false).size()==1,"explicit rumor recall");
        }
        try (var journal = new MemoryJournal(main)) {
            check(journal.awaitIdle(Duration.ofSeconds(10)), "restart load");
            check(journal.view().entries().size()==132, "checkpoint + suffix replay");
            check(waitFor(journal.append(first))==MemoryJournal.Result.DUPLICATE, "tombstone persisted");
        }
        Path corrupt = root.resolve("corrupt"); Files.createDirectories(corrupt);
        Path corruptJournal=corrupt.resolve("journal.jsonl"); Files.writeString(corruptJournal,"{broken");
        try(var journal = new MemoryJournal(corrupt)) {
            check(!journal.awaitIdle(Duration.ofSeconds(5)), "corrupt load unavailable");
            check(journal.failed(), "corruption visible");
            check(waitFor(journal.append(first))==MemoryJournal.Result.UNAVAILABLE, "corruption does not overwrite data");
            check(search(journal,"깊은 강 수영").isEmpty(), "corruption LP fallback");
        }
        check(Files.readString(corruptJournal).equals("{broken"), "corrupt original preserved");
        Path concurrency=root.resolve("six-players");
        try(var journal = new MemoryJournal(concurrency,512)) {
            journal.awaitIdle(Duration.ofSeconds(5));
            List<MemoryJournal.Key> keys = new ArrayList<>(); List<CompletableFuture<MemoryJournal.Result>> futures=new ArrayList<>();
            for(int p=0;p<6;p++) keys.add(new MemoryJournal.Key(WORLD,KEY.god(),UUID.randomUUID()));
            try (var producers = Executors.newFixedThreadPool(6)) {
                List<Future<List<CompletableFuture<MemoryJournal.Result>>>> groups = new ArrayList<>();
                for (var key : keys) groups.add(producers.submit(() -> {
                    List<CompletableFuture<MemoryJournal.Result>> writes = new ArrayList<>();
                    for(int t=0;t<40;t++) writes.add(journal.append(new MemoryJournal.Entry(UUID.randomUUID(),key,UUID.randomUUID(),t,
                            MemoryJournal.Source.PLAYER_STATEMENT,Set.of(key.player()),NOW,"고유한 무기 제작 계획 " + key.player() + " 순서 " + t,false)));
                    return writes;
                }));
                for (var group : groups) futures.addAll(group.get(10, TimeUnit.SECONDS));
            }
            for(var future:futures) check(waitFor(future)==MemoryJournal.Result.STORED,"six player concurrent write");
            for(var key:keys) {
                var found=journal.search(key,Set.of(key.player()),"무기 제작 계획",Set.of(),NOW,3,20_000_000);
                check(found.size()==3 && found.stream().allMatch(e->e.key().equals(key)),"six player result isolation");
                var ordered = journal.view().entries().stream().filter(e -> e.key().equals(key)).toList();
                for (int i=0;i<40;i++) check(ordered.get(i).turn()==i,"per-player order preserved");
            }
            check(journal.view().entries().size()==240,"no lost concurrent writes");
        }
        try(var journal = new MemoryJournal(root.resolve("bounded-queue"),1)) {
            List<CompletableFuture<MemoryJournal.Result>> writes=new ArrayList<>();
            for(int i=0;i<1000;i++) writes.add(journal.append(entry("bounded test " + i,false,NOW)));
            int full=0; for(var future:writes) if(waitFor(future)==MemoryJournal.Result.FULL) full++;
            check(full>0 && journal.rejectedWrites()>0,"queue overload bounded and reported");
        }
        System.out.println("MemoryJournalTest: PASS ("+checks+" checks); artifacts="+root);
    }
}
