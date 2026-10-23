package com.sande.mythictrpg.accessory;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
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
    public static final DeferredHolder<Item,Item> TOKEN=ITEMS.register("bound_accessory",()->new Item(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)) {
        @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand) {
            if(player instanceof ServerPlayer serverPlayer)AccessoryNetwork.open(serverPlayer,0,"");
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide());
        }
    });
    private RewardAccessoryItems() { }
    public static void register(IEventBus bus) { ITEMS.register(bus); }
    public static boolean restore(ServerPlayer player,UUID entitlementId) {
        if(!player.server.isSameThread()||player.server.getPlayerList().getPlayer(player.getUUID())!=player||!AccessoryRewardBridge.isReady(player))return false;
        var entitlement=AccessoryRewardBridge.entitlements(player).stream().filter(value->value.id().equals(entitlementId)).findFirst().orElse(null);
        if(entitlement==null)return false;
        var definition=AccessoryDefinitions.INSTANCE.find(entitlement.definitionId()).orElse(null);if(definition==null)return false;
        for(int i=0;i<player.getInventory().getContainerSize();i++) {
            var stack=player.getInventory().getItem(i);var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            if(stack.is(TOKEN.get())&&data.hasUUID("accessoryEntitlement")&&data.getUUID("accessoryEntitlement").equals(entitlementId))return true;
        }
        ItemStack stack=new ItemStack(TOKEN.get());CompoundTag data=new CompoundTag();
        data.putUUID("accessoryEntitlement",entitlementId);data.putUUID("accessoryOwner",player.getUUID());
        data.putString("accessoryDefinition",entitlement.definitionId().toString());stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(definition.name()));
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
