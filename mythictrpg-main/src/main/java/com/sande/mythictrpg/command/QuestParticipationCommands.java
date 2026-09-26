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
