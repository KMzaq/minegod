package com.sande.mythictrpg.quest;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Higher server-verified scores rank first; tied scores receive the same rank and reward. */
public record QuestRankingPolicy(EndMode endMode, long durationTicks, List<RankReward> rewards) {
    public enum EndMode { TIME_LIMIT, ALL_SUBMITTED }
    public record RankReward(int maximumRank, int minimumScore, int rewardTier) {
        public RankReward {
            if (maximumRank < 1 || minimumScore < 0 || rewardTier < 1 || rewardTier > 100)
                throw new IllegalArgumentException("Invalid ranking reward band");
        }
    }

    public QuestRankingPolicy {
        Objects.requireNonNull(endMode);
        rewards = List.copyOf(rewards);
        if (endMode == EndMode.TIME_LIMIT ? durationTicks < 1 : durationTicks != 0)
            throw new IllegalArgumentException("Only TIME_LIMIT requires a positive durationTicks");
        int last = 0;
        for (RankReward reward : rewards) {
            if (reward.maximumRank() <= last) throw new IllegalArgumentException("Ranks must increase");
            last = reward.maximumRank();
        }
        if (rewards.isEmpty()) throw new IllegalArgumentException("Ranking rewards must be authored");
    }

    public boolean ended(long startedAt, long now, Set<UUID> participants, Set<UUID> submitted) {
        if (participants.isEmpty() || !participants.containsAll(submitted))
            throw new IllegalArgumentException("Invalid ranking participants");
        return endMode == EndMode.TIME_LIMIT
                ? now >= startedAt && now - startedAt >= durationTicks
                : submitted.containsAll(participants);
    }

    public int rank(UUID player, Map<UUID, Integer> scores) {
        Integer score = scores.get(player);
        if (score == null) return 0;
        return 1 + (int) scores.values().stream().filter(other -> other > score).count();
    }

    public int tier(int rank, int score) {
        if (rank < 1) return 0;
        return rewards.stream().filter(b -> rank <= b.maximumRank() && score >= b.minimumScore())
                .min(Comparator.comparingInt(RankReward::maximumRank)).map(RankReward::rewardTier).orElse(0);
    }
}
