package com.sande.mythictrpg.ai.region;

import com.sande.mythictrpg.ai.region.ConnectedBiomeRegions.Cell;
import com.sande.mythictrpg.ai.region.ConnectedBiomeRegions.Membership;
import com.sande.mythictrpg.ai.region.ConnectedBiomeRegions.Region;
import com.sande.mythictrpg.ai.room.ConversationRoomLedger;
import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.List;
import java.util.UUID;

/** Deterministic, server-free connectivity, resource-budget and adapter-boundary regression. */
public final class ConnectedBiomeRegionsTest {
    private static int checks;
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String FOREST = "minecraft:forest";
    private static final String DESERT = "minecraft:desert";

    public static void main(String[] args) throws Exception {
        separatedEqualBiomeAndDetour();
        dimensionAndVerticalConnectivity();
        unavailableFrontierIsUnknown();
        boundedWorkAndMemory();
        pathsBeyondCacheCapacityRemainExact();
        releaseAndInvalidation();
        preCommitReservations();
        randomFiniteWorldsMatchExhaustiveOracle();
        adapterUsesLoadedBiomeSource();
        System.out.println("ConnectedBiomeRegionsTest: " + checks + " assertions passed");
    }

    private static void separatedEqualBiomeAndDetour() {
        Grid grid = new Grid(0, 8, 0, 0, 0, 4);
        grid.fill(FOREST);
        for (int z = 0; z <= 4; z++) grid.put(4, 0, z, DESERT);
        ConnectedBiomeRegions service = new ConnectedBiomeRegions(grid);
        Region left = service.open(cell(1, 0, 2));
        check(left != null, "loaded origin opens");
        check(service.membership(left, cell(7, 0, 2)) == Membership.UNKNOWN,
                "same biome ID across a barrier is not automatically same region");
        int beforePeek = grid.reads;
        for (int i = 0; i < 100; i++) service.peekMembership(left, cell(7, 0, 2));
        check(grid.reads == beforePeek, "peek does not read source");
        finish(service, 2000);
        check(service.peekMembership(left, cell(7, 0, 2)) == Membership.DIFFERENT,
                "sealed disconnected equal-biome component is different");
        check(service.membership(left, cell(4, 0, 2)) == Membership.DIFFERENT,
                "different biome is outside region");
        check(service.membership(left, cell(0, 0, 0)) == Membership.SAME, "connected component spans its corners");

        grid.put(4, 0, 4, FOREST);
        service.invalidateAll();
        Region detour = service.open(cell(1, 0, 2));
        service.membership(detour, cell(7, 0, 2));
        finish(service, 2000);
        check(service.peekMembership(detour, cell(7, 0, 2)) == Membership.SAME,
                "failed direct path still discovers same-biome route around barrier");
    }

    private static void dimensionAndVerticalConnectivity() {
        Grid grid = new Grid(-1, 1, -2, 2, -1, 1);
        grid.fill(FOREST);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) grid.put(x, 0, z, DESERT);
        ConnectedBiomeRegions service = new ConnectedBiomeRegions(grid);
        Region region = service.open(cell(0, -2, 0));
        check(service.membership(region, new Cell("minecraft:the_nether", 0, -2, 0)) == Membership.DIFFERENT,
                "equal coordinates/biome in another dimension never share region");
        check(service.membership(region, cell(0, 3, 0)) == Membership.DIFFERENT, "outside build domain is different");
        check(service.membership(region, cell(0, -1, 0)) == Membership.SAME, "vertical face neighbors connect");
        service.membership(region, cell(0, 2, 0));
        finish(service, 2000);
        check(service.peekMembership(region, cell(0, 2, 0)) == Membership.DIFFERENT,
                "same x/z above separating biome layer remains disconnected");

