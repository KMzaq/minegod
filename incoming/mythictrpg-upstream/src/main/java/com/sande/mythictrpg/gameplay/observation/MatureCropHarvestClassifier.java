package com.sande.mythictrpg.gameplay.observation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.Set;

final class MatureCropHarvestClassifier {
    private static final Set<Block> SUPPORTED_CROP_BLOCKS = Set.of(
            Blocks.WHEAT,
            Blocks.CARROTS,
            Blocks.POTATOES,
            Blocks.BEETROOTS
    );

    private MatureCropHarvestClassifier() {
    }

    static Optional<ResourceLocation> classify(BlockState state) {
        Block block = state.getBlock();
        boolean mature = SUPPORTED_CROP_BLOCKS.contains(block)
                && block instanceof CropBlock crop && crop.isMaxAge(state);
        if (block == Blocks.NETHER_WART) {
            mature = state.getValue(NetherWartBlock.AGE) == NetherWartBlock.MAX_AGE;
        } else if (block == Blocks.COCOA) {
            mature = state.getValue(CocoaBlock.AGE) == CocoaBlock.MAX_AGE;
        }
        return mature ? Optional.of(BuiltInRegistries.BLOCK.getKey(block)) : Optional.empty();
    }
}
