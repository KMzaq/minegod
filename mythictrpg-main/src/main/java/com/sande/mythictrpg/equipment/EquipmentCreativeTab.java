package com.sande.mythictrpg.equipment;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Preview appearances without registering a new reward, skill, or combat-power entitlement. */
public final class EquipmentCreativeTab {
    private static final DeferredRegister<CreativeModeTab> TABS=DeferredRegister.create(Registries.CREATIVE_MODE_TAB,MythicTrpg.MOD_ID);
    public static final DeferredHolder<CreativeModeTab,CreativeModeTab> WEAPON_APPEARANCES=TABS.register("weapon_appearances",()->CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.mythictrpg.weapon_appearances"))
            .icon(()->new ItemStack(EquipmentItems.STEEL_BLADE.get()))
            .displayItems((parameters,output)->{
                output.accept(EquipmentItems.STEEL_BLADE.get());
                output.accept(EquipmentItems.DUELIST_BLADE.get());
                SimplySwordAppearances.items().forEach(output::accept);
            }).build());
    private EquipmentCreativeTab() { }
    public static void register(IEventBus bus){TABS.register(bus);}
}
