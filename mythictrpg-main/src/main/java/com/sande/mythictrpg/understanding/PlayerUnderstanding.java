package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Rebuildable, non-authoritative self-view. This is NOT an NPC knowledge provider. */
public final class PlayerUnderstanding {
    public static final int MAX_EVENTS = 400;
    public static final String VERSION = "understanding-v2";
    public enum Axis {
        FARMING("농사 활동"), GATHERING("채집·채굴 활동"), COMBAT("전투 활동"),
        QUEST("퀘스트 진행"), RAID("레이드 참여"), RAID_INTEREST("레이드 관련 목적 후보"),
        RAID_MECHANIC("레이드 기믹 참여"), RAID_SUPPORT("레이드 동료 지원"), RAID_RISK_RESPONSE("레이드 위험 대응");
        public final String title;
        Axis(String title) { this.title = title; }
    }
    public record Slice(ActionRecord control, List<ActionRecord> events, List<ActionRecord> feedback,
                        long head, boolean truncated) {
        public Slice {
            events = List.copyOf(events); feedback = List.copyOf(feedback);
            if (head < 0 || events.size() > MAX_EVENTS || feedback.size() > 16) throw new IllegalArgumentException("slice budget");
        }
    }
    public record Card(String key, String title, String assessment, String explanation,
                       List<String> evidence, boolean disputed) {
        public Card { evidence = List.copyOf(evidence); }
    }
    public record View(boolean active, String notice, List<Card> cards) {
        public View { cards = List.copyOf(cards); }
    }
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private PlayerUnderstanding() { }

