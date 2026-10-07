package com.sande.mythictrpg.recording.channel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sande.mythictrpg.recording.channel.ChannelCaptureHooks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PlayerList.class)
abstract class PublicChatCaptureMixin {
    @WrapMethod(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V")
    private void mythictrpg$capture(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound type, Operation<Void> original) {
        try (var scope = ChannelCaptureHooks.broadcast(sender, message, type)) { original.call(message, sender, type); }
    }
    @WrapMethod(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/network/chat/ChatType$Bound;)V")
    private void mythictrpg$command(PlayerChatMessage message, CommandSourceStack source, ChatType.Bound type, Operation<Void> original) {
        try (var scope = ChannelCaptureHooks.broadcast(ChannelCaptureHooks.commandPlayer(source, message), message, type)) { original.call(message, source, type); }
    }
}
