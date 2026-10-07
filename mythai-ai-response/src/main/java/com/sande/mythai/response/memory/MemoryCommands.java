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
        root.then(Commands.literal("archive_status").executes(context -> {
            var store = com.sande.mythictrpg.recording.server.RecordingRuntime.current(context.getSource().getServer());
            String health = store.map(s -> {
                var h = s.health();
                return h.state() + "/" + h.reasonCode() + ", committed=" + h.highWatermark()
                        + ", queued=" + h.queuedEntries() + ", captureGaps=" + h.gapCount()
                        + ", quota=" + s.quotaSnapshot().map(q -> q.state() + " " + q.usedPhysicalBytes() + "/" + q.limitBytes()).orElse("UNAVAILABLE")
                        + ", lexical=" + s.lexicalIndexStatus();
            }).orElse("OFF_OR_STARTING");
            context.getSource().sendSuccess(() -> Component.literal("[기록 v2] " + health
                    + "; retrieval=" + com.sande.mythictrpg.recording.server.RecordingRuntime.retrievalState(context.getSource().getServer())
                    + "; bundleSHADOW=" + RecordedRetrievalShadow.diagnostics()
                    + "; projection=" + RecordedProjectionService.diagnostic(context.getSource().getServer())
                    + "; semanticSHADOW=" + RecordedEmbeddingService.diagnostic(context.getSource().getServer())
                    + "; 새 검색은 대사에 미반영, 전체 수집·검색 완료를 뜻하지 않습니다."), false);
            return 1;
        }));
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
