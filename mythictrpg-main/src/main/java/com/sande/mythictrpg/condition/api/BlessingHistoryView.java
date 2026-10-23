package com.sande.mythictrpg.condition.api;

import java.util.UUID;

/** Authoritative acquisition history; active effects and milk suppression do not define ownership. */
public interface BlessingHistoryView {
    boolean isReady();
    boolean hasNonRaidBlessing(UUID playerId);

    static BlessingHistoryView unavailable() {
        return new BlessingHistoryView() {
            @Override public boolean isReady() { return false; }
            @Override public boolean hasNonRaidBlessing(UUID playerId) { return false; }
        };
    }
}
