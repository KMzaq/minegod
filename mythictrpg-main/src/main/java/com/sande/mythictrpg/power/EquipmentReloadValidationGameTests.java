package com.sande.mythictrpg.power;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Exercise the registry-bound production reload listener before it can publish a poisoned item rule. */
@GameTestHolder("mythictrpg_prototype_catalog")
@PrefixGameTestTemplate(false)
public final class EquipmentReloadValidationGameTests {
    private static final ResourceLocation FILE=ResourceLocation.parse("reuse_demo:mythictrpg/equipment/steel_training.json");
    private EquipmentReloadValidationGameTests() { }

    @GameTest(templateNamespace="mythictrpg_prototype_catalog",template="empty",batch="equipment_bound_reload",timeoutTicks=120)
    public static void registryBoundReloadPreparesFourDefinitionsAndFifteenRulesWithoutPublishing(GameTestHelper h) throws Exception {
        var manager=CombatPowerPolicyManager.INSTANCE;var prior=manager.current();
        var prepared=prepare(h,h.getLevel().getServer().getResourceManager());
        h.assertTrue(prepared.policy()!=null&&prepared.reason().equals("READY"),"Valid registry-bound equipment reload was rejected: "+prepared.reason());
        int expected=4+(com.sande.mythictrpg.equipment.SimplySwordAppearances.enabled()?4:0);
        h.assertValueEqual(prepared.equipmentDefinitions().size(),expected,"Registry-bound reload must prepare every enabled equipment definition");
        h.assertValueEqual(prepared.policy().items().size(),11+expected,"Generated equipment rules replaced or duplicated the eleven existing variants");
        for(var definition:prepared.equipmentDefinitions().values()) {
            var actual=definition.reward().createStack(h.getLevel().registryAccess());
            h.assertTrue(!actual.isEmpty(),"Prepared equipment did not decode against the same live registry: "+definition.id());
        }
        h.assertTrue(manager.current()==prior,"Preparation published a replacement catalog before apply");h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_prototype_catalog",template="empty",batch="equipment_bound_reload",timeoutTicks=120)
    public static void malformedCosmeticValueCannotPublishAReadyEquipmentPolicy(GameTestHelper h) throws Exception {
        var invalid=source(h);invalid.getAsJsonObject("components").addProperty("minecraft:rarity","not_a_rarity");
        reject(h,invalid,"Malformed cosmetic rarity reached a READY policy");h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_prototype_catalog",template="empty",batch="equipment_bound_reload",timeoutTicks=120)
    public static void unknownEnchantmentCannotPoisonUnrelatedRegisteredWeaponRules(GameTestHelper h) throws Exception {
        var invalid=source(h);invalid.getAsJsonObject("enchantments").addProperty("minecraft:missing_reload_enchantment",1);
        reject(h,invalid,"Unregistered enchantment reached a READY policy");h.succeed();
    }

    @GameTest(templateNamespace="mythictrpg_prototype_catalog",template="empty",batch="equipment_bound_reload",timeoutTicks=120)
    public static void nonSwordAppearanceCannotPublishAWeaponEquipmentDefinition(GameTestHelper h) throws Exception {
        var invalid=source(h);invalid.addProperty("itemId","minecraft:diamond");
        reject(h,invalid,"Non-SwordItem appearance reached a READY weapon policy");h.succeed();
    }

    private static void reject(GameTestHelper h,JsonObject invalid,String message) throws Exception {
        var manager=CombatPowerPolicyManager.INSTANCE;var prior=manager.current();
        var resources=new OverlayResources(h.getLevel().getServer().getResourceManager(),Map.of(FILE,invalid.toString()));
        var rejected=prepare(h,resources);
        h.assertTrue(rejected.policy()==null&&rejected.equipmentDefinitions().isEmpty(),message);
        h.assertTrue(rejected.reason().startsWith("INVALID_"),"Rejected reload lost its invalid-definition diagnostic: "+rejected.reason());
        h.assertTrue(manager.current()==prior,"Failed preparation changed the live policy or catalog");
    }

    private static CombatPowerPolicyManager.Loaded prepare(GameTestHelper h,ResourceManager resources) throws Exception {
        var listener=CombatPowerPolicyManager.INSTANCE.forRegistries(h.getLevel().registryAccess());
        var prepare=listener.getClass().getDeclaredMethod("prepare",ResourceManager.class,ProfilerFiller.class);
        prepare.setAccessible(true);
        return (CombatPowerPolicyManager.Loaded)prepare.invoke(listener,resources,InactiveProfiler.INSTANCE);
    }

    private static JsonObject source(GameTestHelper h) throws Exception {
        try(var reader=h.getLevel().getServer().getResourceManager().getResource(FILE).orElseThrow().openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /** Preserve real bundled policies, other definitions, and registries; replace exactly one resource. */
    private static final class OverlayResources implements ResourceManager {
        private final ResourceManager delegate;
        private final Map<ResourceLocation,Resource> files;
        OverlayResources(ResourceManager delegate,Map<ResourceLocation,String> json) {
            this.delegate=delegate;var pack=delegate.listPacks().findFirst().orElseThrow();
            var values=new LinkedHashMap<ResourceLocation,Resource>();
            json.forEach((id,value)->values.put(id,new Resource(pack,()->new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)))));
            files=Map.copyOf(values);
        }
        @Override public Optional<Resource> getResource(ResourceLocation id){return Optional.ofNullable(files.get(id)).or(()->delegate.getResource(id));}
        @Override public Set<String> getNamespaces(){var result=new HashSet<>(delegate.getNamespaces());files.keySet().forEach(id->result.add(id.getNamespace()));return Set.copyOf(result);}
        @Override public List<Resource> getResourceStack(ResourceLocation id){return files.containsKey(id)?List.of(files.get(id)):delegate.getResourceStack(id);}
        @Override public Map<ResourceLocation,Resource> listResources(String path,Predicate<ResourceLocation> filter) {
            var result=new LinkedHashMap<>(delegate.listResources(path,filter));
            files.forEach((id,resource)->{if(id.getPath().startsWith(path+"/")&&filter.test(id))result.put(id,resource);});return result;
        }
        @Override public Map<ResourceLocation,List<Resource>> listResourceStacks(String path,Predicate<ResourceLocation> filter) {
            var result=new LinkedHashMap<>(delegate.listResourceStacks(path,filter));
            files.forEach((id,resource)->{if(id.getPath().startsWith(path+"/")&&filter.test(id))result.put(id,List.of(resource));});return result;
        }
        @Override public Stream<PackResources> listPacks(){return delegate.listPacks();}
    }
}
