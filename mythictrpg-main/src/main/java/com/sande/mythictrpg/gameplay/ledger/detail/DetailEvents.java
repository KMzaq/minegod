package com.sande.mythictrpg.gameplay.ledger.detail;

import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import com.sande.mythictrpg.gameplay.observation.GameplayObservationAdapters;
import com.sande.mythictrpg.quest.QuestCompletionRecord;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.*;
import net.neoforged.neoforge.event.tick.*;
import java.util.*;

/** Read-only post events; never feeds the legacy coalescing quest/encounter sinks. */
public final class DetailEvents {
    private DetailEvents() {}
    public static void tick(PlayerTickEvent.Post e) {
        if(!(e.getEntity() instanceof ServerPlayer p) || !DetailCapture.enabled(p))return;
        var r=ActionLedgerService.current(p.server);int interval=r.detailSettings().movementIntervalTicks();
        r.movement().sample(p.getUUID(),DetailCapture.point(p),interval).ifPresent(s -> DetailCapture.record(p,p.blockPosition(),
                new ActionRecord.Subject("LOCATION",s.point().dimension(),null),ActionRecord.Type.POSITION_SAMPLE,"COMPLETED",
                Map.of("xyz",s.point().coordinates(),"interval_ticks",Integer.toString(interval),"elapsed_ticks",Long.toString(s.elapsedTicks()),"coverage",s.coverage())));
    }
    public static void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if(!(e.getEntity() instanceof ServerPlayer p)||!DetailCapture.enabled(p))return;
        DetailCapture.record(p,p.blockPosition(),new ActionRecord.Subject("LOCATION",e.getTo().location().toString(),null),
                ActionRecord.Type.DIMENSION_CHANGED,"COMPLETED",Map.of("from",e.getFrom().location().toString(),"to",e.getTo().location().toString(),"xyz",DetailCapture.point(p).coordinates()));
    }
    public static void logout(PlayerEvent.PlayerLoggedOutEvent e) { reset(e); }
    public static void respawn(PlayerEvent.PlayerRespawnEvent e) { reset(e); }
    private static void reset(PlayerEvent e) { if(e.getEntity() instanceof ServerPlayer p) {var r=ActionLedgerService.current(p.server);if(r!=null)r.movement().forget(p.getUUID());} }
    public static void endTick(ServerTickEvent.Post e) { DetailCapture.clearIncomplete(); com.sande.mythictrpg.gameplay.metric.MiningCredit.clearIncomplete(); com.sande.mythictrpg.gameplay.metric.ProvenanceTracking.clearIncomplete(); }
    public static void damage(LivingDamageEvent.Post e) {
        if(!Float.isFinite(e.getNewDamage()) || e.getNewDamage()<=0)return;
        var responsible=GameplayObservationAdapters.playerResponsibleForKill(e.getSource());
        ServerPlayer actor=responsible.orElse(e.getEntity() instanceof ServerPlayer p?p:null);
        if(actor==null||!DetailCapture.enabled(actor)||!(e.getEntity().level() instanceof net.minecraft.server.level.ServerLevel level))return;
        DetailCapture.recordAt(actor,level,e.getEntity().blockPosition(),DetailCapture.entity(e.getEntity()),ActionRecord.Type.DAMAGE_APPLIED,"COMPLETED",
                Map.of("health_damage",Float.toString(e.getNewDamage()),"role",responsible.isPresent()?"RESPONSIBLE_PLAYER":"DAMAGED_PLAYER",
                        "damage_kind",e.getSource().getMsgId(),"result","health_damage_not_kill_or_loot"));
    }
    public static void pickup(ItemEntityPickupEvent.Post e) {
        if(!(e.getPlayer() instanceof ServerPlayer p)||!DetailCapture.enabled(p))return;
        var original=e.getOriginalStack();var remaining=e.getCurrentStack();
        // Custom item swaps cannot be subtracted as if they were the original item.
        if(!remaining.isEmpty()&&!net.minecraft.world.item.ItemStack.isSameItemSameComponents(original,remaining))return;
        int count=original.getCount()-remaining.getCount();if(count<=0)return;
        DetailCapture.record(p,e.getItemEntity().blockPosition(),new ActionRecord.Subject("ITEM",BuiltInRegistries.ITEM.getKey(original.getItem()).toString(),null),
                ActionRecord.Type.ITEM_PICKED_UP,"COMPLETED",Map.of("quantity",Integer.toString(count),"item_entity",e.getItemEntity().getUUID().toString(),
                        "scope","ground_pickup_only_not_all_inventory_changes"));
    }
    public static void quest(ServerPlayer p,QuestCompletionRecord receipt) {
        if(!ImportantEvents.enabled(p.server))return;
        // Commit receipt is game-owned. Other assignees/team members are not additional completers.
        if(!receipt.completedBy().equals(p.getUUID()))return;
        ImportantEvents.transition(p.server,p.getUUID(),receipt.questId(),"COMPLETED",receipt.completedAt().toString(),receipt.completedAt(),null);
    }
    public static void evaluation(ServerPlayer p,net.minecraft.resources.ResourceLocation quest,net.minecraft.resources.ResourceLocation god,
                                  int score,boolean passed,String rewardState) {
        if(!ImportantEvents.enabled(p.server))return;
        DetailCapture.record(p,p.blockPosition(),new ActionRecord.Subject("QUEST",quest.toString(),null),ActionRecord.Type.QUEST_EVALUATED,"COMPLETED",
                Map.of("evaluator",god.toString(),"score",Integer.toString(score),"passed",Boolean.toString(passed),"reward_state",rewardState));
    }
    public static void death(net.minecraft.world.entity.LivingEntity entity,net.minecraft.world.damagesource.DamageSource source) {
        if(entity instanceof ServerPlayer p&&DetailCapture.enabled(p))DetailCapture.record(p,p.blockPosition(),DetailCapture.entity(p),ActionRecord.Type.PLAYER_DIED,"COMPLETED",
                Map.of("damage_kind",source.getMsgId(),"result","death_commit_not_inventory_drop"));
    }
}
