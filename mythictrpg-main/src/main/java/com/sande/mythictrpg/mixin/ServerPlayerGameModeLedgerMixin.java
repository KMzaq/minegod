package com.sande.mythictrpg.mixin;

import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeLedgerMixin {
    @Shadow protected ServerLevel level;
    @Shadow protected ServerPlayer player;
    // destroyBlock may return true even when removeBlock returned false. Hook the actual removal result.
    @Inject(method = "removeBlock", at = @At("RETURN"), require = 1)
    private void mythictrpg$recordRemoval(BlockPos pos, BlockState state, boolean canHarvest,
                                         CallbackInfoReturnable<Boolean> result) {
        com.sande.mythictrpg.gameplay.metric.MiningCredit.removed(player,pos,state,Boolean.TRUE.equals(result.getReturnValue()));
        ActionLedgerCapture.cropRemoved(level, player, pos, state, Boolean.TRUE.equals(result.getReturnValue()));
        ActionLedgerCapture.blockRemoved(player,pos,state,Boolean.TRUE.equals(result.getReturnValue()));
        com.sande.mythictrpg.gameplay.ledger.detail.DetailCapture.removedBlock(player,pos,state,Boolean.TRUE.equals(result.getReturnValue()));
    }
    @Inject(method="destroyBlock",at=@At("HEAD"),require=1)
    private void mythictrpg$beginBreak(BlockPos pos,CallbackInfoReturnable<Boolean> ci) {
        com.sande.mythictrpg.gameplay.metric.MiningCredit.begin(player,pos,level.getBlockState(pos));
        com.sande.mythictrpg.gameplay.ledger.detail.DetailCapture.beginBlock(player,pos,level.getBlockState(pos));
    }
    @Inject(method="destroyBlock",at=@At("RETURN"),require=1)
    private void mythictrpg$endBreak(BlockPos pos,CallbackInfoReturnable<Boolean> ci) {
        com.sande.mythictrpg.gameplay.metric.MiningCredit.end(player,pos);
        com.sande.mythictrpg.gameplay.ledger.detail.DetailCapture.endBlock(player,pos,Boolean.TRUE.equals(ci.getReturnValue()));
    }
}
