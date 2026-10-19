package com.sande.mythictrpg.raid.combat;

import static com.sande.mythictrpg.raid.combat.RaidPatternTimeline.Stage.*;

/** Cast interruption, repeated ticks and delayed callbacks exercise the actual pure scheduler. */
public final class RaidPatternTimelineTest {
    private static int checks;

    public static void main(String[] args) {
        ordinaryCast();
        cancellationAndIsolation();
        expiredWindowNeverAttacks();
        invalidClocks();
        System.out.println("RaidPatternTimelineTest: PASS (" + checks + " checks; no server or LLM)");
    }

    private static void ordinaryCast() {
        var cast = new RaidPatternTimeline(100, 20, 5, 10);
        check(cast.advance(99).stage() == PENDING, "future cast remains pending");
        check(cast.advance(100).stage() == WINDUP, "windup starts at authored tick");
        check(!cast.advance(119).enteredActive(), "telegraph alone cannot issue a hit");
        var activated = cast.advance(120);
        check(activated.stage() == ACTIVE && activated.enteredActive(), "activation begins once at the exact deadline");
        check(!cast.advance(120).enteredActive() && !cast.advance(124).enteredActive(), "repeated and later active ticks never repeat activation");
        var recovery = cast.advance(125);
        check(recovery.stage() == RECOVERY && recovery.enteredRecovery(), "recovery begins at active window's exclusive endpoint");
        check(!cast.advance(125).enteredRecovery() && cast.advance(134).stage() == RECOVERY, "recovery transition is one-use");
        check(cast.advance(135).completed() && !cast.advance(136).completed(), "completion signal is one-use");
        cast.cancel();
        check(cast.stage() == COMPLETE, "cancelling completed cast cannot resurrect it");
        check(cast.activationTick() == 120 && cast.recoveryTick() == 125 && cast.completionTick() == 135, "published deadlines match server timing");
        var immediate = new RaidPatternTimeline(0, 0, 1, 0);
        check(immediate.advance(0).enteredActive() && immediate.advance(1).completed(), "zero windup and recovery preserve a real active tick");
    }

    private static void cancellationAndIsolation() {
        var phaseA = new RaidPatternTimeline(100, 20, 5, 10);
        var phaseB = new RaidPatternTimeline(100, 20, 5, 10);
        phaseA.advance(110);
        phaseA.cancel();
        var cancelled = phaseA.advance(120);
        check(cancelled.stage() == CANCELLED && !cancelled.enteredActive(), "phase interruption suppresses pending impact");
        check(phaseB.advance(120).enteredActive(), "another attempt's identical cast retains its own activation");
        phaseA.cancel();
        check(phaseA.advance(200).stage() == CANCELLED, "later callbacks cannot restart a cancelled cast");
        phaseB.cancel();
        check(!phaseB.advance(121).enteredActive() && phaseB.stage() == CANCELLED, "interrupting active cast terminates future effects");
    }

    private static void expiredWindowNeverAttacks() {
        var cast = new RaidPatternTimeline(100, 20, 5, 10);
        cast.advance(110);
        var late = cast.advance(130);
        check(late.stage() == RECOVERY && !late.enteredActive(), "callback after active window never emits delayed damage");
        check(!cast.advance(130).enteredRecovery(), "repeated late callback remains one-use");
        var skipped = new RaidPatternTimeline(100, 20, 5, 10).advance(200);
        check(skipped.stage() == COMPLETE && skipped.completed() && !skipped.enteredActive(), "entire expired cast closes without damage");
        var within = new RaidPatternTimeline(100, 20, 5, 10).advance(122);
        check(within.stage() == ACTIVE && within.enteredActive(), "first callback still in active window may issue the impact");
    }

    private static void invalidClocks() {
        invalid(() -> new RaidPatternTimeline(-1, 20, 5, 10), "negative start rejected");
        invalid(() -> new RaidPatternTimeline(0, -1, 5, 10), "negative windup rejected");
        invalid(() -> new RaidPatternTimeline(0, 20, 0, 10), "empty active window rejected");
        invalid(() -> new RaidPatternTimeline(0, 20, 5, -1), "negative recovery rejected");
        invalid(() -> new RaidPatternTimeline(0, 1_728_001, 5, 10), "unbounded cast rejected");
        invalid(() -> new RaidPatternTimeline(Long.MAX_VALUE - 3, 2, 2, 0), "deadline overflow rejected");
        var cast = new RaidPatternTimeline(100, 20, 5, 10);
        invalid(() -> cast.advance(-1), "negative callback rejected");
        cast.advance(120);
        invalid(() -> cast.advance(119), "backwards callback cannot return to windup");
        check(!cast.advance(120).enteredActive(), "rejected clock cannot replay the original activation");
    }

    private static void invalid(Runnable action, String description) {
        try { action.run(); throw new AssertionError(description); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
}
