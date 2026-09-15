package com.sande.mythai.response.memory;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.UUID;

/** OP-only, current player/current God diagnostics. Does not load other players' private records. */
public final class MemoryCommands {
    private MemoryCommands() {}
    public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("ai_memory").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("status").executes(context -> {
            var view = DialogueMemoryBridge.inspect(context.getSource().getPlayerOrException());
            context.getSource().sendSuccess(() -> Component.literal("[기억] " + view.status() + " / 기록=" + view.entries().size() + " / revision=" + view.revision()), false);
            return 1;
        }));
        root.then(Commands.literal("list").executes(context -> {
            var view = DialogueMemoryBridge.inspect(context.getSource().getPlayerOrException());
            view.entries().stream().skip(Math.max(0, view.entries().size()-10)).forEach(entry ->
                    context.getSource().sendSuccess(() -> Component.literal(entry.id() + " [" + entry.source() + (entry.important() ? "/중요" : "") + "] "
                            + entry.text().substring(0, Math.min(100, entry.text().length()))), false));
            return view.entries().size();
        }));
        for (String operation : new String[]{"pin","unpin","forget"}) {
            root.then(Commands.literal(operation).then(Commands.argument("id", StringArgumentType.word()).executes(context -> {
                var player = context.getSource().getPlayerOrException();
                UUID id;
                try { id = UUID.fromString(StringArgumentType.getString(context,"id")); }
                catch (IllegalArgumentException invalid) { context.getSource().sendFailure(Component.literal("기억 UUID가 올바르지 않습니다.")); return 0; }
                DialogueMemoryBridge.manage(player,id,operation).thenAccept(result -> player.server.execute(() ->
                        context.getSource().sendSuccess(() -> Component.literal("[기억] " + operation + ": " + result + " (STALE이면 목록을 새로 확인하세요)"), false)));
                return 1;
            })));
        }
        event.getDispatcher().register(root);
    }
}
