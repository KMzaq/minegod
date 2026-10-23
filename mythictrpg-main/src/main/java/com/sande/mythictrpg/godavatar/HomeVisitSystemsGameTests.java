package com.sande.mythictrpg.godavatar;

import com.sande.mythictrpg.content.*;
import com.sande.mythictrpg.data.world.MythicWorldState;
import com.sande.mythictrpg.ai.room.RecordingScope;
import com.sande.mythictrpg.ai.room.RoomType;
import com.sande.mythictrpg.ai.api.RoomConversationEngine;
import com.sande.mythictrpg.ai.api.RoomConversationEngineRouter;
import com.sande.mythictrpg.ai.server.ConversationRooms;
import com.sande.mythictrpg.quest.structure.PlayerConstructionState;
import com.sande.mythictrpg.story.definition.*;
import com.sande.mythictrpg.story.definition.StoryDefinitions.*;
import com.sande.mythictrpg.story.signal.StorySignalTypes;
import com.sande.mythictrpg.story.state.StoryRuntimeState;
import com.sande.mythictrpg.story.api.StoryStateView.StoryScopeKey;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.sande.mythictrpg.content.ContentSystemsGameTests.*;

@GameTestHolder("mythictrpg_content_systems")
@PrefixGameTestTemplate(false)
public final class HomeVisitSystemsGameTests {
    @GameTest(templateNamespace="mythictrpg_content_systems",template="empty",batch="content_home_visit",timeoutTicks=500)
    public static void consentRevalidationAndActualArrivalPublishOwnedStory(GameTestHelper h) throws Exception {
        var f=new GodHomeVisitGameTests.Fixture(h); var before=StoryDefinitionManager.INSTANCE.snapshot();
        var god=ResourceLocation.parse("mythictrpg:demeter");var fact=id("arrival_fact");var eventId=id("arrival_event");
        var router=RoomConversationEngineRouter.INSTANCE;
        var engineField=RoomConversationEngineRouter.class.getDeclaredField("engine");
        var installedField=RoomConversationEngineRouter.class.getDeclaredField("installed");
        engineField.setAccessible(true);installedField.setAccessible(true);
        var previousEngine=engineField.get(router);boolean previousInstalled=installedField.getBoolean(router);
        boolean[] closed={false};
        Runnable close=()->{
            if(closed[0])return;closed[0]=true;
            try {
                for(var room:ConversationRooms.INSTANCE.memberships(f.player))
                    ConversationRooms.INSTANCE.leave(f.player,room,"FIXTURE_END");
                swapStory(before);
            } catch(Exception failure){throw new IllegalStateException(failure);}
            finally {
                try {engineField.set(router,previousEngine);installedField.setBoolean(router,previousInstalled);}
                catch(Exception failure){throw new IllegalStateException(failure);}
                finally {f.close();}
            }
        };
        h.runAtTickTime(499,close);
        try {
            // Room membership is the scope boundary under test; never call an installed real model.
            engineField.set(router,new RoomConversationEngine() {
                @Override public CompletableFuture<Result> respond(Request request) {
                    return CompletableFuture.completedFuture(Result.failed(request,"HOME_VISIT_SCOPE_FIXTURE"));
                }
                @Override public CompletableFuture<SplitResult> chooseSplit(SplitRequest request) {
                    return CompletableFuture.failedFuture(new IllegalStateException("HOME_VISIT_SCOPE_FIXTURE"));
                }
            });
            installedField.setBoolean(router,true);
            f.prepare(); MythicWorldState.get(f.player.server).setQuestProgress(ResourceLocation.parse("mythictrpg:visit_fixture"),30);
            f.player.setGameMode(GameType.SURVIVAL);
            f.player.setPos(Vec3.atBottomCenterOf(f.feet.offset(6,0,-1)));
            check(command(f,"mythstructure pos1")==1,"non-operator could not set home first corner");
            f.player.setPos(Vec3.atBottomCenterOf(f.feet.offset(9,0,2)));
            check(command(f,"mythstructure pos2")==1,"non-operator could not set home second corner");
            check(command(f,"mythstructure register NativeHome")==1,"non-operator could not register a home");
            f.destination=PlayerConstructionState.get(f.player.server).findByName(f.player.getUUID(),"NativeHome").orElseThrow();
            f.structures.add(f.destination);
            f.player.setPos(Vec3.atBottomCenterOf(f.feet.offset(-2,0,0)));
            var template=event(eventId,false,List.of(new Effect.SetFact(id("arrived"),fact,ScopeSelector.TRIGGER_PLAYER,true)));
            var event=new EventDefinition(template.id(),template.narrativeRole(),template.scope(),
                    List.of(new TriggerDefinition(StorySignalTypes.HOME_VISIT_ARRIVED,Optional.of(god),TriggerMode.IMMEDIATE,0)),
                    template.prerequisites(),template.actors(),template.repeatPolicy(),template.resolutionPolicy(),false,
                    template.outcomes(),template.choicePolicy(),template.failurePolicy(),template.fingerprint());
            var facts=new HashMap<>(before.facts());facts.put(fact,new FactDefinition(fact,Set.of(ScopeType.PLAYER),false,"arrival",List.of(new FactKnowledgeLevel(1,"arrival")),Set.of()));
            var events=new HashMap<>(before.events());events.put(eventId,event);
            var index=new HashMap<>(before.eventsBySignal());index.put(new StoryDefinitionSnapshot.SignalKey(StorySignalTypes.HOME_VISIT_ARRIVED,Optional.of(god)),List.of(event));
            swapStory(new StoryDefinitionSnapshot(before.actors(),before.locations(),facts,before.coverStories(),before.disclosurePolicies(),events,before.hooks(),before.presentations(),index,before.generation()+1));
            check(command(f,"mythvisits off")==1,"non-operator could not disable home visits");
            check(!f.service.canRequest(f.player,god,false),"blocked owner accepted a new visit");
            check(command(f,"mythvisits on")==1,"non-operator could not enable home visits");
            f.request();
            check(f.request.candidates().stream().anyMatch(c->c.structureId().equals(f.destination.id())),"registered home did not enter actual visit candidates");
            check(command(f,"mythvisits off")==1,"withdrawal command failed");f.complete(f.destination.id());
            check(!f.avatar.busyForVisit(),"late AI ignored withdrawn permission");
            check(command(f,"mythvisits on")==1,"restore consent failed");
            f.player.setGameMode(GameType.SPECTATOR);
            check(!f.service.canRequest(f.player,god,false),"spectator owner accepted a visit");
            f.player.setGameMode(GameType.SURVIVAL);f.respawn();f.request();f.complete(f.destination.id());
            check(f.avatar.homeVisitStatus().equals("TRAVELLING"),"ordinary registered home did not start actual travel");
            var testRoom=ConversationRooms.INSTANCE.create(f.player,RoomType.PRIVATE,List.of(god),RecordingScope.TEST_EPHEMERAL);
            check(!f.service.travelCurrent(f.avatar),"in-flight visit crossed into a test conversation");
            check(!StoryRuntimeState.get(f.player.server).fact(StoryScopeKey.player(f.player.getUUID()),fact),"accepting a visit pretended arrival");
            h.startSequence().thenWaitUntil(()->h.assertTrue(f.avatar.homeVisitStatus().equals("CANCELLED"),"test-scope transition did not cancel actual travel"))
                    .thenExecute(()->{
                        check(!StoryRuntimeState.get(f.player.server).fact(StoryScopeKey.player(f.player.getUUID()),fact),"cancelled visit published arrival");
                        ConversationRooms.INSTANCE.leave(f.player,testRoom,"FIXTURE_END");
                        f.respawn();f.request();f.complete(f.destination.id());
                        check(command(f,"mythvisits off")==1,"in-flight withdrawal command failed");
                    })
                    .thenWaitUntil(()->h.assertTrue(f.avatar.homeVisitStatus().equals("CANCELLED"),"withdrawn consent did not cancel actual travel"))
                    .thenExecute(()->{
                        check(!StoryRuntimeState.get(f.player.server).fact(StoryScopeKey.player(f.player.getUUID()),fact),"withdrawn visit published arrival");
                        check(command(f,"mythvisits on")==1,"final consent command failed");
                        f.respawn();f.request();f.complete(f.destination.id());
                    })
                    .thenWaitUntil(()->h.assertTrue(f.avatar.homeVisitStatus().equals("ARRIVED"),"waiting for real home arrival"))
                    .thenExecute(()->{
                        try {check(StoryRuntimeState.get(f.player.server).fact(StoryScopeKey.player(f.player.getUUID()),fact),"actual arrival did not trigger owner story");}
                        finally {close.run();}
                    }).thenSucceed();
        } catch(Throwable failure) {close.run();throw failure;}
    }
    private static int command(GodHomeVisitGameTests.Fixture fixture,String command) {
        try {
            return fixture.player.server.getCommands().getDispatcher().execute(command,
                    fixture.player.createCommandSourceStack().withPermission(0));
        } catch(com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
            throw new IllegalStateException("Native player command failed: "+command,invalid);
        }
    }
}
