package com.sande.mythictrpg.recording.channel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sande.mythictrpg.recording.channel.*;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.commands.MsgCommand;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MsgCommand.class)
abstract class PrivateMessageCaptureMixin {
    @WrapMethod(method = "sendMessage")
    private static void mythictrpg$capture(CommandSourceStack source, Collection<ServerPlayer> targets, PlayerChatMessage message, Operation<Void> original) {
        try (var scope = ChannelCaptureHooks.begin(ChannelCaptureHooks.commandPlayer(source, message), message, ChannelRecordingCapture.Channel.PRIVATE_MSG)) { original.call(source, targets, message); }
    }
}
