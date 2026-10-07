package com.sande.mythictrpg.quest;

import com.google.gson.*;
import java.util.*;

public final class QuestReorganizationTest {
    private static int checks;
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();
    private static final Gson JSON = new Gson();
    private static final QuestParticipationPolicy.Objective GOAL = new QuestParticipationPolicy.Objective(
            QuestParticipationPolicy.ObjectiveKind.ITEM_SUBMISSION, "", "minecraft:heart_of_the_sea", 2);
    private static QuestParticipationRun run(int minimum, boolean ranked) {
        var rank = ranked ? Optional.of(new QuestRankingPolicy(QuestRankingPolicy.EndMode.ALL_SUBMITTED, 0,
                List.of(new QuestRankingPolicy.RankReward(2, 0, 1)))) : Optional.<QuestRankingPolicy>empty();
        return new QuestParticipationRun(UUID.randomUUID(), "mythictrpg:test", "mythictrpg:fortuna",
                new QuestParticipationPolicy(ranked ? QuestParticipationType.RANKING : QuestParticipationType.GROUP,
                    List.of(GOAL), rank, Optional.of(new QuestReorganizationPolicy(true, 200, true, minimum, true))), Set.of(A, B), 100);
    }
    public static void main(String[] args) {
        for (boolean ranked : List.of(false, true)) {
            var r = run(1, ranked);
            r.advance(A, 0, 2, 101); r.submit(A, 2, "mythictrpg:fortuna", 102);
            check(!r.remove(A, true, 500), "submitted player cannot quit");
            check(!r.remove(A, false, 500), "submitted offline player cannot be removed");
            check(!r.absent(B, 299), "absence threshold not early");
            r.seen(B, 280);
            check(!r.remove(B, false, 479), "reconnection resets absence");
            check(r.remove(B, false, 480), "exact threshold allows removal");
            check(!r.remove(B, false, 481), "duplicate removal rejected");
            check(!r.advance(B, 0, 2, 482) && !r.submit(B, 2, "mythictrpg:fortuna", 482), "departed player cannot progress or submit");
            check(r.shouldClose(480), "remaining submitted member can complete");
            r.close(480); r.awarded(A);
            check(r.winners().equals(Set.of(A)), "submitted member keeps completion/reward");
            check(!r.recruit(C, 481), "closed run cannot reopen");
            roundTrip(r);
        }
        var refill = run(2, false);
        refill.advance(A, 0, 2, 101); refill.submit(A, 2, "mythictrpg:fortuna", 102);
        check(refill.remove(B, true, 103), "voluntary withdrawal allowed");
        check(!refill.shouldClose(9000), "minimum requires replacement");
        check(!refill.recruit(B, 104), "departed member cannot recycle refund/run");
        check(refill.recruit(C, 104), "consented replacement accepted");
        check(refill.progress(C, 0) == 0 && refill.snapshot().submissions().containsKey(A), "replacement starts fresh; original submission preserved");
        check(!refill.canRecruit(UUID.randomUUID()), "cannot expand beyond original roster");
        refill.advance(C, 0, 2, 105); refill.submit(C, 2, "mythictrpg:fortuna", 106);
        check(refill.shouldClose(106), "replacement can unblock group");
        roundTrip(refill);
        var cancel = run(1, false);
        cancel.remove(A, true, 101); cancel.remove(B, true, 102); cancel.close(102);
        check(cancel.winners().isEmpty(), "all withdrawn means cancelled, no winners"); roundTrip(cancel);
        var legacy = new QuestParticipationRun(UUID.randomUUID(), "mythictrpg:old", "mythictrpg:fortuna",
                new QuestParticipationPolicy(QuestParticipationType.GROUP, List.of(GOAL), Optional.empty()), Set.of(A), 0);
        JsonObject old = JSON.toJsonTree(legacy.snapshot()).getAsJsonObject(); old.remove("roster");
        var restored = new QuestParticipationRun(JSON.fromJson(old, QuestParticipationRun.Snapshot.class));
        check(!restored.remove(A, true, 1000), "old saves keep fixed-roster policy");
        String policy = """
                {"type":"GROUP","objectives":[{"kind":"ITEM_SUBMISSION","subject":"minecraft:heart_of_the_sea","count":2}],
                 "reorganization":{"allowWithdrawal":true,"absentAfterTicks":200,"allowReplacement":true,"minimumParticipants":1,"refundItems":true}}
                """;
        check(QuestParticipationPolicy.parse(JsonParser.parseString(policy).getAsJsonObject()).reorganization().isPresent(), "authored opt-in");
        reject(() -> QuestParticipationPolicy.parse(JsonParser.parseString(policy.replace("200", "2.5")).getAsJsonObject()), "fractional duration");
        reject(() -> QuestParticipationPolicy.parse(JsonParser.parseString(policy.replace("\"refundItems\":true", "\"refundItems\":\"true\"")).getAsJsonObject()), "string boolean");
        reject(() -> QuestParticipationPolicy.parse(JsonParser.parseString(policy.replace("GROUP", "SOLO")).getAsJsonObject()), "solo policy rejected");
        reject(() -> new QuestReorganizationPolicy(true, 0, false, 2, false), "no irrecoverable minimum without recruitment");
        var invalid = JSON.toJsonTree(refill.snapshot()).getAsJsonObject();
        invalid.getAsJsonObject("roster").getAsJsonObject("departed").addProperty(A.toString(), "WITHDRAWN");
        reject(() -> JSON.fromJson(invalid, QuestParticipationRun.Snapshot.class), "cannot load removed submitted member");
        var receipts = run(1, false);
        receipts.roster(receipts.snapshot().roster().deposit(A, "exact_item", 5)); receipts.remove(A, true, 101);
        receipts.roster(receipts.snapshot().roster().refunded(A, 3)); roundTrip(receipts);
        reject(() -> receipts.snapshot().roster().refunded(A, 3), "refund exceeds actual receipt");
        System.out.println("QuestReorganizationTest: " + checks + " assertions passed");
    }
    private static void roundTrip(QuestParticipationRun run) {
        check(JSON.fromJson(JSON.toJson(run.snapshot()), QuestParticipationRun.Snapshot.class).equals(run.snapshot()), "roster JSON roundtrip");
    }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void reject(Runnable call, String message) { checks++; try { call.run(); } catch (RuntimeException expected) { return; } throw new AssertionError(message); }
}
