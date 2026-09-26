package com.sande.mythictrpg.quest.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;

/** Horizontal build region. Y deliberately follows the dimension build limits. */
public record StructureRegion(ResourceKey<Level> dimension, int minX, int maxX, int minZ, int maxZ) {
    public StructureRegion {
        Objects.requireNonNull(dimension, "dimension");
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("Structure region bounds are inverted");
        }
    }

    public static StructureRegion between(ResourceKey<Level> dimension, BlockPos first, BlockPos second) {
        return new StructureRegion(dimension, Math.min(first.getX(), second.getX()),
                Math.max(first.getX(), second.getX()), Math.min(first.getZ(), second.getZ()),
                Math.max(first.getZ(), second.getZ()));
    }

    public int width() { return Math.addExact(Math.subtractExact(maxX, minX), 1); }
    public int depth() { return Math.addExact(Math.subtractExact(maxZ, minZ), 1); }
    public boolean contains(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }
}
