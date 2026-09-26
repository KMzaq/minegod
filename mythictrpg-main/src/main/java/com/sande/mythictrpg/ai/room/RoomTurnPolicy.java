package com.sande.mythictrpg.ai.room;

import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import com.sande.mythictrpg.ai.api.RoomConversationEngine.HistoryLine;
import java.util.*;

/** One optional reaction opportunity per existing God, never an autonomous endless conversation. */
public final class RoomTurnPolicy {
    private RoomTurnPolicy() { }
    public static Optional<String> secondarySpeaker(ConversationRoomSnapshot room, String primary) {
        return reactionSpeakers(room, primary).stream().findFirst();
    }
    public static List<String> reactionSpeakers(ConversationRoomSnapshot room, String primary) {
        if (!room.godIds().contains(primary)) return List.of();
        return room.godIds().stream().filter(g -> !g.equals(primary)).sorted().toList();
    }
    public static boolean fullyDispatched(RoomDialogueEvent event, UUID player) {
        var receipt = event.deliveries().get(player);
        return receipt != null && (receipt.chatDispatched()
                || receipt.hudPagesDispatched() == RoomHudText.pages(event.text()).size());
    }
    public static Set<String> heardGods(ConversationRoomSnapshot room, java.util.function.Predicate<String> available) {
        return room.godIds().stream().filter(available).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Secondary recall uses only the most recent NPC line remaining after audience and evidence
     * filtering. Player input must never be supplied as an unfiltered fallback. Oversized or blank
     * latest speech yields no query rather than silently truncating it or substituting an older turn.
     */
    public static String latestPermittedNpcText(List<HistoryLine> permittedHistory) {
        Objects.requireNonNull(permittedHistory, "permittedHistory");
        for (int i = permittedHistory.size() - 1; i >= 0; i--) {
            var line = permittedHistory.get(i);
            if ("NPC".equals(line.role())) return line.text().isBlank() || line.text().length() > 4000 ? "" : line.text();
        }
        return "";
    }
}
