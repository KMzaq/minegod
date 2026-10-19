package com.sande.mythictrpg.raid;

import com.sande.mythictrpg.gameplay.ledger.ActionRecord;
import com.sande.mythictrpg.gameplay.ledger.server.ActionLedgerService;
import com.sande.mythictrpg.understanding.RaidChoiceEvidence;
import net.minecraft.server.MinecraftServer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Game-only producer. No network/LLM entry point, no effect on encounter progress when recording fails. */
final class RaidChoiceCapture {
    private RaidChoiceCapture() { }
    static void record(MinecraftServer server, RaidState.Attempt attempt, UUID playerId, RaidChoiceEvidence choice) {
        if (!server.isSameThread() || attempt.status()!=RaidState.Status.ACTIVE || !attempt.playerIds().contains(playerId)) return;
        var runtime=ActionLedgerService.current(server);
        if(runtime==null||runtime.ledger()==null||attempt.arena==null)return;
        var player=server.getPlayerList().getPlayer(playerId);
        var pos=player==null?null:player.blockPosition();
        var dimension=player==null?attempt.arena.dimension():player.serverLevel().dimension().location();
        UUID occurrence=UUID.nameUUIDFromBytes((attempt.id()+"/"+playerId+"/"+choice.decisionId()+"/"+choice.kind()).getBytes(StandardCharsets.UTF_8));
        var type=ActionRecord.Type.RAID_CHOICE;
        var draft=new ActionRecord.Draft(occurrence,runtime.captureSession(),runtime.nextCaptureOrder(),
                "mythictrpg:detail/raid_choice",1,playerId,new ActionRecord.Subject("BATTLE",attempt.definition().id().toString(),null),
                System.currentTimeMillis(),server.overworld().getGameTime(),server.overworld().getDayTime(),dimension.toString(),
                pos==null?null:new ActionRecord.Position(pos.getX(),pos.getY(),pos.getZ()),type,"COMPLETED",choice.payload(),
                "mythictrpg:admin_only_unprojected",new ActionRecord.Details(null,pos==null?"OFFLINE_UNKNOWN":"AT_TRANSITION",attempt.id(),Set.of(playerId)));
        runtime.ledger().submitTransition(draft); // Preserve first capture on exact final-outcome retry; gameplay never waits.
    }
}
