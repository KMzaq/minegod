package com.sande.mythictrpg.skills;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.quest.reward.SkillRewardBridge;
import com.sande.mythictrpg.weaponskill.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;

@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class LearnedSkillRuntime {
    private LearnedSkillRuntime() { }
    public static boolean isOwned(ServerPlayer p,ResourceLocation id){return SkillRewardBridge.isReady(p)&&SkillRewardBridge.ownsLearned(p,id)&&LearnedSkillDefinitions.INSTANCE.find(id).isPresent();}
    public static List<ResourceLocation> ownedSkills(ServerPlayer p){return SkillRewardBridge.ownedIds(p).stream().filter(id->isOwned(p,id)).sorted().toList();}
    public static Optional<ResourceLocation> selected(ServerPlayer p){return WeaponSkillState.get(p.server).selected(p.getUUID()).map(ResourceLocation::parse).filter(id->isOwned(p,id));}
    public static boolean select(ServerPlayer p,ResourceLocation id){return isOwned(p,id)&&WeaponSkillState.get(p.server).select(p.getUUID(),id.toString());}
    public static long remainingTicks(ServerPlayer p,ResourceLocation id){return WeaponSkillState.get(p.server).account(p.getUUID()).map(a->a.remainingTicks(SkillExecutor.cooldownKey(SkillCastEvents.Source.LEARNED,id),SkillMana.now(p))).orElse(0L);}
    public static long reduceCooldown(ServerPlayer p,ResourceLocation id,long ticks){return isOwned(p,id)?WeaponSkillState.get(p.server).reduceCooldown(p.getUUID(),SkillExecutor.cooldownKey(SkillCastEvents.Source.LEARNED,id),ticks,SkillMana.now(p)):0;}
    public static boolean reduceSelectedCooldown(ServerPlayer p,double fraction){
        if(!Double.isFinite(fraction)||fraction<=0)return false;
        return selected(p).flatMap(LearnedSkillDefinitions.INSTANCE::find).map(d->reduceCooldown(p,d.id(),(long)Math.min(Long.MAX_VALUE,Math.floor(d.cooldownTicks()*fraction)))>0).orElse(false);
    }
    public static WeaponSkillRuntime.Result cast(ServerPlayer p,ResourceLocation id){
        if(!SkillExecutor.availablePlayer(p))return WeaponSkillRuntime.Result.INVALID_PLAYER;
        if(!isOwned(p,id))return WeaponSkillRuntime.Result.NOT_BOUND;
        var d=LearnedSkillDefinitions.INSTANCE.find(id).orElseThrow();
        return SkillExecutor.cast(p,SkillCastEvents.Source.LEARNED,id,d.name(),d.manaCost(),d.cooldownTicks(),d.action());
    }
    public static void useSelected(ServerPlayer p){selected(p).ifPresentOrElse(id->feedback(p,cast(p,id)),()->p.sendSystemMessage(Component.literal("선택한 습득 스킬이 없습니다. /mythskills 에서 선택하세요.")));}
    static void feedback(ServerPlayer p,WeaponSkillRuntime.Result result){
        if(result!=WeaponSkillRuntime.Result.SUCCESS)p.sendSystemMessage(Component.literal(switch(result){
            case NO_MANA->"마나가 부족합니다.";case COOLDOWN->"스킬 재사용 대기 중입니다.";case NO_EFFECT->"지금 적용할 효과가 없습니다.";
            case NOT_BOUND->"습득하지 않았거나 사용할 수 없는 스킬입니다.";default->"지금 스킬을 사용할 수 없습니다.";}));
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("mythskills")
                .executes(c->{SkillBookNetwork.send(c.getSource().getPlayerOrException(),0,true);return 1;})
                .then(Commands.literal("list").executes(c->{var p=c.getSource().getPlayerOrException();for(var id:ownedSkills(p)){
                    var d=LearnedSkillDefinitions.INSTANCE.find(id).orElseThrow();p.sendSystemMessage(Component.literal(d.name()+" · 마나 "+d.manaCost()+" · 쿨타임 "+d.cooldownTicks()/20.0+"초 ")
                            .append(Component.literal("[선택]").withStyle(s->s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/mythskills select "+id)))));}return 1;}))
                .then(Commands.literal("use").executes(c->{useSelected(c.getSource().getPlayerOrException());return 1;}))
                .then(Commands.literal("select").then(Commands.argument("skill",ResourceLocationArgument.id()).executes(c->{var p=c.getSource().getPlayerOrException();
                    boolean accepted=select(p,ResourceLocationArgument.getId(c,"skill"));p.sendSystemMessage(Component.literal(accepted?"사용할 습득 스킬을 선택했습니다.":"습득한 스킬만 선택할 수 있습니다."));return accepted?1:0;})))
                .then(Commands.literal("cast").then(Commands.argument("skill",ResourceLocationArgument.id()).executes(c->{var p=c.getSource().getPlayerOrException();
                    var result=cast(p,ResourceLocationArgument.getId(c,"skill"));feedback(p,result);return result==WeaponSkillRuntime.Result.SUCCESS?1:0;}))));
    }
}
