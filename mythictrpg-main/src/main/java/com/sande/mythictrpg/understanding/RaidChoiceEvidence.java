package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import java.util.*;

/** A game-produced opportunity, never an inferred opportunity from damage, deaths or proximity alone. */
public record RaidChoiceEvidence(UUID decisionId, Kind kind, Result result, String phase, String encounterVersion,
                                 boolean test) {
    public enum Kind { MECHANIC, SUPPORT, RISK_RESPONSE }
    public enum Result { CHOSEN, NOT_CHOSEN, UNAVAILABLE, CANCELLED }
    public RaidChoiceEvidence {
        Objects.requireNonNull(decisionId); Objects.requireNonNull(kind); Objects.requireNonNull(result);
        if (phase == null || !phase.matches("[a-zA-Z0-9_/-]{1,64}") || encounterVersion == null
                || !encounterVersion.matches("[a-zA-Z0-9_.-]{1,32}")) throw new IllegalArgumentException("choice metadata");
    }
    public Map<String,String> payload() {
        return Map.of("decision", decisionId.toString(), "kind", kind.name(), "result", result.name(),
                "phase", phase, "encounter_version", encounterVersion, "test", Boolean.toString(test));
    }
    public static RaidChoiceEvidence parse(Map<String,String> p) {
        if (!p.keySet().equals(Set.of("decision","kind","result","phase","encounter_version","test"))
                || !Set.of("true","false").contains(p.get("test"))) throw new IllegalArgumentException("choice shape");
        return new RaidChoiceEvidence(UUID.fromString(p.get("decision")),Kind.valueOf(p.get("kind")),
                Result.valueOf(p.get("result")),p.get("phase"),p.get("encounter_version"),Boolean.parseBoolean(p.get("test")));
    }
    /** Version and raid partitioning prevents a changed mechanic from rewriting an older assessment. */
    public static List<PlayerUnderstanding.Card> cards(List<ActionRecord> records, Map<String,ActionRecord> feedback) {
        var groups = new TreeMap<String,List<ActionRecord>>();
        var latest = new LinkedHashMap<String,ActionRecord>();
        for (var r : records) {
            var c = parse(r.event().payload());
            if (c.test() || r.event().details() == null || r.event().details().runId() == null) continue;
            String key = r.event().details().runId()+"/"+c.decisionId()+"/"+c.kind();
            latest.merge(key,r,(a,b)->a.sequence()>b.sequence()?a:b);
        }
        for (var r : latest.values()) {
            var c = parse(r.event().payload());
            if (c.result()==Result.CANCELLED || c.result()==Result.UNAVAILABLE) continue;
            String partition=r.event().subject().typeId()+"/"+c.encounterVersion()+"/"+c.kind();
            groups.computeIfAbsent(partition,k->new ArrayList<>()).add(r);
        }
        var cards = new ArrayList<PlayerUnderstanding.Card>();
        for (Kind kind:Kind.values()) {
            var relevant=groups.values().stream().filter(rs->parse(rs.getFirst().event().payload()).kind()==kind)
                    .max(Comparator.comparingLong(rs->rs.stream().mapToLong(ActionRecord::sequence).max().orElse(0))).orElse(List.of());
            var runs=new LinkedHashMap<UUID,List<ActionRecord>>();
            relevant.stream().sorted(Comparator.comparingLong(ActionRecord::sequence).reversed())
                    .forEach(r->runs.computeIfAbsent(r.event().details().runId(),k->new ArrayList<>()).add(r));
            int total=0,chosen=0,recentTotal=0,recentChosen=0,run=0;
            for (var rs:runs.values()) {
                // Limit one run's influence, preserve independent recent and older runs.
                var bounded=rs.stream().limit(4).toList();
                int selected=(int)bounded.stream().filter(r->parse(r.event().payload()).result()==Result.CHOSEN).count();
                total+=bounded.size(); chosen+=selected;
                if(run++<10) {recentTotal+=bounded.size();recentChosen+=selected;}
            }
            String key="RAID_"+kind;
            boolean disputed=feedback.containsKey(key)&&"DISPUTE".equals(feedback.get(key).event().payload().get("operation"));
            String title=switch(kind){case MECHANIC->"레이드 기믹 참여 선택";case SUPPORT->"레이드 동료 지원 선택";case RISK_RESPONSE->"레이드 위험 대응 선택";};
            String assessment=total==0?"유효한 선택 기회 근거 부족":runs.size()<3||total<8?"제한적 선택 관측":"반복 선택 관측 · 상황 한정";
            String details="같은 레이드·기믹 버전의 기록 창: 선택 "+chosen+" / 유효 기회 "+total+". 독립 run "+runs.size()
                    +"개. 최근 최대 10 run: "+recentChosen+" / "+recentTotal+". run당 최신 4기회까지만 반영합니다. 성공률·실력·인격 평가가 아닙니다.";
            cards.add(new PlayerUnderstanding.Card(key,title,disputed?"본인 이의 제기됨":assessment,details,
                    relevant.stream().sorted(Comparator.comparingLong(ActionRecord::sequence).reversed()).limit(3)
                            .map(r->"기회 "+parse(r.event().payload()).decisionId().toString().substring(0,8)+" · "+
                                    (parse(r.event().payload()).result()==Result.CHOSEN?"선택 관측":"선택하지 않음 관측")).toList(),disputed));
        }
        return List.copyOf(cards);
    }
}
