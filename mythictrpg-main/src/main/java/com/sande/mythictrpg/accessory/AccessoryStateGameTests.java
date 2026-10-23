package com.sande.mythictrpg.accessory;

import com.google.gson.JsonParser;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("mythictrpg_accessories")
@PrefixGameTestTemplate(false)
public final class AccessoryStateGameTests{
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty")
    public static void twoFreeSlotsRequireDistinctRewardEntitlements(GameTestHelper h){
        var s=new AccessoryState();var p=UUID.randomUUID();var one=UUID.randomUUID();var two=UUID.randomUUID();
        h.assertTrue(s.equip(p,0,one),"first free slot");h.assertTrue(!s.equip(p,1,one),"replacement copy occupied second slot");
        h.assertTrue(s.equip(p,1,two),"distinct legitimate reward did not stack");h.assertTrue(!s.equip(p,2,UUID.randomUUID()),"third slot created");
        h.assertTrue(s.equip(p,0,null)&&s.equipped(p,0)==null&&s.equipped(p,1).equals(two),"unequip changed another slot");h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty")
    public static void utcCooldownSurvivesUnequipSaveAndOtherPlayers(GameTestHelper h){
        var s=new AccessoryState();var p=UUID.randomUUID();var other=UUID.randomUUID();var id=UUID.randomUUID();long now=1000;
        s.equip(p,0,id);h.assertTrue(s.trigger(p,id,AccessoryEffect.Kind.CLEANSE_CC,now,300000),"initial cleanse");s.equip(p,0,null);s.equip(p,1,id);
        var restored=AccessoryState.load(s.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(!restored.trigger(p,id,AccessoryEffect.Kind.CLEANSE_CC,now+299999,300000),"swap/save reset cooldown");
        h.assertTrue(restored.trigger(p,id,AccessoryEffect.Kind.CLEANSE_CC,now+300000,300000),"UTC exact deadline");
        h.assertTrue(restored.trigger(other,id,AccessoryEffect.Kind.CLEANSE_CC,now,300000),"another player's cooldown leaked");h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty")
    public static void emergencyHealingIsGradualAndNoOfflineCatchup(GameTestHelper h){
        var s=new AccessoryState();var p=UUID.randomUUID();var id=UUID.randomUUID();
        h.assertTrue(s.trigger(p,id,AccessoryEffect.Kind.EMERGENCY_HEAL,1000,86400000),"emergency first use");
        h.assertTrue(s.startHealing(p,id,10,100),"emergency schedule");
        h.assertTrue(Math.abs(s.tickHealing(p,1,true)-.1)<.00001,"first gradual tick");
        h.assertTrue(s.tickHealing(p,1,true)==0,"duplicate tick accelerated healing");
        h.assertTrue(Math.abs(s.tickHealing(p,100000,true)-.1)<.00001,"offline time dumped healing instantly");
        var restored=AccessoryState.load(s.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(Math.abs(restored.tickHealing(p,100001,true)-.1)<.00001,"scheduled heal not saved");
        restored.tickHealing(p,100002,false);h.assertTrue(restored.tickHealing(p,100003,true)==0,"death resumed emergency healing");
        h.assertTrue(restored.remainingMillis(p,id,AccessoryEffect.Kind.EMERGENCY_HEAL,2000)==86399000,"death removed emergency cooldown");h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty")
    public static void airborneChargesDoNotResetOnSwappingOrReload(GameTestHelper h){
        var s=new AccessoryState();var p=UUID.randomUUID();
        h.assertTrue(s.jump(p,10,2),"first air jump");h.assertTrue(!s.jump(p,11,2),"too-fast repeated packet");h.assertTrue(s.jump(p,13,2),"second stacked air jump");
        var restored=AccessoryState.load(s.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(!restored.jump(p,16,2),"reconnect restored air charges");restored.grounded(p,17);h.assertTrue(restored.jump(p,18,2),"actual landing did not restore jumps");h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty")
    public static void corruptStateNeverCreatesFreshEquipmentOrCooldowns(GameTestHelper h){
        var s=new AccessoryState();var p=UUID.randomUUID();s.equip(p,0,UUID.randomUUID());var valid=s.save(new CompoundTag(),h.getLevel().registryAccess());
        var bad=valid.copy();bad.putInt("dataVersion",2);assertRejected(h,bad);
        bad=valid.copy();var player=bad.getList("players",Tag.TAG_COMPOUND).getCompound(0);player.putUUID("second",player.getUUID("first"));assertRejected(h,bad);
        bad=valid.copy();bad.getList("players",Tag.TAG_COMPOUND).getCompound(0).putDouble("airJumps",2);assertRejected(h,bad);
        bad=valid.copy();bad.getList("players",Tag.TAG_COMPOUND).getCompound(0).putString("linkTarget","Not an ID!");assertRejected(h,bad);h.succeed();
    }
    private static void assertRejected(GameTestHelper h,CompoundTag bad){var restored=AccessoryState.load(bad,h.getLevel().registryAccess());h.assertTrue(!restored.isReady()&&!restored.equip(UUID.randomUUID(),0,UUID.randomUUID()),"corrupt state remained writable");h.assertValueEqual(restored.save(new CompoundTag(),h.getLevel().registryAccess()),bad,"rejected source was overwritten");}
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty")
    public static void authoredEffectsValidateCapsAndPreserveIndependentNames(GameTestHelper h){
        var j=JsonParser.parseString("""
                {"schemaVersion":1,"name":"Someone's ring","itemId":"minecraft:amethyst_shard","components":{},"fixedPower":12,
                 "effects":[{"type":"MAX_MANA","amount":100},{"type":"LINK_KNOT","amount":0.1}]}
                """).getAsJsonObject();
        var d=AccessoryDefinition.parse(ResourceLocation.parse("test:accessory"),j);h.assertTrue(d.effects().size()==2&&d.fixedPower()==12&&d.name().equals("Someone's ring"),"authored identity/effects");
        boolean rejected=false;j.getAsJsonArray("effects").get(0).getAsJsonObject().addProperty("amount",500000);try{AccessoryDefinition.parse(d.id(),j);}catch(RuntimeException e){rejected=true;}h.assertTrue(rejected,"unsafe maximum mana accepted");
        rejected=false;try{new AccessoryEffect(AccessoryEffect.Kind.MISS_REFUND,1.1,0,0);}catch(RuntimeException e){rejected=true;}h.assertTrue(rejected,"refund greater than cost accepted");h.succeed();
    }
}
