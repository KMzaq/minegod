package com.sande.mythictrpg.gameplay.ledger;

/** Pure threshold/cooldown policy: one notice on crossing 90%, then at most once per 30 minutes. */
public final class LedgerCapacityWarning {
    private static final long REPEAT_MS = 30 * 60 * 1000L;
    private boolean above;
    private long lastNotice;
    public boolean shouldNotify(long used, long limit, long nowMillis) {
        if (limit <= 0 || used < limit - limit / 10) { above = false; return false; }
        if (!above || nowMillis - lastNotice >= REPEAT_MS) {
            above = true; lastNotice = nowMillis; return true;
        }
        return false;
    }
}
