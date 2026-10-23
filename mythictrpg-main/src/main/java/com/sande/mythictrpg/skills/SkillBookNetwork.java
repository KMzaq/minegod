package com.sande.mythictrpg.skills;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;
import java.util.function.Consumer;

@EventBusSubscriber(modid=MythicTrpg.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
public final class SkillBookNetwork {
    private static Consumer<SkillBookPayload> receiver=ignored->{};
    private static final Map<ServerPlayer,Integer> LAST=new WeakHashMap<>();
    private SkillBookNetwork() { }
    public static void receiver(Consumer<SkillBookPayload> value){receiver=Objects.requireNonNull(value);}
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event){
        var r=event.registrar("learned-skills-1");
        r.playToClient(SkillBookPayload.TYPE,SkillBookPayload.CODEC,(p,c)->c.enqueueWork(()->receiver.accept(p)));
        r.playToServer(SkillBookRequest.TYPE,SkillBookRequest.CODEC,(p,c)->c.enqueueWork(()->{if(c.player()instanceof ServerPlayer player)receive(player,p);}));
    }
    public static void receive(ServerPlayer p,SkillBookRequest request){
        if(!p.server.isSameThread()||p.server.getPlayerList().getPlayer(p.getUUID())!=p)return;
        int tick=p.server.getTickCount();Integer before=LAST.put(p,tick);if(before!=null&&before==tick)return;
        switch(request.action()){
            case SELECT->LearnedSkillRuntime.select(p,ResourceLocation.parse(request.skill()));
            case CAST->LearnedSkillRuntime.feedback(p,LearnedSkillRuntime.cast(p,ResourceLocation.parse(request.skill())));
            case USE_SELECTED->LearnedSkillRuntime.useSelected(p);
            default->{ }
        }
        send(p,request.page(),request.action()==SkillBookRequest.Action.OPEN);
    }
    public static void send(ServerPlayer p,int requestedPage,boolean open){
        var ids=LearnedSkillRuntime.ownedSkills(p);int pages=Math.max(1,(ids.size()+SkillBookPayload.PAGE_SIZE-1)/SkillBookPayload.PAGE_SIZE);
        int page=Math.clamp(requestedPage,0,pages-1);
        var rows=ids.stream().skip((long)page*SkillBookPayload.PAGE_SIZE).limit(SkillBookPayload.PAGE_SIZE).map(id->{
            var d=LearnedSkillDefinitions.INSTANCE.find(id).orElseThrow();return new SkillBookPayload.Row(id,d.name(),d.manaCost(),d.cooldownTicks(),
                    (int)Math.min(Integer.MAX_VALUE,LearnedSkillRuntime.remainingTicks(p,id)));}).toList();
        PacketDistributor.sendToPlayer(p,new SkillBookPayload(open,page,pages,SkillMana.mana(p),SkillMana.maximumMana(p),
                LearnedSkillRuntime.selected(p).map(Object::toString).orElse(""),rows));
    }
}
