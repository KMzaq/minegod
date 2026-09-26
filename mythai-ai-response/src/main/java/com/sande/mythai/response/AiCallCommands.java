package com.sande.mythai.response;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** One OP-only test entry point into the authoritative public/private room pipeline. */
final class AiCallCommands {
    static final String USAGE = "/ai_call <public|mobile|private> <on|off> <신 ID> [신 ID ...]";

    private AiCallCommands() {}

    static void onRegisterCommands(RegisterCommandsEvent event) {
        var root = Commands.literal("ai_call").requires(source -> source.hasPermission(2))
                .executes(AiCallCommands::usage);
        root.then(mode("public", RoomType.PUBLIC_FIXED));
        root.then(mode("mobile", RoomType.PUBLIC_MOBILE));
        root.then(mode("private", RoomType.PRIVATE));
        event.getDispatcher().register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mode(String name, RoomType type) {
        var mode = Commands.literal(name).executes(AiCallCommands::usage);
        for (boolean recording : new boolean[]{true, false}) {
            mode.then(Commands.literal(recording ? "on" : "off").executes(AiCallCommands::usage)
                    .then(Commands.argument("gods", StringArgumentType.greedyString())
                            .suggests(AiCallCommands::suggestGods)
                            .executes(context -> start(context, type, recording))));
        }
        return mode;
    }

    private static int start(CommandContext<CommandSourceStack> context, RoomType type, boolean recording)
            throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        try {
            // Validate the complete selection before mutating any room/participant state.
            var gods = AiCallArguments.parseGodIds(StringArgumentType.getString(context, "gods"), catalog())
                    .stream().map(ResourceLocation::parse).toList();
            var missing = gods.stream().filter(god -> GodDefinitionManager.INSTANCE.find(god).isEmpty()).toList();
            if (!missing.isEmpty()) return failure(context, "게임 God Definition이 없는 신 ID입니다: " + missing);
            // The game owns permission/recording checks, spatial membership and session isolation.
            var room = ConversationRooms.INSTANCE.create(player, type, gods,
                    recording ? RecordingScope.TEST_RECORDING : RecordingScope.TEST_EPHEMERAL);
            context.getSource().sendSuccess(() -> Component.literal("[AI Call] 방 " + room.code()
                    + " / 기록=" + (recording ? "on" : "off") + ". "
                    + (type == RoomType.PRIVATE ? "/s <할말>로 입력하세요." : "일반 채팅으로 말하세요.")
                    + " 종료: /mythroom leave " + room.code()), false);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return failure(context, invalid.getMessage());
        }
    }

    /** Read loaded profiles, not files; ID selection never depends on translated display names. */
    private static List<DialogueTestArguments.God> catalog() {
        try {
            Class<?> registryType = Class.forName("com.sande.mythaiaicontent.content.AiContentRegistry");
            Object snapshot = registryType.getMethod("snapshot").invoke(registryType.getField("INSTANCE").get(null));
            Map<?, ?> profiles = (Map<?, ?>) snapshot.getClass().getMethod("godsByGodId").invoke(snapshot);
            var result = new ArrayList<DialogueTestArguments.God>();
            for (var id : profiles.keySet()) result.add(new DialogueTestArguments.God(id.toString(), ""));
            return List.copyOf(result);
        } catch (ReflectiveOperationException | ClassCastException unavailable) {
            throw new IllegalStateException("AI 콘텐츠 레지스트리의 신 목록을 읽을 수 없습니다.", unavailable);
        }
    }

    private static CompletableFuture<Suggestions> suggestGods(CommandContext<CommandSourceStack> context,
                                                              SuggestionsBuilder builder) {
        try {
            var available = catalog().stream()
                    .filter(god -> GodDefinitionManager.INSTANCE.find(ResourceLocation.parse(god.id())).isPresent()).toList();
            AiCallArguments.suggestGodIds(builder.getRemaining(), available).forEach(builder::suggest);
        } catch (IllegalStateException unavailable) {
            // Execution reports registry errors; a missing registry is not a command-tab crash.
        }
        return builder.buildFuture();
    }

    private static int usage(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(USAGE
                + " — public: 공개 고정 / mobile: 공개 이동 / private: 비밀. on/off는 영속 기록 여부입니다."), false);
        return 1;
    }

    private static int failure(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal("[AI Call] " + message));
        return 0;
    }
}
