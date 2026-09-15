package com.sande.mythictrpg.rumor;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder(MythicTrpg.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RumorPersistenceGameTests {
    @GameTest(templateNamespace="minecraft",template="bastion/mobs/empty")
    public static void rumorSavedDataRoundTripAndUnknownSchemaPreserveOriginal(GameTestHelper helper) {
        var server=helper.getLevel().getServer(); var data=new RumorSavedData();
        String god="mythictrpg:demeter";
        UUID player=UUID.randomUUID(),bird=UUID.randomUUID(),event=UUID.randomUUID();
        data.access(server,ledger -> {
            ledger.bindCourier(player,bird);
            ledger.observe(event,player,bird,Set.of(player),"관측 기록",Set.of(god),Set.of(player));
            ledger.publish(event,"확정 사실이 아닌 소문","수식어");
            return ledger.deliver(ledger.pending().getFirst());
        });
        var stored=data.save(new CompoundTag(),server.registryAccess());
        var loaded=RumorSavedData.load(stored,server.registryAccess());
        helper.assertTrue(loaded.ready(),"valid snapshot quarantined");
        helper.assertValueEqual(loaded.worldId(),data.worldId(),"world id changed");
        helper.assertValueEqual(loaded.heard(server,player,god,Set.of(player)).size(),1,"receipt lost");
        var damaged=stored.copy(); damaged.putInt("dataVersion",99);
        var rejected=RumorSavedData.load(damaged,server.registryAccess());
        helper.assertTrue(!rejected.ready(),"unknown schema accepted");
        helper.assertValueEqual(rejected.save(new CompoundTag(),server.registryAccess()),damaged,"unknown data overwritten");
        helper.succeed();
    }
}
