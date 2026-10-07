package com.sande.mythictrpg.raid;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.UUID;

/** Explicit invitations and queue control. No raid starts merely by entering an area. */
@EventBusSubscriber(modid = MythicTrpg.MOD_ID)
public final class RaidCommands {
    private RaidCommands() { }

    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mythraid")
                .executes(context -> open(context.getSource()))
                .then(Commands.literal("ui").executes(context -> open(context.getSource())))
                .then(Commands.literal("create").then(Commands.argument("raid", StringArgumentType.word())
                        .executes(context -> create(context.getSource(), StringArgumentType.getString(context, "raid")))))
                .then(Commands.literal("join").then(Commands.argument("attempt", StringArgumentType.word())
                        .executes(context -> run(context.getSource(), StringArgumentType.getString(context, "attempt"), "join"))))
                .then(Commands.literal("start").then(Commands.argument("attempt", StringArgumentType.word())
                        .executes(context -> run(context.getSource(), StringArgumentType.getString(context, "attempt"), "start"))))
                .then(Commands.literal("cancel").then(Commands.argument("attempt", StringArgumentType.word())
                        .executes(context -> run(context.getSource(), StringArgumentType.getString(context, "attempt"), "cancel"))))
                .then(Commands.literal("leave").then(Commands.argument("attempt", StringArgumentType.word())
                        .executes(context -> run(context.getSource(), StringArgumentType.getString(context, "attempt"), "leave"))))
                .then(Commands.literal("status").then(Commands.argument("attempt", StringArgumentType.word())
                        .executes(context -> status(context.getSource(), StringArgumentType.getString(context, "attempt")))))
                .then(Commands.literal("mine").executes(context -> mine(context.getSource())))
                .then(Commands.literal("admin").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("attempt", StringArgumentType.word())
                                        .executes(context -> adminCancel(context.getSource(),
                                                StringArgumentType.getString(context, "attempt")))))));
    }

    private static int create(CommandSourceStack source, String raw) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ResourceLocation raid = ResourceLocation.tryParse(raw);
        if (raid == null || !raw.contains(":")) return fail(source, "전체 namespaced 레이드 ID가 필요합니다.");
        return report(source, RaidRuntime.INSTANCE.create(source.getPlayerOrException(), raid));
    }

    private static int run(CommandSourceStack source, String raw, String operation)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID id = parse(raw);
        if (id == null) return fail(source, "유효한 attempt UUID가 필요합니다.");
        ServerPlayer player = source.getPlayerOrException();
        return report(source, switch (operation) {
            case "join" -> RaidRuntime.INSTANCE.join(player, id);
            case "start" -> RaidRuntime.INSTANCE.start(player, id);
            case "cancel" -> RaidRuntime.INSTANCE.cancel(player, id);
            case "leave" -> RaidRuntime.INSTANCE.leave(player, id);
            default -> throw new IllegalArgumentException("Unknown raid command");
        });
    }

    private static int open(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        RaidUiService.INSTANCE.open(source.getPlayerOrException());
        return 1;
    }

    private static int status(CommandSourceStack source, String raw)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID id = parse(raw);
        if (id == null) return fail(source, "유효한 attempt UUID가 필요합니다.");
        ServerPlayer player = source.getPlayerOrException();
        RaidState.Attempt attempt = RaidRuntime.INSTANCE.status(player.server, id).orElse(null);
        if (attempt == null || !attempt.playerIds().contains(player.getUUID()) && !source.hasPermission(2))
            return fail(source, "조회할 수 없는 레이드입니다.");
        source.sendSuccess(() -> Component.literal("[레이드] " + attempt.id() + " / " + attempt.definition().id()
                + " / " + attempt.status() + " / 참가 " + attempt.playerIds().size()
                + " / 사유 " + attempt.reason()), false);
        return 1;
    }

    private static int mine(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        RaidState.get(player.server).membership(player.getUUID()).ifPresentOrElse(attempt ->
                source.sendSuccess(() -> Component.literal("[레이드] 참가 중: " + attempt.id() + " / "
                        + attempt.definition().id() + " / " + attempt.status()), false),
                () -> source.sendSuccess(() -> Component.literal("[레이드] 현재 참가 중인 레이드가 없습니다."), false));
        return 1;
    }

    private static int adminCancel(CommandSourceStack source, String raw) {
        UUID id = parse(raw);
        if (id == null) return fail(source, "유효한 attempt UUID가 필요합니다.");
        return report(source, RaidRuntime.INSTANCE.cancelAdministrative(source.getServer(), id));
    }

    private static int report(CommandSourceStack source, RaidRuntime.Result result) {
        if (!result.succeeded()) return fail(source, result.message());
        source.sendSuccess(() -> Component.literal("[레이드] " + result.message()
                + result.attemptId().map(id -> " UUID=" + id).orElse("")), false);
        return 1;
    }
    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("[레이드] " + message)); return 0;
    }
    private static UUID parse(String raw) {
        try { return UUID.fromString(raw); } catch (IllegalArgumentException invalid) { return null; }
    }
}
