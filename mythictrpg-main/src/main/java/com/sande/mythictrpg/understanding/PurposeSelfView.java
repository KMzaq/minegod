package com.sande.mythictrpg.understanding;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Self-only UI projection. This does not grant NPC knowledge or start an extractor. */
public final class PurposeSelfView {
    private PurposeSelfView() { }
    public record Snapshot(PlayerUnderstanding.Slice activity, PurposeLedger.Slice purposes, SupportStatistics.Snapshot statistics) {
        public Snapshot(PlayerUnderstanding.Slice activity,PurposeLedger.Slice purposes) {
            this(activity,purposes,SupportStatistics.Snapshot.empty(activity.head()));
        }
        public Snapshot {
            Objects.requireNonNull(activity); Objects.requireNonNull(purposes);
            if(activity.head()!=purposes.head()||activity.head()!=statistics.head())throw new IllegalArgumentException("PURPOSE_SNAPSHOT_HEAD");
        }
    }
    public static PlayerUnderstanding.View append(PlayerUnderstanding.View base, UUID world, UUID player, PurposeLedger.Slice slice) {
        return append(base,world,player,slice,false);
    }
    public static PlayerUnderstanding.View append(PlayerUnderstanding.View base, UUID world, UUID player, PurposeLedger.Slice slice,boolean advice) {
        if(!base.active()||!slice.active())return base;
        var projection=slice.project(world,player,e->true);
        var cards=new ArrayList<>(base.cards());
        int available=Math.max(0,UnderstandingPayload.MAX_CARDS-cards.size()), shown=Math.min(available,projection.goals().size());
        var byId=new HashMap<UUID,PurposeEvidence>();
        for(var e:slice.evidence())if(e.worldId().equals(world)&&e.playerId().equals(player))byId.put(e.sourceId(),e);
        for(var state:projection.goals().subList(0,shown)) {
            String intention=switch(state.statedIntention()) {
                case UNKNOWN->"본인 의향 미확인";case INTEREST->"정보 관심";case DESIRED->"희망";
                case CONSIDERING->"검토 중";case DECIDED->"본인이 밝힌 결심";
            };
            String progress=switch(state.observedProgress()) {
                case NONE->"행동 진행 미확인";case INFORMATION->"정보 수집";case PREPARING->"준비 행동";
                case PURSUING->"추진 행동";case ACHIEVED->"게임 결과로 달성 확인";
            };
            String lifecycle=switch(state.lifecycle()) {
                case ACTIVE->"현재 후보";case PAUSED->"보류";case WITHDRAWN->"철회";case DISOWNED->"본인 목적 아님";case ACHIEVED->"달성";
            };
            String kind=switch(state.goal().kind()){case MEET->"만남";case LEARN_ABOUT->"정보 탐색";case DO_ACTIVITY->"활동";};
            var ids=new HashSet<>(state.support());ids.addAll(state.counterevidence());
            var evidence=ids.stream().map(byId::get).filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong(PurposeEvidence::sourceOrder).reversed()).limit(3)
                    .map(e->shorten("#"+e.sourceOrder()+" · "+e.signal()+" · "+e.sourceDescription(),256)).toList();
            String explanation="대상 "+state.goal().targetId()+" · "+progress+". 독립 구간 "+state.independentEpisodes()
                    +" / 근거 종류 "+state.evidenceFamilies()+". 반복 희망과 관측 행동은 본인의 결심 선언으로 승격하지 않습니다."
                    +(state.inferredPursuit()?" 행동에서 추진을 추정했으며 확정된 속마음이 아닙니다.":"")
                    +(state.conflictingEvidence()?" 철회·정정 뒤 충돌하는 행동 근거가 있습니다. 옛 목적을 재개하지 않았습니다.":"")
                    +(advice&&state.lifecycle()==PurposeState.Lifecycle.ACTIVE&&state.independentEpisodes()>=3
                        ?" 선택 가능한 도움: 이 대상에 대해 이미 알고 있는 정보와 다음에 알아볼 내용을 정리해 보세요. 만남이나 퀘스트를 자동 시작하지 않습니다.":"")
                    +" NPC에게 자동 공유하지 않습니다.";
            String key="purpose_"+UUID.nameUUIDFromBytes((state.goal().kind()+"/"+state.goal().targetId()).getBytes(StandardCharsets.UTF_8));
            cards.add(new PlayerUnderstanding.Card(key,shorten(kind+" · "+state.goal().targetId(),128),
                    lifecycle+" / "+intention,explanation,evidence,false));
        }
        String notice=base.notice();
        if(!projection.complete())notice+=" 목적 기록은 일부가 누락되거나 조회 한도를 넘었을 수 있습니다.";
        if(shown<projection.goals().size())notice+=" 목적 "+projection.goals().size()+"개 중 "+shown+"개만 표시합니다.";
        return new PlayerUnderstanding.View(true,notice,cards);
    }
    private static String shorten(String text,int max){return text.length()<=max?text:text.substring(0,max-6)+"…(일부)";}
}
