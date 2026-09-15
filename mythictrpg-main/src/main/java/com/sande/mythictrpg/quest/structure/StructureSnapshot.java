package com.sande.mythictrpg.quest.structure;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

/** Immutable copied features. No live Level, Chunk, BlockState, or Entity reference is retained. */
public record StructureSnapshot(int playerPlacedBlocks, int derivedBlocks, int decorationEntities,
        Bounds bounds, Map<ResourceLocation, Integer> blockCounts,
        Map<ResourceLocation, Double> tagRatios, Map<ResourceLocation, Integer> tagCounts,
        Map<ResourceLocation, Double> biomeRatios, Map<ResourceLocation, Double> biomeTagRatios,
        Map<String, Double> features, List<String> notes, String fingerprint) {
    public StructureSnapshot {
        blockCounts = Map.copyOf(blockCounts); tagRatios = Map.copyOf(tagRatios); tagCounts = Map.copyOf(tagCounts);
        biomeRatios = Map.copyOf(biomeRatios); biomeTagRatios = Map.copyOf(biomeTagRatios);
        features = Map.copyOf(features); notes = List.copyOf(notes);
    }
    public int totalBlocks() { return playerPlacedBlocks + derivedBlocks; }

    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public int width() { return maxX - minX + 1; }
        public int height() { return maxY - minY + 1; }
        public int depth() { return maxZ - minZ + 1; }
        public long volume() { return (long) width() * height() * depth(); }
    }
}
