package com.sande.mythai.response;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Migration hint only: the legacy HUD-only test must not create new sessions. */
final class AiTestCommands {
    private AiTestCommands() {}

    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ai_test").requires(source -> source.hasPermission(2))
                .executes(AiTestCommands::moved)
                .then(Commands.argument("legacy", StringArgumentType.greedyString()).executes(AiTestCommands::moved)));
    }

    private static int moved(CommandContext<CommandSourceStack> context) {
        context.getSource().sendFailure(Component.literal("[AI Call] 이전 시험 명령은 사용하지 않습니다. "
                + AiCallCommands.USAGE + " — 신은 전체 ID로 지정하세요. 공개방은 일반 채팅, 비밀방은 /s <할말>입니다."));
        return 0;
    }
}
