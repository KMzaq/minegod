package com.sande.mythictrpg.quest;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.util.*;

/** Server-free domain regression; game integrations have separate GameTests. */
public final class QuestParticipationTest {
    private static int checks;
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);
    private static final QuestParticipationPolicy.Objective GOAL = new QuestParticipationPolicy.Objective(
            QuestParticipationPolicy.ObjectiveKind.ITEM_SUBMISSION, "", "minecraft:heart_of_the_sea", 2);

    public static void main(String[] args) {
        consent(); solo(); group(); competitive(); ranking(); persistence(); parser();
        System.out.println("QuestParticipationTest: " + checks + " assertions passed");
    }
    private static void consent() {
        UUID offer = UUID.randomUUID(), session = UUID.randomUUID();
        var poll = new QuestEnrollment(offer, session, Set.of(A, B, C), Set.of(A, B));
        rejects(poll::accepted, "no early acceptance");
        check(!poll.answer(UUID.randomUUID(), session, A, QuestEnrollment.Answer.YES), "old offer rejected");
        check(!poll.answer(offer, UUID.randomUUID(), A, QuestEnrollment.Answer.YES), "other session rejected");
        check(!poll.answer(offer, session, UUID.randomUUID(), QuestEnrollment.Answer.YES), "outsider rejected");
        check(poll.answer(offer, session, A, QuestEnrollment.Answer.YES), "eligible accepts");
        check(!poll.answer(offer, session, A, QuestEnrollment.Answer.NO), "duplicate rejected");
        check(!poll.answer(offer, session, C, QuestEnrollment.Answer.YES), "ineligible cannot accept");
        check(poll.answer(offer, session, B, QuestEnrollment.Answer.NO), "decline");
        check(!poll.ready() && poll.waitingFor().equals(Set.of(C)), "wait even for ineligible participant");
        check(poll.answer(offer, session, C, QuestEnrollment.Answer.NO), "explicit decline required");
        check(poll.ready() && poll.accepted().equals(Set.of(A)), "only affirmative recipients");
        rejects(() -> new QuestEnrollment(offer, session, Set.of(A), Set.of(B)), "eligible subset");
    }
    private static QuestParticipationRun run(QuestParticipationType type, Set<UUID> people, QuestRankingPolicy rank) {
        return new QuestParticipationRun(UUID.randomUUID(), "mythictrpg:test", "mythictrpg:fortuna",
                new QuestParticipationPolicy(type, List.of(GOAL), Optional.ofNullable(rank)), people, 100);
    }
    private static void ready(QuestParticipationRun run, UUID player, long now) {
        check(run.advance(player, 0, 2, now), "objective advances");
    }
    private static void solo() {
        rejects(() -> run(QuestParticipationType.SOLO, Set.of(A, B), null), "solo has one owner");
        var run = run(QuestParticipationType.SOLO, Set.of(A), null);
        check(!run.advance(B, 0, 2, 100), "no teammate progress");
        check(!run.submit(A, 2, "mythictrpg:fortuna", 100), "not ready cannot submit");
        ready(run, A, 100);
        check(run.submit(A, 2, "mythictrpg:fortuna", 101), "ready submit");
        check(run.shouldClose(101), "solo closes"); run.close(101);
        check(run.winners().equals(Set.of(A)), "sole winner");
        check(!run.submit(A, 2, "mythictrpg:fortuna", 102), "completion idempotent");
        run.awarded(A); run.awarded(A);
        check(run.snapshot().awarded().size() == 1, "one receipt marker");
        rejects(() -> run.awarded(B), "outsider no payout");
    }
    private static void group() {
        var run = run(QuestParticipationType.GROUP, Set.of(A, B), null);
        ready(run, A, 101);
        check(run.submit(A, 2, "mythictrpg:fortuna", 102), "first group submission");
        check(!run.shouldClose(100000), "no implicit timeout for group");
        check(!run.ready(B), "personal not shared objectives");
        rejects(() -> run.close(103), "no partial group completion");
        ready(run, B, 104); check(run.submit(B, 2, "mythictrpg:delegate", 105), "delegate submission");
        run.close(105); check(run.winners().equals(Set.of(A, B)), "everyone wins group");
        var alone = run(QuestParticipationType.GROUP, Set.of(A), null);
        ready(alone, A, 100); alone.submit(A, 2, "mythictrpg:fortuna", 100);
        check(alone.shouldClose(100), "one-person group allowed");
    }
    private static void competitive() {
        var run = run(QuestParticipationType.COMPETITIVE, Set.of(A, B), null);
        ready(run, A, 100); ready(run, B, 100);
        check(run.submit(B, 2, "mythictrpg:fortuna", 101), "first server-verified contender");
        check(!run.submit(A, 2, "mythictrpg:fortuna", 101), "same-tick later submission loses");
        run.close(101); check(run.winners().equals(Set.of(B)), "not UUID order");
    }
    private static QuestRankingPolicy ranking(QuestRankingPolicy.EndMode mode) {
        return new QuestRankingPolicy(mode, mode == QuestRankingPolicy.EndMode.TIME_LIMIT ? 200 : 0,
                List.of(new QuestRankingPolicy.RankReward(1, 80, 5), new QuestRankingPolicy.RankReward(3, 60, 2)));
    }
    private static void ranking() {
        var timer = run(QuestParticipationType.RANKING, Set.of(A, B, C), ranking(QuestRankingPolicy.EndMode.TIME_LIMIT));
        ready(timer, A, 110); timer.submit(A, 90, "mythictrpg:fortuna", 120);
        ready(timer, B, 150); timer.submit(B, 90, "mythictrpg:fortuna", 170);
        check(!timer.shouldClose(299), "before deadline");
        check(timer.shouldClose(300), "exact deadline");
        check(!timer.advance(C, 0, 2, 300), "late objective rejected");
        timer.close(300);
        check(timer.winners().equals(Set.of(A, B)), "unsubmitted excluded");
        var policy = timer.snapshot().ranking();
        check(policy.rank(A, timer.scores()) == 1 && policy.rank(B, timer.scores()) == 1, "ties equal rank");
        check(policy.rank(C, Map.of(A, 90, B, 90, C, 70)) == 3, "competition ranks skip tie places");
        check(policy.tier(1, 90) == 5 && policy.tier(3, 70) == 2 && policy.tier(4, 99) == 0, "reward bands");
        check(policy.tier(1, 59) == 0, "minimum grade");
        var all = run(QuestParticipationType.RANKING, Set.of(A, B), ranking(QuestRankingPolicy.EndMode.ALL_SUBMITTED));
        ready(all, A, 100); all.submit(A, 80, "mythictrpg:fortuna", 100);
        check(!all.shouldClose(1000000), "all-submitted has no timer");
        ready(all, B, 1000001); all.submit(B, 70, "mythictrpg:fortuna", 1000001);
        check(all.shouldClose(1000001), "ends on last submit");
        rejects(() -> new QuestRankingPolicy(QuestRankingPolicy.EndMode.TIME_LIMIT, 0, policy.rewards()), "timer needs positive duration");
        rejects(() -> new QuestRankingPolicy(QuestRankingPolicy.EndMode.ALL_SUBMITTED, 1, policy.rewards()), "no stray timer");
    }
    private static void persistence() {
        var run = run(QuestParticipationType.GROUP, Set.of(A, B), null);
        run.advance(A, 0, 1, 100); run.mirror(A, new QuestParticipationRun.Mirror(1, 2, 3));
        Gson gson = new Gson(); String json = gson.toJson(run.snapshot());
        var restored = new QuestParticipationRun(gson.fromJson(json, QuestParticipationRun.Snapshot.class));
        check(restored.snapshot().equals(run.snapshot()), "immutable JSON snapshot round trip");
        rejects(() -> restored.snapshot().progress().get(A).set(0, 2), "immutable counts");
        var invalid = JsonParser.parseString(json).getAsJsonObject();
        invalid.getAsJsonObject("progress").getAsJsonArray(A.toString()).set(0, new com.google.gson.JsonPrimitive(-1));
        rejects(() -> gson.fromJson(invalid, QuestParticipationRun.Snapshot.class), "corrupt progress fails closed");
        check(!restored.accepting(A, 99), "clock before start rejected");
    }
    private static void parser() {
        String json = """
                {"type":"SOLO","objectives":[{"kind":"ITEM_SUBMISSION","subject":"minecraft:heart_of_the_sea","count":2}]}
                """;
        check(QuestParticipationPolicy.parse(JsonParser.parseString(json).getAsJsonObject()).type() == QuestParticipationType.SOLO, "strict parser");
        rejects(() -> QuestParticipationPolicy.parse(JsonParser.parseString(json.replace("\"count\":2", "\"count\":2.5")).getAsJsonObject()), "fraction rejected");
        rejects(() -> QuestParticipationPolicy.parse(JsonParser.parseString(json.replace("\"count\":2", "\"count\":2,\"typo\":true")).getAsJsonObject()), "unknown field rejected");
        rejects(() -> new QuestParticipationPolicy(QuestParticipationType.RANKING, List.of(GOAL), Optional.empty()), "ranking requires policy");
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void rejects(Runnable task, String message) {
        checks++;
        try { task.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }
}