        Grid diagonal = new Grid(0, 1, 0, 0, 0, 1);
        diagonal.fill(DESERT);
        diagonal.put(0, 0, 0, FOREST); diagonal.put(1, 0, 1, FOREST);
        ConnectedBiomeRegions corners = new ConnectedBiomeRegions(diagonal);
        Region origin = corners.open(cell(0, 0, 0));
        finish(corners, 30);
        check(corners.membership(origin, cell(1, 0, 1)) == Membership.DIFFERENT,
                "corner-only contact is not six-face connectivity");
    }

    private static void unavailableFrontierIsUnknown() {
        Grid grid = new Grid(0, 4, 0, 0, 0, 0);
        grid.fill(FOREST);
        grid.unavailable.add(cell(2, 0, 0));
        ConnectedBiomeRegions service = new ConnectedBiomeRegions(grid);
        Region origin = service.open(cell(0, 0, 0));
        service.membership(origin, cell(4, 0, 0));
        service.advance(1000);
        check(service.peekMembership(origin, cell(4, 0, 0)) == Membership.UNKNOWN,
                "missing chunk in path never proves separation or connectivity");
        check(!service.stats(origin).complete(), "unloaded frontier prevents complete proof");
        grid.unavailable.clear();
        finish(service, 1000);
        check(service.peekMembership(origin, cell(4, 0, 0)) == Membership.SAME,
                "ordinary later chunk availability resumes proof");
        grid.unavailable.add(cell(2, 0, 0));
        check(service.peekMembership(origin, cell(4, 0, 0)) == Membership.SAME,
                "unload preserves proof in an unchanged biome graph");
        grid.unavailable.add(cell(0, 0, 0));
        check(service.open(cell(0, 0, 0)) == null, "unknown origin cannot produce a region handle");
    }

    private static void boundedWorkAndMemory() {
        Grid grid = new Grid(-100, 100, -100, 100, -100, 100);
        grid.defaultBiome = FOREST;
        ConnectedBiomeRegions service = new ConnectedBiomeRegions(grid, 3, 31);
        Region first = service.open(cell(-5, 0, 0));
        Region second = service.open(cell(5, 0, 0));
        Region third = service.open(cell(0, 0, 0));
        check(service.open(cell(1, 0, 0)) == null, "region handle count bounded");
        check(service.advance(0) == 0 && service.advance(-4) == 0, "zero/negative work budget does no work");
        int reads = grid.reads;
        int work = service.advance(7);
        check(work == 7 && grid.reads - reads <= 7, "advance budget is global across regions");
        check(service.stats(first).rememberedCells() > 7 && service.stats(second).rememberedCells() > 7
                && service.stats(third).rememberedCells() > 7, "work rotates across live regions");
        service.advance(500);
        for (Region region : new Region[]{first, second, third}) {
            var stats = service.stats(region);
            check(stats.rememberedCells() <= 31, "remembered graph cells bounded");
            check(stats.capacityReached() && !stats.complete(), "memory limit is not a region edge");
            check(service.peekMembership(region, cell(99, 99, 99)) == Membership.UNKNOWN,
                    "unseen same-biome cells remain unknown after cap");
        }
        for (int x = 30; x < 100; x++) service.membership(first, cell(x, 90, 90));
        check(service.stats(first).pendingPaths() <= 32, "target queue bounded");
        reads = grid.reads;
        work = service.advance(13);
        check(work == 13 && grid.reads - reads <= 13, "bounded route probes after memory limit");
    }

    private static void pathsBeyondCacheCapacityRemainExact() {
        Grid grid = new Grid(0, 200, 0, 0, 0, 0);
        grid.fill(FOREST);
        ConnectedBiomeRegions service = new ConnectedBiomeRegions(grid, 1, 7);
        Region region = service.open(cell(0, 0, 0));
        check(service.membership(region, cell(200, 0, 0)) == Membership.UNKNOWN, "long path initially unknown");
        service.advance(1000);
        check(service.peekMembership(region, cell(200, 0, 0)) == Membership.SAME,
                "exact path can prove remote membership without retaining every visited cell");
        check(service.stats(region).rememberedCells() <= 7, "long path does not exceed graph storage cap");

        grid.put(100, 0, 0, DESERT);
        service.invalidateAll();
        Region blocked = service.open(cell(0, 0, 0));
        service.membership(blocked, cell(200, 0, 0));
        service.advance(1000);
        check(service.peekMembership(blocked, cell(200, 0, 0)) == Membership.UNKNOWN,
                "failed path plus capped component never falsely proves a distant equal-biome island");
    }

    private static void releaseAndInvalidation() {
        Grid grid = new Grid(0, 1, 0, 0, 0, 0);
        grid.fill(FOREST);
        ConnectedBiomeRegions service = new ConnectedBiomeRegions(grid, 1, 10);
        Region old = service.open(cell(0, 0, 0));
        service.release(old);
        check(service.regionCount() == 0 && service.stats(old) == null, "release drops region cache");
        check(service.membership(old, cell(0, 0, 0)) == Membership.UNKNOWN, "released handle never yields proof");
        Region fresh = service.open(cell(0, 0, 0));
        check(fresh.id() != old.id(), "replacement region handle unique");
        service.invalidateAll();
        check(service.peekMembership(fresh, cell(0, 0, 0)) == Membership.UNKNOWN, "invalidation revokes old proofs");
        Region reset = service.open(cell(0, 0, 0));
        check(reset.id() != fresh.id(), "invalidation never reuses handle IDs");
        check(service.membership(null, cell(0, 0, 0)) == Membership.UNKNOWN, "missing handle is unknown");
        check(service.membership(reset, null) == Membership.UNKNOWN, "missing position is unknown");
    }

    private static void randomFiniteWorldsMatchExhaustiveOracle() {
        Random random = new Random(20260921L);
        for (int iteration = 0; iteration < 80; iteration++) {
            Grid grid = new Grid(-3, 3, -2, 2, -3, 3);
            for (int x = -3; x <= 3; x++) for (int y = -2; y <= 2; y++) for (int z = -3; z <= 3; z++) {
                grid.put(x, y, z, random.nextInt(3) == 0 ? DESERT : FOREST);
            }
            ConnectedBiomeRegions service = new ConnectedBiomeRegions(grid, 2, 2000);
            Cell origin = cell(0, 0, 0);
            Region region = service.open(origin);
            Set<Cell> expected = component(grid, origin);
            for (int i = 0; i < 20; i++) {
                Cell target = cell(random.nextInt(7) - 3, random.nextInt(5) - 2, random.nextInt(7) - 3);
                Membership result = service.membership(region, target);
                check(result != Membership.SAME || expected.contains(target), "incremental SAME has exact path proof");
                check(result != Membership.DIFFERENT || !expected.contains(target), "incremental DIFFERENT has exact exclusion proof");
                service.advance(5);
            }
            finish(service, 5000);
            check(service.stats(region).complete(), "finite loaded component eventually finishes");
            for (Cell target : grid.biomes.keySet()) {
                check(service.peekMembership(region, target) == (expected.contains(target) ? Membership.SAME : Membership.DIFFERENT),
                        "incremental proof agrees with exhaustive 3D connected-component oracle");
            }
        }
    }

    private static void preCommitReservations() {
        Grid grid = new Grid(0, 8, 0, 0, 0, 0);
        grid.fill(FOREST);
        grid.put(7, 0, 0, DESERT);grid.put(8, 0, 0, DESERT);
        ConnectedBiomeRegions regions = new ConnectedBiomeRegions(grid);
        var reservations = new FixedRoomReservations(regions, 3);
        var ledger = new ConversationRoomLedger();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var firstGod = List.of("mythictrpg:first");var secondGod = List.of("mythictrpg:second");
        var privateRoom = ledger.create(RoomType.PRIVATE, first, firstGod, "", RecordingScope.STANDARD);
        var held = reservations.reserve(UUID.randomUUID(), first, firstGod, RecordingScope.STANDARD,
                cell(0, 0, 0), ledger.activeRooms().size()).orElseThrow();
        check(ledger.activeRooms().size() == 1 && reservations.size() == 1 && regions.regionCount() == 1,
                "pre-commit reservation owns an actual region without creating a visible room");
        check(!reservations.participantsAvailable(first, secondGod), "pending public player cannot be taken");
        check(!reservations.participantsAvailable(second, firstGod), "pending public God cannot be taken");
        check(!reservations.regionAvailable(cell(6, 0, 0)), "UNKNOWN overlapping region remains reserved");
        check(reservations.reserve(UUID.randomUUID(), second, secondGod, RecordingScope.STANDARD,
                cell(6, 0, 0), 1).isEmpty(), "second pending reservation cannot enter an unproved equal-biome region");
        check(reservations.find(held.interactionId()).orElseThrow().equals(held), "interaction ID resolves exact pending reservation");
        check(reservations.reserve(held.interactionId(), second, secondGod, RecordingScope.STANDARD,
                cell(8, 0, 0), 1).isEmpty(), "one interaction ID cannot obtain duplicate reservations");
        var other = reservations.reserve(UUID.randomUUID(), second, secondGod, RecordingScope.STANDARD,
                cell(8, 0, 0), 1).orElseThrow();
        check(!reservations.hasCapacity(1), "pending reservations count toward room capacity");
        reservations.release(other);
        check(reservations.hasCapacity(1) && regions.regionCount() == 1, "failed start releases its room slot and region");
        reservations.release(other);
        check(regions.regionCount() == 1, "duplicate final release cannot release another region");

        var room = ledger.create(RoomType.PUBLIC_FIXED, first, firstGod, Long.toString(held.region().id()), RecordingScope.STANDARD);
        check(reservations.consume(held), "successful room takes the exact pre-commit reservation");
        reservations.release(held);
        check(regions.regionCount() == 1 && reservations.size() == 0, "finally after successful consume preserves room's region");
        check(ledger.selectedPrivateRoom(first).orElseThrow().equals(privateRoom), "public reservation leaves private selection untouched");
        ledger.end(room.roomId(), room.revision());regions.release(held.region());
        grid.unavailable.add(cell(0, 0, 0));
        check(reservations.reserve(UUID.randomUUID(), first, firstGod, RecordingScope.STANDARD,
                cell(0, 0, 0), 1).isEmpty(), "unreadable origin fails before encounter commit");
        check(reservations.size() == 0 && regions.regionCount() == 0, "failed origin probe leaks no slot or handle");
        grid.unavailable.clear();
        var pending = reservations.reserve(UUID.randomUUID(), first, firstGod, RecordingScope.STANDARD,
                cell(0, 0, 0), 1).orElseThrow();
        reservations.clear();regions.invalidateAll();
        check(reservations.find(pending.interactionId()).isEmpty() && regions.regionCount() == 0,
                "fixed invalidation revokes pending reservations too");
        check(ledger.find(privateRoom.roomId()).isPresent() && ledger.selectedPrivateRoom(first).isPresent(),
                "fixed cache invalidation preserves independent private rooms");
    }

    private static void adapterUsesLoadedBiomeSource() throws IOException {
        Set<String> calls = new HashSet<>();
        String resource = "/com/sande/mythictrpg/ai/region/MinecraftBiomeRegionSource.class";
        try (var stream = ConnectedBiomeRegionsTest.class.getResourceAsStream(resource)) {
            check(stream != null, "Minecraft adapter class compiled");
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String called, String desc, boolean isInterface) {
                            calls.add(owner + "." + called);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        check(calls.contains("net/minecraft/server/level/ServerChunkCache.getChunkNow"), "adapter exclusively requests present chunks");
        check(calls.contains("net/minecraft/world/level/biome/BiomeManager.withDifferentSource"), "adapter preserves vanilla zoom seed");
        check(calls.contains("net/minecraft/world/level/biome/BiomeManager.getBiome"), "adapter evaluates actual block biome including Y");
        check(calls.stream().noneMatch(call -> call.endsWith(".getChunk") || call.endsWith(".getUncachedNoiseBiome")
                || call.equals("net/minecraft/server/level/ServerLevel.getBiome")), "adapter cannot force load or generator fallback");
    }

    private static Set<Cell> component(Grid grid, Cell origin) {
        Set<Cell> found = new HashSet<>();
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        found.add(origin); queue.add(origin);
        String biome = grid.biomeAt(origin);
        while (!queue.isEmpty()) {
            Cell here = queue.removeFirst();
            for (int[] direction : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                Cell next = cell(here.x() + direction[0], here.y() + direction[1], here.z() + direction[2]);
                if (grid.contains(next) && biome.equals(grid.biomeAt(next)) && found.add(next)) queue.addLast(next);
            }
        }
        return found;
    }

    private static void finish(ConnectedBiomeRegions service, int budget) {
        for (int i = 0; i < budget; i += 17) if (service.advance(Math.min(17, budget - i)) == 0) return;
    }

    private static Cell cell(int x, int y, int z) { return new Cell(OVERWORLD, x, y, z); }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static final class Grid implements ConnectedBiomeRegions.Source {
        final int minX, maxX, minY, maxY, minZ, maxZ;
        final Map<Cell, String> biomes = new HashMap<>();
        final Set<Cell> unavailable = new HashSet<>();
        String defaultBiome;
        int reads;

        Grid(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.minX = minX; this.maxX = maxX; this.minY = minY; this.maxY = maxY; this.minZ = minZ; this.maxZ = maxZ;
        }

        void put(int x, int y, int z, String biome) { biomes.put(cell(x, y, z), biome); }

        void fill(String biome) {
            for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) put(x, y, z, biome);
        }

        @Override
        public boolean contains(Cell cell) {
            return OVERWORLD.equals(cell.dimension()) && cell.x() >= minX && cell.x() <= maxX
                    && cell.y() >= minY && cell.y() <= maxY && cell.z() >= minZ && cell.z() <= maxZ;
        }

        @Override
        public String biomeAt(Cell cell) {
            reads++;
            if (!contains(cell)) throw new AssertionError("Out-of-domain read: " + cell);
            return unavailable.contains(cell) ? null : biomes.getOrDefault(cell, defaultBiome);
        }
    }
}
