package com.sande.mythictrpg.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.sande.mythictrpg.gameplay.ledger.ActionLedgerStore;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.Locale;
import java.util.UUID;

/** Administrative raw-event inspection only. No player/NPC search or write/repair/enable command. */
final class ActionLedgerCommands {
    private ActionLedgerCommands() {}
    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("ledger").requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(ActionLedgerCommands::status))
                .then(Commands.literal("recent")
                        .then(Commands.argument("player_uuid", UuidArgument.uuid())
                                .executes(c -> recent(c, 0))
                                .then(Commands.argument("after_sequence", LongArgumentType.longArg(0))
                                        .executes(c -> recent(c, LongArgumentType.getLong(c, "after_sequence"))))));
    }
    private static int status(CommandContext<CommandSourceStack> context) {
        var source = context.getSource(); var runtime = ActionLedgerService.current(source.getServer());
        if (runtime == null || runtime.ledger() == null) {
            source.sendSuccess(() -> Component.literal("행동 원장: " + (runtime == null ? "NOT_STARTED" : runtime.reason())), false); return 0;
        }
        var s = runtime.ledger().status();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "행동 원장 %s / %s, 저장 %d/%d bytes (%.2f%%), durable cursor=%d, 원본=%d, pending=%d, 누락진단=%d [%d..%d UTC ms], 비정상복구=%s. 별도 주시 상태는 /mythadmin watch status.",
                s.state(), s.reason(), s.usedBytes(), s.maxBytes(), s.usedRatio() * 100,
                s.committedSequence(), s.indexedEvents(), s.pending(), s.rejected(), s.firstGapUtc(), s.lastGapUtc(), s.recoveredUnclean())), false);
        source.sendSuccess(() -> Component.literal("상세 수집="+runtime.detailSettings().enabled()+", 위치 간격="+runtime.detailSettings().movementIntervalTicks()
                +" ticks (0=미정/OFF). 새 상세 타입은 관리자 원장 전용이며 자동 주시/AI 공개하지 않습니다."), false);
        return 1;
    }
    private static int recent(CommandContext<CommandSourceStack> context, long after) {
        var source = context.getSource(); var server = source.getServer();
        var runtime = ActionLedgerService.current(server);
        if (runtime == null || runtime.ledger() == null || runtime.worldId() == null) {
            source.sendFailure(Component.literal("행동 원장을 조회할 수 없습니다. status로 OFF/오류 상태를 확인하세요.")); return 0;
        }
        UUID actor = UuidArgument.getUuid(context, "player_uuid");
        var request = runtime.ledger().after(new ActionLedgerStore.Cursor(runtime.worldId(), after), actor, 10);
        request.whenComplete((page, failure) -> server.execute(() -> {
            // Permission may have been revoked or player/session/server replaced during asynchronous IO.
            if (ActionLedgerService.current(server) != runtime || !source.hasPermission(2)
                    || source.getEntity() instanceof ServerPlayer player
                    && (server.getPlayerList().getPlayer(player.getUUID()) != player || !player.hasPermissions(2))) return;
            if (failure != null) {
                source.sendFailure(Component.literal("행동 원장 조회 실패. 저장소 상태/커서를 확인하세요. '기록 없음'과 다릅니다.")); return;
            }
            for (var record : page.records()) {
                var event = record.event();
                source.sendSuccess(() -> Component.literal("#" + record.sequence() + " event=" + event.occurrenceId()
                        + " " + event.type() + "/" + event.outcome() + " actor=" + event.actorId()
                        + " subject=" + event.subject() + " " + event.dimensionId() + " " + event.position()
                        + " utc=" + event.occurredAtUtc() + " tick=" + event.gameTick()), false);
            }
            source.sendSuccess(() -> Component.literal("조회 " + page.records().size() + "건. next=" + page.next().sequence()
                    + " / durable head=" + page.durableHead().sequence() + ". 완전한 행동/목격 이력임을 보장하지 않습니다."), false);
        }));
        return 1;
    }
}
