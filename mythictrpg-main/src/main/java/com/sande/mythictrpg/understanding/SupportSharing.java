package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;
import java.util.concurrent.*;

/** Explicit self-UI opt-in, private one-player/one-NPC support only. Not global NPC knowledge. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class SupportSharing {
    public static final String KEY="support_sharing";
    private static final Map<MinecraftServer,Map<UUID,Long>> REVISIONS=new IdentityHashMap<>();
    private static final Map<MinecraftServer,Set<UUID>> REVOKED=new IdentityHashMap<>();
    private static long revision(ServerPlayer p){return REVISIONS.getOrDefault(p.server,Map.of()).getOrDefault(p.getUUID(),0L);}
    public static long invalidate(ServerPlayer p){return REVISIONS.computeIfAbsent(p.server,k->new HashMap<>()).merge(p.getUUID(),1L,Long::sum);}
    /** Fail closed even when the durable DENY write fails. Only a later committed explicit ALLOW clears this latch. */
    public static void revoke(ServerPlayer p){invalidate(p);REVOKED.computeIfAbsent(p.server,k->new HashSet<>()).add(p.getUUID());}
    public static boolean blocked(ServerPlayer p){return REVOKED.getOrDefault(p.server,Set.of()).contains(p.getUUID());}
    static void allowCommitted(ServerPlayer p,long expectedRevision){
        if(revision(p)==expectedRevision){var denied=REVOKED.get(p.server);if(denied!=null)denied.remove(p.getUUID());}
    }
    public static final class Grant {
        private final ServerPlayer player;private final ActionLedgerService.Runtime runtime;
        private final UUID room,turn;private final long roomRevision,revision,analysisStart;private final String god;
        private final Map<String,Object> context;
        private Grant(ServerPlayer player,ActionLedgerService.Runtime runtime,UUID room,UUID turn,long roomRevision,
                String god,long revision,long analysisStart,Map<String,Object> context) {
            this.player=player;this.runtime=runtime;this.room=room;this.turn=turn;this.roomRevision=roomRevision;
            this.god=god;this.revision=revision;this.analysisStart=analysisStart;this.context=Map.copyOf(context);
        }
        public Map<String,Object> context(){return context;}
        public boolean current(RoomConversationEngine.Request r) {
            return player.server.isSameThread()&&player.server.getPlayerList().getPlayer(player.getUUID())==player
                    &&ActionLedgerService.current(player.server)==runtime&&SupportSharing.revision(player)==revision&&!blocked(player)
                    &&MinecraftActivityCapture.enrolled(player)&&MinecraftActivityCapture.analysisStart(player)==analysisStart
                    &&scope(r)&&player.getUUID().equals(r.playerId())&&room.equals(r.roomId())&&turn.equals(r.turnId())
                    &&roomRevision==r.revision()&&god.equals(r.speakerGodId().toString());
        }
    }
    public static boolean scope(RoomConversationEngine.Request r) {
        return !r.readOnly()&&!r.secondary()&&!r.publicRoom()&&r.godIds().size()==1&&r.audiencePlayerIds().equals(Set.of(r.playerId()));
    }
    public static boolean allowed(UUID world,UUID player,PurposeSelfView.Snapshot s) {
        var control=s.activity().control();
        if(control==null||!world.equals(control.worldId())||!player.equals(control.event().actorId())
                ||!"START".equals(control.event().payload().get("operation")))return false;
        return s.activity().feedback().stream().filter(r->world.equals(r.worldId())&&player.equals(r.event().actorId())
                &&r.sequence()>control.sequence()&&r.sequence()<=s.activity().head()&&KEY.equals(r.event().payload().get("key")))
                .max(Comparator.comparingLong(com.sande.mythictrpg.gameplay.ledger.ActionRecord::sequence))
                .map(r->"ALLOW".equals(r.event().payload().get("operation"))).orElse(false);
    }
    public static CompletableFuture<Optional<Grant>> prepare(ServerPlayer p,RoomConversationEngine.Request request) {
        if(!p.server.isSameThread()||blocked(p)||!scope(request)||!p.getUUID().equals(request.playerId())||!MinecraftActivityCapture.enrolled(p))
            return CompletableFuture.completedFuture(Optional.empty());
        var runtime=ActionLedgerService.current(p.server);long rev=revision(p),start=MinecraftActivityCapture.analysisStart(p);
        var result=new CompletableFuture<Optional<Grant>>();
        MinecraftActivityCapture.flush(p);
        runtime.ledger().understandingSnapshot(p.getUUID()).orTimeout(1500,TimeUnit.MILLISECONDS)
                .whenComplete((snapshot,error)->p.server.execute(()->{
                    if(error!=null||revision(p)!=rev||ActionLedgerService.current(p.server)!=runtime
                            ||!allowed(runtime.worldId(),p.getUUID(),snapshot)) {result.complete(Optional.empty());return;}
                    var context=withTargetNames(projection(runtime.worldId(),p.getUUID(),snapshot));
                    var grant=new Grant(p,runtime,request.roomId(),request.turnId(),request.revision(),request.speakerGodId().toString(),rev,start,context);
                    result.complete(grant.current(request)&&!context.isEmpty()?Optional.of(grant):Optional.empty());
                }));
        return result;
    }
    public static Map<String,Object> projection(UUID world,UUID player,PurposeSelfView.Snapshot snapshot) {
        if(!allowed(world,player,snapshot))return Map.of();
        var latestFeedback=new HashMap<String,com.sande.mythictrpg.gameplay.ledger.ActionRecord>();
        for(var r:snapshot.activity().feedback())if(r.sequence()>snapshot.statistics().startOrder()&&r.sequence()<=snapshot.statistics().head()
                &&world.equals(r.worldId())&&player.equals(r.event().actorId()))
            latestFeedback.merge(r.event().payload().getOrDefault("key",""),r,(a,b)->a.sequence()>b.sequence()?a:b);
        var excluded=new HashSet<String>();
        latestFeedback.forEach((key,r)->{if("DISPUTE".equals(r.event().payload().get("operation")))excluded.add(key);});
        // A large play total must not crowd raid/conversation hints out of a requested consultation.
        var options=List.of("PLAY","RAID","DIALOGUE").stream().flatMap(domain->snapshot.statistics().counters().stream()
                .filter(c->c.key().domain().equals(domain)&&c.established()&&!excluded.contains(c.key().feedbackKey()))
                .sorted(Comparator.comparingInt(SupportStatistics.Counter::recentEpisodes).reversed().thenComparing(c->c.key().name())).limit(2))
                .map(c->Map.of("domain",c.key().domain(),"optionalNextStep",c.key().suggestion)).toList();
        var goals=snapshot.purposes().project(world,player,e->true).goals().stream()
                .filter(g->g.lifecycle()==PurposeState.Lifecycle.ACTIVE&&g.independentEpisodes()>=2).limit(2)
                .map(g->Map.of("targetId",g.goal().targetId(),"kind",g.goal().kind().name(),"statedIntention",g.statedIntention().name(),
                        "observedProgress",g.observedProgress().name(),"status","TENTATIVE_NOT_A_COMMAND")).toList();
        if(options.isEmpty()&&goals.isEmpty())return Map.of();
        return Map.of("source","PLAYER_OPTED_IN_SYSTEM_SUPPORT_STATISTICS","owner",player.toString(),"supportOptions",options,"goals",goals,
                "scope","Player-shared support hints for this requested private consultation only. Not NPC eyewitness facts, fixed personality, skill, permission or a requirement to mention them.");
    }
    private static Map<String,Object> withTargetNames(Map<String,Object> raw) {
        if(raw.isEmpty())return raw;
        var context=new LinkedHashMap<>(raw);var named=new ArrayList<Map<String,Object>>();
        for(Object item:(List<?>)raw.get("goals")) {
            var goal=new LinkedHashMap<String,Object>();((Map<?,?>)item).forEach((key,value)->goal.put((String)key,value));
            var id=net.minecraft.resources.ResourceLocation.tryParse((String)goal.get("targetId"));
            if(id!=null)com.sande.mythictrpg.data.god.GodDefinitionManager.INSTANCE.find(id)
                    .ifPresent(definition->goal.put("targetName",SupportCharacterNames.preferred(definition)));
            named.add(Map.copyOf(goal));
        }
        context.put("goals",List.copyOf(named));return Map.copyOf(context);
    }
    public static PlayerUnderstanding.View card(PlayerUnderstanding.View base,boolean allowed) {
        var cards=new ArrayList<>(base.cards());cards.add(new PlayerUnderstanding.Card(KEY,"NPC 진행 도움용 통계 공유",allowed?"허용":"차단 (기본값)",
                "허용해도 평상시 대화에는 전달하지 않습니다. 혼자 한 NPC에게 개인화 도움을 요청했을 때에만 요약한 경향·목표를 공유합니다. 원문·좌표·타인 기록은 공유하지 않습니다.",List.of(),false));
        return new PlayerUnderstanding.View(base.active(),base.notice(),cards);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){REVISIONS.remove(e.getServer());REVOKED.remove(e.getServer());}
}
