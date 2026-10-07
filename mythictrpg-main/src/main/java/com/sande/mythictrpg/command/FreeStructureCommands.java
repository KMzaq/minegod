package com.sande.mythictrpg.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.sande.mythictrpg.quest.structure.FreeStructureService;
import com.sande.mythictrpg.quest.structure.PlayerConstructionState;
import com.sande.mythictrpg.quest.structure.StructureEvaluationPolicyManager;
import com.sande.mythictrpg.quest.structure.StructureEvaluationState;
import com.sande.mythictrpg.quest.structure.StructureQuestRegistrationService;
import com.sande.mythictrpg.quest.FtbQuestBindingManager;
import com.sande.mythictrpg.quest.MythicQuestState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class FreeStructureCommands {
    private FreeStructureCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mythstructure").requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.literal("pos1").executes(context -> point(context, false)))
                .then(Commands.literal("pos2").executes(context -> point(context, true)))
                .then(Commands.literal("quest").then(Commands.literal("confirm")
                        .then(Commands.argument("questId", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        MythicQuestState.get(context.getSource().getServer())
                                                .assignmentsFor(context.getSource().getPlayerOrException().getUUID())
                                                .stream().map(value -> value.questId())
                                                .filter(id -> FtbQuestBindingManager.INSTANCE.find(id)
                                                        .filter(binding -> binding.structureEvaluationPolicyId().isPresent()).isPresent()), builder))
                                .executes(FreeStructureCommands::confirmQuest))))
                .then(Commands.literal("register").then(Commands.argument("name", StringArgumentType.string())
                        .executes(FreeStructureCommands::registerSelection)))
                .then(Commands.literal("discover").then(Commands.argument("name", StringArgumentType.string())
                        .executes(FreeStructureCommands::discover)))
                .then(Commands.literal("evaluate").then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.argument("policy", ResourceLocationArgument.id())
                                .suggests((context,builder)-> SharedSuggestionProvider.suggestResource(
                                        StructureEvaluationPolicyManager.INSTANCE.ids(),builder))
                                .executes(FreeStructureCommands::evaluate))))
                .then(Commands.literal("result").then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.argument("policy", ResourceLocationArgument.id())
                                .suggests((context,builder)-> SharedSuggestionProvider.suggestResource(
                                        StructureEvaluationPolicyManager.INSTANCE.ids(),builder))
                                .executes(FreeStructureCommands::result))))
                .then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.string())
                        .executes(FreeStructureCommands::delete)))
                .then(Commands.literal("list").executes(FreeStructureCommands::list))
                .then(Commands.literal("status").executes(FreeStructureCommands::status)));
    }

    private static int confirmQuest(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        // No target-player argument: a non-operator can only register their own assigned quest.
        var result = StructureQuestRegistrationService.INSTANCE.confirm(
                context.getSource().getPlayerOrException(), ResourceLocationArgument.getId(context, "questId"));
        if (!result.accepted()) { context.getSource().sendFailure(Component.literal(result.reason())); return 0; }
        var build = result.build().orElseThrow();
        context.getSource().sendSuccess(() -> Component.literal("퀘스트 건축 영역 확정: "
                + build.region().width() + "x" + build.region().depth() + ", 참가자 "
                + build.eligibleContributors().size() + "명. 기존 원장은 초기화되며 지금부터 건축을 기록합니다."), false);
        return 1;
    }

    private static int point(CommandContext<CommandSourceStack> context, boolean second)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player=context.getSource().getPlayerOrException();
        StructureEvaluationState.get(player.server).setPoint(player.getUUID(), player.serverLevel().dimension(),
                player.blockPosition(), second);
        context.getSource().sendSuccess(()->Component.literal((second?"지점2: ":"지점1: ")
                +player.blockPosition().toShortString()),false); return 1;
    }
    private static int registerSelection(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player=context.getSource().getPlayerOrException();
        var result=FreeStructureService.INSTANCE.registerSelection(player,StringArgumentType.getString(context,"name"));
        if(!result.accepted()){context.getSource().sendFailure(Component.literal(result.reason()));return 0;}
        var region=result.structure().region();context.getSource().sendSuccess(()->Component.literal("자유 건축물 '"
                +result.structure().name()+"' 등록: "+region.width()+"x"+region.depth()+", 블록 "
                +result.structure().placements().size()+"개, 평가 "+result.queuedPolicies()+"건 예약"),false);return 1;
    }
    private static int discover(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player=context.getSource().getPlayerOrException();
        var result=FreeStructureService.INSTANCE.discoverConnected(player,StringArgumentType.getString(context,"name"));
        if(!result.accepted()){context.getSource().sendFailure(Component.literal(result.reason()));return 0;}
        context.getSource().sendSuccess(()->Component.literal("연결된 건축물 '"+result.structure().name()
                +"' 자동 등록: 블록 "+result.structure().placements().size()+"개, 평가 "
                +result.queuedPolicies()+"건 예약"),false);return 1;
    }
    private static int evaluate(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player=context.getSource().getPlayerOrException();String name=StringArgumentType.getString(context,"name");
        ResourceLocation policy=ResourceLocationArgument.getId(context,"policy");
        var result=FreeStructureService.INSTANCE.requestEvaluation(player,name,policy);
        if(!result.accepted()){context.getSource().sendFailure(Component.literal(result.reason()));return 0;}
        context.getSource().sendSuccess(()->Component.literal("건축 평가를 예약했습니다. 대기열 위치: "+result.queuePosition()),false);return 1;
    }
    private static int delete(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player=context.getSource().getPlayerOrException();String name=StringArgumentType.getString(context,"name");
        boolean removed=PlayerConstructionState.get(player.server).delete(player.getUUID(),name);
        if(!removed){context.getSource().sendFailure(Component.literal("해당 이름의 건축물이 없습니다"));return 0;}
        context.getSource().sendSuccess(()->Component.literal("자유 건축물 등록과 평가 이력을 삭제했습니다: "+name),false);return 1;
    }
    private static int result(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player=context.getSource().getPlayerOrException();String name=StringArgumentType.getString(context,"name");
        ResourceLocation policy=ResourceLocationArgument.getId(context,"policy");
        var structure=PlayerConstructionState.get(player.server).findByName(player.getUUID(),name).orElse(null);
        if(structure==null){context.getSource().sendFailure(Component.literal("해당 이름의 건축물이 없습니다"));return 0;}
        var evaluation=PlayerConstructionState.get(player.server).evaluation("free:"+structure.id(),policy).orElse(null);
        if(evaluation==null){context.getSource().sendFailure(Component.literal("해당 정책의 평가 결과가 없습니다"));return 0;}
        context.getSource().sendSuccess(()->Component.literal("[건축 결과] "+name+" / "+policy+" = "+evaluation.score()
                +"점 (블록 "+evaluation.objectiveScore()+"점)"),false);
        evaluation.visualAssessment().ifPresentOrElse(visual-> {
            context.getSource().sendSuccess(()->Component.literal("유형: "+visual.buildingType()+" / 세부: "
                    +(visual.subtype().isBlank()?"미정":visual.subtype())+" / 스타일: "
                    +(visual.styles().isEmpty()?"미정":String.join(", ",visual.styles()))),false);
            context.getSource().sendSuccess(()->Component.literal("시각 품질 "+visual.visualQualityScore()+", 신 선호도 "
                    +visual.godPreferenceScore()+", 완성도 "+visual.completenessScore()+", 신뢰도 "
                    +Math.round(visual.confidence()*100)+"%"),false);
            if(!visual.evidence().isEmpty())context.getSource().sendSuccess(()->Component.literal("근거: "
                    +String.join(" / ",visual.evidence())),false);
        },()->context.getSource().sendSuccess(()->Component.literal("시각 분석 결과 없음: 블록 분석만 완료됨"),false));
        return 1;
    }
    private static int list(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player=context.getSource().getPlayerOrException();var values=PlayerConstructionState.get(player.server).structuresFor(player.getUUID());
        context.getSource().sendSuccess(()->Component.literal("등록된 자유 건축물: "+values.size()),false);
        values.forEach(value->context.getSource().sendSuccess(()->Component.literal("- "+value.name()+" ["
                +value.id()+"] "+value.region().width()+"x"+value.region().depth()+", 블록 "+value.placements().size()),false));
        return values.size();
    }
    private static int status(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player=context.getSource().getPlayerOrException();int objective=FreeStructureService.INSTANCE.pending(player.server,player.getUUID());
        int visual=com.sande.mythictrpg.quest.structure.StructureVisualEvaluationService.INSTANCE.pending(player.server,player.getUUID());
        context.getSource().sendSuccess(()->Component.literal("대기 중인 건축 평가: 블록 "+objective+", 시각 "+visual),false);
        return objective+visual;
    }
}
