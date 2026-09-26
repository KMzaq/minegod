package com.sande.mythictrpg.item;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MythicTrpg.MOD_ID);
    public static final DeferredItem<Item> STRUCTURE_SELECTOR = ITEMS.register("structure_selector",
            () -> new StructureSelectorItem(new Item.Properties().stacksTo(1)));

    private ModItems() {}
    public static void register(IEventBus bus) { ITEMS.register(bus); }
}
