package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import java.util.*;

/** Rebuilt by the existing ledger writer. No extra database, per-request full scan, wall-clock decay or LLM call. */
public final class SupportStatistics {
    public static final int RECENT_EPISODES = 32, EPISODE_LIMIT = 8192;
    public enum Key {
        DEVELOPMENT("내실·생활 성장", "농사·제작·채집·건축 활동", "작은 제작이나 생활 기반 정비를 다음 후보로 살펴보세요."),
        EXPLORATION("탐험", "활동 중 바이옴 이동", "아직 가보지 않은 지역 탐험을 다음 후보로 살펴보세요."),
        BUILDING("건축", "직접 블록 설치", "작은 건축 목표를 나눠 진행하는 방법을 살펴보세요."),
        FARMING("농사", "심기·수확", "작물 재배나 생활 재료 준비를 다음 후보로 살펴보세요."),
        FISHING("낚시", "어획", "낚시를 다음 활동 후보로 살펴보세요."),
        QUEST("퀘스트 진행", "퀘스트 진행 사건", "현재 수주한 퀘스트의 다음 단계를 확인해 보세요."),
        RAID_ATTACK("레이드 공격 활용", "참가 레이드에서 실제 공격 적중", "공격 기회와 안전한 복귀 시점을 함께 확인해 보세요."),
        RAID_GUARD("레이드 방어 활용", "참가 레이드에서 방패 방어 자세 선택", "방어로 대응할 수 있는 패턴과 공격 기회를 함께 확인해 보세요."),
        DIALOGUE_INFORMATION("AI 대화 · 정보 탐색", "정보를 묻는 발화", "필요한 인물·세계관 정보를 물어보는 방식을 활용해 보세요."),
        DIALOGUE_HELP("AI 대화 · 진행 도움 요청", "도움·퀘스트 정보를 묻는 발화", "다음 진행 단계와 선택지를 구체적으로 물어보세요."),
        DIALOGUE_SOCIAL("AI 대화 · 친목·잡담", "안부·친목·농담 발화", "원할 때 등장인물과 가벼운 대화를 이어가 보세요.");
        public final String title, basis, suggestion;
        Key(String title,String basis,String suggestion) { this.title=title;this.basis=basis;this.suggestion=suggestion; }
        public String feedbackKey() { return "SUPPORT_"+name(); }
        public String domain() { return name().startsWith("RAID_")?"RAID":name().startsWith("DIALOGUE_")?"DIALOGUE":"PLAY"; }
    }
    public record Counter(Key key,long actions,int episodes,int recentEpisodes,List<String> evidence,boolean limited) {
        public Counter { evidence=List.copyOf(evidence); }
        public boolean established() { return recentEpisodes>=3; }
    }
    public record Snapshot(boolean active,long startOrder,long head,List<Counter> counters) {
        public Snapshot { counters=List.copyOf(counters); }
        public static Snapshot empty(long head) { return new Snapshot(false,0,head,List.of()); }
    }
    private static final class Count {
        long actions; boolean limited;
        final Set<UUID> episodes=new HashSet<>();
        final ArrayDeque<String> evidence=new ArrayDeque<>();
    }
    private final UUID world,player;
    private boolean active; private long start;
    private final EnumMap<Key,Count> totals=new EnumMap<>(Key.class);
    private final Map<String,LinkedHashMap<UUID,EnumSet<Key>>> recent=new HashMap<>();
    public SupportStatistics(UUID world,UUID player) { this.world=world;this.player=player; }
    public static boolean feedbackKey(String key) {
        if(key==null||!key.startsWith("SUPPORT_"))return false;
        try {Key.valueOf(key.substring(8));return true;}catch(IllegalArgumentException ignored){return false;}
    }
    /** Called exactly once per durable record, including recovery. Capture retry IDs are deduplicated upstream. */
    public void accept(ActionRecord record) {
        var e=record.event();
        if(!world.equals(record.worldId())||!player.equals(e.actorId()))return;
        if(e.type()==ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL) {
            active="START".equals(e.payload().get("operation"));start=record.sequence();totals.clear();recent.clear();return;
        }
        if(!active||record.sequence()<=start||"true".equals(e.payload().get("test"))
                ||e.dimensionId().equals("mythictrpg:raid_trial")||e.subject().typeId().equals("mythictrpg:trial/sealed_guardian"))return;
        var keys=EnumSet.noneOf(Key.class); int amount=1; String detail="";
        if(e.type()==ActionRecord.Type.PLAYER_ACTIVITY_FACT) {
            var a=ActivityEvidence.parse(e.payload());amount=a.count();detail=a.signal().description;
            switch(a.signal()) {
                case HARVEST_CROP,PLANT_CROP -> keys.addAll(EnumSet.of(Key.FARMING,Key.DEVELOPMENT));
                case PLACE_BLOCK -> keys.addAll(EnumSet.of(Key.BUILDING,Key.DEVELOPMENT));
                case CRAFT_ITEM,REMOVE_BLOCK -> keys.add(Key.DEVELOPMENT);
                case BIOME_CHANGE -> keys.add(Key.EXPLORATION);
                case FISH_CAUGHT -> keys.add(Key.FISHING);
                default -> { }
            }
        } else if(e.type()==ActionRecord.Type.PLAYER_SUPPORT_SIGNAL) {
            var signal=SupportSignal.parse(e.payload());keys.add(Key.valueOf(signal.kind().name()));amount=signal.count();detail=signal.context();
        } else if(e.type()==ActionRecord.Type.QUEST_TRANSITION) {keys.add(Key.QUEST);detail="퀘스트 진행 "+e.subject().typeId();}
        if(keys.isEmpty())return;
        UUID episode=PurposeLedger.episodeOf(record);
        for(var key:keys) {
            var domain=recent.computeIfAbsent(key.domain(),k->new LinkedHashMap<>());
            domain.computeIfAbsent(episode,k->EnumSet.noneOf(Key.class)).add(key);
            while(domain.size()>RECENT_EPISODES)domain.remove(domain.keySet().iterator().next());
            var count=totals.computeIfAbsent(key,k->new Count());count.actions=Math.addExact(count.actions,amount);
            if(count.episodes.size()<EPISODE_LIMIT)count.episodes.add(episode);
            else if(!count.episodes.contains(episode))count.limited=true;
            String line="#"+record.sequence()+" · "+detail+" · "+amount+"회/개";
            count.evidence.addFirst(line.length()>250?line.substring(0,250):line);
            while(count.evidence.size()>3)count.evidence.removeLast();
        }
    }
    public Snapshot snapshot(long head) {
        var counters=new ArrayList<Counter>();
        totals.forEach((key,c)->counters.add(new Counter(key,c.actions,c.episodes.size(),
                (int)recent.getOrDefault(key.domain(),new LinkedHashMap<>()).values().stream().filter(keys->keys.contains(key)).count(),List.copyOf(c.evidence),c.limited)));
        return new Snapshot(active,start,head,counters);
    }
    public static PlayerUnderstanding.View append(PlayerUnderstanding.View base,Snapshot stats,List<ActionRecord> feedback,boolean advice) {
        if(!base.active()||!stats.active())return base;
        var disputed=new HashMap<String,ActionRecord>();
        feedback.stream().filter(r->r.sequence()>stats.startOrder()&&r.sequence()<=stats.head())
                .forEach(r->disputed.merge(r.event().payload().getOrDefault("key",""),r,(a,b)->a.sequence()>b.sequence()?a:b));
        var cards=new ArrayList<PlayerUnderstanding.Card>();
        for(var c:stats.counters()) {
            var correction=disputed.get(c.key().feedbackKey());
            boolean excluded=correction!=null&&"DISPUTE".equals(correction.event().payload().get("operation"));
            String assessment=excluded?"본인 이의 제기 · 지원에서 제외":!c.established()?"누적 중 · 성향 근거 부족":"반복된 플레이 경향 · 잠정 추정";
            String detail="분석 시작 이후 누적 "+c.actions()+"회/개 · 활동 구간 "+c.episodes()+(c.limited()?"개 이상":"개")
                    +". 같은 영역의 최근 최대 "+RECENT_EPISODES+"개 활동 구간 중 "+c.recentEpisodes()+"구간에서 관측. 시간 비율이나 실력 점수가 아닙니다. "
                    +(c.key().name().startsWith("RAID_")?"장비·역할·레이드 상황에 따라 달라질 수 있습니다. 공격과 방어 경향은 함께 존재할 수 있습니다. ":"")
                    +(advice&&c.established()&&!excluded?"선택 가능한 도움: "+c.key().suggestion:"필요할 때만 지원 후보를 참고합니다.");
            cards.add(new PlayerUnderstanding.Card(c.key().feedbackKey(),"누적 · "+c.key().title,assessment,detail,c.evidence(),excluded));
        }
        cards.addAll(base.cards());
        return new PlayerUnderstanding.View(true,base.notice()+" 누적 통계는 최근 400개 표시와 별도로 유지됩니다. AFK는 집계·감점하지 않습니다.",cards);
    }
}
