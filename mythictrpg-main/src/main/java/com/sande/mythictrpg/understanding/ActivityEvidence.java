package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import java.util.*;

/** Positive Minecraft activity facts, not time shares, preferences or NPC knowledge. No AFK/absence signal exists. */
public record ActivityEvidence(Signal signal, int count, long firstTick, long lastTick) {
    public static final String SUBJECT = "mythictrpg:player_activity";
    public enum Signal {
        BIOME_CHANGE(PlayerUnderstanding.Axis.EXPLORATION, "활동 중 바이옴 변경 관측 (이동 원인·탐사 목적 미확인)"),
        HARVEST_CROP(PlayerUnderstanding.Axis.FARMING, "성숙 작물 제거"),
        PLANT_CROP(PlayerUnderstanding.Axis.FARMING, "작물 심기"),
        REMOVE_BLOCK(PlayerUnderstanding.Axis.GATHERING, "비작물 블록 제거"),
        PLACE_BLOCK(PlayerUnderstanding.Axis.BUILDING, "직접 블록 설치"),
        FISH_CAUGHT(PlayerUnderstanding.Axis.FISHING, "바닐라 낚시 통계 증가"),
        CRAFT_ITEM(PlayerUnderstanding.Axis.CRAFTING, "제작 결과 수령"),
        SLEEP_IN_BED(PlayerUnderstanding.Axis.REST, "침대 수면 통계 증가"),
        KILL_ENTITY(PlayerUnderstanding.Axis.COMBAT, "처치 확인");
        public final PlayerUnderstanding.Axis axis;
        public final String description;
        Signal(PlayerUnderstanding.Axis axis, String description) { this.axis = axis; this.description = description; }
    }
    public ActivityEvidence {
        Objects.requireNonNull(signal);
        if (count < 1 || count > 1_000_000 || firstTick < 0 || lastTick < firstTick)
            throw new IllegalArgumentException("activity evidence bounds");
    }
    public Map<String,String> payload() {
        return Map.of("signal",signal.name(),"count",Integer.toString(count),
                "first_tick",Long.toString(firstTick),"last_tick",Long.toString(lastTick));
    }
    public static ActivityEvidence parse(Map<String,String> payload) {
        if (!payload.keySet().equals(Set.of("signal","count","first_tick","last_tick")))
            throw new IllegalArgumentException("activity evidence shape");
        return new ActivityEvidence(Signal.valueOf(payload.get("signal")),Integer.parseInt(payload.get("count")),
                Long.parseLong(payload.get("first_tick")),Long.parseLong(payload.get("last_tick")));
    }
    /** Raw diagnostics and the compact observation must never count the same action twice. */
    public static boolean covers(ActionRecord compact, ActionRecord raw, PlayerUnderstanding.Axis axis) {
        if (compact.event().type()!=ActionRecord.Type.PLAYER_ACTIVITY_FACT
                || !compact.worldId().equals(raw.worldId()) || !compact.event().actorId().equals(raw.event().actorId())
                || !compact.event().dimensionId().equals(raw.event().dimensionId())
                || !compact.event().captureSession().equals(raw.event().captureSession())) return false;
        var evidence=parse(compact.event().payload());
        return evidence.signal.axis==axis && raw.event().gameTick()>=evidence.firstTick && raw.event().gameTick()<=evidence.lastTick;
    }
    public static final class Buffer {
        private final EnumMap<Signal,ActivityEvidence> pending=new EnumMap<>(Signal.class);
        public void add(Signal signal,int count,long tick) {
            var value=new ActivityEvidence(signal,count,tick,tick);
            pending.merge(signal,value,(a,b)->new ActivityEvidence(signal,(int)Math.min(1_000_000L,(long)a.count+b.count),
                    Math.min(a.firstTick,b.firstTick),Math.max(a.lastTick,b.lastTick)));
        }
        public List<ActivityEvidence> drain() { var result=List.copyOf(pending.values());pending.clear();return result; }
        public void clear() { pending.clear(); }
    }
}
