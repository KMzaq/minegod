package com.sande.mythictrpg.ai.region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Incremental proof of six-face connectivity between actual block biomes. Server-thread only.
 * A region is the origin's connected component, never all occurrences of its biome registry ID.
 *
 * <p>SAME requires a sampled same-biome path. DIFFERENT requires a different dimension/biome,
 * an out-of-domain position, or a completely explored component. Unloaded cells and exhausted
 * memory/work budgets remain UNKNOWN. Callers must not interpret UNKNOWN as permission to
 * reserve another region: it may overlap an existing reservation.</p>
 *
 * <p>The source's biome graph must remain unchanged during a region's lifetime. Chunk unload
 * does not invalidate an already proved path, but biome edits/replacement worlds require
 * {@link #invalidateAll()}. Nothing is persisted across server lifetimes.</p>
 */
public final class ConnectedBiomeRegions {
    public static final int DEFAULT_MAX_REGIONS = 64;
    public static final int DEFAULT_MAX_CELLS_PER_REGION = 131_072;
    private static final int MAX_PATHS = 32;
    private static final int MAX_RECENT_RESULTS = 64;
    private static final int MAX_ANCHORS = 16;

    public enum Membership { SAME, DIFFERENT, UNKNOWN }

    public record Cell(String dimension, int x, int y, int z) {
        public Cell {
            Objects.requireNonNull(dimension, "dimension");
            if (dimension.isBlank()) throw new IllegalArgumentException("Blank dimension");
        }

        private Cell offset(int dx, int dy, int dz) {
            long nx = (long) x + dx, ny = (long) y + dy, nz = (long) z + dz;
            if (nx < Integer.MIN_VALUE || nx > Integer.MAX_VALUE
                    || ny < Integer.MIN_VALUE || ny > Integer.MAX_VALUE
                    || nz < Integer.MIN_VALUE || nz > Integer.MAX_VALUE) return null;
            return new Cell(dimension, (int) nx, (int) ny, (int) nz);
        }
    }

    public record Region(long id, Cell origin, String biomeId) {}

    /** contains describes fixed world bounds, not which chunks happen to be loaded. */
    public interface Source {
        boolean contains(Cell cell);

        /** Actual biome registry ID, or null if it cannot be read without loading/generating. */
        String biomeAt(Cell cell);
    }

    public record Stats(int rememberedCells, int queuedCells, int pendingPaths,
                        boolean complete, boolean capacityReached) {}

    private enum State { PENDING, CONNECTED, BOUNDARY }

    private final Source source;
    private final int maxRegions;
    private final int maxCellsPerRegion;
    private final Map<Region, Search> searches = new LinkedHashMap<>();
    private long nextId;
    private int roundRobin;

    public ConnectedBiomeRegions(Source source) {
        this(source, DEFAULT_MAX_REGIONS, DEFAULT_MAX_CELLS_PER_REGION);
    }

    public ConnectedBiomeRegions(Source source, int maxRegions, int maxCellsPerRegion) {
        this.source = Objects.requireNonNull(source, "source");
        if (maxRegions < 1 || maxCellsPerRegion < 7) throw new IllegalArgumentException("Invalid capacity");
        this.maxRegions = maxRegions;
        this.maxCellsPerRegion = maxCellsPerRegion;
    }

    /** One biome read at most; null means unavailable/capacity full, not an empty biome region. */
    public Region open(Cell origin) {
        if (origin == null || !source.contains(origin) || searches.size() >= maxRegions) return null;
        String biomeId = source.biomeAt(origin);
        if (biomeId == null || biomeId.isBlank()) return null;
        Region region = new Region(++nextId, origin, biomeId);
        searches.put(region, new Search(region));
        return region;
    }

    /**
     * Constant bounded work: one biome read at most, six cached neighbors, sixteen path anchors.
     * Unknown same-biome targets enqueue a bounded exact path probe; advance does the work.
     */
    public Membership membership(Region region, Cell target) {
        Search search = searches.get(region);
        if (search == null || target == null) return Membership.UNKNOWN;
        Membership cached = search.peek(target);
        if (cached != Membership.UNKNOWN) return cached;
        String biomeId = source.biomeAt(target);
        if (biomeId != null && !biomeId.equals(region.biomeId())) {
            search.remember(target, Membership.DIFFERENT);
            return Membership.DIFFERENT;
        }
        if (biomeId != null && search.hasConnectedNeighbor(target)) {
            search.connect(target);
            search.remember(target, Membership.SAME);
            search.anchor(target);
            return Membership.SAME;
        }
        search.requestPath(target);
        return Membership.UNKNOWN;
    }

    /** Cached proof only; does not sample biomes or enqueue/advance any searches. */
    public Membership peekMembership(Region region, Cell target) {
        Search search = searches.get(region);
        return search == null || target == null ? Membership.UNKNOWN : search.peek(target);
    }

    /**
     * Global budget across all regions, with fair rotation. Each work unit performs at most one
     * biome read plus constant bookkeeping. Cache hits and retrying unloaded cells also consume
     * work units, so an unlimited frontier or loaded-cache traversal cannot block a tick.
     */
    public int advance(int maxWorkUnits) {
        if (maxWorkUnits <= 0 || searches.isEmpty()) return 0;
        var active = new ArrayList<>(searches.values());
        int work = 0, idle = 0;
        while (work < maxWorkUnits && idle < active.size()) {
            if (roundRobin >= active.size()) roundRobin = 0;
            Search search = active.get(roundRobin++);
            if (search.step()) { work++; idle = 0; }
            else idle++;
        }
        return work;
    }

    public Stats stats(Region region) {
        Search search = searches.get(region);
        return search == null ? null : new Stats(search.cells.size(), search.frontier.size(),
                search.paths.size(), search.complete(), search.truncated);
    }

    public void release(Region region) { searches.remove(region); }

    /** Invalidates handles as well as cached proofs; IDs are never reused by this service. */
    public void invalidateAll() { searches.clear(); roundRobin = 0; }

    public int regionCount() { return searches.size(); }

    private final class Search {
        final Region region;
        final Map<Cell, State> cells = new HashMap<>();
        final ArrayDeque<Cell> frontier = new ArrayDeque<>();
        final ArrayDeque<Path> paths = new ArrayDeque<>();
        final ArrayDeque<Cell> anchors = new ArrayDeque<>();
        final Map<Cell, Membership> recent = new LinkedHashMap<>();
        boolean truncated;
        int pathTurns;

        Search(Region region) {
            this.region = region;
            connect(region.origin());
        }

        boolean complete() { return frontier.isEmpty() && !truncated; }

        Membership peek(Cell target) {
            if (!region.origin().dimension().equals(target.dimension()) || !source.contains(target)) {
                return Membership.DIFFERENT;
            }
            State state = cells.get(target);
            if (state == State.CONNECTED) return Membership.SAME;
            if (state == State.BOUNDARY) return Membership.DIFFERENT;
            Membership cached = recent.get(target);
            if (cached != null) return cached;
            return complete() ? Membership.DIFFERENT : Membership.UNKNOWN;
        }

        void remember(Cell cell, Membership result) {
            recent.remove(cell);
            recent.put(cell, result);
            if (recent.size() > MAX_RECENT_RESULTS) recent.remove(recent.keySet().iterator().next());
        }

        void anchor(Cell cell) {
            anchors.remove(cell);
            anchors.addLast(cell);
            if (anchors.size() > MAX_ANCHORS) anchors.removeFirst();
        }

        boolean hasConnectedNeighbor(Cell cell) {
            for (int axis = 0; axis < 3; axis++) {
                for (int direction : new int[]{-1, 1}) {
                    Cell neighbor = neighbor(cell, axis, direction);
                    if (neighbor != null && (cells.get(neighbor) == State.CONNECTED
                            || recent.get(neighbor) == Membership.SAME)) return true;
                }
            }
            return false;
        }

        void connect(Cell cell) {
            State old = cells.get(cell);
            if (old == State.CONNECTED) return;
            if (old == null && cells.size() >= maxCellsPerRegion) {
                truncated = true;
                return;
            }
            cells.put(cell, State.CONNECTED);
            for (int axis = 0; axis < 3; axis++) {
                schedule(neighbor(cell, axis, -1));
                schedule(neighbor(cell, axis, 1));
            }
        }

        void schedule(Cell cell) {
            if (cell == null || !source.contains(cell) || cells.containsKey(cell)) return;
            if (cells.size() >= maxCellsPerRegion) { truncated = true; return; }
            cells.put(cell, State.PENDING);
            frontier.addLast(cell);
        }

        void requestPath(Cell target) {
            for (Path path : paths) if (path.target.equals(target)) return;
            if (paths.size() >= MAX_PATHS) return;
            Cell start = region.origin();
            for (Cell anchor : anchors) {
                if (distance(anchor, target) < distance(start, target)) start = anchor;
            }
            paths.addLast(new Path(start, target));
        }

        boolean step() {
            // Goal-directed axis paths help ordinary walking/joining; BFS proves detours and
            // separation. A failed axis path is never itself evidence of disconnection.
            if (!paths.isEmpty() && (frontier.isEmpty() || (pathTurns++ & 3) != 3)) {
                Path path = paths.removeFirst();
                if (peek(path.target) != Membership.UNKNOWN) return true;
                Cell next = path.next();
                if (next == null || !source.contains(next)) return true;
                Membership known = peek(next);
                if (known == Membership.DIFFERENT) return true;
                String biome = known == Membership.SAME ? region.biomeId() : source.biomeAt(next);
                if (biome == null) { paths.addLast(path); return true; }
                if (!biome.equals(region.biomeId())) {
                    remember(next, Membership.DIFFERENT);
                    return true;
                }
                connect(next);
                path.current = next;
                if (next.equals(path.target)) {
                    remember(next, Membership.SAME);
                    anchor(next);
                } else paths.addLast(path);
                return true;
            }
            Cell cell = frontier.pollFirst();
            if (cell == null) return false;
            if (cells.get(cell) != State.PENDING) return true;
            String biome = source.biomeAt(cell);
            if (biome == null) { frontier.addLast(cell); return true; }
            if (biome.equals(region.biomeId())) connect(cell);
            else cells.put(cell, State.BOUNDARY);
            return true;
        }
    }

    private static Cell neighbor(Cell cell, int axis, int direction) {
        return cell.offset(axis == 0 ? direction : 0, axis == 1 ? direction : 0, axis == 2 ? direction : 0);
    }

    private static long distance(Cell a, Cell b) {
        return Math.abs((long) a.x() - b.x()) + Math.abs((long) a.y() - b.y()) + Math.abs((long) a.z() - b.z());
    }

    private static final class Path {
        Cell current;
        final Cell target;

        Path(Cell current, Cell target) { this.current = current; this.target = target; }

        Cell next() {
            if (current.x() != target.x()) return current.offset(Integer.compare(target.x(), current.x()), 0, 0);
            if (current.z() != target.z()) return current.offset(0, 0, Integer.compare(target.z(), current.z()));
            if (current.y() != target.y()) return current.offset(0, Integer.compare(target.y(), current.y()), 0);
            return null;
        }
    }
}
