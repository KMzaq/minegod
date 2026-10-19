package com.sande.mythictrpg.raid.combat;

/** One server-owned cast window. Cancellation and expired active windows never emit a late attack. */
public final class RaidPatternTimeline {
    public static final int MAX_STAGE_TICKS = 1_728_000;
    public enum Stage { PENDING, WINDUP, ACTIVE, RECOVERY, COMPLETE, CANCELLED }
    public record Advance(Stage stage, boolean enteredActive, boolean enteredRecovery, boolean completed) { }

    private final long startTick, activationTick, recoveryTick, completionTick;
    private long lastTick = -1;
    private Stage stage = Stage.PENDING;

    public RaidPatternTimeline(long startTick, int windupTicks, int activeTicks, int recoveryTicks) {
        if (startTick < 0 || !validDuration(windupTicks, true) || !validDuration(activeTicks, false)
                || !validDuration(recoveryTicks, true)) throw new IllegalArgumentException("Invalid cast timeline");
        this.startTick = startTick;
        try {
            activationTick = Math.addExact(startTick, windupTicks);
            recoveryTick = Math.addExact(activationTick, activeTicks);
            completionTick = Math.addExact(recoveryTick, recoveryTicks);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Cast deadline overflows game time", overflow);
        }
    }

    /** Nondecreasing server time is required. Repeated ticks do not repeat transition signals. */
    public Advance advance(long now) {
        if (now < 0 || now < lastTick) throw new IllegalArgumentException("Cast clock moved backwards");
        lastTick = now;
        if (stage == Stage.CANCELLED) return new Advance(stage, false, false, false);
        Stage previous = stage;
        stage = now < startTick ? Stage.PENDING : now < activationTick ? Stage.WINDUP
                : now < recoveryTick ? Stage.ACTIVE : now < completionTick ? Stage.RECOVERY : Stage.COMPLETE;
        return new Advance(stage, stage == Stage.ACTIVE && previous != Stage.ACTIVE,
                stage == Stage.RECOVERY && previous != Stage.RECOVERY,
                stage == Stage.COMPLETE && previous != Stage.COMPLETE);
    }

    /** A phase interruption is terminal for this cast, including all later tick callbacks. */
    public void cancel() {
        if (stage != Stage.COMPLETE) stage = Stage.CANCELLED;
    }

    public Stage stage() { return stage; }
    public long startTick() { return startTick; }
    public long activationTick() { return activationTick; }
    public long recoveryTick() { return recoveryTick; }
    public long completionTick() { return completionTick; }

    private static boolean validDuration(int ticks, boolean allowZero) {
        return ticks >= (allowZero ? 0 : 1) && ticks <= MAX_STAGE_TICKS;
    }
}
