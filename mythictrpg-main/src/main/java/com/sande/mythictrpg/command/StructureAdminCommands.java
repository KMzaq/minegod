package com.sande.mythictrpg.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sande.mythictrpg.item.ModItems;
import com.sande.mythictrpg.quest.FtbQuestBindingManager;
import com.sande.mythictrpg.quest.structure.StructureEvaluationPolicyManager;
import com.sande.mythictrpg.quest.structure.StructureEvaluationService;
import com.sande.mythictrpg.quest.structure.StructureEvaluationState;
import com.sande.mythictrpg.quest.structure.StructureRegion;
import com.sande.mythictrpg.quest.structure.StructureQuestRegistrationService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

final class StructureAdminCommands {
    private StructureAdminCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> node(){
        return Commands.literal("structure")
                .then(Commands.literal("tool").then(player().executes(StructureAdminCommands::tool)))
                .then(Commands.literal("pos1").then(player().executes(c->point(c,false))))
                .then(Commands.literal("pos2").then(player().executes(c->point(c,true))))
                .then(Commands.literal("confirm").then(player().then(quest().executes(StructureAdminCommands::confirm))))
                .then(Commands.literal("status").then(player().then(quest().executes(StructureAdminCommands::status))))
                .then(Commands.literal("inspect").then(player().then(quest().executes(StructureAdminCommands::inspect))))
                .then(Commands.literal("evaluate").then(player().then(quest().then(god().executes(StructureAdminCommands::evaluate)))))
                .then(Commands.literal("clear").then(player().then(quest().executes(StructureAdminCommands::clear))))
                .then(Commands.literal("policies").executes(StructureAdminCommands::policies));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, net.minecraft.commands.arguments.selector.EntitySelector> player(){return Commands.argument("player",EntityArgument.player());}
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack,ResourceLocation> quest(){return Commands.argument("quest",ResourceLocationArgument.id()).suggests((c,b)->SharedSuggestionProvider.suggestResource(FtbQuestBindingManager.INSTANCE.ids(),b));}
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack,ResourceLocation> god(){return Commands.argument("god",ResourceLocationArgument.id());}
    private static int tool(CommandContext<CommandSourceStack> c)throws CommandSyntaxException{ServerPlayer p=EntityArgument.getPlayer(c,"player");p.getInventory().placeItemBackInInventory(new ItemStack(ModItems.STRUCTURE_SELECTOR.get()));c.getSource().sendSuccess(()->Component.literal("건축 영역 선택기를 지급했습니다: 일반 사용=지점1, 웅크리고 사용=지점2"),false);return 1;}
    private static int point(CommandContext<CommandSourceStack>c,boolean second)throws CommandSyntaxException{ServerPlayer p=EntityArgument.getPlayer(c,"player");StructureEvaluationState.get(p.server).setPoint(p.getUUID(),p.serverLevel().dimension(),p.blockPosition(),second);c.getSource().sendSuccess(()->Component.literal((second?"지점2: ":"지점1: ")+p.blockPosition().toShortString()),false);return 1;}
    private static int confirm(CommandContext<CommandSourceStack>c)throws CommandSyntaxException{ServerPlayer p=EntityArgument.getPlayer(c,"player");ResourceLocation q=ResourceLocationArgument.getId(c,"quest");var result=StructureQuestRegistrationService.INSTANCE.confirm(p,q);if(!result.accepted()){c.getSource().sendFailure(Component.literal(result.reason()));return 0;}var record=result.build().orElseThrow();StructureRegion r=record.region();c.getSource().sendSuccess(()->Component.literal("영역 확정: "+r.width()+"x"+r.depth()+", 참가자 "+record.eligibleContributors().size()+"명. 기존 원장은 초기화되었습니다."),false);return 1;}
    private static int status(CommandContext<CommandSourceStack>c)throws CommandSyntaxException{ServerPlayer p=EntityArgument.getPlayer(c,"player");ResourceLocation q=ResourceLocationArgument.getId(c,"quest");var state=StructureEvaluationState.get(p.server);var draft=state.draft(p.getUUID());c.getSource().sendSuccess(()->Component.literal("선택 지점: "+draft.map(d->String.valueOf(d.first())+" / "+d.second()).orElse("없음")),false);var build=state.build(p.getUUID(),q).orElse(null);if(build==null){c.getSource().sendFailure(Component.literal("확정 영역 없음"));return 0;}c.getSource().sendSuccess(()->Component.literal("확정 영역 "+build.region().width()+"x"+build.region().depth()+", 원장 "+build.placements().size()+"블록, 참가자 "+build.eligibleContributors().size()+"명"),false);return 1;}
    private static int inspect(CommandContext<CommandSourceStack>c)throws CommandSyntaxException{ServerPlayer p=EntityArgument.getPlayer(c,"player");ResourceLocation q=ResourceLocationArgument.getId(c,"quest");var result=StructureEvaluationService.INSTANCE.inspect(p,q);if(!result.succeeded()){c.getSource().sendFailure(Component.literal(result.reason()));return 0;}sendReport(c,result.report());return result.report().score();}
    private static int evaluate(CommandContext<CommandSourceStack>c)throws CommandSyntaxException{ServerPlayer p=EntityArgument.getPlayer(c,"player");ResourceLocation q=ResourceLocationArgument.getId(c,"quest");ResourceLocation g=ResourceLocationArgument.getId(c,"god");var result=StructureEvaluationService.INSTANCE.requestEvaluation(p,q,g);if(!result.succeeded()){c.getSource().sendFailure(Component.literal(result.reason()));return 0;}sendReport(c,result.report());c.getSource().sendSuccess(()->Component.literal("퀘스트 판정: "+result.questResult().status()),false);return result.report().score();}
    private static int clear(CommandContext<CommandSourceStack>c)throws CommandSyntaxException{ServerPlayer p=EntityArgument.getPlayer(c,"player");ResourceLocation q=ResourceLocationArgument.getId(c,"quest");boolean removed=StructureEvaluationState.get(p.server).clear(p.getUUID(),q);c.getSource().sendSuccess(()->Component.literal(removed?"건축 영역과 원장을 제거했습니다":"제거할 원장이 없습니다"),false);return removed?1:0;}
    private static int policies(CommandContext<CommandSourceStack>c){var ids=StructureEvaluationPolicyManager.INSTANCE.ids().stream().sorted().toList();c.getSource().sendSuccess(()->Component.literal("건축 평가 정책: "+ids.size()),false);ids.forEach(id->c.getSource().sendSuccess(()->Component.literal("- "+id),false));return ids.size();}
    private static void sendReport(CommandContext<CommandSourceStack>c,com.sande.mythictrpg.quest.structure.StructureEvaluationReport r){c.getSource().sendSuccess(()->Component.literal("점수 "+r.score()+"/100 (BUILD "+Math.round(r.buildScore())+", ENV "+Math.round(r.environmentScore())+")"),false);r.criteria().forEach(v->c.getSource().sendSuccess(()->Component.literal("- "+v.id()+": "+Math.round(v.awardedPoints())+"/"+Math.round(v.availablePoints())+" raw="+String.format(java.util.Locale.ROOT,"%.3f",v.rawValue())),false));c.getSource().sendSuccess(()->Component.literal(r.evidenceSummary()),false);}
}
