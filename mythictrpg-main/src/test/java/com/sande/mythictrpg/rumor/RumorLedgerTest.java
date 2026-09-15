package com.sande.mythictrpg.rumor;

import java.util.*;

public final class RumorLedgerTest {
    private static int checks;
    private static final String GOD = "mythictrpg:demeter", OTHER_GOD = "mythictrpg:fortuna";
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        RumorLedger ledger = new RumorLedger();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), birdA = UUID.randomUUID(), birdB = UUID.randomUUID();
        check(ledger.bindCourier(a, birdA), "bind A"); check(ledger.bindCourier(b, birdB), "bind B");
        check(!ledger.bindCourier(a, UUID.randomUUID()), "no implicit additional courier");
        UUID delivered = UUID.randomUUID(), waiting = UUID.randomUUID(), other = UUID.randomUUID();
        check(!ledger.observe(UUID.randomUUID(), a, birdB, Set.of(a), "x", Set.of(GOD), Set.of(a)), "wrong observer rejected");
        check(!ledger.observe(UUID.randomUUID(), a, birdA, Set.of(a,b), "A and B", Set.of(GOD), Set.of(a,b)), "compound bypass rejected");
        check(ledger.observe(delivered, a, birdA, Set.of(a), "제작 이유 없이 비늘을 요청했다", Set.of(GOD), Set.of(a)), "real observed excerpt");
        check(!ledger.observe(delivered, a, birdA, Set.of(a), "다른 사건", Set.of(GOD), Set.of(a)), "dedup observation");
        check(!ledger.publish(UUID.randomUUID(), "없는 사건", ""), "AI cannot invent observation");
        check(ledger.publish(delivered, "불순한 의도로 오해받았다는 이야기", "오해받은 여행자"), "allegation published");
        check(ledger.heard(a, GOD, Set.of(a)).isEmpty(), "not heard until delivery");
        var first = ledger.pending().getFirst();
        check(!ledger.deliver(new RumorLedger.Delivery(first.rootId(), first.revision(), OTHER_GOD, first.epoch())), "unauthorized receiver");
        check(ledger.deliver(first), "delivery"); check(!ledger.deliver(first), "delivery exactly once");
        check(ledger.heard(a, GOD, Set.of(a)).size() == 1, "receiver knows rumor");
        check(ledger.heard(a, GOD, Set.of(a,b)).isEmpty(), "cannot reveal to new audience");
        check(ledger.heard(a, OTHER_GOD, Set.of(a)).isEmpty(), "gods do not share by default");
        check(ledger.heard(b, GOD, Set.of(b)).isEmpty(), "players do not share");
        check(ledger.observe(waiting, a, birdA, Set.of(a), "새 업적", Set.of(GOD), Set.of(a)), "pending A");
        check(ledger.publish(waiting, "긍정 소문", "친우"), "positive rumor queued");
        check(ledger.observe(other, b, birdB, Set.of(b), "B 사건", Set.of(GOD), Set.of(b)), "pending B");
        check(ledger.publish(other, "B 소문", ""), "B published");
        var stale = ledger.pending().stream().filter(d -> d.rootId().equals(waiting)).findFirst().orElseThrow();
        check(!ledger.courierDied(UUID.randomUUID()), "unknown death no effect");
        check(ledger.courierDied(birdA), "kill applies to bound subject");
        check(!ledger.courierDied(birdA), "duplicate death ignored");
        check(!ledger.deliver(stale), "late delivery blocked");
        check(ledger.heard(a, GOD, Set.of(a)).size() == 1, "already delivered rumor remains");
        check(ledger.pending().size() == 1 && ledger.pending().getFirst().rootId().equals(other), "B unaffected");
        ledger = RumorLedger.restore(ledger.snapshot());
        check(!ledger.deliver(stale), "restart cannot revive old delivery");
        check(ledger.deliver(ledger.pending().getFirst()), "B survives restart");
        check(!ledger.observe(UUID.randomUUID(), a, birdA, Set.of(a), "차단 중", Set.of(GOD), Set.of(a)), "blocked observations");
        check(ledger.bindCourier(a, UUID.randomUUID()), "explicit respawn");
        check(!ledger.deliver(stale), "new epoch rejects old work");
        check(!ledger.publish(waiting, "재전송", ""), "no old backlog replay");
        check(ledger.revoke(delivered), "administrative correction");
        check(ledger.heard(a, GOD, Set.of(a)).isEmpty(), "revocation invalidates derived reputation");
        check(!ledger.publish(delivered, "재발행", ""), "revoke cannot resurrect same root");
        var snapshot = ledger.snapshot();
        check(RumorLedger.restore(snapshot).worldId().equals(ledger.worldId()), "persistent world identity");
        try { RumorLedger.restore(new RumorLedger.Snapshot(2, snapshot.worldId(), snapshot.couriers(), snapshot.evidence(), snapshot.claims(), snapshot.pending(), snapshot.receipts())); throw new AssertionError("unknown schema"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { RumorLedger.restore(new RumorLedger.Snapshot(1, snapshot.worldId(), snapshot.couriers(), snapshot.evidence(), snapshot.claims(), snapshot.pending(), List.of(new RumorLedger.Receipt(UUID.randomUUID(),1,GOD)))); throw new AssertionError("forged receipt"); }
        catch (IllegalArgumentException expected) { checks++; }
        System.out.println("RumorLedgerTest: PASS (" + checks + " checks)");
    }
}
