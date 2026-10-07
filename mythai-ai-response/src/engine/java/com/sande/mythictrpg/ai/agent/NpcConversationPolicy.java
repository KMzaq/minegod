package com.sande.mythictrpg.ai.agent;

/**
 * Declarative availability for one physical NPC. Remote avatars or split forms must opt in explicitly; the safe
 * default prevents the same deity from appearing in two unrelated sessions at once.
 */
public record NpcConversationPolicy(boolean allowSimultaneousSessions, int maxSessions) {
    public NpcConversationPolicy {
        if (maxSessions < 1 || maxSessions > 16) {
            throw new IllegalArgumentException("maxSessions must be between 1 and 16");
        }
        if (!allowSimultaneousSessions && maxSessions != 1) {
            throw new IllegalArgumentException("maxSessions must be 1 unless simultaneous sessions are enabled");
        }
    }

    public static NpcConversationPolicy singlePhysicalPresence() {
        return new NpcConversationPolicy(false, 1);
    }

    public boolean canJoinSessionCount(int currentSessionCount) {
        return currentSessionCount >= 0 && currentSessionCount < maxSessions;
    }
}
