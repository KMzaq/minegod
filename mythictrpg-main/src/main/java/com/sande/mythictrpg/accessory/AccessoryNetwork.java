package com.sande.mythictrpg.accessory;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.skills.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;
import java.util.function.Consumer;

@EventBusSubscriber(modid=MythicTrpg.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
public final class AccessoryNetwork{
    private record Session(UUID token,long sequence){}
    private static final Map<MinecraftServer,Map<UUID,Session>> SESSIONS=new IdentityHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,Long>> REQUEST_TICKS=new IdentityHashMap<>();
    private static Consumer<AccessoryPage> receiver=p->{};
    private AccessoryNetwork(){}
    public static void receiver(Consumer<AccessoryPage> value){receiver=Objects.requireNonNull(value);}
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event){
        var r=event.registrar("accessories-2");r.playToClient(AccessoryPage.TYPE,AccessoryPage.CODEC,(p,c)->c.enqueueWork(()->receiver.accept(p)));
        r.playToServer(AccessoryRequest.TYPE,AccessoryRequest.CODEC,(p,c)->c.enqueueWork(()->{if(c.player()instanceof ServerPlayer player)receive(player,p);}));
    }
    public static void open(ServerPlayer player,int page,String notice){
        if(!live(player))return;var sessions=SESSIONS.computeIfAbsent(player.server,s->new HashMap<>());
        var session=new Session(UUID.randomUUID(),0);sessions.put(player.getUUID(),session);send(player,session.token(),true,page,notice);
    }
    public static void receive(ServerPlayer player,AccessoryRequest request){
        if(!live(player))return;
        if(request.action()==AccessoryRequest.Action.JUMP){AccessoryRuntime.jump(player);return;}
        long tick=player.server.overworld().getGameTime();var requests=REQUEST_TICKS.computeIfAbsent(player.server,s->new HashMap<>());
        Long previous=requests.put(player.getUUID(),tick);if(previous!=null&&previous==tick)return;
        if(request.action()==AccessoryRequest.Action.OPEN){open(player,0,"");return;}
        var sessions=SESSIONS.get(player.server);var session=sessions==null?null:sessions.get(player.getUUID());
        if(session==null||!session.token().equals(request.token())||request.sequence()<=session.sequence())return;
        sessions.put(player.getUUID(),new Session(session.token(),request.sequence()));
        if(request.action()==AccessoryRequest.Action.CLOSE){sessions.remove(player.getUUID());return;}
        boolean okay=true;
        try{switch(request.action()){
            case EQUIP->okay=AccessoryInventory.legacyEquip(player,request.slot(),UUID.fromString(request.target()));
            case UNEQUIP->okay=AccessoryInventory.legacyEquip(player,request.slot(),null);
            case LINK->okay=AccessoryRuntime.linkTarget(player,request.target().isEmpty()?null:ResourceLocation.parse(request.target()));
            case REISSUE->okay=RewardAccessoryItems.restore(player,UUID.fromString(request.target()));
            default->{}
        }}catch(RuntimeException invalid){okay=false;}
        send(player,session.token(),false,request.page(),!okay?"요청을 적용할 수 없습니다. 소유권과 인벤토리 공간을 확인하세요.":request.action()==AccessoryRequest.Action.REISSUE?"장신구를 재지급했습니다. 사용 기록은 유지됩니다.":"");
    }
    private static boolean live(ServerPlayer player){return player.server.isSameThread()&&player.server.getPlayerList().getPlayer(player.getUUID())==player&&player.isAlive()&&!player.isSpectator();}
    private static void send(ServerPlayer player,UUID token,boolean open,int requested,String notice){
        var state=AccessoryState.get(player.server);var owned=AccessoryRuntime.entitlements(player).stream().filter(e->AccessoryDefinitions.INSTANCE.find(e.definitionId()).isPresent()).toList();
        int page=Math.max(0,Math.min(requested,Math.max(0,(owned.size()-1)/8)));List<AccessoryPage.Slot> slots=new ArrayList<>();
        for(int i=0;i<2;i++){
            var entitlement=state.equipped(player.getUUID(),i);var entry=owned.stream().filter(e->e.id().equals(entitlement)).findFirst().orElse(null);
            var d=entry==null?null:AccessoryDefinitions.INSTANCE.find(entry.definitionId()).orElse(null);
            slots.add(new AccessoryPage.Slot(entry==null?null:entry.id(),d==null?"비어 있음":d.name(),d==null?ItemStack.EMPTY:d.displayStack(player.registryAccess())));
        }
        List<AccessoryPage.Row> rows=new ArrayList<>();for(var e:owned.stream().skip(page*8L).limit(8).toList()){
            var d=AccessoryDefinitions.INSTANCE.find(e.definitionId()).orElseThrow();
            long now=System.currentTimeMillis();var effects=d.effects().stream().map(effect->label(effect,state.remainingMillis(player.getUUID(),e.id(),effect.kind(),now))).toList();rows.add(new AccessoryPage.Row(e.id(),d.name(),d.displayStack(player.registryAccess()),d.fixedPower(),effects));
        }
        var targets=LearnedSkillRuntime.ownedSkills(player).stream().limit(128).map(id->new AccessoryPage.Target(id,LearnedSkillDefinitions.INSTANCE.find(id).orElseThrow().name())).toList();
        String status=!state.isReady()||!AccessoryRewardBridge.isReady(player)?"장신구 획득·저장 자료를 확인할 수 없습니다.":notice;
        PacketDistributor.sendToPlayer(player,new AccessoryPage(token,open,page,owned.size(),slots,rows,AccessoryRuntime.linkTarget(player).map(Object::toString).orElse(""),targets,status));
    }
    private static String label(AccessoryEffect e,long remaining){
        String name=switch(e.kind()){
            case MAX_MANA->"최대 마나";case LINK_KNOT->"연계 쿨타임 감소";case CLEANSE_CC->"일반 CC 정화";case EMERGENCY_HEAL->"위기 회복";
            case MISS_REFUND->"빗나감 마나 환급";case LUCK->"행운";case DAMAGE_FLAT->"피해 증가";case SPEED_FLAT->"이동속도";
            case EXTRA_JUMPS->"공중 추가 점프";case DAMAGE_REDUCTION_FLAT->"받는 피해 감소";case HEALTH_REGEN_PER_SECOND->"초당 체력 회복";case GOLD_BONUS_FRACTION->"골드 추가 획득";
        };boolean percentage=switch(e.kind()){case LINK_KNOT,MISS_REFUND,EMERGENCY_HEAL,GOLD_BONUS_FRACTION->true;default->false;};
        return name+" +"+String.format(Locale.ROOT,"%.2f",percentage?e.amount()*100:e.amount())+(percentage?"%":"")
                +(e.cooldownMillis()>0?remaining>0?" · 남은 시간 "+(remaining+999)/1000+"초":" · 준비됨":"");
    }
    @EventBusSubscriber(modid=MythicTrpg.MOD_ID)public static final class Lifecycle{
        @SubscribeEvent public static void left(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity()instanceof ServerPlayer p){var m=SESSIONS.get(p.server);if(m!=null)m.remove(p.getUUID());var r=REQUEST_TICKS.get(p.server);if(r!=null)r.remove(p.getUUID());}}
        @SubscribeEvent public static void stopped(ServerStoppedEvent event){SESSIONS.remove(event.getServer());REQUEST_TICKS.remove(event.getServer());}
    }
}
