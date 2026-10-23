package com.sande.mythictrpg.understanding;

/** Ephemeral self-only display. Never persisted, scored, sent to NPCs or used for missed-opportunity penalties. */
public enum SupportPresence {
    ACTIVE("활동 신호 확인"), AFK("AFK 추정 · 불이익 없음"),
    RESTING("수면 중 · 불이익 없음"), WAITING("낚시·메뉴 대기 · AFK 판정 유보"), UNKNOWN("활동 상태 미확인");
    public final String title;
    SupportPresence(String title) { this.title=title; }
    public static SupportPresence resolve(boolean available,boolean idle,boolean sleeping,boolean fishing,boolean menu) {
        if(sleeping)return RESTING;
        if(fishing||menu)return WAITING;
        if(!available)return UNKNOWN;
        return idle?AFK:ACTIVE;
    }
    public String notice() { return " 현재: "+title+". AFK 시간은 성향·목적·추천의 감점이나 활동 비율에 사용하지 않습니다."; }
}
