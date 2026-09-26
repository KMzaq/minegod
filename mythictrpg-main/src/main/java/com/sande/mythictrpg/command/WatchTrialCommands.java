package com.sande.mythictrpg.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.sande.mythictrpg.gameplay.watch.GodWatchRuntime;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Explicit server-local developer trial; never changes shared god definitions or relationship thresholds. */
final class WatchTrialCommands {
    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("watch").requires(s -> s.hasPermission(2))
                .then(Commands.literal("status").executes(c -> {
                    var r = GodWatchRuntime.current(c.getSource().getServer());
                    c.getSource().sendSuccess(() -> Component.literal(r == null ? "주시 시험 OFF / 원장·watch-trial.json 명시 설정 필요" : r.status()), false); return 1;
                }))
                .then(Commands.literal("trial_start").then(Commands.argument("god", ResourceLocationArgument.id())
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1,128)).executes(c -> {
                                    var source = c.getSource(); var r = GodWatchRuntime.current(source.getServer());
                                    if (r == null) { source.sendFailure(Component.literal("주시 시험 OFF. 저장 용량과 시험 모드를 별도로 설정해야 합니다.")); return 0; }
                                    var player = EntityArgument.getPlayer(c, "player");
                                    r.startTrial(source.getServer(), ResourceLocationArgument.getId(c, "god"), player, IntegerArgumentType.getInteger(c, "radius"))
                                            .whenComplete((w, failure) -> source.getServer().execute(() -> {
                                                if (!authorized(source, r)) return;
                                                if (failure != null) source.sendFailure(Component.literal("주시 시작 실패. 중복/저장소/권한 상태를 확인하세요."));
                                                else source.sendSuccess(() -> Component.literal("주시 시험 저장됨: " + w.approval().key() + ", 시작 순서=" + w.from()
                                                        + ". 지정 상자 영역의 하늘이 보이는 성숙 작물 제거만 관찰, 해당 플레이어와의 대화에서만 공개. 관계 자동 주시 아님."), false);
                                            }));
                                    source.sendSuccess(() -> Component.literal("주시 시작 요청 대기 중—아직 저장 완료가 아닙니다."), false); return 1;
                                })))))
                .then(Commands.literal("suspend").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    var source = c.getSource(); var r = GodWatchRuntime.current(source.getServer()); if (r == null) return 0;
                    r.suspend(EntityArgument.getPlayer(c, "player").getUUID()).whenComplete((w, error) -> source.getServer().execute(() -> {
                        if (!authorized(source, r)) return;
                        if (error == null) source.sendSuccess(() -> Component.literal("대상 주시 일시중지 저장됨. 기존 목격은 유지됩니다."), false);
                        else source.sendFailure(Component.literal("주시 중지 저장 실패. 조회/권한은 보수적으로 차단됩니다."));
                    })); return 1;
                })));
    }
    private static boolean authorized(CommandSourceStack source, GodWatchRuntime runtime) {
        return GodWatchRuntime.current(source.getServer()) == runtime && source.hasPermission(2)
                && (!(source.getEntity() instanceof ServerPlayer p) || source.getServer().getPlayerList().getPlayer(p.getUUID()) == p && p.hasPermissions(2));
    }
}
