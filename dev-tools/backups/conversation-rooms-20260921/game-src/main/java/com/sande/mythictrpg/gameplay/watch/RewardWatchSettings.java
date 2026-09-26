package com.sande.mythictrpg.gameplay.watch;

import com.google.gson.Gson;
import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import static com.sande.mythictrpg.gameplay.watch.WatchContract.*;
import java.nio.file.*;
import java.util.*;

/** Authored, server-local remote observation policy. No inferred pantheon hierarchy or default region. */
public record RewardWatchSettings(int schemaVersion, boolean enabled, long maxStorageBytes,
        int maxEntries, int queueCapacity, List<Rule> rules, List<Barrier> barriers, int activityWindowTicks) {
    public RewardWatchSettings(int schemaVersion,boolean enabled,long maxStorageBytes,int maxEntries,int queueCapacity,List<Rule> rules,List<Barrier> barriers) {
        this(schemaVersion,enabled,maxStorageBytes,maxEntries,queueCapacity,rules,barriers,0);
    }
    public static final RewardWatchSettings OFF = new RewardWatchSettings(1, false, 0, 10_000, 128, List.of(), List.of());
    public record Rule(Policy policy, Set<ActionRecord.Type> eventTypes, boolean requiresSky) {
        public Rule { Objects.requireNonNull(policy); eventTypes = Set.copyOf(eventTypes); }
    }
    /** Empty allowedGods on an ALL barrier is intentionally absolute, including its owner. */
    public record Barrier(Ref ref, Area area, boolean blockAll, Set<String> allowedGods) {
        public Barrier {
            Objects.requireNonNull(ref); Objects.requireNonNull(area); allowedGods = Set.copyOf(allowedGods);
            if (allowedGods.size() > 64 || blockAll && !allowedGods.isEmpty()) throw new IllegalArgumentException("barrier allow list");
            allowedGods.forEach(WatchContract::resource);
        }
        public boolean blocks(String god, ActionRecord.Draft event) {
            return area.contains(event) && (blockAll || !allowedGods.contains(god));
        }
    }
    public RewardWatchSettings {
        if (schemaVersion != 1) throw new IllegalArgumentException("watch reward schema");
        rules = List.copyOf(rules); barriers = List.copyOf(barriers);
        if (rules.size() > 63 || barriers.size() > 128 || activityWindowTicks != 0 && (activityWindowTicks<20 || activityWindowTicks>72000)) throw new IllegalArgumentException("watch policy budget");
        if (rules.stream().map(r -> r.policy().godId()).distinct().count() != rules.size())
            throw new IllegalArgumentException("duplicate god policy");
        if (enabled) new AsyncGodWatch.Limits(maxStorageBytes, maxEntries, queueCapacity);
    }
    public AsyncGodWatch.Limits limits() { return new AsyncGodWatch.Limits(maxStorageBytes, maxEntries, queueCapacity); }
    public Optional<Rule> rule(String god) { return enabled ? rules.stream().filter(r -> r.policy().godId().equals(god)).findFirst() : Optional.empty(); }
    public boolean blocked(String god, ActionRecord.Draft event) { return barriers.stream().anyMatch(b -> b.blocks(god, event)); }
    public static RewardWatchSettings load(Path path) {
        if (!Files.exists(path)) return OFF;
        try {
            if (Files.size(path) > 262_144) throw new IllegalArgumentException("watch policy file budget");
            return Objects.requireNonNull(new Gson().fromJson(Files.readString(path), RewardWatchSettings.class));
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid reward-watch.json; remote watch disabled", invalid); }
    }
}
