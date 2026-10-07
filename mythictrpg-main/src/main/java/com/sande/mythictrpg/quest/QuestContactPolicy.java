package com.sande.mythictrpg.quest;

/** Pure decision boundary: location alone and reward ownership alone are never a contact. */
public final class QuestContactPolicy {
    private QuestContactPolicy() { }
    public static boolean permits(boolean actualContact, boolean physical, boolean encounter,
            boolean hasWatch, boolean activelyWatching, boolean remoteIdle, boolean atReturnPlace,
            boolean specialVisit) {
        if (!actualContact) return false;
        if (specialVisit) return encounter;
        if (physical || encounter) return hasWatch || atReturnPlace || encounter;
        return hasWatch && activelyWatching && remoteIdle;
    }
}
