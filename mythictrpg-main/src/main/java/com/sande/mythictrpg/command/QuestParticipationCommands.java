package com.sande.mythictrpg.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.sande.mythictrpg.ai.server.AiConversationRuntimeService;
import com.sande.mythictrpg.quest.QuestEnrollment;
import com.sande.mythictrpg.quest.QuestParticipationService;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class QuestParticipationCommands {
    private QuestParticipationCommands() {}
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("mythtalk")
                .then(Commands.literal("join").then(Commands.argument("host", EntityArgument.player()).executes(ctx -> {
                    boolean joined = AiConversationRuntimeService.INSTANCE.joinConversation(ctx.getSource().getPlayerOrException(),
                            EntityArgument.getPlayer(ctx, "host"));
                    ctx.getSource().sendSuccess(() -> Component.literal(joined ? "같은 신과의 대화에 참여했습니다."
                            : "16블록 안의 대화 참가자를 지정하세요. 다른 신과 대화 중이면 먼저 나가세요."), false);
                    return joined ? 1 : 0;
                })))
                .then(Commands.literal("leave").executes(ctx -> {
                    AiConversationRuntimeService.INSTANCE.leaveConversation(ctx.getSource().getPlayerOrException());
                    return 1;
                })));
        dispatcher.register(Commands.literal("mythquest")
                .then(Commands.literal("call").then(Commands.argument("god", ResourceLocationArgument.id()).executes(ctx -> {
                    boolean replied = com.sande.mythictrpg.quest.QuestContactService.call(ctx.getSource().getPlayerOrException(),
                            ResourceLocationArgument.getId(ctx, "god"));
                    if (!replied) ctx.getSource().sendFailure(Component.literal(
                            "지금 당신을 주시하며 응답할 수 있는 상태가 아닙니다. 실제로 만나거나 나중에 다시 부르세요."));
                    return replied ? 1 : 0;
                })))
                .then(Commands.literal("roster").then(Commands.argument("room", StringArgumentType.word())
                        .then(Commands.argument("god", ResourceLocationArgument.id())
                        .then(Commands.argument("quest", ResourceLocationArgument.id()).executes(ctx -> {
                            var player = ctx.getSource().getPlayerOrException();
                            var rooms = com.sande.mythictrpg.ai.server.ConversationRooms.INSTANCE;
                            var room = rooms.resolveMember(player, StringArgumentType.getString(ctx, "room")).orElse(null);
                            var god = ResourceLocationArgument.getId(ctx, "god");
                            var scope = room == null ? null : rooms.actionScope(player, room.roomId(), room.revision(), god).orElse(null);
                            String problem = com.sande.mythictrpg.quest.QuestReorganizationService.INSTANCE.open(player,
                                    ResourceLocationArgument.getId(ctx, "quest"), scope);
                            if (!problem.isEmpty()) ctx.getSource().sendFailure(Component.literal(problem));
                            return problem.isEmpty() ? 1 : 0;
                        })))))
                .then(Commands.literal("roster-select").then(Commands.argument("token", UuidArgument.uuid()).executes(ctx -> {
                    boolean ok = com.sande.mythictrpg.quest.QuestReorganizationService.INSTANCE.select(
                            ctx.getSource().getPlayerOrException(), UuidArgument.getUuid(ctx, "token"));
                    if (!ok) ctx.getSource().sendFailure(Component.literal("만료됐거나 사용할 수 없는 참가자 관리 선택입니다."));
                    return ok ? 1 : 0;
                })))
                .then(Commands.literal("roster-answer").then(Commands.argument("token", UuidArgument.uuid())
                        .then(Commands.argument("answer", StringArgumentType.word())
                                .suggests((ctx, builder) -> { builder.suggest("yes"); builder.suggest("no"); return builder.buildFuture(); })
                                .executes(ctx -> {
                                    String answer = StringArgumentType.getString(ctx, "answer");
                                    if (!answer.equals("yes") && !answer.equals("no")) return 0;
                                    boolean ok = com.sande.mythictrpg.quest.QuestReorganizationService.INSTANCE.answer(
                                            ctx.getSource().getPlayerOrException(), UuidArgument.getUuid(ctx, "token"), answer.equals("yes"));
                                    if (!ok) ctx.getSource().sendFailure(Component.literal("만료됐거나 본인에게 발급되지 않은 참가자 변경 확인입니다."));
                                    return ok ? 1 : 0;
                                }))))
                .then(Commands.literal("watches").executes(ctx -> {
                    var player=ctx.getSource().getPlayerOrException();
                    var entries=com.sande.mythictrpg.quest.reward.RewardClaimState.get(player.server).watchesFor(player.getUUID());
                    ctx.getSource().sendSuccess(()->Component.literal(entries.isEmpty()?"획득한 주시 보상이 없습니다.":
                            "획득한 주시: "+String.join(", ",entries.stream().map(w->w.displayName()+"의 주시").toList())
                            +" (획득 상태이며 현재 관측 가능 여부와는 별개입니다.)"),false);
                    return entries.size();
                }))
                .then(Commands.literal("rewards").executes(ctx -> {
                    com.sande.mythictrpg.quest.reward.RewardClaimService.INSTANCE.deliverQueued(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("confirm").then(Commands.argument("quest", ResourceLocationArgument.id()).executes(ctx -> {
                    var result = QuestParticipationService.INSTANCE.confirm(ctx.getSource().getPlayerOrException(),
                            ResourceLocationArgument.getId(ctx, "quest"));
                    if (!result.succeeded() && result.status() != com.sande.mythictrpg.quest.QuestOperationResult.Status.SUBMITTED)
                        ctx.getSource().sendFailure(Component.literal(result.reason()));
                    return result.succeeded() || result.status() == com.sande.mythictrpg.quest.QuestOperationResult.Status.SUBMITTED ? 1 : 0;
                })))
                .then(Commands.literal("answer").then(Commands.argument("offer", UuidArgument.uuid())
                        .then(Commands.argument("answer", StringArgumentType.word())
                                .suggests((ctx, builder) -> { builder.suggest("yes"); builder.suggest("no"); return builder.buildFuture(); })
                                .executes(ctx -> {
                                    String answer = StringArgumentType.getString(ctx, "answer");
                                    if (!answer.equals("yes") && !answer.equals("no")) return 0;
                                    boolean accepted = QuestParticipationService.INSTANCE.answer(ctx.getSource().getPlayerOrException(),
                                            UuidArgument.getUuid(ctx, "offer"), answer.equals("yes") ? QuestEnrollment.Answer.YES : QuestEnrollment.Answer.NO);
                                    if (!accepted) ctx.getSource().sendFailure(Component.literal("유효한 수주 질문 또는 응답이 아닙니다."));
                                    return accepted ? 1 : 0;
                                }))))
                .then(Commands.literal("submit").then(Commands.argument("quest", ResourceLocationArgument.id())
                        .executes(ctx -> {
                            int consumed = QuestParticipationService.INSTANCE.submitItems(ctx.getSource().getPlayerOrException(),
                                    ResourceLocationArgument.getId(ctx, "quest"));
                            ctx.getSource().sendSuccess(() -> Component.literal("아이템 " + consumed + "개를 제출했습니다."), false);
                            return consumed;
                        }))));
    }
}
