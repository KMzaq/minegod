package com.sande.mythictrpg.mixin;

import com.sande.mythictrpg.skills.CombatCastRuntime;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe every committed inventory change, including a swap and restore within one server tick. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class PrototypeCastInventoryMixin {
    @Shadow public ServerPlayer player;

    @Inject(method="handleSetCarriedItem",at=@At("RETURN"),require=1)
    private void mythictrpg$castCarriedChanged(ServerboundSetCarriedItemPacket packet,CallbackInfo ci) {
        CombatCastRuntime.heldChanged(player);
    }
    @Inject(method="handleContainerClick",at=@At("RETURN"),require=1)
    private void mythictrpg$castContainerChanged(ServerboundContainerClickPacket packet,CallbackInfo ci) {
        CombatCastRuntime.heldChanged(player);
    }
    @Inject(method="handlePlayerAction",at=@At("RETURN"),require=1)
    private void mythictrpg$castHandsChanged(ServerboundPlayerActionPacket packet,CallbackInfo ci) {
        CombatCastRuntime.heldChanged(player);
    }
    @Inject(method="handleSetCreativeModeSlot",at=@At("RETURN"),require=1)
    private void mythictrpg$castCreativeChanged(ServerboundSetCreativeModeSlotPacket packet,CallbackInfo ci) {
        CombatCastRuntime.heldChanged(player);
    }
}
