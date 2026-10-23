package com.sande.mythictrpg.accessory;

import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("mythictrpg_accessories")
@PrefixGameTestTemplate(false)
public final class AccessoryNetworkGameTests{
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty")
    public static void twoSlotPageRoundTripKeepsDefensiveItemCopies(GameTestHelper h){
        var item=new ItemStack(Items.AMETHYST_SHARD);var id=UUID.randomUUID();var token=UUID.randomUUID();
        var page=new AccessoryPage(token,true,0,1,List.of(new AccessoryPage.Slot(id,"ring",item),new AccessoryPage.Slot(null,"empty",ItemStack.EMPTY)),
                List.of(new AccessoryPage.Row(id,"ring",item,12,List.of("maximum mana +100"))),"test:skill",List.of(new AccessoryPage.Target(ResourceLocation.parse("test:skill"),"skill")),"");
        item.setCount(9);h.assertTrue(page.rows().get(0).icon().getCount()==1,"mutable source item leaked into page");page.slots().get(0).icon().setCount(8);h.assertTrue(page.slots().get(0).icon().getCount()==1,"getter exposed icon");
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());try{
            AccessoryPage.CODEC.encode(b,page);var restored=AccessoryPage.CODEC.decode(b);h.assertTrue(restored.token().equals(token)&&restored.slots().size()==2&&restored.rows().get(0).entitlement().equals(id)&&restored.targets().get(0).id().toString().equals("test:skill"),"page wire protocol");
        }finally{b.release();}h.succeed();
    }
    @GameTest(templateNamespace="mythictrpg_accessories",template="empty")
    public static void equipmentRequestsAreBoundedAndRoundTrip(GameTestHelper h){
        var r=new AccessoryRequest(UUID.randomUUID(),7,AccessoryRequest.Action.EQUIP,1,UUID.randomUUID().toString(),2);
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),h.getLevel().registryAccess());try{AccessoryRequest.CODEC.encode(b,r);h.assertValueEqual(AccessoryRequest.CODEC.decode(b),r,"request wire");}finally{b.release();}
        boolean rejected=false;try{new AccessoryRequest(r.token(),8,AccessoryRequest.Action.EQUIP,2,r.target(),0);}catch(RuntimeException bad){rejected=true;}h.assertTrue(rejected,"third equipment slot accepted");
        rejected=false;try{new AccessoryRequest(r.token(),-1,AccessoryRequest.Action.EQUIP,0,r.target(),0);}catch(RuntimeException bad){rejected=true;}h.assertTrue(rejected,"negative request sequence accepted");h.succeed();
    }
}
