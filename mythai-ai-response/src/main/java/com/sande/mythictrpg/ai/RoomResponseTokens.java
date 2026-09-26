package com.sande.mythictrpg.ai;

import java.util.*;

/** Server-thread-only tokens; rooms sharing a player or God never share a pending turn. */
final class RoomResponseTokens {
    record Token(UUID room, long revision, UUID turn, long sequence) { }
    private final Map<UUID, Token> turns = new HashMap<>();
    private final Map<UUID, Long> sequences = new HashMap<>();
    Token issue(UUID room, long revision, UUID turn) {
        var token = new Token(room, revision, turn, sequences.merge(room, 1L, Long::sum));
        turns.put(room, token); return token;
    }
    boolean current(Token token) { return token.equals(turns.get(token.room())); }
    void invalidate(UUID room) { turns.remove(room); }
    void clear() { turns.clear(); sequences.clear(); }
}
