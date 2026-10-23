package com.sande.mythictrpg.ai.action.protection;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import java.util.*;
import java.util.function.Supplier;

/** Death loss is not silently authorized by an LLM threat. No item recovery is invented here. */
public final class AiDeathLossGuard {
    private record Damage(ServerPlayer player,ResourceLocation god,UUID proposal) { }
    private record Warning(com.sande.mythictrpg.godavatar.GodAvatarEntity actor,long order,boolean answered) {
        boolean current(ServerPlayer player){return actor.isAlive()&&actor.orderRevision()==order&&actor.getTarget()==player;}
    }
    private static final ThreadLocal<Damage> DAMAGE=new ThreadLocal<>();
    private static final ThreadLocal<Damage> CONFIRMED=new ThreadLocal<>();
    private static final Map<ServerPlayer,Map<ResourceLocation,Warning>> WARNINGS=new WeakHashMap<>();
    private AiDeathLossGuard() { }
    public static <T> T withConfirmedRisk(ServerPlayer player,ResourceLocation god,UUID proposal,Supplier<T> work) {
        var prior=CONFIRMED.get();CONFIRMED.set(new Damage(player,god,proposal));
        try{return work.get();}finally{if(prior==null)CONFIRMED.remove();else CONFIRMED.set(prior);}
    }
    public static boolean damage(ServerPlayer player,ResourceLocation god,UUID proposal,java.util.function.BooleanSupplier work) {
        var prior=DAMAGE.get();DAMAGE.set(new Damage(player,god,proposal));
        try{return work.getAsBoolean();}finally{if(prior==null)DAMAGE.remove();else DAMAGE.set(prior);}
    }
    static ResourceLocation explicitGod(ServerPlayer player) {
        var damage=DAMAGE.get();return damage!=null&&damage.player()==player?damage.god():null;
    }
    static boolean possessionsAtRisk(ServerPlayer player) {
        if(player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY))return false;
        return player.totalExperience>0||player.experienceLevel>0||player.experienceProgress>0
                ||!player.getInventory().isEmpty()||!player.containerMenu.getCarried().isEmpty()
                ||player.containerMenu.slots.stream().anyMatch(slot->slot.hasItem());
    }
    static boolean protectedPossessions(ServerPlayer player) {
        if(player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY))return false;
        for(int i=0;i<player.getInventory().getContainerSize();i++) {
            var item=player.getInventory().getItem(i);
            if(!item.isEmpty()&&AiProtectedItems.rejection(player,item).isPresent())return true;
        }
        var carried=player.containerMenu.getCarried();
        if(!carried.isEmpty()&&AiProtectedItems.rejection(player,carried).isPresent())return true;
        // Conservative for unknown mod menus: temporary crafting/anvil inputs may drop on death.
        return player.containerMenu.slots.stream().anyMatch(slot->slot.hasItem()&&AiProtectedItems.rejection(player,slot.getItem()).isPresent());
    }
    static void warn(ServerPlayer player,com.sande.mythictrpg.godavatar.GodAvatarEntity actor) {
        if(!possessionsAtRisk(player))return;
        var god=actor.godId().orElse(null);if(god==null)return;
        var warnings=WARNINGS.computeIfAbsent(player,key->new HashMap<>());
        warnings.entrySet().removeIf(entry->!entry.getValue().current(player));
        if(warnings.containsKey(god)||warnings.size()>=64)return;
        player.sendSystemMessage(Component.literal("[위험 경고] 이 신과의 전투에서 사망하면 일반 아이템·경험치를 잃을 수 있습니다. 물러날 수 있습니다. 경고 이후 직접 다시 공격하면 이 전투의 사망 위험을 감수하는 것으로 처리합니다. 중요·귀속 물품의 회복이 보장되지 않으면 해당 손실은 막습니다."));
        warnings.put(god,new Warning(actor,actor.orderRevision(),false));
    }
    static void voluntaryAttack(ServerPlayer player,ResourceLocation god) {
        var warnings=WARNINGS.get(player);
        var warning=warnings==null?null:warnings.get(god);
        if(warning!=null&&warning.current(player))warnings.put(god,new Warning(warning.actor(),warning.order(),true));
    }
    static boolean preventDeath(ServerPlayer player,ResourceLocation god) {
        if(protectedPossessions(player))return true;
        if(!possessionsAtRisk(player))return false;
        var current=DAMAGE.get();
        if(current!=null&&current.player()==player&&current.god().equals(god)&&current.equals(CONFIRMED.get()))return false;
        var warning=WARNINGS.getOrDefault(player,Map.of()).get(god);
        return warning==null||!warning.current(player)||!warning.answered();
    }
    public static void combatEnded(com.sande.mythictrpg.godavatar.GodAvatarEntity actor,ServerPlayer player) {
        var warnings=WARNINGS.get(player);if(warnings!=null)warnings.entrySet().removeIf(e->e.getValue().actor()==actor);
    }
    static void clear(ServerPlayer player){WARNINGS.remove(player);}
    static void clearAll(){WARNINGS.clear();DAMAGE.remove();CONFIRMED.remove();}
}
