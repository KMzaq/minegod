package com.sande.mythictrpg.accessory;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** These catalog entries are appearance items; reward ownership is granted by the existing reward system. */
public final class AccessoryCreativeTab {
    private static final DeferredRegister<CreativeModeTab> TABS=DeferredRegister.create(Registries.CREATIVE_MODE_TAB,MythicTrpg.MOD_ID);
    public static final DeferredHolder<CreativeModeTab,CreativeModeTab> ACCESSORIES=TABS.register("accessories",()->CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.mythictrpg.accessories"))
            .icon(()->new ItemStack(RewardAccessoryItems.AZURE_RING.get()))
            .displayItems((parameters,output)->{
                output.accept(RewardAccessoryItems.AMETHYST_PENDANT.get());
                output.accept(RewardAccessoryItems.AZURE_RING.get());
                output.accept(RewardAccessoryItems.BRASS_CUFF.get());
                output.accept(RewardAccessoryItems.CRIMSON_TALISMAN.get());
            }).build());
    private AccessoryCreativeTab() { }
    public static void register(IEventBus bus){TABS.register(bus);}
}
