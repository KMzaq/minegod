package com.sande.mythictrpg.equipment;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

@GameTestHolder("mythictrpg_combat_optional")
@PrefixGameTestTemplate(false)
public final class SimplySwordAppearanceGameTests {
    private static final ResourceLocation TEST=ResourceLocation.parse("test:optional_weapon");
    private SimplySwordAppearanceGameTests() { }

    @GameTest(templateNamespace="mythictrpg_combat_optional",template="empty",batch="swords_appearances")
    public static void optionalDefinitionsAndFacadeRegistrationFollowBothInstalledMods(GameTestHelper h) throws Exception {
        boolean enabled=SimplySwordAppearances.enabled();
        var definitions=EquipmentCatalog.load(h.getLevel().getServer().getResourceManager());
        long count=definitions.keySet().stream().filter(id->id.getNamespace().equals("swords_demo")).count();
        h.assertValueEqual(count,enabled?4L:0L,"Optional swords definitions leaked or were not loaded");
        h.assertValueEqual(SimplySwordAppearances.items().size(),enabled?2:0,"Optional appearance registration mismatch");
        for(var appearance:SimplySwordAppearances.APPEARANCES)
            h.assertTrue(BuiltInRegistries.ITEM.containsKey(appearance.itemId())==enabled,"Facade registration did not follow both dependencies");
        h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_optional",template="empty",batch="swords_appearances")
    public static void dependencyMetadataIsStrictAndNeverConcealsMalformedDefinitions(GameTestHelper h) throws Exception {
        for(String metadata:List.of("null","\"simplyswords\"","[]","[5]","[\"SimplySwords\"]","[\"simplyswords\",\"simplyswords\"]")) {
            var json=source();json.add("requiredMods",JsonParser.parseString(metadata));
            rejects(h,()->EquipmentCatalog.load(single(json)),"Invalid dependency metadata accepted: "+metadata);
        }
        var oversized=source();var mods=new JsonArray();for(int i=0;i<17;i++)mods.add("missing_mod_"+i);oversized.add("requiredMods",mods);
        rejects(h,()->EquipmentCatalog.load(single(oversized)),"Unbounded dependency metadata accepted");
        var malformed=missingDependency();malformed.addProperty("attackDamage",-1);
        rejects(h,()->EquipmentCatalog.load(single(malformed)),"Missing mod concealed invalid attack damage");
        var unknown=missingDependency();unknown.addProperty("inventedField",true);
        rejects(h,()->EquipmentCatalog.load(single(unknown)),"Missing mod concealed unknown definition field");
        var bypass=missingDependency();bypass.getAsJsonObject("components").add("minecraft:custom_data",new JsonObject());
        rejects(h,()->EquipmentCatalog.load(single(bypass)),"Missing mod concealed forbidden authoritative component");
        var nativeItem=source();nativeItem.addProperty("itemId","simplyswords:stormbringer");
        rejects(h,()->EquipmentDefinition.parse(TEST,nativeItem),"Native item class bypassed the clean appearance contract");
        h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_optional",template="empty",batch="swords_appearances")
    public static void onlyExplicitMissingDependenciesSkipRegistryResolution(GameTestHelper h) throws Exception {
        var optional=missingDependency();optional.addProperty("itemId","test:uninstalled_mod_sword");
        h.assertTrue(EquipmentCatalog.load(single(optional)).isEmpty(),"Declared missing dependency did not skip optional registry lookup");
        optional.remove("requiredMods");
        rejects(h,()->EquipmentCatalog.load(single(optional)),"Undeclared missing item silently disappeared");
        optional.add("requiredMods",JsonParser.parseString("[\"minecraft\"]"));
        rejects(h,()->EquipmentCatalog.load(single(optional)),"Present dependency concealed unregistered item");
        h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_optional",template="empty",batch="swords_appearances")
    public static void cleanFacadesHaveNoNativeItemClassUseAbilityOrAbilityComponents(GameTestHelper h) {
        if(!SimplySwordAppearances.enabled()){h.succeed();return;}
        var player=new ServerPlayer(h.getLevel().getServer(),h.getLevel(),new GameProfile(UUID.randomUUID(),"FacadeUse"),ClientInformation.createDefault());
        for(var appearance:SimplySwordAppearances.APPEARANCES) {
            var item=BuiltInRegistries.ITEM.get(appearance.itemId());
            h.assertTrue(item.getClass()==SwordItem.class,"Facade inherited a Simply Swords item implementation");
            var raw=new ItemStack(item);
            h.assertTrue(raw.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,ItemAttributeModifiers.EMPTY).modifiers().isEmpty(),"Raw facade gained reward attack attributes");
            h.assertFalse(raw.has(DataComponents.CUSTOM_DATA),"Raw facade gained reward ownership or native ability state");
            h.assertFalse(NativeApi.hasImplicit(raw),"Simply Swords inferred a native passive from the facade item name");
            h.assertTrue(NativeApi.hasImplicit(new ItemStack(BuiltInRegistries.ITEM.get(appearance.sourceId()))),"Facade opt-out disabled the original Simply Swords item's passive");
            for(var definition:EquipmentCatalog.all().stream().filter(d->d.reward().itemId().equals(appearance.itemId())).toList()) {
                var stack=definition.reward().createStack(player.registryAccess());player.setItemSlot(EquipmentSlot.MAINHAND,stack);
                var before=stack.copy();before.remove(DataComponents.DAMAGE);
                for(var component:stack.getComponents().keySet())
                    h.assertFalse(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component).getNamespace().equals("simplyswords"),"Native Simply Swords ability component leaked into facade");
                for(boolean shift:List.of(false,true)) {
                    player.setShiftKeyDown(shift);
                    h.assertValueEqual(stack.use(h.getLevel(),player,InteractionHand.MAIN_HAND).getResult(),InteractionResult.PASS,"Facade item executes a native right-click ability");
                    stack.inventoryTick(h.getLevel(),player,0,true);
                    h.assertFalse(player.isUsingItem(),"Native charging ability began");
                    h.assertTrue(player.getActiveEffects().isEmpty(),"Native passive potion effect was applied");
                }
                var target=java.util.Objects.requireNonNull(EntityType.HUSK.create(h.getLevel()));
                stack.hurtEnemy(target,player);
                h.assertFalse(NativeApi.hasImplicit(stack),"Actual stack use/tick/hit acquired a native passive");
                var after=stack.copy();after.remove(DataComponents.DAMAGE);
                h.assertTrue(ItemStack.isSameItemSameComponents(before,after),"Actual stack hooks mutated authoritative equipment components");
            }
        }
        h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_combat_optional",template="empty",batch="swords_appearances")
    public static void modelAndAttackLinksReuseInstalledSourceResourcesWithoutAbilityInheritance(GameTestHelper h) throws Exception {
        if(!SimplySwordAppearances.enabled()){h.succeed();return;}
        var resources=h.getLevel().getServer().getResourceManager();
        var ownFile=ModList.get().getModFileById("mythictrpg").getFile();
        for(var appearance:SimplySwordAppearances.APPEARANCES) {
            SimplySwordAppearances.validateResources(resources,appearance.itemId());
            var model=JsonParser.parseString(Files.readString(ownFile.findResource("assets","mythictrpg","models","item",appearance.itemPath()+".json"))).getAsJsonObject();
            h.assertValueEqual(model.get("parent").getAsString(),"simplyswords:item/"+appearance.sourcePath(),"Facade model stopped borrowing the source appearance");
            var attackId=appearance.itemId().withPath("weapon_attributes/"+appearance.itemPath()+".json");
            try(var reader=resources.getResource(attackId).orElseThrow().openAsReader()) {
                var attacks=JsonParser.parseReader(reader).getAsJsonObject();
                h.assertTrue(attacks.size()==1&&attacks.get("parent").getAsString().equals(appearance.sourceId().toString()),"Facade normal attacks gained custom native ability payload");
            }
            try(var reader=resources.getResource(appearance.sourceAttacks()).orElseThrow().openAsReader()) {
                var attacks=JsonParser.parseReader(reader).getAsJsonObject();
                h.assertTrue(attacks.getAsJsonObject("attributes").getAsJsonArray("attacks").size()==3,"Installed source normal-attack chain changed");
            }
        }
        h.succeed();
    }

    private static JsonObject source() throws Exception {
        try(var input=SimplySwordAppearanceGameTests.class.getResourceAsStream("/data/reuse_demo/mythictrpg/equipment/steel_training.json")) {
            if(input==null)throw new IllegalStateException("Missing weapon fixture");
            return JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    /** Keep optional API classes out of absent-mod test discovery and ordinary equipment class loading. */
    private static final class NativeApi {
        static boolean hasImplicit(ItemStack stack){return net.sweenus.simplyswords.api.WeaponImplicitRegistry.getOrCreateWeaponImplicit(stack).isPresent();}
    }
    private static JsonObject missingDependency() throws Exception {
        var json=source();json.add("requiredMods",JsonParser.parseString("[\"test_missing_mod_for_optional_equipment\"]"));return json;
    }
    @FunctionalInterface private interface Work {void run()throws Exception;}
    private static void rejects(GameTestHelper h,Work work,String message)throws Exception {
        boolean rejected=false;try{work.run();}catch(IllegalArgumentException invalid){rejected=true;}h.assertTrue(rejected,message);
    }
    private static ResourceManager single(JsonObject value) {
        var file=TEST.withPath("mythictrpg/equipment/"+TEST.getPath()+".json");
        var resource=new Resource(null,()->new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8)));
        return new ResourceManager() {
            @Override public Set<String> getNamespaces(){return Set.of(TEST.getNamespace());}
            @Override public Optional<Resource> getResource(ResourceLocation id){return file.equals(id)?Optional.of(resource):Optional.empty();}
            @Override public List<Resource> getResourceStack(ResourceLocation id){return getResource(id).map(List::of).orElse(List.of());}
            @Override public Map<ResourceLocation,Resource> listResources(String path,Predicate<ResourceLocation> filter){return file.getPath().startsWith(path+"/")&&filter.test(file)?Map.of(file,resource):Map.of();}
            @Override public Map<ResourceLocation,List<Resource>> listResourceStacks(String path,Predicate<ResourceLocation> filter){var result=new LinkedHashMap<ResourceLocation,List<Resource>>();listResources(path,filter).forEach((id,r)->result.put(id,List.of(r)));return result;}
            @Override public Stream<PackResources> listPacks(){return Stream.empty();}
        };
    }
}
