package com.sande.mythictrpg.recording.channel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sande.mythictrpg.recording.channel.ChannelCaptureHooks;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class CommandOriginCaptureMixin {
    @WrapMethod(method = "performUnsignedChatCommand")
    private void mythictrpg$unsigned(String command, Operation<Void> original) {
        try (var scope = ChannelCaptureHooks.command(((ServerGamePacketListenerImpl) (Object) this).player)) { original.call(command); }
    }
    @WrapMethod(method = "performSignedChatCommand")
    private void mythictrpg$signed(ServerboundChatCommandSignedPacket packet, LastSeenMessages seen, Operation<Void> original) {
        try (var scope = ChannelCaptureHooks.command(((ServerGamePacketListenerImpl) (Object) this).player)) { original.call(packet, seen); }
    }
}
