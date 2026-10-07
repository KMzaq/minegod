package com.sande.mythictrpg.recording.channel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.sande.mythictrpg.recording.channel.*;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.commands.TeamMsgCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TeamMsgCommand.class)
abstract class TeamMessageCaptureMixin {
    @WrapMethod(method = "sendMessage")
    private static void mythictrpg$capture(CommandSourceStack source, Entity sender, PlayerTeam team, List<ServerPlayer> targets, PlayerChatMessage message, Operation<Void> original) {
        try (var scope = ChannelCaptureHooks.begin(ChannelCaptureHooks.commandPlayer(source, message), message, ChannelRecordingCapture.Channel.SCOREBOARD_TEAM)) { original.call(source, sender, team, targets, message); }
    }
}
