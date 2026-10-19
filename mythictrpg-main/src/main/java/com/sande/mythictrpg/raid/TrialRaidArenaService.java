package com.sande.mythictrpg.raid;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit administrator preparation of one bounded arena in its own void dimension. */
public final class TrialRaidArenaService {
    public static final ResourceLocation DIMENSION_ID = ResourceLocation.fromNamespaceAndPath("mythictrpg", "raid_trial");
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, DIMENSION_ID);
    public static final BlockPos ARENA_MINIMUM = new BlockPos(-24, 79, -24);
    public static final BlockPos ARENA_MAXIMUM = new BlockPos(24, 96, 24);
    public static final Vec3 ENTRY = new Vec3(0.5, 81, 17.5);
    public static final Vec3 BOSS = new Vec3(0.5, 81, 0.5);
    public static final Vec3 EXIT = new Vec3(0.5, 81, 30.5);
    public static final List<BlockPos> SEAL_ANCHORS = List.of(
            new BlockPos(0, 81, -15), new BlockPos(-13, 81, 8), new BlockPos(13, 81, 8));

    private static final BlockPos BUILD_MINIMUM = new BlockPos(-24, 79, -24);
    private static final BlockPos BUILD_MAXIMUM = new BlockPos(24, 96, 35);
    private static final int FLOOR_Y = 80;
    private static final int RADIUS = 22;
    private static final int MAXIMUM_WRITES = 10_000;
    private static final String MARKER_NAME = "mythictrpg_trial_raid_arena";

    public record Result(boolean success, String message) { }

    private TrialRaidArenaService() { }

    /** Must be called by an explicit authorized setup command on the server thread. */
    public static Result prepare(MinecraftServer server) {
        if (!server.isSameThread()) return new Result(false, "시험 전장 준비는 서버 스레드에서 실행해야 합니다.");
        ServerLevel level = server.getLevel(DIMENSION);
        if (level == null) return new Result(false, "시험 전용 차원이 없습니다. 새 차원 데이터가 포함된 서버를 부팅해야 합니다.");
        Marker marker = marker(level);
        if (!marker.supported) return new Result(false, "시험 전장 준비 기록의 형식을 확인할 수 없어 원본을 보존했습니다.");
        if (marker.prepared) {
            return essentialBlocksIntact(level)
                    ? new Result(true, "이미 준비된 시험 전장입니다. 블록을 다시 덮어쓰지 않았습니다.")
                    : new Result(false, "시험 전장의 필수 바닥·경계·봉인이 손상되었습니다. 기존 편집을 보존하고 준비를 중단했습니다.");
        }

        // Exactly 4 x 5 chunks: only this dedicated dimension, never a player's existing world.
        for (int chunkX = BUILD_MINIMUM.getX() >> 4; chunkX <= BUILD_MAXIMUM.getX() >> 4; chunkX++) {
            for (int chunkZ = BUILD_MINIMUM.getZ() >> 4; chunkZ <= BUILD_MAXIMUM.getZ() >> 4; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
        for (BlockPos position : BlockPos.betweenClosed(BUILD_MINIMUM, BUILD_MAXIMUM)) {
            if (!level.getBlockState(position).isAir() || !level.getFluidState(position).isEmpty()) {
                return new Result(false, "시험 전용 구역에 기존 블록이나 유체가 있습니다. 아무것도 덮어쓰지 않았습니다.");
            }
        }
        AABB region = new AABB(BUILD_MINIMUM.getX(), BUILD_MINIMUM.getY(), BUILD_MINIMUM.getZ(),
                BUILD_MAXIMUM.getX() + 1, BUILD_MAXIMUM.getY() + 1, BUILD_MAXIMUM.getZ() + 1);
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, region).isEmpty()) {
            return new Result(false, "시험 전용 구역에 엔티티가 있어 준비를 중단했습니다.");
        }

        Map<BlockPos, BlockState> plan = layout();
        if (plan.size() > MAXIMUM_WRITES) return new Result(false, "시험 전장 생성량이 안전 한도를 넘었습니다.");
        for (Map.Entry<BlockPos, BlockState> entry : plan.entrySet()) {
            BlockPos position = entry.getKey();
            if (!withinBuildRegion(position)) return new Result(false, "시험 전장 설계가 전용 구역을 벗어났습니다.");
        }
        try {
            for (Map.Entry<BlockPos, BlockState> entry : plan.entrySet()) {
                if (!level.setBlock(entry.getKey(), entry.getValue(), 2)) {
                    return new Result(false, "시험 전장 일부 블록 생성에 실패했습니다. 생성된 구역은 자동 덮어쓰기 없이 보존합니다.");
                }
            }
            if (!essentialBlocksIntact(level)) return new Result(false, "시험 전장 필수 블록 검사를 통과하지 못했습니다.");
            marker.prepared = true;
            marker.setDirty();
            return new Result(true, "시험 전장 준비 완료: 전용 차원, 원형 전투장, 봉인 3개와 복귀 발판을 생성했습니다.");
        } catch (RuntimeException exception) {
            MythicTrpg.LOGGER.error("Trial raid arena preparation failed; partial blocks were preserved", exception);
            return new Result(false, "시험 전장 준비 중 오류가 발생했습니다. 기존 구역은 자동 초기화하지 않습니다.");
        }
    }

    public static boolean isReady(MinecraftServer server) {
        if (!server.isSameThread()) return false;
        ServerLevel level = server.getLevel(DIMENSION);
        if (level == null) return false;
        Marker marker = marker(level);
        return marker.supported && marker.prepared && essentialBlocksIntact(level);
    }

    private static Map<BlockPos, BlockState> layout() {
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                int distance = x * x + z * z;
                if (distance > RADIUS * RADIUS) continue;
                put(plan, x, FLOOR_Y - 1, z, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                BlockState floor = distance >= 20 * 20
                        ? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState()
                        : ((x + z) & 1) == 0 ? Blocks.DEEPSLATE_TILES.defaultBlockState()
                        : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
                if (Math.abs(distance - 11 * 11) <= 8 || distance <= 9) {
                    floor = Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();
                }
                if (distance > 12 && (x == 0 || z == 0 || Math.abs(x) == Math.abs(z))) {
                    floor = Blocks.POLISHED_BASALT.defaultBlockState();
                }
                if (distance >= 18 * 18 && distance <= 19 * 19 && (x == 0 || z == 0 || Math.abs(x) == Math.abs(z))) {
                    floor = Blocks.SEA_LANTERN.defaultBlockState();
                }
                put(plan, x, FLOOR_Y, z, floor);
                if (distance >= 21 * 21 && !(Math.abs(x) <= 2 && z > 0)) {
                    put(plan, x, FLOOR_Y + 1, z, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                    put(plan, x, FLOOR_Y + 2, z, Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultBlockState());
                }
            }
        }

        // Carved runes and low pedestals mark the three puzzle anchors.
        for (BlockPos anchor : SEAL_ANCHORS) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    put(plan, anchor.getX() + dx, FLOOR_Y, anchor.getZ() + dz,
                            Math.abs(dx) == 2 && Math.abs(dz) == 2
                                    ? Blocks.SEA_LANTERN.defaultBlockState()
                                    : Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
                }
            }
            plan.put(anchor, Blocks.LODESTONE.defaultBlockState());
        }

        int[][] pillars = {{0, -21}, {15, -15}, {21, 0}, {15, 15}, {-15, 15}, {-21, 0}, {-15, -15}};
        for (int[] pillar : pillars) {
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 6; y++) {
                put(plan, pillar[0], y, pillar[1], y == FLOOR_Y + 5
                        ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.POLISHED_BASALT.defaultBlockState());
            }
            put(plan, pillar[0], FLOOR_Y + 7, pillar[1], Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
        }

        // A guarded approach also gives expelled outsiders a safe platform beyond arena bounds.
        for (int z = 21; z <= 34; z++) {
            int width = z >= 27 ? 4 : 2;
            for (int x = -width; x <= width; x++) {
                put(plan, x, FLOOR_Y - 1, z, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                put(plan, x, FLOOR_Y, z, Blocks.POLISHED_BASALT.defaultBlockState());
                if (Math.abs(x) == width || z == 34) {
                    put(plan, x, FLOOR_Y + 1, z, Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultBlockState());
                }
            }
        }
        put(plan, 0, FLOOR_Y, 30, Blocks.SEA_LANTERN.defaultBlockState());
        return plan;
    }

    private static boolean essentialBlocksIntact(ServerLevel level) {
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                int distance = x * x + z * z;
                if (distance > RADIUS * RADIUS) continue;
                if (!solidFloor(level, new BlockPos(x, FLOOR_Y, z))) return false;
                if (distance >= 21 * 21 && !(Math.abs(x) <= 2 && z > 0)
                        && !barrierIntact(level, new BlockPos(x, FLOOR_Y + 1, z))) return false;
            }
        }
        for (BlockPos anchor : SEAL_ANCHORS) {
            if (!level.getBlockState(anchor).is(Blocks.LODESTONE)) return false;
        }
        for (int z = 21; z <= 34; z++) {
            int width = z >= 27 ? 4 : 2;
            for (int x = -width; x <= width; x++) {
                if (!solidFloor(level, new BlockPos(x, FLOOR_Y, z))) return false;
                if ((Math.abs(x) == width || z == 34)
                        && !barrierIntact(level, new BlockPos(x, FLOOR_Y + 1, z))) return false;
            }
        }
        // Match TrialRaidEntities' registered 2.0 x 3.4 guardian and the player's standing body.
        // Only block collisions count: an ongoing fight's own entities must not invalidate the arena.
        return standingSpace(level, ENTRY, .6, 1.8) && standingSpace(level, BOSS, 2.0, 3.4)
                && standingSpace(level, EXIT, .6, 1.8);
    }

    private static boolean solidFloor(ServerLevel level, BlockPos position) {
        return level.getBlockState(position).isCollisionShapeFullBlock(level, position)
                && level.getFluidState(position).isEmpty();
    }

    private static boolean barrierIntact(ServerLevel level, BlockPos position) {
        return !level.getBlockState(position).getCollisionShape(level, position).isEmpty()
                && level.getFluidState(position).isEmpty();
    }

    private static boolean standingSpace(ServerLevel level, Vec3 position, double width, double height) {
        BlockPos feet = BlockPos.containing(position);
        if (!solidFloor(level, feet.below())) return false;
        AABB body = new AABB(position.x - width / 2, position.y, position.z - width / 2,
                position.x + width / 2, position.y + height, position.z + width / 2);
        for (net.minecraft.world.phys.shapes.VoxelShape shape : level.getBlockCollisions(null, body)) {
            if (!shape.isEmpty()) return false;
        }
        // Fluids have no ordinary block collision; retain safe, dry entry/exit and boss space.
        for (BlockPos cell : BlockPos.betweenClosed(
                BlockPos.containing(body.minX, body.minY, body.minZ),
                BlockPos.containing(body.maxX - 1.0e-7, body.maxY - 1.0e-7, body.maxZ - 1.0e-7))) {
            if (!level.getFluidState(cell).isEmpty()) return false;
        }
        return true;
    }

    private static void put(Map<BlockPos, BlockState> plan, int x, int y, int z, BlockState state) {
        plan.put(new BlockPos(x, y, z), state);
    }

    private static boolean withinBuildRegion(BlockPos position) {
        return position.getX() >= BUILD_MINIMUM.getX() && position.getX() <= BUILD_MAXIMUM.getX()
                && position.getY() >= BUILD_MINIMUM.getY() && position.getY() <= BUILD_MAXIMUM.getY()
                && position.getZ() >= BUILD_MINIMUM.getZ() && position.getZ() <= BUILD_MAXIMUM.getZ();
    }

    private static Marker marker(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Marker.FACTORY, MARKER_NAME);
    }

    private static final class Marker extends SavedData {
        private static final Factory<Marker> FACTORY = new Factory<>(Marker::new, Marker::load);
        private boolean prepared;
        private boolean supported = true;

        private static Marker load(CompoundTag tag, HolderLookup.Provider registries) {
            Marker marker = new Marker();
            marker.supported = tag.getInt("schemaVersion") == 1;
            marker.prepared = marker.supported && tag.getBoolean("prepared");
            return marker;
        }

        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt("schemaVersion", 1);
            tag.putBoolean("prepared", prepared);
            return tag;
        }
    }
}
