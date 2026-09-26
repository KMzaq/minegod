package com.sande.mythictrpg.mixin;
import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import com.sande.mythictrpg.gameplay.ledger.detail.DetailCapture;
import net.minecraft.core.*;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Map;
/** Final post-bus hook results; not a LOWEST listener that might still be changed by another listener. */
@Mixin(value=CommonHooks.class,remap=false)
abstract class CommonHooksDetailMixin {
    @Inject(method="onPlaceItemIntoWorld",at=@At("HEAD"),require=1)
    private static void mythictrpg$beginPlace(net.minecraft.world.item.context.UseOnContext context,CallbackInfoReturnable<net.minecraft.world.InteractionResult> ci) {
        com.sande.mythictrpg.gameplay.metric.ProvenanceTracking.beginUse(context);
    }
    @Inject(method="onPlaceItemIntoWorld",at=@At("RETURN"),require=1)
    private static void mythictrpg$endPlace(net.minecraft.world.item.context.UseOnContext context,CallbackInfoReturnable<net.minecraft.world.InteractionResult> ci) {
        com.sande.mythictrpg.gameplay.metric.ProvenanceTracking.endUse(context,ci.getReturnValue());
    }
    @Inject(method="fireBlockBreak",at=@At("RETURN"),require=1)
    private static void mythictrpg$breakGate(Level level,GameType mode,ServerPlayer p,BlockPos pos,BlockState state,CallbackInfoReturnable<BlockEvent.BreakEvent> ci) {
        DetailCapture.cancelledBlock(p,pos,ci.getReturnValue().isCanceled());
    }
    @Inject(method="onLeftClickBlock",at=@At("RETURN"),require=1)
    private static void mythictrpg$leftClick(Player player,BlockPos pos,Direction face,ServerboundPlayerActionPacket.Action action,CallbackInfoReturnable<PlayerInteractEvent.LeftClickBlock> ci) {
        if(!(player instanceof ServerPlayer p)||!DetailCapture.enabled(p))return;
        DetailCapture.record(p,pos,DetailCapture.block(p.level().getBlockState(pos)),ActionRecord.Type.BLOCK_INTERACTION,
                ci.getReturnValue().isCanceled()?"CANCELLED":action==ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK?"ABORTED":"REQUEST_ACCEPTED",
                Map.of("packet_action",action.name(),"use_block",ci.getReturnValue().getUseBlock().name(),"use_item",ci.getReturnValue().getUseItem().name(),
                        "result","not_block_removal_or_item_acquisition; REQUEST_ACCEPTED means uncancelled event only"));
    }
    @Inject(method="onPlayerAttackTarget",at=@At(value="RETURN",ordinal=0),require=1)
    private static void mythictrpg$attackCancel(Player player,Entity target,CallbackInfoReturnable<Boolean> ci) { if(player instanceof ServerPlayer p)DetailCapture.deniedAttack(p,true,ci.getReturnValue()); }
    @Inject(method="onPlayerAttackTarget",at=@At(value="RETURN",ordinal=1),require=1)
    private static void mythictrpg$attackItemGate(Player player,Entity target,CallbackInfoReturnable<Boolean> ci) { if(player instanceof ServerPlayer p)DetailCapture.deniedAttack(p,false,ci.getReturnValue()); }
}
