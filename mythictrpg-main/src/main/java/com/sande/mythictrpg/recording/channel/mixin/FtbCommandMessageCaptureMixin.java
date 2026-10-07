package com.sande.mythictrpg.recording.channel.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.context.CommandContext;
import com.sande.mythictrpg.recording.channel.FtbChannelCaptureHooks;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import java.util.UUID;

/** Only /ftbteams msg's accepted send operation, additionally bound to a real inbound player command. */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbteams.data.FTBTeamsCommands", remap = false)
abstract class FtbCommandMessageCaptureMixin {
    @WrapOperation(method = "lambda$register$28(Lcom/mojang/brigadier/context/CommandContext;)I",
            at = @At(value = "INVOKE", target = "Ldev/ftb/mods/ftbteams/api/Team;sendMessage(Ljava/util/UUID;Ljava/lang/String;)V", remap = false),
            remap = false, require = 1, expect = 1)
    private static void mythictrpg$captureCommand(Team receiver, UUID senderId, String body, Operation<Void> original,
            CommandContext<CommandSourceStack> context) {
        try (var scope = FtbChannelCaptureHooks.beginCommand(context.getSource(), receiver, senderId, body)) {
            original.call(receiver, senderId, body);
        }
    }
}
