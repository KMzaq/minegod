package com.sande.mythictrpg.ai.room;

import com.sande.mythictrpg.ai.api.RoomDialogueEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual fan-out/recording port, no Minecraft boot or LLM. */
public final class RoomDialoguePublisherTest {
    private static int checks;
    public static void main(String[] args) {
        UUID player=UUID.randomUUID(), peer=UUID.randomUUID(), spectator=UUID.randomUUID();
        var participants=Map.of(player,"author",peer,"peer");
        var event=event(RoomType.PRIVATE,RecordingScope.TEST_RECORDING,participants,player,"PLAYER","원문\n  공백 그대로 🌾");
        var observed=new ArrayList<RoomDialogueEvent>();var failures=new ArrayList<RuntimeException>();
        var calls=new ArrayList<UUID>();
        var result=RoomDialoguePublisher.publish(event,List.of(player,peer,player),id->{calls.add(id);
            return new RoomDialogueEvent.Delivery(participants.get(id),true,id.equals(peer)?3:0);
        },observed::add,failures::add);
        check(calls.size()==2&&observed.size()==1,"one logical event, distinct recipients only");
        check(result.messageId().equals(event.messageId())&&result.text().equals(event.text()),"stable ID and exact body");
        check(result.deliveries().get(peer).hudPagesDispatched()==3,"HUD pages do not create extra speech");
        check(result.turnId().isEmpty(),"non-LLM accepted input has no fabricated turn");
        check(event.deliveries().isEmpty(),"draft remains immutable");
        expect(()->result.deliveries().clear(),"immutable dispatch snapshot");
        int before=calls.size();
        expect(()->RoomDialoguePublisher.publish(event,List.of(player,spectator),id->{calls.add(id);return null;},observed::add,failures::add),"private outsider rejected");
        check(calls.size()==before,"privacy checked before any send");
        var partial=RoomDialoguePublisher.publish(event,List.of(player,peer),id->{
            if(id.equals(player))throw new IllegalStateException("transport");
            return new RoomDialogueEvent.Delivery("peer",false,1);
        },observed::add,failures::add);
        check(partial.deliveries().size()==1&&partial.deliveries().containsKey(peer),"failed send absent; later recipient still gets HUD");
        check(failures.size()==1,"dispatch failure diagnosed");
        var hidden=RoomDialoguePublisher.publish(event,List.of(player,peer),id->null,observed::add,failures::add);
        check(hidden.deliveries().isEmpty(),"hidden/offline is not a receipt");
        check(observed.contains(hidden),"accepted input can be recorded without inventing recipients");
        var off=event(RoomType.PRIVATE,RecordingScope.TEST_EPHEMERAL,participants,player,"PLAYER","OFF_SECRET");
        int stored=observed.size();var dispatched=new AtomicInteger();
        RoomDialoguePublisher.publish(off,List.of(player),id->{dispatched.incrementAndGet();return new RoomDialogueEvent.Delivery("author",true,0);},observed::add,failures::add);
        check(observed.size()==stored&&dispatched.get()==1,"off still speaks, never calls recorder");
        var publicEvent=event(RoomType.PUBLIC_MOBILE,RecordingScope.STANDARD,participants,player,"NPC","첫 조우 대사");
        var pub=RoomDialoguePublisher.publish(publicEvent,List.of(player,spectator),id->new RoomDialogueEvent.Delivery("viewer",true,0),observed::add,failures::add);
        check(pub.deliveries().containsKey(spectator)&&!pub.participantNames().containsKey(spectator),"public viewer is not a participant");
        check(pub.turnId().isEmpty()&&observed.contains(pub),"initial authored speech reaches common recorder");
        var survived=RoomDialoguePublisher.publish(publicEvent,List.of(player,peer),id->new RoomDialogueEvent.Delivery("viewer",true,0),e->{throw new IllegalStateException("disk queue full");},failures::add);
        check(survived.deliveries().size()==2&&failures.size()==2,"recorder failure cannot undo game delivery");
        expect(()->new RoomDialogueEvent.Delivery("hidden",false,0),"no false success receipt");
        expect(()->new RoomDialogueEvent(UUID.randomUUID(),UUID.randomUUID(),1,Optional.empty(),RoomType.PRIVATE,
                RecordingScope.STANDARD,"NPC","unknown:god","text",Set.of("mythictrpg:fortuna"),participants,Map.of(),1),"unknown god cannot author");
        check(!event.messageId().equals(event(RoomType.PRIVATE,RecordingScope.TEST_RECORDING,participants,player,"PLAYER",event.text()).messageId()),"same words spoken again are a new event");
        System.out.println("RoomDialoguePublisherTest: PASS ("+checks+" checks; no server/LLM)");
    }
    private static RoomDialogueEvent event(RoomType type,RecordingScope scope,Map<UUID,String> players,UUID actor,String role,String text) {
        return new RoomDialogueEvent(UUID.randomUUID(),UUID.randomUUID(),1,Optional.empty(),type,scope,role,
                role.equals("PLAYER")?actor.toString():"mythictrpg:fortuna",text,Set.of("mythictrpg:fortuna"),players,Map.of(),1);
    }
    private static void check(boolean ok,String what){checks++;if(!ok)throw new AssertionError(what);}
    private static void expect(Runnable run,String what){try{run.run();throw new AssertionError(what);}catch(IllegalArgumentException|UnsupportedOperationException expected){checks++;}}
}
