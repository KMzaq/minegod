package com.sande.mythictrpg.recording.channel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.sande.mythictrpg.recording.channel.ChannelCaptureHooks;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerCommonPacketListenerImpl.class)
abstract class PacketReceiptCaptureMixin {
    @WrapOperation(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V"))
    private void mythictrpg$receipt(Connection connection, Packet<?> packet, PacketSendListener listener, boolean flush, Operation<Void> original) {
        var receipt = connection.isConnected() && (Object) this instanceof ServerGamePacketListenerImpl game
                ? ChannelCaptureHooks.prepare(game.player, packet) : null;
        PacketSendListener confirming = receipt == null ? listener : new PacketSendListener() {
            @Override public void onSuccess() {
                try { if (listener != null) listener.onSuccess(); } finally { receipt.success(); }
            }
            @Override public Packet<?> onFailure() {
                receipt.failure(); return listener == null ? null : listener.onFailure();
            }
        };
        try { original.call(connection, packet, confirming, flush); }
        catch (RuntimeException | Error failure) { if (receipt != null) receipt.failure(); throw failure; }
    }
}
