package com.sande.mythictrpg.quest;

import java.util.*;

/** Durable roster audit, presence evidence and refundable inventory receipts. No Minecraft side effects. */
public record QuestRoster(QuestReorganizationPolicy policy, int initialSize, long revision,
        Map<UUID, Long> lastSeen, Map<UUID, String> departed,
        Map<UUID, Map<String, Integer>> deposits, Map<UUID, Integer> refundQueued) {
    public QuestRoster {
        Objects.requireNonNull(policy);
        lastSeen = Map.copyOf(lastSeen); departed = Map.copyOf(departed); refundQueued = Map.copyOf(refundQueued);
        Map<UUID, Map<String, Integer>> copy = new LinkedHashMap<>();
        deposits.forEach((id, items) -> copy.put(id, Map.copyOf(items))); deposits = Map.copyOf(copy);
        if (initialSize < policy.minimumParticipants() || initialSize > 16 || revision < 0
                || lastSeen.values().stream().anyMatch(t -> t < 0)
                || departed.values().stream().anyMatch(r -> !Set.of("WITHDRAWN", "ABSENT_REMOVED").contains(r)))
            throw new IllegalArgumentException("Invalid roster history");
        for (var items : deposits.values()) {
            if (!policy.refundItems() || items.size() > 128 || items.keySet().stream().anyMatch(s -> s.isBlank() || s.length() > 20_000)
                    || items.values().stream().anyMatch(n -> n < 1) || items.values().stream().mapToLong(Integer::longValue).sum() > 16_000_000)
                throw new IllegalArgumentException("Invalid inventory receipts");
        }
        for (var entry : refundQueued.entrySet())
            if (!departed.containsKey(entry.getKey()) || entry.getValue() < 0
                    || entry.getValue() > deposits.getOrDefault(entry.getKey(), Map.of()).values().stream().mapToInt(Integer::intValue).sum())
                throw new IllegalArgumentException("Invalid refund cursor");
    }
    public static QuestRoster create(QuestReorganizationPolicy policy, Set<UUID> players, long now) {
        Map<UUID, Long> seen = new HashMap<>(); players.forEach(id -> seen.put(id, now));
        return new QuestRoster(policy, players.size(), 0, seen, Map.of(), Map.of(), Map.of());
    }
    public QuestRoster seen(UUID player, long now) {
        Map<UUID, Long> seen = new HashMap<>(lastSeen); seen.put(player, now);
        return new QuestRoster(policy, initialSize, revision, seen, departed, deposits, refundQueued);
    }
    public QuestRoster remove(UUID player, String reason) {
        Map<UUID, String> removed = new HashMap<>(departed); removed.put(player, reason);
        return new QuestRoster(policy, initialSize, revision + 1, lastSeen, removed, deposits, refundQueued);
    }
    public QuestRoster joined(UUID player, long now) {
        var seen = seen(player, now);
        return new QuestRoster(policy, initialSize, revision + 1, seen.lastSeen, departed, deposits, refundQueued);
    }
    public QuestRoster deposit(UUID player, String item, int count) {
        Map<UUID, Map<String, Integer>> all = new HashMap<>(deposits);
        Map<String, Integer> items = new HashMap<>(all.getOrDefault(player, Map.of()));
        items.merge(item, count, Math::addExact); all.put(player, items);
        return new QuestRoster(policy, initialSize, revision, lastSeen, departed, all, refundQueued);
    }
    public QuestRoster refunded(UUID player, int count) {
        Map<UUID, Integer> queued = new HashMap<>(refundQueued); queued.merge(player, count, Math::addExact);
        return new QuestRoster(policy, initialSize, revision, lastSeen, departed, deposits, queued);
    }
}
