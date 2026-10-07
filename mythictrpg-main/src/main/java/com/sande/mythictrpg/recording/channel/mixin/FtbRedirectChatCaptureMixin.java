package com.sande.mythictrpg.recording.channel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sande.mythictrpg.recording.channel.FtbChannelCaptureHooks;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import java.util.UUID;

/** Only the successful chat-redirect branch; unrelated system/team-management announcements remain unqualified. */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbteams.FTBTeams", remap = false)
abstract class FtbRedirectChatCaptureMixin {
    @WrapOperation(method = "lambda$chatReceived$6(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/Component;Ldev/ftb/mods/ftbteams/api/Team;)Ldev/architectury/event/EventResult;",
            at = @At(value = "INVOKE", target = "Ldev/ftb/mods/ftbteams/api/Team;sendMessage(Ljava/util/UUID;Lnet/minecraft/network/chat/Component;)V", remap = false),
            remap = false, require = 1, expect = 1)
    private static void mythictrpg$captureRedirect(Team receiver, UUID senderId, Component body, Operation<Void> original,
            ServerPlayer author, Component acceptedMessage, Team selectedTeam) {
        ServerPlayer qualified = author != null && receiver == selectedTeam && author.getUUID().equals(senderId) ? author : null;
        try (var scope = FtbChannelCaptureHooks.begin(qualified, receiver, body)) {
            original.call(receiver, senderId, body);
        }
    }
}
