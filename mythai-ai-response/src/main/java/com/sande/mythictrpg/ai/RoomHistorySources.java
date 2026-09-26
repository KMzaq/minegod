package com.sande.mythictrpg.ai;

import com.sande.mythictrpg.ai.api.RoomConversationEngine.HistoryLine;
import java.util.*;
import java.util.function.BiPredicate;

/** Merge/split changes a room container, never the provenance of a previously spoken line. */
public final class RoomHistorySources {
    private RoomHistorySources() { }
    public static Map<UUID, List<String>> npcSources(UUID fallbackRoom, List<HistoryLine> history) {
        var result = new LinkedHashMap<UUID, List<String>>();
        for (var line : history) if ("NPC".equals(line.role()))
            result.computeIfAbsent(origin(fallbackRoom, line), ignored -> new ArrayList<>()).add(line.text());
        result.replaceAll((key, value) -> List.copyOf(value));
        return Collections.unmodifiableMap(result);
    }
    public static List<HistoryLine> filter(UUID fallbackRoom, List<HistoryLine> history,
            BiPredicate<UUID, String> stillValid) {
        return history.stream().filter(line -> !"NPC".equals(line.role())
                || stillValid.test(origin(fallbackRoom, line), line.text())).toList();
    }
    private static UUID origin(UUID fallbackRoom, HistoryLine line) {
        return line.sourceRoomId() == null ? fallbackRoom : line.sourceRoomId();
    }
}
