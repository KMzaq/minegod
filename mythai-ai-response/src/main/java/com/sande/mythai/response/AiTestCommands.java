package com.sande.mythai.response;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sande.mythictrpg.ai.AiTestDialogueAdapter;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/** OP-only command surface for the removable registry-profile dialogue test. */
final class AiTestCommands {
    private static final int OP_LEVEL = 2;
    private static final List<String> RELATIONSHIPS = List.of("R_EXTREME_HOSTILE", "R_HOSTILE", "R_DISLIKE",
            "R_WARY", "R_NEUTRAL", "R_FAVORABLE", "R_FRIENDLY", "R_TRUSTED", "R_DEEP_BOND");

    private AiTestCommands() {
    }

    static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("ai_test").requires(source -> source.hasPermission(OP_LEVEL))
                .then(Commands.literal("start").then(Commands.argument("godId", ResourceLocationArgument.id())
                        .executes(AiTestCommands::start)))
                .then(Commands.literal("say").then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(AiTestCommands::say)))
                .then(Commands.literal("relationship").then(Commands.argument("tier", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(RELATIONSHIPS, builder))
                        .executes(AiTestCommands::relationship)))
                .then(Commands.literal("emotion").then(Commands.argument("emotionTag", StringArgumentType.word())
                        .executes(AiTestCommands::emotion)))
                .then(Commands.literal("participants")
                        .then(Commands.argument("godId1", ResourceLocationArgument.id())
                                .then(Commands.argument("godId2", ResourceLocationArgument.id())
                                        .executes(AiTestCommands::participants))))
                .then(Commands.literal("debug").executes(AiTestCommands::debug))
                .then(Commands.literal("status").executes(AiTestCommands::status))
                .then(Commands.literal("stop").executes(AiTestCommands::stop)));
    }

    private static int start(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation godId = ResourceLocationArgument.getId(context, "godId");
        return switch (AiTestDialogueAdapter.INSTANCE.start(context.getSource().getPlayerOrException(), godId)) {
            case STARTED -> 1;
            case NORMAL_AI_SESSION_ACTIVE -> failure(context, "이미 AI 대화 세션이 활성화되어 있습니다. /ai_test stop 후 다시 시작하세요.");
            case CONTENT_FAILURE -> failure(context, "AI 콘텐츠 레지스트리 또는 해당 프로필을 확인하세요.");
        };
    }

    private static int say(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        if (!AiTestDialogueAdapter.INSTANCE.isActive(player)) {
            return failure(context, "먼저 /ai_test start <godId>를 실행하세요.");
        }
        AiTestDialogueAdapter.INSTANCE.handlePlayerText(player, StringArgumentType.getString(context, "message"));
        return 1;
    }

    private static int relationship(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return AiTestDialogueAdapter.INSTANCE.setRelationship(context.getSource().getPlayerOrException(),
                StringArgumentType.getString(context, "tier")) ? 1 : failure(context, "활성 세션 또는 관계 태그가 없습니다.");
    }

    private static int emotion(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return AiTestDialogueAdapter.INSTANCE.setEmotion(context.getSource().getPlayerOrException(),
                StringArgumentType.getString(context, "emotionTag")) ? 1 : failure(context, "E_* 형식의 감정 태그가 필요합니다.");
    }

    private static int participants(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return AiTestDialogueAdapter.INSTANCE.setParticipants(context.getSource().getPlayerOrException(),
                ResourceLocationArgument.getId(context, "godId1"), ResourceLocationArgument.getId(context, "godId2"))
                ? 1 : failure(context, "참가자 설정에 실패했습니다. 두 프로필을 확인하세요.");
    }

    private static int debug(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return AiTestDialogueAdapter.INSTANCE.toggleDebug(context.getSource().getPlayerOrException()) ? 1
                : failure(context, "활성 AI 테스트 세션이 없습니다.");
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        context.getSource().sendSuccess(() -> AiTestDialogueAdapter.INSTANCE.status(player), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return AiTestDialogueAdapter.INSTANCE.stop(context.getSource().getPlayerOrException()) ? 1
                : failure(context, "활성 AI 테스트 세션이 없습니다.");
    }

    private static int failure(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal("[AI Test] " + message).withStyle(ChatFormatting.RED));
        return 0;
    }
}
