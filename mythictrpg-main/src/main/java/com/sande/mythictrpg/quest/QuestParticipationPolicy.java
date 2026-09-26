package com.sande.mythictrpg.quest;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Authored participation settings. Objectives belong to the game, FTB is a display mirror. */
public record QuestParticipationPolicy(QuestParticipationType type, List<Objective> objectives,
        Optional<QuestRankingPolicy> ranking) {
    public enum ObjectiveKind { OBSERVATION, ITEM_SUBMISSION, ITEM_DONATION, EVALUATION }
    public record Objective(ObjectiveKind kind, String observation, String subject, int count) {
        public Objective {
            Objects.requireNonNull(kind);
            observation = observation == null ? "" : observation;
            subject = subject == null ? "" : subject;
            if (count < 1 || count > 1000000) throw new IllegalArgumentException("Objective count outside 1..1000000");
            if (kind != ObjectiveKind.EVALUATION && !subject.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
                throw new IllegalArgumentException("Objective requires a namespaced subject");
            if (kind == ObjectiveKind.OBSERVATION && !Set.of("mythictrpg:entity_killed", "mythictrpg:block_broken",
                    "mythictrpg:mature_crop_harvested", "mythictrpg:animal_fed", "mythictrpg:animal_bred")
                    .contains(observation)) throw new IllegalArgumentException("Unsupported quest observation");
            if (kind == ObjectiveKind.EVALUATION && count != 1)
                throw new IllegalArgumentException("Evaluation objective count must be 1");
        }
        public int maximumProgress() { return kind == ObjectiveKind.ITEM_DONATION ? 1_000_000 : count; }
    }

    public QuestParticipationPolicy {
        Objects.requireNonNull(type);
        objectives = List.copyOf(objectives);
        ranking = Objects.requireNonNull(ranking);
        if (objectives.isEmpty() || objectives.size() > 16) throw new IllegalArgumentException("Expected 1..16 objectives");
        if ((type == QuestParticipationType.RANKING) != ranking.isPresent())
            throw new IllegalArgumentException("Only RANKING requires ranking settings");
    }

    public static QuestParticipationPolicy parse(JsonObject json) {
        fields(json, Set.of("type", "objectives", "ranking"));
        QuestParticipationType type = QuestParticipationType.valueOf(json.get("type").getAsString());
        List<Objective> objectives = new ArrayList<>();
        for (var value : json.getAsJsonArray("objectives")) {
            JsonObject obj = value.getAsJsonObject();
            fields(obj, Set.of("kind", "observation", "subject", "count"));
            objectives.add(new Objective(ObjectiveKind.valueOf(obj.get("kind").getAsString()),
                    obj.has("observation") ? obj.get("observation").getAsString() : "",
                    obj.has("subject") ? obj.get("subject").getAsString() : "", integer(obj, "count")));
        }
        Optional<QuestRankingPolicy> ranking = Optional.empty();
        if (json.has("ranking")) {
            JsonObject rank = json.getAsJsonObject("ranking");
            fields(rank, Set.of("endMode", "durationTicks", "rewards"));
            List<QuestRankingPolicy.RankReward> rewards = new ArrayList<>();
            for (var value : rank.getAsJsonArray("rewards")) {
                JsonObject reward = value.getAsJsonObject();
                fields(reward, Set.of("maximumRank", "minimumScore", "rewardTier"));
                rewards.add(new QuestRankingPolicy.RankReward(integer(reward, "maximumRank"),
                        integer(reward, "minimumScore"), integer(reward, "rewardTier")));
            }
            ranking = Optional.of(new QuestRankingPolicy(QuestRankingPolicy.EndMode.valueOf(rank.get("endMode").getAsString()),
                    rank.has("durationTicks") ? integer(rank, "durationTicks") : 0, rewards));
        }
        return new QuestParticipationPolicy(type, objectives, ranking);
    }

    private static int integer(JsonObject json, String key) {
        var value = json.getAsJsonPrimitive(key);
        if (!value.isNumber()) throw new IllegalArgumentException("Expected integer " + key);
        return value.getAsBigDecimal().intValueExact();
    }

    private static void fields(JsonObject json, Set<String> allowed) {
        for (String field : json.keySet()) if (!allowed.contains(field))
            throw new IllegalArgumentException("Unknown participation field " + field);
    }
}
