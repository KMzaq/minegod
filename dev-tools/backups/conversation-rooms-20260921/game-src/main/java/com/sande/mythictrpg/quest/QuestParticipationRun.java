package com.sande.mythictrpg.quest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Server-thread state machine, persisted inside MythicQuestState. No game side effects here. */
public final class QuestParticipationRun {
    public record Submission(int score, String npcId, long submittedAt) {
        public Submission {
            if (score < 0 || submittedAt < 0) throw new IllegalArgumentException("Invalid submission");
            Objects.requireNonNull(npcId);
            if (!validId(npcId)) throw new IllegalArgumentException("Invalid submission NPC");
        }
    }
    public record Mirror(long quest, long marker, long task) {
        public Mirror {
            if (quest <= 0 || marker <= 0 || task <= 0 || quest == marker || quest == task || marker == task)
                throw new IllegalArgumentException("Invalid FTB mirror IDs");
        }
    }
    public record Snapshot(UUID runId, String questId, String giverId, QuestParticipationType type,
            List<QuestParticipationPolicy.Objective> objectives, QuestRankingPolicy ranking,
            long startedAt, Map<UUID, List<Integer>> progress, Map<UUID, Submission> submissions,
            Map<UUID, Mirror> mirrors, boolean closed, Set<UUID> awarded) {
        public Snapshot {
            Objects.requireNonNull(runId); Objects.requireNonNull(questId); Objects.requireNonNull(giverId);
            Objects.requireNonNull(type);
            if (!validId(questId) || !validId(giverId)) throw new IllegalArgumentException("Invalid quest or giver ID");
            objectives = List.copyOf(objectives);
            Map<UUID, List<Integer>> copied = new LinkedHashMap<>();
            progress.forEach((id, counts) -> copied.put(id, List.copyOf(counts)));
            progress = Map.copyOf(copied);
            submissions = Map.copyOf(submissions); mirrors = Map.copyOf(mirrors); awarded = Set.copyOf(awarded);
            new QuestParticipationPolicy(type, objectives, java.util.Optional.ofNullable(ranking));
            if (startedAt < 0 || progress.isEmpty() || progress.size() > 16
                    || (type == QuestParticipationType.SOLO && progress.size() != 1)
                    || (type == QuestParticipationType.COMPETITIVE && submissions.size() > 1)
                    || !progress.keySet().containsAll(submissions.keySet()) || !progress.keySet().containsAll(mirrors.keySet())
                    || !progress.keySet().containsAll(awarded) || (!closed && !awarded.isEmpty()))
                throw new IllegalArgumentException("Invalid participation state");
            for (List<Integer> counts : progress.values()) {
                if (counts.size() != objectives.size()) throw new IllegalArgumentException("Invalid objective vector");
                for (int i = 0; i < counts.size(); i++)
                    if (counts.get(i) < 0 || counts.get(i) > objectives.get(i).maximumProgress())
                        throw new IllegalArgumentException("Invalid objective progress");
            }
            for (UUID player : submissions.keySet()) {
                if (submissions.get(player).submittedAt() < startedAt)
                    throw new IllegalArgumentException("Submission predates acceptance");
                if (ranking != null && ranking.endMode() == QuestRankingPolicy.EndMode.TIME_LIMIT
                        && submissions.get(player).submittedAt() - startedAt >= ranking.durationTicks())
                    throw new IllegalArgumentException("Submission after deadline");
                for (int i = 0; i < objectives.size(); i++)
                    if (progress.get(player).get(i) < objectives.get(i).count())
                        throw new IllegalArgumentException("Submitted without completing objectives");
            }
            if (!submissions.keySet().containsAll(awarded)
                    || (closed && type != QuestParticipationType.RANKING && submissions.isEmpty())
                    || (closed && type == QuestParticipationType.GROUP && submissions.size() != progress.size()))
                throw new IllegalArgumentException("Invalid settlement state");
            if (closed && ranking != null && ranking.endMode() == QuestRankingPolicy.EndMode.ALL_SUBMITTED
                    && submissions.size() != progress.size()) throw new IllegalArgumentException("Ranking closed before all submissions");
        }
    }

    private Snapshot data;

