package com.sande.mythai.response.memory;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.experiencecontract.ExperienceView;
import java.util.*;

/** Bounded facts of THIS observer, never a private profile or an inferred biography. */
public final class ObservedSupportMemory {
    private static final Gson JSON = new Gson();
    private ObservedSupportMemory() { }
    public static ExperienceMemory.Selection select(ExperienceView view) {
        if (!view.available()) return ExperienceMemory.Selection.EMPTY;
        var unique = new LinkedHashMap<UUID, ExperienceView.Event>();
        for (var event : view.events()) {
            if (category(event) == null) continue;
            unique.putIfAbsent(event.eventId(), event);
            if (unique.size() == 6) break;
        }
        return new ExperienceMemory.Selection(List.copyOf(unique.values()), false);
    }
    public static String prompt(ExperienceMemory.Selection selected) {
        return prompt(selected, com.sande.mythictrpg.ai.intent.SupportConsultation.Use.OBSERVED_ACTION_RECALL);
    }
    public static boolean requested(com.sande.mythictrpg.ai.intent.SupportConsultation.Use use) {
        // A few witnessed actions are not the cumulative play profile used for personalized help.
        return use == com.sande.mythictrpg.ai.intent.SupportConsultation.Use.OBSERVED_ACTION_RECALL;
    }
    public static String prompt(ExperienceMemory.Selection selected, com.sande.mythictrpg.ai.intent.SupportConsultation.Use use) {
        if (!requested(use)) return "";
        if (selected.events().isEmpty()) return "";
        var facts = new ArrayList<Map<String, String>>();
        for (var event : selected.events()) {
            String axis = category(event);
            if (axis == null) throw new IllegalArgumentException("Unsupported observed support fact");
            facts.add(Map.of("action", event.actionType(), "subject", event.subjectType(),
                    "outcome", event.outcome(), "time", event.gameTime(), "literalMeaning", literalMeaning(event)));
        }
        return "\n[OBSERVED_PLAYER_SUPPORT]\n" + JSON.toJson(Map.of("facts", facts, "requestedUse", use.name(),
                "coverage", "BOUNDED_OBSERVER_SAMPLE_NOT_PLAYER_PROFILE")) + "\n"
                + "이 자료는 지금 상대의 행동을 이 신이 주시한 실제 근거다. literalMeaning까지만 목격했다. "
                + "행동에 앞선 준비·그 후 결과·동기·감정·취향·성실함은 기록되어 있지 않다. "
                + "관심을 물으면 본 행동을 짚고 그것만으로 좋아하는지는 모른다고 구분한다. "
                + "추천은 앞으로 할 수 있는 선택지로 제시하며, 이미 가꾼 땅·얻은 물건·즐거움이 있다고 전제하지 않는다. "
                + "따뜻한 말투는 신 자신의 반응으로 표현하고 플레이어의 정성/진심/평온함을 만들어 칭찬하지 않는다. "
                + "이유를 모른다고 말하거나 질문할 때도 미확인 행동을 이미 했다고 전제하지 않는다. "
                + "These facts belong to the current player and were disclosed to this speaker and current audience by the game. "
                + "Use only to answer this current request. For recollection report the observed actions; for advice offer optional choices. "
                + "For self-assessment, distinguish observed activity from unknown preference; ask what the player enjoys instead of assigning a trait. "
                + "Do not add a personality judgment, praise for assumed care/skill, or a new goal. Do not carry this assessment into later topics. "
                + "Repeated action is not proof of preference, motivation, skill, voluntary choice, or a decision. "
                + "A wish to meet someone remains a wish, not a decision; another person's plan remains theirs. "
                + "Current explicit correction outranks a hypothesis. Never infer inactivity, AFK, avoidance or lack of interest from missing records. "
                + "Counts are visible records, not total actions/time or a ranking. No reward, penalty, affinity, quest, difficulty or world change follows. "
                + "This supplies no hidden self-profile, private purpose, unobserved action or new gameplay permission.\n";
    }
    private static String literalMeaning(ExperienceView.Event event) {
        String activity = event.actionType().equals("OBSERVED_ACTIVITY_SUMMARY") ? event.outcome() : event.actionType();
        if (activity.endsWith("MATURE_CROP_REMOVED")) return "다 자란 작물 블록을 제거함(수확 동작). 심기·경작·돌봄·아이템 획득은 미확인.";
        if (activity.endsWith("BLOCK_REMOVED")) return "블록을 제거함. 건축·탐사·자원 획득 목적과 아이템 획득은 미확인.";
        if (activity.endsWith("ENTITY_KILLED")) return "대상을 처치함. 레이드 역할·공격성·전투 실력·즐거움은 미확인.";
        throw new IllegalArgumentException("Unsupported observed action");
    }
    /** Mechanical semantic projection of the same selected events; never another retrieval or inference. */
    public static Map<String,Object> boundary(ExperienceMemory.Selection selected) {
        var confirmed = new LinkedHashSet<String>();
        for (var event : selected.events()) {
            if (category(event) == null) continue;
            String subject = event.subjectType().equals("minecraft:wheat") ? "밀(minecraft:wheat)" : event.subjectType();
            confirmed.add("대상: " + subject + ". " + literalMeaning(event));
        }
        if (confirmed.isEmpty()) return Map.of();
        return Map.of("source", "SAME_GAME_AUTHORIZED_OBSERVATIONS_AS_OWN_PERMITTED_MEMORY",
                "actor", "현재 플레이어", "observer", "지금 말하는 신", "confirmed", List.copyOf(confirmed),
                "unknown", "준비·심기·돌봄·아이템 소유·정성·성실함·기쁨·평온함·취향·목적은 이 행동만으로 알 수 없음",
                "scope", "관찰된 동작만 회상. 농사를 좋아하는지와 동기는 모름. 추천은 앞으로의 선택지이며 과거 사실이 아님.");
    }
    private static String category(ExperienceView.Event event) {
        if (event.actionType().equals("MATURE_CROP_REMOVED")) return "FARMING";
        if (event.actionType().equals("BATTLE_RESULT")) return null; // Win/death/withdrawal is not a play-style choice.
        if (!event.actionType().equals("OBSERVED_ACTIVITY_SUMMARY")) return null;
        if (event.outcome().endsWith(";ACTIVITY=MATURE_CROP_REMOVED")) return "FARMING";
        if (event.outcome().endsWith(";ACTIVITY=BLOCK_REMOVED")) return "GATHERING";
        if (event.outcome().endsWith(";ACTIVITY=ENTITY_KILLED")) return "COMBAT_ACTIVITY";
        return null;
    }
}