    public static boolean validFeedbackKey(String key) {
        if ("purpose".equals(key)) return true;
        try { Axis.valueOf(key); return true; } catch (RuntimeException invalid) { return false; }
    }
    public static View project(UUID world, UUID player, Slice slice, boolean possibleGap) {
        ActionRecord control = slice.control();
        if (control == null || !owned(control, world, player) || control.sequence() > slice.head()
                || control.event().type() != ActionRecord.Type.PLAYER_UNDERSTANDING_CONTROL
                || !"START".equals(control.event().payload().get("operation")))
            return new View(false, "분석을 시작하면 그 이후의 기록만 사용합니다. 이전 테스트 기록은 가져오지 않습니다.", List.of());
        var groups = new EnumMap<Axis, List<ActionRecord>>(Axis.class);
        var choices = new ArrayList<ActionRecord>();
        var seen = new HashSet<UUID>();
        for (var record : slice.events()) {
            if (!world.equals(record.worldId()) || record.sequence() <= control.sequence() || record.sequence() > slice.head()
                    || !seen.add(record.event().occurrenceId()) || test(record)) continue;
            var event = record.event();
            boolean self = player.equals(event.actorId());
            if(self && event.type()==ActionRecord.Type.RAID_CHOICE) choices.add(record);
            boolean participant = event.details() != null && event.details().participants().contains(player);
            Axis axis = switch (event.type()) {
                case MATURE_CROP_REMOVED -> self ? Axis.FARMING : null;
                case BLOCK_REMOVED -> self ? Axis.GATHERING : null;
                case ENTITY_KILLED -> self ? Axis.COMBAT : null;
                case QUEST_COMPLETED, QUEST_TRANSITION -> self ? Axis.QUEST : null;
                case BATTLE_RESULT -> participant && Set.of("VICTORY", "DEFEAT").contains(event.payload().getOrDefault("battle_result", "")) ? Axis.RAID : null;
                default -> null;
            };
            if (axis != null) groups.computeIfAbsent(axis, ignored -> new ArrayList<>()).add(record);
        }
        var latest = new HashMap<String, ActionRecord>();
        for (var record : slice.feedback()) {
            if (!owned(record, world, player) || record.sequence() <= control.sequence() || record.sequence() > slice.head()
                    || record.event().type() != ActionRecord.Type.PLAYER_UNDERSTANDING_FEEDBACK) continue;
            String key = record.event().payload().get("key");
            if (validFeedbackKey(key)) latest.merge(key, record, (a, b) -> a.sequence() > b.sequence() ? a : b);
        }
        var cards = new ArrayList<Card>();
        for (Axis axis : Axis.values()) {
            if (axis == Axis.RAID_INTEREST || axis.name().startsWith("RAID_")) continue;
            var records = groups.getOrDefault(axis, List.of());
            int episodes = episodes(records);
            boolean disputed = disputed(latest.get(axis.name()));
            String assessment = records.isEmpty() ? "근거 부족" : episodes < 3 ? "단일·제한적 관측" : "반복 관측 · 잠정적 경향";
            cards.add(new Card(axis.name(), axis.title, disputed ? "본인 이의 제기됨" : assessment,
                    records.isEmpty() ? "기록이 없다고 이 활동을 싫어하거나 하지 않았다는 뜻은 아닙니다."
                    : "기록 " + records.size() + "건 / 독립 구간 " + episodes
                    + "개. 관측된 활동이며 선호·실력·전체 플레이 시간의 비율을 확정하지 않습니다.", evidence(records), disputed));
        }
        var raids = groups.getOrDefault(Axis.RAID, List.of());
        var byRaid = new HashMap<String, List<ActionRecord>>();
        for (var r : raids) byRaid.computeIfAbsent(r.event().subject().typeId(), ignored -> new ArrayList<>()).add(r);
        var repeated = byRaid.values().stream().filter(rs -> episodes(rs) >= 3)
                .max(Comparator.comparingLong(rs -> rs.stream().mapToLong(ActionRecord::sequence).max().orElse(0))).orElse(List.of());
        if (!repeated.isEmpty()) cards.add(new Card(Axis.RAID_INTEREST.name(), Axis.RAID_INTEREST.title,
                disputed(latest.get(Axis.RAID_INTEREST.name())) ? "본인 이의 제기됨" : "행동에서 추정한 목적 후보",
                "같은 레이드의 반복 참여가 관측되었습니다. 공략·보상·동료 지원 중 무엇이 목적이었는지는 알 수 없습니다.",
                evidence(repeated), disputed(latest.get(Axis.RAID_INTEREST.name()))));
        var purpose = latest.get("purpose");
        if (purpose != null && "DECLARE".equals(purpose.event().payload().get("operation")))
            cards.add(new Card("purpose", "직접 적은 현재 목적", "본인 진술 · 달성 확인 아님",
                    purpose.event().payload().getOrDefault("text", ""), List.of("NPC에게 자동 공유하지 않습니다."), false));
        cards.addAll(RaidChoiceEvidence.cards(choices,latest));
        String notice = "시스템의 추정이며 NPC·신의 속마음이 아닙니다. 최근 최대 " + MAX_EVENTS + "개 원본만 분석합니다.";
        if (slice.truncated()) notice += " 이전 기록이 더 있어 부분 분석입니다.";
        if (possibleGap) notice += " 원장 누락 진단이 있어 완전한 기록이 아닙니다.";
        return new View(true, notice, cards);
    }
    private static boolean owned(ActionRecord r, UUID world, UUID player) {
        return world.equals(r.worldId()) && player.equals(r.event().actorId());
    }
    private static boolean disputed(ActionRecord r) { return r != null && "DISPUTE".equals(r.event().payload().get("operation")); }
    private static boolean test(ActionRecord r) {
        String id = r.event().subject().typeId();
        return "true".equals(r.event().payload().get("test")) || id.equals("mythictrpg:trial/sealed_guardian");
    }
    private static int episodes(List<ActionRecord> records) {
        var keys = new HashSet<String>();
        for (var r : records) {
            var e = r.event();
            keys.add(e.details() != null && e.details().runId() != null ? "run/" + e.details().runId()
                    : e.captureSession() + "/" + e.gameTick() / 12000);
        }
        return keys.size();
    }
    private static List<String> evidence(List<ActionRecord> records) {
        return records.stream().sorted(Comparator.comparingLong(ActionRecord::sequence).reversed()).limit(3)
                .map(r -> DATE.format(Instant.ofEpochMilli(r.event().occurredAtUtc())) + " · " + switch (r.event().type()) {
                    case MATURE_CROP_REMOVED -> "성숙 작물 제거 확인 (아이템 획득과 다름)";
                    case BLOCK_REMOVED -> "블록 제거 확인";
                    case ENTITY_KILLED -> "처치 기록";
                    case BATTLE_RESULT -> "레이드 참가 명단과 결과 확인 (개인 기여도 아님)";
                    default -> "퀘스트 진행 사건 (보상 지급 증명 아님)";
                }).toList();
    }
}
