package com.sande.mythictrpg.equipment;

import com.sande.mythictrpg.MythicTrpg;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Clean items borrow model/normal-attack resources, never Simply Swords item classes or ability data. */
public final class SimplySwordAppearances {
    public static final List<String> REQUIRED_MODS=List.of("simplyswords","bettercombat");
    /** Add an appearance here and matching item model + weapon_attributes JSON; reward stats stay in equipment JSON. */
    public static final List<Appearance> APPEARANCES=List.of(
            new Appearance("ss_iron_longsword","iron_longsword"),
            new Appearance("ss_stormbringer","stormbringer"));
    private static final Map<ResourceLocation,DeferredItem<Item>> ITEMS=new LinkedHashMap<>();

    public record Appearance(String itemPath,String sourcePath) {
        public ResourceLocation itemId(){return ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,itemPath);}
        public ResourceLocation sourceId(){return ResourceLocation.fromNamespaceAndPath("simplyswords",sourcePath);}
        public ResourceLocation sourceAttacks(){return ResourceLocation.fromNamespaceAndPath("simplyswords","weapon_attributes/"+sourcePath+".json");}
    }
    private SimplySwordAppearances() { }
    public static boolean enabled(){return ModList.get()!=null&&REQUIRED_MODS.stream().allMatch(ModList.get()::isLoaded);}
    static void registerItems(DeferredRegister.Items registry) {
        if(!enabled())return;
        for(var appearance:APPEARANCES)ITEMS.put(appearance.itemId(),registry.register(appearance.itemPath(),
                ()->new SwordItem(Tiers.IRON,EquipmentItems.appearanceProperties())));
    }
    static void commonSetup(FMLCommonSetupEvent event) {
        if(enabled())event.enqueueWork(()->items().forEach(NativeImplicitOptOut::register));
    }
    /**
     * Simply Swords 1.70.2 otherwise guesses native passives from any SwordItem's path, even outside its namespace.
     * Its explicit public item->type mapping takes precedence over that guess. This private type has no native
     * implicit definition, so lookup returns empty before any component is rolled or any native effect executes.
     * Do not register an implicit ability for this reserved type or remove components after the fact.
     */
    private static final class NativeImplicitOptOut {
        private static final ResourceLocation AUTHORED_ONLY=ResourceLocation.fromNamespaceAndPath(MythicTrpg.MOD_ID,"authored_reward_no_native_implicit");
        static void register(Item item){net.sweenus.simplyswords.api.WeaponImplicitRegistry.registerWeaponType(item,AUTHORED_ONLY);}
    }
    public static List<Item> items(){return ITEMS.values().stream().map(DeferredItem::get).toList();}
    public static boolean isAppearance(ResourceLocation itemId){return APPEARANCES.stream().anyMatch(a->a.itemId().equals(itemId));}

    /** Fail the catalog rather than silently publishing facades whose installed source resources do not exist. */
    public static void validateResources(ResourceManager resources,ResourceLocation itemId) {
        var appearance=APPEARANCES.stream().filter(a->a.itemId().equals(itemId)).findFirst();
        if(appearance.isEmpty())return;
        var descriptor=appearance.orElseThrow();
        if(!enabled()||!ITEMS.containsKey(itemId)||!BuiltInRegistries.ITEM.containsKey(descriptor.sourceId()))
            throw new IllegalArgumentException("Simply Swords appearance requires installed source item: "+descriptor.sourceId());
        if(resources.getResource(descriptor.sourceAttacks()).isEmpty())
            throw new IllegalArgumentException("Missing Simply Swords normal-attack definition: "+descriptor.sourceAttacks());
        var modFile=ModList.get().getModFileById("simplyswords").getFile();
        if(!Files.isRegularFile(modFile.findResource("assets","simplyswords","models","item",descriptor.sourcePath()+".json")))
            throw new IllegalArgumentException("Missing Simply Swords appearance model: "+descriptor.sourceId());
    }
}
