package com.sande.mythictrpg.ai.region;

import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the exact vanilla block biome (including zoom seed and Y), exclusively from loaded
 * full chunks. Using level.getBiome/getNoiseBiome directly could fall back to generation.
 * Domain is each dimension's build-height volume inside Minecraft's absolute world bounds;
 * this is a three-dimensional component, not a surface projection or fixed-height slice.
 */
public final class MinecraftBiomeRegionSource implements ConnectedBiomeRegions.Source {
    private final MinecraftServer server;
    private final Map<ServerLevel, BiomeManager> managers = new IdentityHashMap<>();

    public MinecraftBiomeRegionSource(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    public static ConnectedBiomeRegions.Cell cell(ServerLevel level, BlockPos pos) {
        return new ConnectedBiomeRegions.Cell(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public boolean contains(ConnectedBiomeRegions.Cell cell) {
        ServerLevel level = level(cell.dimension());
        return level != null && cell.y() >= level.getMinBuildHeight() && cell.y() < level.getMaxBuildHeight()
                && cell.x() >= -Level.MAX_LEVEL_SIZE && cell.x() < Level.MAX_LEVEL_SIZE
                && cell.z() >= -Level.MAX_LEVEL_SIZE && cell.z() < Level.MAX_LEVEL_SIZE;
    }

    @Override
    public String biomeAt(ConnectedBiomeRegions.Cell cell) {
        if (!server.isSameThread() || !contains(cell)) return null;
        ServerLevel level = level(cell.dimension());
        BiomeManager manager = managers.computeIfAbsent(level, current ->
                current.getBiomeManager().withDifferentSource((quartX, quartY, quartZ) -> {
                    var chunk = current.getChunkSource().getChunkNow(QuartPos.toSection(quartX), QuartPos.toSection(quartZ));
                    return chunk == null ? null : chunk.getNoiseBiome(quartX, quartY, quartZ);
                }));
        var biome = manager.getBiome(new BlockPos(cell.x(), cell.y(), cell.z()));
        return biome == null ? null : biome.unwrapKey().map(key -> key.location().toString()).orElse(null);
    }

    private ServerLevel level(String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
