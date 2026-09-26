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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

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
                .then(Commands.literal("on").then(Commands.argument("gods", StringArgumentType.greedyString())
                        .suggests(AiTestCommands::suggestGods).executes(context -> start(context, true))))
                .then(Commands.literal("off").then(Commands.argument("gods", StringArgumentType.greedyString())
                        .suggests(AiTestCommands::suggestGods).executes(context -> start(context, false))))
                .then(Commands.literal("start").then(Commands.argument("legacy", StringArgumentType.greedyString())
                        .executes(context -> failure(context, "시작 명령이 변경되었습니다: /ai_test <on|off> <신 이름 또는 ID>..."))))
                .then(Commands.literal("say").then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(AiTestCommands::say)))
                .then(Commands.literal("relationship").then(Commands.argument("tier", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(RELATIONSHIPS, builder))
                        .executes(AiTestCommands::relationship)))
                .then(Commands.literal("emotion").then(Commands.argument("emotionTag", StringArgumentType.word())
                        .executes(AiTestCommands::emotion)))
                .then(Commands.literal("debug").executes(AiTestCommands::debug))
                .then(Commands.literal("status").executes(AiTestCommands::status))
                .then(Commands.literal("stop").executes(AiTestCommands::stop)));
    }

    private static int start(CommandContext<CommandSourceStack> context, boolean recording) throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        List<ResourceLocation> gods;
        try {
            gods = DialogueTestArguments.parseGods(StringArgumentType.getString(context, "gods"), catalog())
                    .stream().map(ResourceLocation::parse).toList();
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return failure(context, invalid.getMessage());
        }
        var missing = gods.stream().filter(god -> com.sande.mythictrpg.data.god.GodDefinitionManager.INSTANCE.find(god).isEmpty()).toList();
        if (!missing.isEmpty()) return failure(context, "AI 프로필은 있지만 게임 God Definition이 없는 신입니다: " + missing
                + ". 먼저 게임 데이터팩에 같은 ID를 등록하세요.");
        if (recording && com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.mode()
                == com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings.Mode.OFF)
            return failure(context, "전역 기억 기능이 OFF입니다. on 시험은 ai-memory-foundation.json의 PERSONAL 또는 RUMOR_TEST 설정이 필요합니다.");
        return switch (AiTestDialogueAdapter.INSTANCE.start(player, gods, recording)) {
            case STARTED -> 1;
            case NORMAL_AI_SESSION_ACTIVE -> failure(context, "이미 AI 대화 세션이 활성화되어 있습니다. /ai_test stop 후 다시 시작하세요.");
            case CONTENT_FAILURE -> failure(context, "게임의 신 정의·AI 프로필·기억 저장소 준비 상태를 확인하고, 기존 게임 대화가 있다면 먼저 종료하세요.");
        };
    }

    private static int say(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        if (!AiTestDialogueAdapter.INSTANCE.isActive(player)) {
            return failure(context, "먼저 /ai_test <on|off> <신 이름 또는 ID>...를 실행하세요.");
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

    /** Read the already-loaded registry; no file-system scan or LLM call during command parsing. */
    private static List<DialogueTestArguments.God> catalog() {
        try {
            Class<?> registryType = Class.forName("com.sande.mythaiaicontent.content.AiContentRegistry");
            Object snapshot = registryType.getMethod("snapshot").invoke(registryType.getField("INSTANCE").get(null));
            Map<?, ?> profiles = (Map<?, ?>) snapshot.getClass().getMethod("godsByGodId").invoke(snapshot);
            var result = new ArrayList<DialogueTestArguments.God>();
            for (var entry : profiles.entrySet()) {
                Object profile = entry.getValue();
                String name = (String) profile.getClass().getMethod("displayName").invoke(profile);
                result.add(new DialogueTestArguments.God(entry.getKey().toString(), name));
            }
            return List.copyOf(result);
        } catch (ReflectiveOperationException | ClassCastException unavailable) {
            throw new IllegalStateException("AI 콘텐츠 레지스트리의 신 목록을 읽을 수 없습니다.", unavailable);
        }
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestGods(
            CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        try {
            DialogueTestArguments.suggestGods(builder.getRemaining(), catalog()).forEach(builder::suggest);
            return builder.buildFuture();
        } catch (IllegalStateException unavailable) {
            return builder.buildFuture();
        }
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
