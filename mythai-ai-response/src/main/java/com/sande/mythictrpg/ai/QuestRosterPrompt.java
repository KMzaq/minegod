package com.sande.mythictrpg.ai;

import com.google.gson.JsonParser;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.Request;
import java.util.*;

/** A narrow menu-only exception; membership mutations still require the game's separate confirmation. */
final class QuestRosterPrompt {
    static Set<String> quests(Request request) {
        if (request == null || request.readOnly() || request.secondary()) return Set.of();
        String context = request.speakerState().gameContext(), start = "[QUEST_ROSTER_REQUESTS]", end = "[/QUEST_ROSTER_REQUESTS]";
        int from = context.indexOf(start), to = context.indexOf(end);
        if (from < 0 || to < from || context.length() > 65536 || context.indexOf(start, from + start.length()) >= 0
                || context.indexOf(end, to + end.length()) >= 0) return Set.of();
        try {
            var json = JsonParser.parseString(context.substring(from + start.length(), to).trim()).getAsJsonArray();
            if (json.size() > 8) return Set.of();
            Set<String> result = new HashSet<>();
            for (var entry : json) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()
                        || !entry.getAsString().matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || !result.add(entry.getAsString())) return Set.of();
            }
            return Set.copyOf(result);
        } catch (RuntimeException malformed) { return Set.of(); }
    }
    static boolean allows(Request request, AiDialogueModels.Proposal proposal) {
        return proposal != null && Set.of("quest_roster_request", "mythictrpg:quest_roster_request").contains(proposal.type())
                && proposal.targetParticipantIds().isEmpty() && proposal.parameters().keySet().equals(Set.of("quest_id"))
                && quests(request).contains(proposal.parameters().get("quest_id"));
    }
}
