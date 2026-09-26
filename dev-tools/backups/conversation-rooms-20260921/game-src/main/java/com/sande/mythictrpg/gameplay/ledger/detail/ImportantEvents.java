package com.sande.mythictrpg.gameplay.ledger.detail;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import com.sande.mythictrpg.gameplay.watch.GodWatchRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Confirmed game transitions only, independent of receipt delivery and AI narration. */
public final class ImportantEvents {
    public static final TagKey<EntityType<?>> NAMED_TARGETS = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath("mythictrpg","detailed_battle_targets"));
    private ImportantEvents() { }
    public static UUID key(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    public static boolean enabled(MinecraftServer server) {
        var r=ActionLedgerService.current(server); return r!=null && r.ledger()!=null && r.detailSettings().enabled();
    }
    public static void transition(MinecraftServer server, UUID player, ResourceLocation quest, String transition,
            String evidence, Instant when, UUID run) {
        ServerPlayer online=server.getPlayerList().getPlayer(player);
        record(server,key("quest/"+quest+"/"+player+"/"+transition+"/"+evidence),player,
                new ActionRecord.Subject("QUEST",quest.toString(),null),ActionRecord.Type.QUEST_TRANSITION,
                when.toEpochMilli(),online==null?null:online.serverLevel(),online==null?null:online.blockPosition(),
                run,Set.of(player),Map.of("transition",transition,"evidence",evidence,"reward_state","NOT_ESTABLISHED_BY_TRANSITION"));
    }
    public static void advancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var progress=player.getAdvancements().getOrStartProgress(event.getAdvancement());
        if (!progress.isDone()) return;
        Instant latest=null;
        for(String criterion:progress.getCompletedCriteria()) {
            var obtained=progress.getCriterion(criterion).getObtained();
            if(obtained!=null && (latest==null || obtained.isAfter(latest))) latest=obtained;
        }
        String evidence=latest==null?"no_criteria":Long.toString(latest.getEpochSecond());
        record(player.server,key("advancement/"+player.getUUID()+"/"+event.getAdvancement().id()+"/"+evidence),player.getUUID(),
                new ActionRecord.Subject("ADVANCEMENT",event.getAdvancement().id().toString(),null),ActionRecord.Type.ADVANCEMENT_EARNED,
                System.currentTimeMillis(),player.serverLevel(),player.blockPosition(),null,Set.of(player.getUUID()),
                Map.of("basis","AUTHORITATIVE_ADVANCEMENT_EARN","disclosure","PRIVATE_UNPROJECTED"));
    }
    public static void namedDeath(ServerPlayer actor, LivingEntity victim) {
        if (!victim.getType().is(NAMED_TARGETS)) return; // Never use a name-tag string as classification.
        var participants=new LinkedHashSet<UUID>();participants.add(actor.getUUID());
        var data=victim.getPersistentData().getCompound("mythictrpg_battle_damage_participants");
        for(String id:data.getAllKeys()) { try { if(participants.size()<64)participants.add(UUID.fromString(id)); }catch(IllegalArgumentException ignored){ } }
        battle(actor.server,victim.getUUID(),BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()),actor.getUUID(),
                participants,(ServerLevel)victim.level(),victim.blockPosition(),"TARGET_KILLED",
                "CONFIRMED_DAMAGE_AND_KILL_CREDIT_NOT_SUPPORT_OR_PROXIMITY");
    }
    public static void damage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if(!(event.getEntity().level() instanceof ServerLevel)||!event.getEntity().getType().is(NAMED_TARGETS)
                ||!Float.isFinite(event.getNewDamage())||event.getNewDamage()<=0)return;
        com.sande.mythictrpg.gameplay.observation.GameplayObservationAdapters.playerResponsibleForKill(event.getSource()).ifPresent(player->{
            var entity=event.getEntity();var data=entity.getPersistentData().getCompound("mythictrpg_battle_damage_participants");
            if(data.size()<64){data.putBoolean(player.getUUID().toString(),true);entity.getPersistentData().put("mythictrpg_battle_damage_participants",data);}
        });
    }
    /** Authored battle engine supplies the actual roster/result; proximity/team/AI never supplies participants. */
    public static void battle(MinecraftServer server, UUID run, ResourceLocation content, UUID recorder,
            Set<UUID> participants, ServerLevel level, BlockPos resultPosition, String outcome, String rosterBasis) {
        if (participants.isEmpty() || !participants.contains(recorder) || !Set.of("TARGET_KILLED","VICTORY","DEFEAT","WITHDRAWN","ABORTED").contains(outcome)) throw new IllegalArgumentException("battle participants/result");
        record(server,key("battle/"+run),recorder,new ActionRecord.Subject("BATTLE",content.toString(),null),
                ActionRecord.Type.BATTLE_RESULT,System.currentTimeMillis(),level,resultPosition,run,participants,
                Map.of("battle_result",outcome,"roster_basis",rosterBasis,"reward_state","NOT_ESTABLISHED_BY_BATTLE"));
    }
    static void record(MinecraftServer server, UUID occurrence, UUID actor, ActionRecord.Subject subject,
            ActionRecord.Type type, long utc, ServerLevel level, BlockPos pos, UUID run,
            Set<UUID> participants, Map<String,String> payload) {
        if (!enabled(server)) return;
        var r=ActionLedgerService.current(server);
        try {
            boolean known=level!=null && pos!=null;
            var details=new ActionRecord.Details(known?level.getBiome(pos).unwrapKey().map(k->k.location().toString()).orElse(null):null,
                    known?"AT_TRANSITION":"OFFLINE_UNKNOWN",run,participants);
            var draft=new ActionRecord.Draft(occurrence,r.captureSession(),r.nextCaptureOrder(),"mythictrpg:detail/"+type.name().toLowerCase(Locale.ROOT),1,
                    actor,subject,utc,server.overworld().getGameTime(),known?level.getDayTime():0,
                    known?level.dimension().location().toString():"mythictrpg:unknown",known?new ActionRecord.Position(pos.getX(),pos.getY(),pos.getZ()):null,
                    type,"COMPLETED",payload,"mythictrpg:admin_only_unprojected",details);
            var submission=r.ledger().submitTransition(draft);
            GodWatchRuntime.observed(server,draft,submission);
            try { com.sande.mythictrpg.rumor.SocialRuntime.important(server,draft); }
            catch(RuntimeException unavailable) { MythicTrpg.LOGGER.warn("Public rumor projection unavailable; original result unchanged",unavailable); }
        } catch(RuntimeException failure) {
            r.ledger().gap("IMPORTANT_CAPTURE_FAILED"); MythicTrpg.LOGGER.error("Important event capture failed; game result unchanged",failure);
        }
    }
}
