package com.sande.mythictrpg.understanding;

import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.ai.api.*;
import com.sande.mythictrpg.data.god.GodDefinitionManager;
import com.sande.mythictrpg.gameplay.ledger.*;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Game-issued, one-use admission. Models cannot choose actor/world/turn or write a global private profile. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class DialogueSupportCapture {
    private record Source(RoomDialogueEvent event,long expires,long analysisStart,ActionLedgerService.Runtime runtime,long definitionGeneration) { }
    private static final Map<MinecraftServer,LinkedHashMap<UUID,Source>> SOURCES=new IdentityHashMap<>();
    private DialogueSupportCapture() { }
    public static void published(MinecraftServer server,RoomDialogueEvent event) {
        if(!server.isSameThread()||!event.role().equals("PLAYER")||event.turnId().isEmpty()
                ||event.recordingScope().isTest()||!event.recordingScope().recordingAllowed())return;
        var p=server.getPlayerList().getPlayer(UUID.fromString(event.speakerId()));
        if(p==null||!MinecraftActivityCapture.enrolled(p))return;
        var runtime=ActionLedgerService.current(server);
        if(runtime==null||!Objects.equals(runtime.worldId(),event.worldId()))return;
        var map=SOURCES.computeIfAbsent(server,s->new LinkedHashMap<>());
        map.entrySet().removeIf(e->e.getValue().expires()<server.getTickCount());
        map.putIfAbsent(event.turnId().orElseThrow(),new Source(event,server.getTickCount()+1200,MinecraftActivityCapture.analysisStart(p),runtime,GodDefinitionManager.INSTANCE.generation()));
        while(map.size()>256)map.remove(map.keySet().iterator().next());
    }
    public static void classified(ServerPlayer player,RoomConversationEngine.Request request,SupportSignal.Kind style,
            String targetName,PurposeEvidence.Kind kind,PurposeEvidence.Signal signal,String fullQuote) {
        if(!player.server.isSameThread()||request.secondary()||request.readOnly()||!request.recording()
                ||!player.getUUID().equals(request.playerId())||!MinecraftActivityCapture.enrolled(player))return;
        var map=SOURCES.get(player.server);if(map==null)return;
        var source=map.get(request.turnId());
        if(source==null||!matches(source.event(),request)||source.expires()<player.server.getTickCount()
                ||source.analysisStart()!=MinecraftActivityCapture.analysisStart(player)
                ||source.runtime()!=ActionLedgerService.current(player.server)
                ||source.definitionGeneration()!=GodDefinitionManager.INSTANCE.generation())return;
        map.remove(request.turnId());
        if(style!=null&&style.name().startsWith("DIALOGUE_"))
            MinecraftActivityCapture.recordSupport(player,style,null,"플레이어 발화 분류 · "+source.event().messageId());
        if(targetName==null||targetName.isBlank()||kind==null||signal==null||!request.currentText().equals(fullQuote)
                ||fullQuote.length()>256||!fullQuote.contains(targetName))return;
        var matches=SupportCharacterNames.resolve(targetName);
        if(matches.size()!=1)return; // Ambiguous/unresolved names are not invented IDs.
        var r=ActionLedgerService.current(player.server);if(r==null||r.ledger()==null)return;
        var evidence=new CharacterGoalSignal(new PurposeEvidence.Goal(kind,matches.getFirst().toString()),signal,source.event().messageId(),fullQuote);
        var pos=player.blockPosition();
        UUID id=UUID.nameUUIDFromBytes((r.worldId()+"/character-signal/"+source.event().messageId()).getBytes(StandardCharsets.UTF_8));
        r.ledger().submit(new ActionRecord.Draft(id,r.captureSession(),r.nextCaptureOrder(),"mythictrpg:detail/player_character_signal",1,
                player.getUUID(),new ActionRecord.Subject("ACTIVITY",CharacterGoalSignal.SUBJECT,null),source.event().occurredAtUtc(),
                player.server.overworld().getGameTime(),player.level().getDayTime(),player.level().dimension().location().toString(),
                new ActionRecord.Position(pos.getX(),pos.getY(),pos.getZ()),ActionRecord.Type.PLAYER_CHARACTER_SIGNAL,"COMPLETED",
                evidence.payload(),"mythictrpg:admin_only_unprojected",new ActionRecord.Details(null,"AT_TRANSITION",null,Set.of(player.getUUID()))));
    }
    public static boolean matches(RoomDialogueEvent event,RoomConversationEngine.Request request) {
        return event.role().equals("PLAYER")&&!event.recordingScope().isTest()&&event.turnId().equals(Optional.of(request.turnId()))
                &&event.roomId().equals(request.roomId())&&event.revision()==request.revision()
                &&event.speakerId().equals(request.playerId().toString())&&event.text().equals(request.currentText())
                &&event.heardGodIds().contains(request.speakerGodId().toString());
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {SOURCES.remove(event.getServer());}
}