    private static boolean validId(String value) { return value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"); }

    public QuestParticipationRun(UUID id, String quest, String giver, QuestParticipationPolicy policy,
            Set<UUID> players, long now) {
        Map<UUID, List<Integer>> progress = new LinkedHashMap<>();
        players.forEach(player -> progress.put(player, policy.objectives().stream().map(o -> 0).toList()));
        data = new Snapshot(id, quest, giver, policy.type(), policy.objectives(), policy.ranking().orElse(null),
                now, progress, Map.of(), Map.of(), false, Set.of());
    }

    public QuestParticipationRun(Snapshot snapshot) { data = Objects.requireNonNull(snapshot); }
    public Snapshot snapshot() { return data; }
    public Set<UUID> participants() { return data.progress().keySet(); }
    public boolean ready(UUID player) {
        List<Integer> progress = data.progress().get(player);
        if (progress == null) return false;
        for (int i = 0; i < progress.size(); i++) if (progress.get(i) < data.objectives().get(i).count()) return false;
        return true;
    }
    public boolean accepting(UUID player, long now) {
        return now >= data.startedAt() && !data.closed() && participants().contains(player) && !data.submissions().containsKey(player)
                && (data.type() != QuestParticipationType.COMPETITIVE || data.submissions().isEmpty())
                && (data.ranking() == null || data.ranking().endMode() != QuestRankingPolicy.EndMode.TIME_LIMIT
                    || now - data.startedAt() < data.ranking().durationTicks());
    }
    public int progress(UUID player, int objective) { return data.progress().get(player).get(objective); }
    public int total(UUID player) {
        int total = 0;
        for (int i = 0; i < data.objectives().size(); i++) total += Math.min(progress(player, i), data.objectives().get(i).count());
        return total;
    }
    public int score(UUID player) {
        int donated = 0; boolean donationQuest = false;
        for (int i = 0; i < data.objectives().size(); i++) if (data.objectives().get(i).kind()
                == QuestParticipationPolicy.ObjectiveKind.ITEM_DONATION) {
            donationQuest = true; donated += progress(player, i);
        }
        return donationQuest ? donated : total(player);
    }
    public int maximum() { return data.objectives().stream().mapToInt(QuestParticipationPolicy.Objective::count).sum(); }

    public boolean advance(UUID player, int objective, int delta, long now) {
        if (!accepting(player, now) || delta < 1) return false;
        List<Integer> counts = new java.util.ArrayList<>(data.progress().get(player));
        int value = (int) Math.min(data.objectives().get(objective).maximumProgress(), (long) counts.get(objective) + delta);
        if (value == counts.get(objective)) return false;
        counts.set(objective, value);
        Map<UUID, List<Integer>> progress = new LinkedHashMap<>(data.progress()); progress.put(player, counts);
        data = new Snapshot(data.runId(), data.questId(), data.giverId(), data.type(), data.objectives(), data.ranking(),
                data.startedAt(), progress, data.submissions(), data.mirrors(), data.closed(), data.awarded());
        return true;
    }

    public boolean submit(UUID player, int score, String npc, long now) {
        if (!accepting(player, now) || !ready(player)) return false;
        Map<UUID, Submission> submissions = new LinkedHashMap<>(data.submissions());
        submissions.put(player, new Submission(score, npc, now));
        data = new Snapshot(data.runId(), data.questId(), data.giverId(), data.type(), data.objectives(), data.ranking(),
                data.startedAt(), data.progress(), submissions, data.mirrors(), false, data.awarded());
        return true;
    }

    public boolean shouldClose(long now) {
        if (data.closed()) return false;
        return switch (data.type()) {
            case SOLO, COMPETITIVE -> !data.submissions().isEmpty();
            case GROUP -> data.submissions().keySet().containsAll(participants());
            case RANKING -> data.ranking().ended(data.startedAt(), now, participants(), data.submissions().keySet());
        };
    }

    public Map<UUID, Integer> scores() {
        Map<UUID, Integer> result = new LinkedHashMap<>();
        data.submissions().forEach((id, submission) -> result.put(id, submission.score()));
        return Map.copyOf(result);
    }
    public Set<UUID> winners() {
        if (!data.closed()) return Set.of();
        if (data.type() == QuestParticipationType.COMPETITIVE) {
            return data.submissions().entrySet().stream()
                    .min(Map.Entry.<UUID, Submission>comparingByValue(java.util.Comparator.comparingLong(Submission::submittedAt))
                            .thenComparing(e -> e.getKey().toString())).map(e -> Set.of(e.getKey())).orElse(Set.of());
        }
        return data.submissions().keySet();
    }
    public void close(long now) {
        if (!shouldClose(now)) throw new IllegalStateException("Quest is not ready to close");
        data = new Snapshot(data.runId(), data.questId(), data.giverId(), data.type(), data.objectives(), data.ranking(),
                data.startedAt(), data.progress(), data.submissions(), data.mirrors(), true, data.awarded());
    }
    public void awarded(UUID player) {
        if (!winners().contains(player)) throw new IllegalArgumentException("Not a successful participant");
        Set<UUID> awarded = new java.util.LinkedHashSet<>(data.awarded()); awarded.add(player);
        data = new Snapshot(data.runId(), data.questId(), data.giverId(), data.type(), data.objectives(), data.ranking(),
                data.startedAt(), data.progress(), data.submissions(), data.mirrors(), true, awarded);
    }
    public void mirror(UUID player, Mirror mirror) {
        Map<UUID, Mirror> mirrors = new LinkedHashMap<>(data.mirrors()); mirrors.put(player, mirror);
        data = new Snapshot(data.runId(), data.questId(), data.giverId(), data.type(), data.objectives(), data.ranking(),
                data.startedAt(), data.progress(), data.submissions(), mirrors, data.closed(), data.awarded());
    }
}
