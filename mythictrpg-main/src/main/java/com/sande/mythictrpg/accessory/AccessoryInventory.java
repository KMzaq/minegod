package com.sande.mythictrpg.accessory;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Inventory slots are views of existing reward rights. Only these validated transactions may move their tokens. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class AccessoryInventory {
    private static final Set<ServerPlayer> MUTATING=Collections.newSetFromMap(new IdentityHashMap<>());
    private AccessoryInventory() { }

    public static Slot slot(Player owner,int logical,int x,int y) {
        if(logical<0||logical>1)throw new IllegalArgumentException("Accessory slot");
        // Player constructs its menu before ServerPlayer.server is initialized. Do not read ownership here.
        Container view=owner instanceof ServerPlayer server?new ServerView(server):new SimpleContainer(2);
        return new AccessorySlot(view,logical,x,y);
    }
    public static boolean isAccessorySlot(Slot slot){return slot instanceof AccessorySlot;}
    public static int logicalSlot(Slot slot){return slot instanceof AccessorySlot accessory?accessory.logical:-1;}
    public static int slotIndex(InventoryMenu menu,int logical){
        for(int i=0;i<menu.slots.size();i++)if(logicalSlot(menu.slots.get(i))==logical)return i;
        return -1;
    }

    /** Returns true when vanilla must not process this click. Client clicks await ordinary server menu sync. */
    public static boolean handleClick(InventoryMenu menu,int slotId,int button,ClickType type,Player player) {
        Slot clicked=slotId>=0&&slotId<menu.slots.size()?menu.slots.get(slotId):null;
        int logical=logicalSlot(clicked);
        boolean fromInventory=type==ClickType.QUICK_MOVE&&slotId>=9&&slotId<45&&clicked!=null
                &&RewardAccessoryItems.isToken(clicked.getItem());
        if(logical<0&&!fromInventory)return false;
        if(!(player instanceof ServerPlayer server))return true;
        if(!live(server)||server.inventoryMenu!=menu||server.containerMenu!=menu||!MUTATING.add(server))return true;
        try {
            if(logical<0) {
                if(button<0||button>1||clicked.container!=server.getInventory())return true;
                var right=right(server,clicked.getItem()).orElse(null);if(right==null)return false;
                if(worn(server,0)==null)logical=0;
                else if(worn(server,1)==null)logical=1;
                else return false; // Preserve normal inventory/hotbar Shift movement when both slots are full.
                UUID selected=right.id();
                if(canWear(server,logical,selected)&&AccessoryRuntime.equip(server,logical,selected))clicked.setByPlayer(ItemStack.EMPTY);
                return true;
            }
            switch(type) {
                case PICKUP -> {
                    if(button==0||button==1)exchangeCursor(server,menu,logical);
                }
                case QUICK_MOVE -> {
                    if(button==0||button==1)unequipToInventory(server,logical);
                }
                case SWAP -> {
                    if(button>=0&&button<=8||button==40)exchangeInventory(server,logical,button);
                }
                case THROW -> {
                    if((button==0||button==1)&&menu.getCarried().isEmpty())drop(server,logical);
                }
                // Creative clone, collect-all and distributed drag cannot mutate an entitlement view.
                default -> { }
            }
            return true;
        } finally {MUTATING.remove(server);}
    }

    private static void exchangeCursor(ServerPlayer player,InventoryMenu menu,int slot) {
        ItemStack carried=menu.getCarried();UUID old=worn(player,slot);
        var incoming=carried.isEmpty()?null:right(player,carried).orElse(null);
        if(!carried.isEmpty()&&incoming==null)return;
        UUID next=incoming==null?null:incoming.id();
        if(java.util.Objects.equals(old,next)||!canWear(player,slot,next))return;
        ItemStack previous=canonical(player,old);if(old!=null&&previous.isEmpty())return;
        if(AccessoryRuntime.equip(player,slot,next))menu.setCarried(previous);
    }
    private static void exchangeInventory(ServerPlayer player,int slot,int inventorySlot) {
        ItemStack carried=player.getInventory().getItem(inventorySlot);UUID old=worn(player,slot);
        var incoming=carried.isEmpty()?null:right(player,carried).orElse(null);
        if(!carried.isEmpty()&&incoming==null)return;
        UUID next=incoming==null?null:incoming.id();
        if(java.util.Objects.equals(old,next)||!canWear(player,slot,next))return;
        ItemStack previous=canonical(player,old);if(old!=null&&previous.isEmpty())return;
        if(AccessoryRuntime.equip(player,slot,next))player.getInventory().setItem(inventorySlot,previous);
    }
    private static boolean unequipToInventory(ServerPlayer player,int slot) {
        UUID old=worn(player,slot);if(old==null)return true;
        int empty=player.getInventory().getFreeSlot();if(empty<0)return false;
        ItemStack previous=canonical(player,old);if(previous.isEmpty()||!AccessoryRuntime.equip(player,slot,null))return false;
        player.getInventory().setItem(empty,previous);return true;
    }
    private static void drop(ServerPlayer player,int slot) {
        UUID old=worn(player,slot);if(old==null)return;
        ItemStack previous=canonical(player,old);if(previous.isEmpty())return;
        // A cancelled ItemTossEvent must leave equipment and cooldowns untouched.
        var dropped=player.drop(previous,true);if(dropped==null)return;
        if(!AccessoryRuntime.equip(player,slot,null))dropped.discard();
    }

    /** Compatibility for the management screen; returns the old item using a freed source slot or empty inventory slot. */
    public static boolean legacyEquip(ServerPlayer player,int slot,UUID entitlement) {
        if(!live(player)||slot<0||slot>1||!MUTATING.add(player))return false;
        try {
            UUID old=worn(player,slot);
            if(java.util.Objects.equals(old,entitlement))return true;
            if(!canWear(player,slot,entitlement))return false;
            ItemStack previous=canonical(player,old);if(old!=null&&previous.isEmpty())return false;
            int source=-1;
            if(entitlement!=null)for(int i=0;i<player.getInventory().getContainerSize();i++) {
                var right=right(player,player.getInventory().getItem(i)).orElse(null);
                if(right!=null&&right.id().equals(entitlement)){source=i;break;}
            }
            boolean cursor=source<0&&entitlement!=null&&right(player,player.containerMenu.getCarried())
                    .map(r->r.id().equals(entitlement)).orElse(false);
            int destination=source;
            if(!previous.isEmpty()&&source<0&&!cursor)destination=player.getInventory().getFreeSlot();
            if(!previous.isEmpty()&&destination<0&&!cursor)return false;
            if(!AccessoryRuntime.equip(player,slot,entitlement))return false;
            if(cursor)player.containerMenu.setCarried(previous);
            else if(source>=0)player.getInventory().setItem(source,previous);
            else if(!previous.isEmpty())player.getInventory().setItem(destination,previous);
            return true;
        } finally {MUTATING.remove(player);}
    }

    public static boolean represented(ServerPlayer player,UUID entitlement) {
        if(entitlement==null||player.server==null||!player.server.isSameThread())return false;
        if(entitlement.equals(worn(player,0))||entitlement.equals(worn(player,1)))return true;
        if(right(player,player.containerMenu.getCarried()).map(r->r.id().equals(entitlement)).orElse(false))return true;
        if(player.containerMenu!=player.inventoryMenu&&right(player,player.inventoryMenu.getCarried()).map(r->r.id().equals(entitlement)).orElse(false))return true;
        for(int i=0;i<player.getInventory().getContainerSize();i++)
            if(right(player,player.getInventory().getItem(i)).map(r->r.id().equals(entitlement)).orElse(false))return true;
        return false;
    }
    private static Optional<AccessoryRewardBridge.Entitlement> right(ServerPlayer player,ItemStack stack) {
        if(!RewardAccessoryItems.isToken(stack)||stack.getCount()!=1||!AccessoryRewardBridge.isReady(player))return Optional.empty();
        var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        if(!data.hasUUID("accessoryEntitlement")||!data.hasUUID("accessoryOwner")||!data.getUUID("accessoryOwner").equals(player.getUUID()))return Optional.empty();
        return AccessoryRuntime.entitlements(player).stream().filter(r->r.id().equals(data.getUUID("accessoryEntitlement"))
                &&r.definitionId().toString().equals(data.getString("accessoryDefinition"))&&AccessoryDefinitions.INSTANCE.find(r.definitionId())
                .map(definition->RewardAccessoryItems.matchesAppearance(stack,definition)).orElse(false)).findFirst();
    }
    private static boolean canWear(ServerPlayer player,int slot,UUID entitlement) {
        if(!AccessoryState.get(player.server).isReady()||!AccessoryRewardBridge.isReady(player))return false;
        if(entitlement==null)return true;
        return !entitlement.equals(worn(player,1-slot))&&AccessoryRuntime.entitlements(player).stream()
                .anyMatch(r->r.id().equals(entitlement)&&AccessoryDefinitions.INSTANCE.find(r.definitionId()).isPresent());
    }
    private static UUID worn(ServerPlayer player,int slot){return AccessoryState.get(player.server).equipped(player.getUUID(),slot);}
    private static ItemStack canonical(ServerPlayer player,UUID entitlement){return entitlement==null?ItemStack.EMPTY:RewardAccessoryItems.stack(player,entitlement);}
    private static boolean live(ServerPlayer player){return player.server!=null&&player.server.isSameThread()
            &&player.server.getPlayerList().getPlayer(player.getUUID())==player&&player.isAlive()&&!player.isSpectator();}

    /** Remove old keepsake copies for worn rights after upgrading from the separate equipment screen. */
    public static void reconcile(ServerPlayer player) {
        if(!live(player)||MUTATING.contains(player))return;
        UUID first=worn(player,0),second=worn(player,1);if(first==null&&second==null)return;
        boolean changed=false;
        for(int i=0;i<player.getInventory().getContainerSize();i++) {
            var right=right(player,player.getInventory().getItem(i)).orElse(null);
            if(right!=null&&(right.id().equals(first)||right.id().equals(second))){player.getInventory().setItem(i,ItemStack.EMPTY);changed=true;}
        }
        if(changed)player.inventoryMenu.broadcastChanges();
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer player)reconcile(player);}
    @SubscribeEvent public static void tick(PlayerTickEvent.Post event){if(event.getEntity() instanceof ServerPlayer player&&player.tickCount%20==0)reconcile(player);}

    private static final class AccessorySlot extends Slot {
        final int logical;
        AccessorySlot(Container container,int logical,int x,int y){super(container,logical,x,y);this.logical=logical;}
        @Override public boolean mayPlace(ItemStack stack){return false;}
        @Override public boolean mayPickup(Player player){return false;}
        @Override public int getMaxStackSize(){return 1;}
    }
    private static final class ServerView implements Container {
        final ServerPlayer owner;
        ServerView(ServerPlayer owner){this.owner=owner;}
        @Override public int getContainerSize(){return 2;}
        @Override public boolean isEmpty(){return getItem(0).isEmpty()&&getItem(1).isEmpty();}
        @Override public ItemStack getItem(int slot){return slot>=0&&slot<2&&owner.server!=null&&owner.server.isSameThread()?canonical(owner,worn(owner,slot)):ItemStack.EMPTY;}
        @Override public ItemStack removeItem(int slot,int count){return ItemStack.EMPTY;}
        @Override public ItemStack removeItemNoUpdate(int slot){return ItemStack.EMPTY;}
        @Override public void setItem(int slot,ItemStack stack){ }
        @Override public void setChanged(){ }
        @Override public boolean stillValid(Player player){return player==owner;}
        @Override public void clearContent(){ }
    }
}
