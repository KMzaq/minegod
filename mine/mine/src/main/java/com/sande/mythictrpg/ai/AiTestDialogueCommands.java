package com.sande.mythictrpg.ai;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/** OP-only commands for the removable AI content-registry dry run. */
public final class AiTestDialogueCommands {
    private static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final List<String> RELATIONSHIP_TIERS = List.of("R_EXTREME_HOSTILE", "R_HOSTILE", "R_DISLIKE",
            "R_WARY", "R_NEUTRAL", "R_FAVORABLE", "R_FRIENDLY", "R_TRUSTED", "R_DEEP_BOND");

    private AiTestDialogueCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("ai_test").requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
                .then(Commands.literal("start").then(Commands.argument("godId", ResourceLocationArgument.id())
                        .executes(AiTestDialogueCommands::start)))
                .then(Commands.literal("say").then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(AiTestDialogueCommands::say)))
                .then(Commands.literal("relationship").then(Commands.argument("tier", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(RELATIONSHIP_TIERS, builder))
                        .executes(AiTestDialogueCommands::relationship)))
                .then(Commands.literal("emotion").then(Commands.argument("emotionTag", StringArgumentType.word())
                        .executes(AiTestDialogueCommands::emotion)))
                .then(Commands.literal("participants")
                        .then(Commands.argument("godId1", ResourceLocationArgument.id())
                                .then(Commands.argument("godId2", ResourceLocationArgument.id())
                                        .executes(AiTestDialogueCommands::participants))))
                .then(Commands.literal("debug").executes(AiTestDialogueCommands::debug))
                .then(Commands.literal("status").executes(AiTestDialogueCommands::status))
                .then(Commands.literal("stop").executes(AiTestDialogueCommands::stop)));
    }

    private static int start(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ResourceLocation godId = ResourceLocationArgument.getId(context, "godId");
        var player = context.getSource().getPlayerOrException();
        return switch (AiTestDialogueAdapter.INSTANCE.start(player, godId)) {
            case STARTED -> 1;
            case NORMAL_AI_SESSION_ACTIVE -> failure(context, "기존 /mythai AI 대화가 활성화되어 있습니다. 먼저 종료하세요.");
            case CONTENT_FAILURE -> failure(context, "AI 콘텐츠 레지스트리 또는 해당 신 프로필을 확인하세요.");
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
        ResourceLocation first = ResourceLocationArgument.getId(context, "godId1");
        ResourceLocation second = ResourceLocationArgument.getId(context, "godId2");
        return AiTestDialogueAdapter.INSTANCE.setParticipants(context.getSource().getPlayerOrException(), first, second)
                ? 1 : failure(context, "참가자 설정에 실패했습니다. 두 신의 콘텐츠 프로필을 확인하세요.");
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
