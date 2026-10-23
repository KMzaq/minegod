package com.sande.mythictrpg.accessory;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.sande.mythictrpg.MythicTrpg;
import com.sande.mythictrpg.quest.reward.NpcRewardEntry;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.*;
import java.util.UUID;

/** Recoverable keepsake. Only committed entitlement IDs activate the two accessory slots. */
@EventBusSubscriber(modid=MythicTrpg.MOD_ID)
public final class RewardAccessoryItems {
    private static final DeferredRegister<Item> ITEMS=DeferredRegister.create(Registries.ITEM,MythicTrpg.MOD_ID);
    public static final DeferredHolder<Item,Item> TOKEN=accessory("bound_accessory");
    public static final DeferredHolder<Item,Item> AMETHYST_PENDANT=accessory("amethyst_pendant");
    public static final DeferredHolder<Item,Item> AZURE_RING=accessory("azure_ring");
    public static final DeferredHolder<Item,Item> BRASS_CUFF=accessory("brass_cuff");
    public static final DeferredHolder<Item,Item> CRIMSON_TALISMAN=accessory("crimson_talisman");
    private RewardAccessoryItems() { }
    private static DeferredHolder<Item,Item> accessory(String name){return ITEMS.register(name,()->new AccessoryItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));}
    public static void register(IEventBus bus) { ITEMS.register(bus); }
    public static boolean isToken(ItemStack stack){return !stack.isEmpty()&&stack.getItem() instanceof AccessoryItem;}
    private static Item appearance(AccessoryDefinition definition) {
        for(var type:java.util.List.of(AMETHYST_PENDANT,AZURE_RING,BRASS_CUFF,CRIMSON_TALISMAN))
            if(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(type.get()).equals(definition.itemId()))return type.get();
        return TOKEN.get();
    }
    public static boolean matchesAppearance(ItemStack stack,AccessoryDefinition definition){return stack.is(TOKEN.get())||stack.is(appearance(definition));}
    /** One cosmetic source for previews, delivery, equipment views and recovery; this creates no ownership. */
    public static ItemStack authoredBase(AccessoryDefinition definition,HolderLookup.Provider registries) {
        var itemId=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(appearance(definition));
        ItemStack stack=new NpcRewardEntry(itemId,1,definition.components()).createStack(registries);
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(definition.name()));
        return stack;
    }
    public static ItemStack stack(ServerPlayer player,UUID entitlementId) {
        if(player.server==null||!player.server.isSameThread()||!AccessoryRewardBridge.isReady(player))return ItemStack.EMPTY;
        var entitlement=AccessoryRewardBridge.entitlements(player).stream().filter(value->value.id().equals(entitlementId)).findFirst().orElse(null);
        if(entitlement==null)return ItemStack.EMPTY;
        var definition=AccessoryDefinitions.INSTANCE.find(entitlement.definitionId()).orElse(null);if(definition==null)return ItemStack.EMPTY;
        ItemStack stack=authoredBase(definition,player.registryAccess());CompoundTag data=new CompoundTag();
        data.putUUID("accessoryEntitlement",entitlementId);data.putUUID("accessoryOwner",player.getUUID());
        data.putString("accessoryDefinition",entitlement.definitionId().toString());stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        return stack;
    }
    public static boolean restore(ServerPlayer player,UUID entitlementId) {
        if(!player.server.isSameThread()||player.server.getPlayerList().getPlayer(player.getUUID())!=player||!AccessoryRewardBridge.isReady(player))return false;
        var entitlement=AccessoryRewardBridge.entitlements(player).stream().filter(value->value.id().equals(entitlementId)).findFirst().orElse(null);
        if(entitlement==null)return false;
        var definition=AccessoryDefinitions.INSTANCE.find(entitlement.definitionId()).orElse(null);if(definition==null)return false;
        if(AccessoryInventory.represented(player,entitlementId))return true;
        ItemStack stack=stack(player,entitlementId);if(stack.isEmpty())return false;
        if(!player.getInventory().add(stack))return false;
        player.inventoryMenu.broadcastChanges();return true;
    }
    public static void restoreGranted(ServerPlayer player,net.minecraft.resources.ResourceLocation definitionId) {
        for(var value:AccessoryRewardBridge.entitlements(player))if(value.definitionId().equals(definitionId))restore(player,value.id());
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mythaccessoryreissue")
                .then(Commands.argument("entitlement",StringArgumentType.word()).executes(context->{
                    UUID id;try{id=UUID.fromString(StringArgumentType.getString(context,"entitlement"));}catch(IllegalArgumentException bad){return 0;}
                    boolean restored=restore(context.getSource().getPlayerOrException(),id);
                    context.getSource().sendSuccess(()->Component.literal(restored?"장신구를 복구했습니다. 기존 사용 기록은 유지됩니다.":"복구 가능한 본인 장신구가 아닙니다."),false);return restored?1:0;
                })));
    }
}
