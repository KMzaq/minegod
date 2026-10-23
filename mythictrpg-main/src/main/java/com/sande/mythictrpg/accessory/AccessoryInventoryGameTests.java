package com.sande.mythictrpg.accessory;

import com.sande.mythictrpg.prototype.PrototypeCatalog;
import com.sande.mythictrpg.quest.reward.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/** Production InventoryMenu.clicked, real committed reward rights, and normal server-side item transfers. */
@GameTestHolder("mythictrpg_accessory_inventory")
@PrefixGameTestTemplate(false)
public final class AccessoryInventoryGameTests {
    @GameTest(templateNamespace="mythictrpg_accessory_inventory",template="empty",batch="accessory_inventory")
    public static void pickupUsesTwoActualSlotsAndRejectsOneRightInBoth(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var p=f.player("InvAccessory");var first=f.grant(p,PrototypeCatalog.CLEAR_MANA_STONE);var second=f.grant(p,PrototypeCatalog.CLEAR_MANA_STONE);
            var knot=f.grant(p,PrototypeCatalog.BLADE_KNOT);
            h.assertTrue(f.slot(p,0)>=46&&f.slot(p,1)>f.slot(p,0),"real player inventory did not append two marked slots");
            f.pickup(p,first);f.click(p,0,0,ClickType.PICKUP);
            h.assertTrue(f.worn(p,0,first)&&p.inventoryMenu.getCarried().isEmpty()&&f.count(p,first)==0,"pickup did not move the actual token into equipment");
            f.pickup(p,second);f.click(p,1,0,ClickType.PICKUP);
            h.assertTrue(AccessoryRuntime.maximumMana(p)==280,"two independent mana stone rights did not stack");
            p.inventoryMenu.setCarried(RewardAccessoryItems.stack(p,first));f.click(p,1,0,ClickType.PICKUP);
            h.assertTrue(f.worn(p,0,first)&&f.worn(p,1,second)&&token(p.inventoryMenu.getCarried()).equals(first),"a copied right replaced or doubled equipped rights");
            p.inventoryMenu.setCarried(ItemStack.EMPTY);f.pickup(p,knot);f.click(p,0,1,ClickType.PICKUP);
            h.assertTrue(f.worn(p,0,knot)&&token(p.inventoryMenu.getCarried()).equals(first),"right-click did not swap the previous accessory back to the cursor");
            h.assertTrue(AccessoryRuntime.entitlements(p).size()==3,"equipment movement created new reward ownership");
        }h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_accessory_inventory",template="empty",batch="accessory_inventory")
    public static void shiftTransferAndFullInventoryFailuresPreserveBothItemAndEquipment(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var p=f.player("ShiftAccessory");var id=f.grant(p,PrototypeCatalog.CLEAR_MANA_STONE);
            p.inventoryMenu.clicked(f.tokenSlot(p,id),0,ClickType.QUICK_MOVE,p);
            h.assertTrue(f.worn(p,0,id)&&f.count(p,id)==0,"Shift did not equip a token from normal inventory");
            f.fill(p);f.click(p,0,0,ClickType.QUICK_MOVE);
            h.assertTrue(f.worn(p,0,id)&&f.count(p,id)==0,"full inventory lost or duplicated a worn item");
            h.assertTrue(!AccessoryInventory.legacyEquip(p,0,null)&&f.worn(p,0,id),"legacy removal bypassed full inventory capacity");
            p.getInventory().setItem(5,ItemStack.EMPTY);f.click(p,0,0,ClickType.QUICK_MOVE);
            h.assertTrue(!f.worn(p,0,id)&&f.count(p,id)==1&&token(p.getInventory().getItem(5)).equals(id),"Shift removal failed to use the available inventory slot");
            p.inventoryMenu.clicked(f.tokenSlot(p,id),1,ClickType.QUICK_MOVE,p);
            h.assertTrue(f.worn(p,0,id)&&f.count(p,id)==0,"repeated Shift transfer duplicated the physical item");
        }h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_accessory_inventory",template="empty",batch="accessory_inventory")
    public static void hotbarAndOffhandSwapWorkWhileDragCloneAndDirectSlotMutationAreRejected(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var p=f.player("SwapAccessory");var stone=f.grant(p,PrototypeCatalog.CLEAR_MANA_STONE);var knot=f.grant(p,PrototypeCatalog.BLADE_KNOT);
            f.pickup(p,stone);f.click(p,0,0,ClickType.PICKUP);
            int raw=f.rawToken(p,knot);var input=p.getInventory().getItem(raw);p.getInventory().setItem(raw,ItemStack.EMPTY);p.getInventory().setItem(1,input);
            f.click(p,0,1,ClickType.SWAP);
            h.assertTrue(f.worn(p,0,knot)&&token(p.getInventory().getItem(1)).equals(stone),"number-key swap lost the previous item");
            f.click(p,0,40,ClickType.SWAP);f.click(p,1,40,ClickType.SWAP);
            h.assertTrue(f.worn(p,1,knot)&&p.getInventory().getItem(40).isEmpty(),"offhand exchange failed");
            var slot=p.inventoryMenu.getSlot(f.slot(p,1));
            slot.set(ItemStack.EMPTY);slot.remove(1);slot.container.removeItemNoUpdate(1);slot.container.clearContent();
            h.assertTrue(f.worn(p,1,knot),"raw container methods bypassed equipment transactions");
            p.inventoryMenu.setCarried(RewardAccessoryItems.stack(p,stone));
            for(var type:List.of(ClickType.CLONE,ClickType.PICKUP_ALL,ClickType.QUICK_CRAFT))f.click(p,1,0,type);
            h.assertTrue(f.worn(p,1,knot)&&token(p.inventoryMenu.getCarried()).equals(stone),"drag, clone, or collect-all mutated the projected slot");
        }h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_accessory_inventory",template="empty",batch="accessory_inventory")
    public static void throwHonorsTossCancellationAndKeepsRewardOwnership(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var p=f.player("DropAccessory");var id=f.grant(p,PrototypeCatalog.CLEAR_MANA_STONE);
            f.pickup(p,id);f.click(p,0,0,ClickType.PICKUP);
            var veto=new TossVeto(p);NeoForge.EVENT_BUS.register(veto);
            try{f.click(p,0,0,ClickType.THROW);h.assertTrue(f.worn(p,0,id),"cancelled toss unequipped the accessory");}
            finally{NeoForge.EVENT_BUS.unregister(veto);}
            f.click(p,0,1,ClickType.THROW);
            h.assertTrue(!f.worn(p,0,id)&&AccessoryRuntime.entitlements(p).stream().anyMatch(r->r.id().equals(id)),"normal toss removed ownership or retained equipment");
            var drops=h.getLevel().getEntitiesOfClass(ItemEntity.class,p.getBoundingBox().inflate(5),e->id.equals(token(e.getItem())));
            h.assertTrue(drops.size()==1,"normal toss did not create exactly one original-UUID item");
        }h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_accessory_inventory",template="empty",batch="accessory_inventory")
    public static void ownerDefinitionUnknownRightAndOversizedStacksCannotBeEquipped(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var p=f.player("SecureAccessory");var other=f.player("ForeignRing");var own=f.grant(p,PrototypeCatalog.CLEAR_MANA_STONE);var foreign=f.grant(other,PrototypeCatalog.CLEAR_MANA_STONE);
            var forged=RewardAccessoryItems.stack(other,foreign);p.inventoryMenu.setCarried(forged);f.click(p,0,0,ClickType.PICKUP);
            h.assertTrue(AccessoryState.get(p.server).equipped(p.getUUID(),0)==null,"foreign owner token was equipped");
            var bad=RewardAccessoryItems.stack(p,own);var data=bad.get(DataComponents.CUSTOM_DATA).copyTag();data.putUUID("accessoryEntitlement",UUID.randomUUID());bad.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
            p.inventoryMenu.setCarried(bad);f.click(p,0,0,ClickType.PICKUP);
            h.assertTrue(AccessoryState.get(p.server).equipped(p.getUUID(),0)==null,"unknown reward UUID was equipped");
            bad=RewardAccessoryItems.stack(p,own);data=bad.get(DataComponents.CUSTOM_DATA).copyTag();data.putString("accessoryDefinition",PrototypeCatalog.BLADE_KNOT.toString());bad.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
            p.inventoryMenu.setCarried(bad);f.click(p,0,0,ClickType.PICKUP);
            h.assertTrue(AccessoryState.get(p.server).equipped(p.getUUID(),0)==null,"mismatched definition token was equipped");
            bad=RewardAccessoryItems.stack(p,own);bad.setCount(2);p.inventoryMenu.setCarried(bad);f.click(p,0,0,ClickType.PICKUP);
            h.assertTrue(AccessoryState.get(p.server).equipped(p.getUUID(),0)==null&&p.inventoryMenu.getCarried().getCount()==2,"oversized accessory stack was split or equipped");
            p.inventoryMenu.setCarried(new ItemStack(Items.AMETHYST_SHARD));f.click(p,0,0,ClickType.PICKUP);
            h.assertTrue(AccessoryState.get(p.server).equipped(p.getUUID(),0)==null,"ordinary crafting material became equipment");
        }h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_accessory_inventory",template="empty",batch="accessory_inventory")
    public static void existingScreenPacketsAndReissueRespectWornAndCarriedRepresentation(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var p=f.player("LegacyAccessory");var stone=f.grant(p,PrototypeCatalog.CLEAR_MANA_STONE);var knot=f.grant(p,PrototypeCatalog.BLADE_KNOT);
            AccessoryNetwork.open(p,0,"");
            AccessoryNetwork.receive(p,new AccessoryRequest(f.session(p),1,AccessoryRequest.Action.EQUIP,0,stone.toString(),0));
            h.assertTrue(f.worn(p,0,stone)&&f.count(p,stone)==0,"old equipment packet bypassed physical token movement");
            long now=System.currentTimeMillis();var state=AccessoryState.get(p.server);state.trigger(p.getUUID(),stone,AccessoryEffect.Kind.CLEANSE_CC,now,300000);
            f.fillExcept(p,knot);
            h.assertTrue(AccessoryInventory.legacyEquip(p,0,knot)&&f.worn(p,0,knot)&&f.count(p,stone)==1,"full inventory exchange did not reuse its source slot");
            h.assertTrue(RewardAccessoryItems.restore(p,knot)&&f.count(p,knot)==0,"worn reissue minted an extra physical token");
            f.click(p,0,0,ClickType.PICKUP);
            h.assertTrue(RewardAccessoryItems.restore(p,knot)&&f.count(p,knot)==0&&token(p.inventoryMenu.getCarried()).equals(knot),"cursor reissue minted an extra copy");
            h.assertTrue(state.remainingMillis(p.getUUID(),stone,AccessoryEffect.Kind.CLEANSE_CC,now)==300000,"equipment or reissue reset its entitlement cooldown");
        }h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_accessory_inventory",template="empty",batch="accessory_inventory")
    public static void savedEquipmentAndNewPlayerMenuKeepRightsAndCooldownWithoutTokenDuplication(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var p=f.player("PersistAccessory");var right=f.grant(p,PrototypeCatalog.CLEAR_MANA_STONE);f.pickup(p,right);f.click(p,0,0,ClickType.PICKUP);
            var state=AccessoryState.get(p.server);long now=System.currentTimeMillis();state.trigger(p.getUUID(),right,AccessoryEffect.Kind.CLEANSE_CC,now,300000);
            var saved=AccessoryState.load(state.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
            h.assertTrue(right.equals(saved.equipped(p.getUUID(),0))&&saved.remainingMillis(p.getUUID(),right,AccessoryEffect.Kind.CLEANSE_CC,now)==300000,"save/load lost worn UUID or its cooldown");
            UUID uuid=p.getUUID();p.setHealth(0);f.disconnect(p);
            var replacement=f.player("PersistReturn",uuid);
            h.assertTrue(f.worn(replacement,0,right)&&token(replacement.inventoryMenu.getSlot(f.slot(replacement,0)).getItem()).equals(right),"replacement player's actual menu did not reconstruct equipment");
            h.assertTrue(f.count(replacement,right)==0&&RewardAccessoryItems.restore(replacement,right)&&f.count(replacement,right)==0,"new player or reissue duplicated an equipped representation");
            h.assertTrue(state.remainingMillis(uuid,right,AccessoryEffect.Kind.CLEANSE_CC,now)==300000,"new player object reset cooldown");
        }h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_accessory_inventory",template="empty",batch="accessory_inventory")
    public static void normalArmorClicksRemainVanillaAndLegacyKeepsakeReconciliationOnlyRemovesWornCopies(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var p=f.player("ArmorAccessory");p.getInventory().setItem(0,new ItemStack(Items.IRON_CHESTPLATE));
            p.inventoryMenu.clicked(36,0,ClickType.PICKUP,p);p.inventoryMenu.clicked(6,0,ClickType.PICKUP,p);
            h.assertTrue(p.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)&&p.inventoryMenu.getCarried().isEmpty(),"new accessory slots changed normal armor placement");
            var worn=f.grant(p,PrototypeCatalog.CLEAR_MANA_STONE);var spare=f.grant(p,PrototypeCatalog.CLEAR_MANA_STONE);
            h.assertTrue(AccessoryRuntime.equip(p,0,worn)&&f.count(p,worn)==1,"fixture did not create the old keepsake layout");
            AccessoryInventory.reconcile(p);
            h.assertTrue(f.count(p,worn)==0&&f.count(p,spare)==1&&f.worn(p,0,worn),"upgrade reconciliation deleted another acquisition or unequipped a right");
        }h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_accessory_inventory",template="empty",batch="accessory_inventory")
    public static void creativeInventoryTabUsesRealClickPacketsAndFullStateWithoutCreativeSlotForgery(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var p=f.player("CreativeRing");p.setGameMode(GameType.CREATIVE);
            var stone=f.grant(p,PrototypeCatalog.CLEAR_MANA_STONE);var knot=f.grant(p,PrototypeCatalog.BLADE_KNOT);
            var channel=f.channels.get(0);fullStates(channel);
            int first=p.inventoryMenu.getSlot(f.slot(p,0)).index,second=p.inventoryMenu.getSlot(f.slot(p,1)).index;
            packetClick(p,f.tokenSlot(p,stone),ItemStack.EMPTY);
            h.assertTrue(token(p.inventoryMenu.getCarried()).equals(stone)&&f.count(p,stone)==0,"creative container packet did not pick up the authoritative inventory token");
            assertFullState(h,channel,first,second,null,null,stone);

            var forged=RewardAccessoryItems.stack(p,knot);var data=forged.get(DataComponents.CUSTOM_DATA).copyTag();
            data.putUUID("accessoryEntitlement",UUID.randomUUID());forged.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
            p.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(first,forged));
            p.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(second,new ItemStack(Items.DIAMOND)));
            h.assertTrue(AccessoryState.get(p.server).equipped(p.getUUID(),0)==null&&AccessoryState.get(p.server).equipped(p.getUUID(),1)==null
                    &&token(p.inventoryMenu.getCarried()).equals(stone)&&f.count(p,knot)==1,"creative set-slot packet forged equipment or mutated real inventory");

            // These stale client predictions may affect remote bookkeeping, never the actual carried item or rights.
            packetClick(p,first,new ItemStack(Items.STONE));
            h.assertTrue(f.worn(p,0,stone)&&p.inventoryMenu.getCarried().isEmpty()&&f.count(p,stone)==0,"stale creative prediction replaced the real cursor or failed to equip");
            assertFullState(h,channel,first,second,stone,null,null);
            packetClick(p,f.tokenSlot(p,knot),ItemStack.EMPTY);
            assertFullState(h,channel,first,second,stone,null,knot);
            packetClick(p,second,ItemStack.EMPTY);
            h.assertTrue(f.worn(p,0,stone)&&f.worn(p,1,knot)&&p.inventoryMenu.getCarried().isEmpty(),"creative packet path did not support both accessory slots");
            assertFullState(h,channel,first,second,stone,knot,null);

            p.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(first,ItemStack.EMPTY));
            p.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(second,forged));
            h.assertTrue(f.worn(p,0,stone)&&f.worn(p,1,knot),"creative set-slot packet cleared or replaced a worn reward right");
            packetClick(p,first,ItemStack.EMPTY);
            h.assertTrue(!f.worn(p,0,stone)&&token(p.inventoryMenu.getCarried()).equals(stone),"creative packet removal failed to return the same entitlement token");
            assertFullState(h,channel,first,second,null,knot,stone);
            packetClick(p,36,ItemStack.EMPTY);
            h.assertTrue(f.count(p,stone)==1&&p.inventoryMenu.getCarried().isEmpty(),"creative packet could not return equipment to ordinary inventory");
            assertFullState(h,channel,first,second,null,knot,null);
            packetClick(p,second,ItemStack.EMPTY);
            assertFullState(h,channel,first,second,null,null,knot);
            h.assertTrue(f.count(p,knot)==0&&RewardAccessoryItems.restore(p,knot)&&f.count(p,knot)==0
                    &&AccessoryRuntime.entitlements(p).size()==2,"creative cursor recovery duplicated an item or changed ownership");
        }h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_accessory_inventory",template="empty",batch="accessory_inventory")
    public static void registeredAccessoryAppearancesPreserveLegacyRightsAndRejectWrongNewTypes(GameTestHelper h) {
        try(var f=new Fixture(h)) {
            var p=f.player("TypedAccessory");
            var types=List.of(RewardAccessoryItems.AMETHYST_PENDANT,RewardAccessoryItems.AZURE_RING,
                    RewardAccessoryItems.BRASS_CUFF,RewardAccessoryItems.CRIMSON_TALISMAN);
            var names=List.of("amethyst_pendant","azure_ring","brass_cuff","crimson_talisman");
            for(int i=0;i<types.size();i++) {
                var plain=new ItemStack(types.get(i).get());
                h.assertTrue(plain.getItem() instanceof AccessoryItem&&plain.getMaxStackSize()==1&&RewardAccessoryItems.isToken(plain)
                        &&net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(plain.getItem()).equals(ResourceLocation.fromNamespaceAndPath("mythictrpg",names.get(i))),"accessory appearance item was not registered in its common class");
                p.inventoryMenu.setCarried(plain);f.click(p,0,0,ClickType.PICKUP);
                h.assertTrue(AccessoryState.get(p.server).equipped(p.getUUID(),0)==null,"unbound appearance item created equipment ownership");
            }
            p.inventoryMenu.setCarried(ItemStack.EMPTY);
            h.assertTrue(RewardAccessoryItems.isToken(new ItemStack(RewardAccessoryItems.TOKEN.get()))
                    &&RewardAccessoryItems.TOKEN.get() instanceof AccessoryItem,"legacy bound_accessory lost common accessory classification");
            var stone=f.grant(p,PrototypeCatalog.CLEAR_MANA_STONE);var knot=f.grant(p,PrototypeCatalog.BLADE_KNOT);
            var pendant=RewardAccessoryItems.stack(p,stone);var ring=RewardAccessoryItems.stack(p,knot);
            h.assertTrue(pendant.is(RewardAccessoryItems.AMETHYST_PENDANT.get())&&ring.is(RewardAccessoryItems.AZURE_RING.get()),"prototype rights did not select the registered pendant and ring appearances");
            var wrongType=new ItemStack(RewardAccessoryItems.AZURE_RING.get());wrongType.set(DataComponents.CUSTOM_DATA,pendant.get(DataComponents.CUSTOM_DATA));
            p.inventoryMenu.setCarried(wrongType);f.click(p,0,0,ClickType.PICKUP);
            h.assertTrue(AccessoryState.get(p.server).equipped(p.getUUID(),0)==null,"copied valid pendant metadata authorized a different new accessory type");
            p.inventoryMenu.setCarried(ItemStack.EMPTY);
            var legacy=new ItemStack(RewardAccessoryItems.TOKEN.get());legacy.set(DataComponents.CUSTOM_DATA,pendant.get(DataComponents.CUSTOM_DATA));
            p.getInventory().setItem(f.rawToken(p,stone),legacy);
            p.inventoryMenu.clicked(f.tokenSlot(p,stone),0,ClickType.QUICK_MOVE,p);
            h.assertTrue(f.worn(p,0,stone)&&p.inventoryMenu.getSlot(f.slot(p,0)).getItem().is(RewardAccessoryItems.AMETHYST_PENDANT.get())
                    &&f.count(p,stone)==0,"legacy same-UUID token did not equip as the canonical new pendant");
            p.inventoryMenu.clicked(f.tokenSlot(p,knot),0,ClickType.QUICK_MOVE,p);
            h.assertTrue(f.worn(p,1,knot)&&p.inventoryMenu.getSlot(f.slot(p,1)).getItem().is(RewardAccessoryItems.AZURE_RING.get()),"new ring token did not support inventory Shift equip");
            h.assertTrue(AccessoryInventory.represented(p,stone)&&RewardAccessoryItems.restore(p,stone)&&f.count(p,stone)==0
                    &&AccessoryRuntime.entitlements(p).size()==2,"appearance migration or reissue duplicated an equipped right");
            f.click(p,0,0,ClickType.PICKUP);
            h.assertTrue(p.inventoryMenu.getCarried().is(RewardAccessoryItems.AMETHYST_PENDANT.get())
                    &&token(p.inventoryMenu.getCarried()).equals(stone)&&RewardAccessoryItems.restore(p,stone)&&f.count(p,stone)==0,"legacy appearance migration changed UUID or duplicated the cursor representation");
        }h.succeed();
    }

    private static void packetClick(ServerPlayer player,int slot,ItemStack staleCarried) {
        player.connection.handleContainerClick(new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
                0,player.inventoryMenu.getStateId(),slot,0,ClickType.PICKUP,staleCarried,
                new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>()));
    }
    private static List<net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket> fullStates(EmbeddedChannel channel) {
        channel.runPendingTasks();var packets=new ArrayList<net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket>();Object outbound;
        while((outbound=channel.readOutbound())!=null) {
            if(outbound instanceof net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket packet)packets.add(packet);
            io.netty.util.ReferenceCountUtil.release(outbound);
        }
        return packets;
    }
    private static void assertFullState(GameTestHelper h,EmbeddedChannel channel,int first,int second,UUID wornFirst,UUID wornSecond,UUID carried) {
        var full=fullStates(channel);h.assertTrue(!full.isEmpty(),"creative click did not send authoritative full inventory state");
        var latest=full.getLast();h.assertTrue(latest.getContainerId()==0&&latest.getItems().size()==48,"creative sync did not contain the 48-slot player inventory");
        h.assertTrue(Objects.equals(token(latest.getItems().get(first)),wornFirst)&&Objects.equals(token(latest.getItems().get(second)),wornSecond)
                &&Objects.equals(token(latest.getCarriedItem()),carried),"creative full-state equipment or cursor disagreed with server ownership");
    }

    public static final class TossVeto {
        final ServerPlayer player;TossVeto(ServerPlayer player){this.player=player;}
        @SubscribeEvent public void toss(ItemTossEvent event){if(event.getPlayer()==player)event.setCanceled(true);}
    }
    private static UUID token(ItemStack stack){var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();return data.hasUUID("accessoryEntitlement")?data.getUUID("accessoryEntitlement"):null;}
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;final List<ServerPlayer> players=new ArrayList<>();final List<EmbeddedChannel> channels=new ArrayList<>();final Set<UUID> ids=new HashSet<>();
        Fixture(GameTestHelper h){this.h=h;}
        ServerPlayer player(String name){return player(name,UUID.randomUUID());}
        ServerPlayer player(String name,UUID uuid){
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(uuid,name),false);
            var p=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),cookie.gameProfile(),cookie.clientInformation());
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);channels.add(new EmbeddedChannel(connection));
            net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);p.server.getPlayerList().placeNewPlayer(connection,p,cookie);
            p.setGameMode(GameType.SURVIVAL);p.setHealth(20);p.setNoGravity(true);
            var position=h.absolutePos(new net.minecraft.core.BlockPos(1+players.size()*2,3,1));p.moveTo(position.getX()+.5,position.getY(),position.getZ()+.5,0,0);
            players.add(p);ids.add(uuid);return p;
        }
        void disconnect(ServerPlayer p){p.server.getPlayerList().remove(p);players.remove(p);}
        UUID grant(ServerPlayer p,ResourceLocation definition){
            var state=RewardClaimState.get(p.server);var source=ResourceLocation.parse("test:accessory_inventory/"+UUID.randomUUID());
            var claim=state.create(new RewardClaim(UUID.randomUUID(),p.getUUID(),ResourceLocation.parse("test:inventory_fixture_source"),source,"inventory fixture",
                    List.of(new ProgressionRewardEntry(ProgressionRewardEntry.Kind.ACCESSORY,definition)),List.of(),false,Optional.empty(),p.server.overworld().getGameTime(),RewardOrigin.LEGACY_UNSPECIFIED));
            state.markAutomaticGranted(claim.claimId(),p.server.overworld().getGameTime());
            var right=state.progressionFor(p.getUUID()).stream().filter(r->r.sourceId().equals(source)).findFirst().orElseThrow().entitlementId();
            h.assertTrue(RewardAccessoryItems.restore(p,right),"committed reward could not provide its token");return right;
        }
        int slot(ServerPlayer p,int logical){int slot=AccessoryInventory.slotIndex(p.inventoryMenu,logical);h.assertTrue(slot>=0,"missing physical inventory accessory slot");return slot;}
        void click(ServerPlayer p,int logical,int button,ClickType type){p.inventoryMenu.clicked(slot(p,logical),button,type,p);}
        boolean worn(ServerPlayer p,int logical,UUID id){return id.equals(AccessoryState.get(p.server).equipped(p.getUUID(),logical));}
        int count(ServerPlayer p,UUID id){int count=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(id.equals(token(p.getInventory().getItem(i))))count+=p.getInventory().getItem(i).getCount();return count;}
        int rawToken(ServerPlayer p,UUID id){for(int i=0;i<p.getInventory().getContainerSize();i++)if(id.equals(token(p.getInventory().getItem(i))))return i;throw new IllegalStateException("missing token "+id);}
        int tokenSlot(ServerPlayer p,UUID id){int raw=rawToken(p,id);for(int i=0;i<p.inventoryMenu.slots.size();i++){var slot=p.inventoryMenu.slots.get(i);if(slot.container==p.getInventory()&&slot.getContainerSlot()==raw)return i;}throw new IllegalStateException("missing inventory slot");}
        void pickup(ServerPlayer p,UUID id){h.assertTrue(p.inventoryMenu.getCarried().isEmpty(),"pickup fixture cursor was occupied");p.inventoryMenu.clicked(tokenSlot(p,id),0,ClickType.PICKUP,p);}
        void fill(ServerPlayer p){for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.STONE,64));}
        void fillExcept(ServerPlayer p,UUID retained){for(int i=0;i<36;i++)if(!retained.equals(token(p.getInventory().getItem(i))))p.getInventory().setItem(i,new ItemStack(Items.STONE,64));}
        UUID session(ServerPlayer p){try{var field=AccessoryNetwork.class.getDeclaredField("SESSIONS");field.setAccessible(true);var sessions=(Map<?,?>)((Map<?,?>)field.get(null)).get(p.server);var session=sessions.get(p.getUUID());var token=session.getClass().getDeclaredMethod("token");token.setAccessible(true);return (UUID)token.invoke(session);}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}}
        @Override public void close(){var center=net.minecraft.world.phys.Vec3.atCenterOf(h.absolutePos(net.minecraft.core.BlockPos.ZERO));
            for(var entity:h.getLevel().getEntitiesOfClass(ItemEntity.class,new net.minecraft.world.phys.AABB(center,center).inflate(32),e->{var data=e.getItem().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();return data.hasUUID("accessoryOwner")&&ids.contains(data.getUUID("accessoryOwner"));}))entity.discard();
            players.forEach(p->{p.inventoryMenu.setCarried(ItemStack.EMPTY);p.server.getPlayerList().remove(p);});channels.forEach(EmbeddedChannel::finishAndReleaseAll);}
    }
}
