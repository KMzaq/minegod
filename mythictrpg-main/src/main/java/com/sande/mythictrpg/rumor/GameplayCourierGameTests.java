package com.sande.mythictrpg.rumor;

import com.google.gson.Gson;
import com.sande.mythictrpg.ai.memorycontract.MemoryFoundationSettings;
import com.sande.mythictrpg.content.ContentSystemsGameTests;
import com.sande.mythictrpg.story.definition.*;
import com.sande.mythictrpg.story.definition.StoryDefinitions.Effect;
import com.sande.mythictrpg.story.runtime.StoryEventService;
import com.sande.mythictrpg.story.state.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.*;
import java.nio.file.*;
import java.util.*;
import static com.sande.mythictrpg.content.ContentSystemsGameTests.check;

/** Real game lifecycle, connected player, witnessed entity and Story execution; no model or runtime injection. */
@GameTestHolder("mythictrpg_content_systems")
@PrefixGameTestTemplate(false)
public final class GameplayCourierGameTests {
    @GameTest(templateNamespace="mythictrpg_content_systems",template="empty",timeoutTicks=100)
    public static void ordinaryWitnessReputationRecoveryAndTestIsolation(GameTestHelper h) throws Exception {
        var server=h.getLevel().getServer();
        var memoryPath=FMLPaths.CONFIGDIR.get().resolve("mythictrpg/ai-memory-foundation.json");
        var courierPath=server.getServerDirectory().resolve("config/mythictrpg/rumor-courier.json");
        var reputationPath=server.getServerDirectory().resolve("config/mythictrpg/reputation-judgement.json");
        for(var path:List.of(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),
                memoryPath,courierPath,reputationPath))
            check(path.toAbsolutePath().normalize().toString().replace('\\','/').contains("/build/"),
                    "courier lifecycle tests require an isolated build fixture: "+path);
        var previous=new LinkedHashMap<Path,byte[]>();
        for(var path:List.of(memoryPath,courierPath,reputationPath))
            previous.put(path,Files.exists(path)?Files.readAllBytes(path):null);
        var before=StoryDefinitionManager.INSTANCE.snapshot();
        var channels=new ArrayList<EmbeddedChannel>();
        var player=ContentSystemsGameTests.connect(h,"CourierPlay",channels);
        var bird=h.spawn(EntityType.PARROT,new BlockPos(2,4,2));
        var god=ResourceLocation.parse("mythictrpg:demeter");
        var rule=new CourierSettings.Rule("test:gameplay_courier","test:gameplay_result",
                CourierSettings.Source.GAME_EVENT,h.getLevel().dimension().location().toString(),24,10,200,
                Map.of(god.toString(),CourierSettings.Reception.CAUTIOUS),CourierSettings.Publication.CANDIDATE,"","");
        var courierSettings=new CourierSettings(1,true,false,false,8,Set.of("minecraft:parrot"),List.of(rule));
        var policy=new ReputationSettings.Rule("test:gameplay_repair",rule.id(),god.toString(),-20,-1000,1000);
        try {
            Files.createDirectories(courierPath.getParent());
            Files.writeString(courierPath,new Gson().toJson(courierSettings));
            Files.writeString(reputationPath,new Gson().toJson(new ReputationSettings(1,true,50,50,List.of(policy))));
            memoryMode(memoryPath,"PERSONAL");
            CourierRumorService.started(new ServerStartedEvent(server));
            ReputationService.started(new ServerStartedEvent(server));
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.setPos(bird.getX()+2,bird.getY(),bird.getZ());
            check(CourierRumorService.bindExisting(player,bird),"ordinary service could not bind actual authored courier");
            UUID source=UUID.randomUUID();
            var ordinary=CourierRumorService.captureApproved(player,bird,source,1,ResourceLocation.parse(rule.eventType()),
                    rule.source(),player.blockPosition(),"게임이 확인한 공개 사건",Set.of(player.getUUID()),Set.of(player.getUUID()));
            check(ordinary.size()==1,"PERSONAL actual distance/line-of-sight witness failed");
            var input=ordinary.getFirst();
            var rumors=RumorSavedData.get(server);
            check(rumors.access(server,l->l.evidence(input.rootId()).memoryMode().equals("PERSONAL")),"birth mode not stored");

            CourierRumorService.stopped(new ServerStoppedEvent(server));
            memoryMode(memoryPath,"RUMOR_TEST");
            CourierRumorService.started(new ServerStartedEvent(server));
            check(CourierRumorService.pendingCandidates(server,32).stream().noneMatch(c->c.rootId().equals(input.rootId()))
                    &&!CourierRumorService.publishCandidate(server,candidate(input)),"test lifecycle consumed pending gameplay root");
            UUID trialSource=UUID.randomUUID();
            var trial=CourierRumorService.captureApproved(player,bird,trialSource,1,ResourceLocation.parse(rule.eventType()),
                    rule.source(),player.blockPosition(),"시험에서 확인한 공개 사건",Set.of(player.getUUID()),Set.of(player.getUUID()));
            check(trial.size()==1&&!trial.getFirst().rootId().equals(input.rootId()),"test root collided with ordinary event/cooldown");
            check(CourierRumorService.publishCandidate(server,candidate(trial.getFirst())),"test fixture publication failed");
            CourierRumorService.tick(new ServerTickEvent.Post(()->true,server));
            var testDecision=new ReputationLedger.Decision(UUID.randomUUID(),rumors.worldId(),player.getUUID(),god.toString(),
                    trialSource,trial.getFirst().rootId(),1,policy.id(),policy.fingerprint(),0,ReputationLedger.Outcome.ACCEPTED,
                    ReputationLedger.DirectImpact.NOT_APPLIED,new ReputationLedger.Approval(UUID.randomUUID(),
                    ReputationLedger.ApprovalKind.GAME_RESULT,"test:verified_trial_result"));
            check(ReputationService.applyApproved(server,testDecision)==ReputationLedger.Result.APPLIED,"test fixture assessment failed");

            CourierRumorService.stopped(new ServerStoppedEvent(server));
            memoryMode(memoryPath,"PERSONAL");
            CourierRumorService.started(new ServerStartedEvent(server));
            check(CourierRumorService.heardOne(server,player.getUUID(),god.toString(),trial.getFirst().rootId(),
                    Set.of(player.getUUID())).isEmpty(),"test receipt leaked after ordinary lifecycle restart");
            check(CourierRumorService.publishCandidate(server,candidate(input)),"ordinary publication failed after restart");
            CourierRumorService.tick(new ServerTickEvent.Post(()->true,server));
            var heard=RoomRumorAccess.heard(server,player.getUUID(),god.toString(),Set.of(player.getUUID()),Set.of(god.toString()),false);
            check(heard.size()==1&&heard.getFirst().rootId().equals(input.rootId()),"ordinary room did not receive own scoped rumor");
            check(RoomRumorAccess.heard(server,player.getUUID(),god.toString(),Set.of(player.getUUID()),Set.of(god.toString()),true).isEmpty(),
                    "public room leaked private rumor");
            var decision=new ReputationLedger.Decision(UUID.randomUUID(),rumors.worldId(),player.getUUID(),god.toString(),
                    source,input.rootId(),1,policy.id(),policy.fingerprint(),0,ReputationLedger.Outcome.ACCEPTED,
                    ReputationLedger.DirectImpact.NOT_APPLIED,new ReputationLedger.Approval(UUID.randomUUID(),
                    ReputationLedger.ApprovalKind.GAME_RESULT,"test:verified_game_result"));
            check(ReputationService.applyApproved(server,decision)==ReputationLedger.Result.APPLIED,"ordinary actual receipt assessment failed");
            check(ReputationService.judgement(server,player.getUUID(),god,Set.of(player.getUUID())).view().modifier()==-20,
                    "approved ordinary assessment did not enter judgement");
            var eventId=ContentSystemsGameTests.id("ordinary_recovery");
            var event=ContentSystemsGameTests.event(eventId,false,List.of(new Effect.RecoverReputation(
                    ContentSystemsGameTests.id("repair"),god,ResourceLocation.parse(policy.id()),ResourceLocation.parse("test:restitution"))));
            ContentSystemsGameTests.swapStory(new StoryDefinitionSnapshot(before.actors(),before.locations(),before.facts(),
                    before.coverStories(),before.disclosurePolicies(),Map.of(eventId,event),Map.of(),Map.of(),Map.of(),before.generation()+1));
            var result=StoryEventService.INSTANCE.triggerRegistered(server,eventId,Optional.of(player));
            check(result.startedInstanceIds().size()==1&&StoryRuntimeState.get(server).eventInstances().get(result.startedInstanceIds().getFirst())
                    .status()==StoryRuntimeModels.EventStatus.RESOLVED,"ordinary Story recovery did not resolve");
            var recovered=RoomRumorAccess.heard(server,player.getUUID(),god.toString(),Set.of(player.getUUID()),Set.of(god.toString()),false);
            check(recovered.size()==1&&recovered.getFirst().assessment().equals("RECOVERED")
                    &&ReputationService.judgement(server,player.getUUID(),god,Set.of(player.getUUID())).view().modifier()==0,
                    "Story recovery was not visible to actual ordinary dialogue projection");
            var restored=RumorSavedData.load(rumors.save(new CompoundTag(),server.registryAccess()),server.registryAccess());
            check(restored.ready()&&restored.snapshot().evidence().stream().filter(e->e.id().equals(input.rootId()))
                    .allMatch(e->e.memoryMode().equals("PERSONAL")),"ordinary evidence birth mode did not survive save/load");
            var reputation=ReputationSavedData.get(server,rumors.worldId());
            check(reputation.snapshot().entries().stream().anyMatch(e->e.decision().sourceId().equals(trialSource)
                    &&e.decision().outcome()==ReputationLedger.Outcome.ACCEPTED),"ordinary recovery modified test-only assessment");
            check(ReputationSavedData.load(reputation.save(new CompoundTag(),server.registryAccess()),server.registryAccess())
                    .snapshot().equals(reputation.snapshot()),"ordinary recovery receipt did not survive save/load");
            memoryMode(memoryPath,"OFF");
            check(CourierRumorService.binding(server,player.getUUID()).isEmpty()
                    &&RoomRumorAccess.heard(server,player.getUUID(),god.toString(),Set.of(player.getUUID()),Set.of(god.toString()),false).isEmpty(),
                    "memory OFF allowed active courier access or rumor read");
            h.succeed();
        } finally {
            CourierRumorService.stopped(new ServerStoppedEvent(server));ReputationService.stopped(new ServerStoppedEvent(server));
            for(var entry:previous.entrySet()) {
                if(entry.getValue()==null)Files.deleteIfExists(entry.getKey());else Files.write(entry.getKey(),entry.getValue());
            }
            MemoryFoundationSettings.reset();CourierRumorService.started(new ServerStartedEvent(server));ReputationService.started(new ServerStartedEvent(server));
            ContentSystemsGameTests.swapStory(before);bird.discard();ContentSystemsGameTests.disconnect(player,channels);
        }
    }
    private static CourierEngine.Candidate candidate(CourierEngine.CandidateInput input) {
        return new CourierEngine.Candidate(input,input.excerpt(),"확인된 발언에 관한 전언","");
    }
    private static void memoryMode(Path path,String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path,"{\"schemaVersion\":1,\"mode\":\""+value+"\"}");
        MemoryFoundationSettings.reset();
    }
}
