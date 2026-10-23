package com.sande.mythictrpg.equipment;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/** Reusable sword appearances. Equipment definitions supply the complete stats, name, enchantments, and skill. */
public final class EquipmentItems {
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(MythicTrpg.MOD_ID);
    public static final DeferredItem<Item> STEEL_BLADE=ITEMS.register("steel_blade",()->new SwordItem(Tiers.IRON,appearanceProperties()));
    public static final DeferredItem<Item> DUELIST_BLADE=ITEMS.register("duelist_blade",()->new SwordItem(Tiers.IRON,appearanceProperties()));
    private EquipmentItems() { }

    static Item.Properties appearanceProperties() {
        // Do not copy a tier's attack bonus into the appearance. The player retains only innate attributes
        // until a concrete equipment definition overrides minecraft:attribute_modifiers.
        return new Item.Properties().attributes(ItemAttributeModifiers.EMPTY)
                .component(DataComponents.LORE,new ItemLore(List.of(Component.translatable("tooltip.mythictrpg.weapon_appearance"))));
    }

    public static void register(IEventBus bus){SimplySwordAppearances.registerItems(ITEMS);ITEMS.register(bus);bus.addListener(SimplySwordAppearances::commonSetup);}
}
