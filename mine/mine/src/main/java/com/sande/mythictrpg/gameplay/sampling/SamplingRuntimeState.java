package com.sande.mythictrpg.gameplay.sampling;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

public final class SamplingRuntimeState {
    private static final Map<MinecraftServer, SamplingRuntimeState> STATES = new IdentityHashMap<>();
    private static final Comparator<ScheduledSource> SCHEDULE_ORDER = Comparator
            .comparingLong(ScheduledSource::nextDueTick)
            .thenComparing(scheduled -> scheduled.group().source());

    private final MinecraftServer server;
    private final Map<SamplingCursorKey, Long> cursors = new HashMap<>();
    private final PriorityQueue<ScheduledSource> schedule = new PriorityQueue<>(SCHEDULE_ORDER);
    private WatchedMetricSnapshot appliedSnapshot = WatchedMetricSnapshot.empty();

    private SamplingRuntimeState(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    public static SamplingRuntimeState get(MinecraftServer server) {
        requireServerThread(server);
        return STATES.computeIfAbsent(server, SamplingRuntimeState::new);
    }

    public static void removePlayerIfPresent(MinecraftServer server, UUID playerId) {
        requireServerThread(server);
        SamplingRuntimeState state = STATES.get(server);
        if (state != null) {
            state.removePlayer(playerId);
        }
    }

    public static void discard(MinecraftServer server) {
        requireServerThread(server);
        STATES.remove(server);
    }

    public static boolean hasStateForTesting(MinecraftServer server) {
        requireServerThread(server);
        return STATES.containsKey(server);
    }

    SamplingResult sample(WatchedMetricSnapshot snapshot, long gameTime,
            Collection<SamplingPlayerView> players) {
        requireServerThread(server);
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(players, "players");
        applySnapshot(snapshot, gameTime);
        if (schedule.isEmpty() || schedule.peek().nextDueTick() > gameTime) {
            return SamplingResult.empty();
        }

        List<WatchedMetricSnapshot.SourceGroup> dueGroups = new ArrayList<>();
        while (!schedule.isEmpty() && schedule.peek().nextDueTick() <= gameTime) {
            ScheduledSource due = schedule.remove();
            dueGroups.add(due.group());
            schedule.add(new ScheduledSource(nextDueTick(gameTime, due.group().intervalTicks()), due.group()));
        }

        int sourceReads = 0;
        int baselines = 0;
        int resets = 0;
        int unavailable = 0;
        List<ThresholdCrossing> crossings = new ArrayList<>();
        for (SamplingPlayerView player : players) {
            for (WatchedMetricSnapshot.SourceGroup group : dueGroups) {
                sourceReads++;
                var value = group.source().read(player.statistics());
                if (!value.isAvailable()) {
                    unavailable++;
                    continue;
                }

                long current = value.value();
                SamplingCursorKey cursorKey = new SamplingCursorKey(player.playerId(), group.source());
                Long previous = cursors.put(cursorKey, current);
                if (previous == null) {
                    baselines++;
                    continue;
                }
                if (current < previous) {
                    resets++;
                    continue;
                }
                if (current == previous) {
                    continue;
                }

                long delta = current - previous;
                for (WatchedMetricDefinition watch : group.watches()) {
                    long threshold = watch.thresholdPolicy().milestone();
                    if (previous < threshold && current >= threshold) {
                        crossings.add(new ThresholdCrossing(watch.watchId(), watch.metricKey(), group.source(),
                                player.playerId(), previous, current, threshold, delta));
                    }
                }
            }
        }
        return new SamplingResult(players.size(), sourceReads, baselines, resets, unavailable, crossings);
    }

    void removePlayer(UUID playerId) {
        requireServerThread(server);
        cursors.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    int cursorCountForTesting() {
        return cursors.size();
    }

    private void applySnapshot(WatchedMetricSnapshot snapshot, long gameTime) {
        if (appliedSnapshot == snapshot) {
            return;
        }
        Set<VanillaStatisticSource> retainedSources = snapshot.sourceGroups().keySet();
        cursors.keySet().removeIf(key -> !retainedSources.contains(key.source()));
        schedule.clear();
        snapshot.sourceGroups().values().forEach(group ->
                schedule.add(new ScheduledSource(gameTime, group)));
        appliedSnapshot = snapshot;
    }

    private static long nextDueTick(long currentTick, int intervalTicks) {
        return currentTick > Long.MAX_VALUE - intervalTicks ? Long.MAX_VALUE : currentTick + intervalTicks;
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Gameplay statistic sampling must run on the server thread");
        }
    }

    private record ScheduledSource(long nextDueTick, WatchedMetricSnapshot.SourceGroup group) {
    }
}
