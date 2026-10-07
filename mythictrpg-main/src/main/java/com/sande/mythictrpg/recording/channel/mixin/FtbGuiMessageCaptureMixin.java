package com.sande.mythictrpg.recording.channel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sande.mythictrpg.recording.channel.FtbChannelCaptureHooks;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.net.SendMessageMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import java.util.UUID;

/** Queued server handler after the authenticated packet player has resolved its actual team. */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbteams.net.SendMessageMessage", remap = false)
abstract class FtbGuiMessageCaptureMixin {
    @WrapOperation(method = "lambda$handle$0(Lnet/minecraft/server/level/ServerPlayer;Ldev/ftb/mods/ftbteams/net/SendMessageMessage;Ldev/ftb/mods/ftbteams/api/Team;)V",
            at = @At(value = "INVOKE", target = "Ldev/ftb/mods/ftbteams/api/Team;sendMessage(Ljava/util/UUID;Ljava/lang/String;)V", remap = false),
            remap = false, require = 1, expect = 1)
    private static void mythictrpg$captureGui(Team receiver, UUID senderId, String body, Operation<Void> original,
            ServerPlayer author, SendMessageMessage request, Team selectedTeam) {
        ServerPlayer qualified = author != null && receiver == selectedTeam && author.getUUID().equals(senderId) ? author : null;
        try (var scope = FtbChannelCaptureHooks.beginString(qualified, receiver, body)) {
            original.call(receiver, senderId, body);
        }
    }
}
