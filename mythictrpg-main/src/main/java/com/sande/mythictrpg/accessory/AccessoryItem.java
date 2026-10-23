package com.sande.mythictrpg.accessory;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;

/** Accessory appearance and interaction; the item alone never creates an equipment entitlement. */
public final class AccessoryItem extends Item {
    public AccessoryItem(Properties properties){super(properties);}

    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand) {
        if(player instanceof ServerPlayer serverPlayer)AccessoryNetwork.open(serverPlayer,0,"");
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide());
    }

    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> tooltip,TooltipFlag flag) {
        super.appendHoverText(stack,context,tooltip,flag);
        var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        boolean bound=data.hasUUID("accessoryOwner")&&data.hasUUID("accessoryEntitlement")&&!data.getString("accessoryDefinition").isEmpty();
        tooltip.add(Component.translatable(bound?"tooltip.mythictrpg.accessory.bound":"tooltip.mythictrpg.accessory.unbound")
                .withStyle(bound?ChatFormatting.GRAY:ChatFormatting.DARK_GRAY));
    }
}
